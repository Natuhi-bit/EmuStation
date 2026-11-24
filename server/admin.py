"""
emusync の管理画面（ブラウザ用）。/admin/ 以下に置く。

- ログインは管理者パスワード（`python3 app.py set-admin-password` で設定）。セッションは署名付きの Cookie
- 管理できるもの：概要、ファイル（版の履歴・ダウンロード・復元）、端末（追加・削除）、ロック（強制解除）
- インポート：フォルダまたは zip をアップロード → プレビュー（新規・同じ・内容が違う）→ 取り込み

同期 API（/api/...）とは別の入口で、端末のトークンでは使えない。
"""
from __future__ import annotations

import fnmatch
import hashlib
import hmac
import json
import re
import secrets
import shutil
import time
import zipfile
from pathlib import Path
from typing import Annotated, Callable

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse, RedirectResponse
from pydantic import BaseModel, Field

STATIC_DIR = Path(__file__).resolve().parent / "static" / "admin"
COOKIE = "emusync_admin"
SESSION_SECONDS = 12 * 3600
ADMIN_DEVICE = "admin"
IMPORT_DEVICE = "admin-import"
IMPORT_TTL = 3600                      # アップロードしたものを置いておく時間
IMPORT_MAX_FILES = 20000
IMPORT_MAX_TOTAL = 4 * 1024 ** 3        # 展開後の合計の上限（zip 爆弾よけ）
DEFAULT_EXCLUDES = ["desktop.ini", "Thumbs.db", ".DS_Store", "*.part", ".nextcloudsync.log", ".sync_*.db*"]

META_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


# ---------------------------------------------------------------- パスワード
def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    n, r, p = 2 ** 14, 8, 1
    h = hashlib.scrypt(password.encode(), salt=salt, n=n, r=r, p=p, dklen=32)
    return f"scrypt${n}${r}${p}${salt.hex()}${h.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, n, r, p, salt, h = stored.split("$")
        if algo != "scrypt":
            return False
        got = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=int(n), r=int(r), p=int(p), dklen=32)
        return hmac.compare_digest(got.hex(), h)
    except (ValueError, TypeError):
        return False


class Core:
    """app.py の関数（DB・パスの検査・版の作成など）をまとめて受け取る"""

    def __init__(self, *, db: Callable, data_dir: Path, blob_path: Callable, check_path: Callable,
                 check_target: Callable, put_version: Callable, restore_version: Callable,
                 register_device: Callable, max_bytes: int):
        self.db = db
        self.data_dir = data_dir
        self.blob_path = blob_path
        self.check_path = check_path
        self.check_target = check_target
        self.put_version = put_version
        self.restore_version = restore_version
        self.register_device = register_device
        self.max_bytes = max_bytes

    # ---- meta テーブル
    def meta_get(self, key: str) -> str | None:
        with self.db() as conn:
            conn.executescript(META_SCHEMA)
            row = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row["value"] if row else None

    def meta_set(self, key: str, value: str) -> None:
        with self.db() as conn:
            conn.executescript(META_SCHEMA)
            conn.execute("INSERT OR REPLACE INTO meta VALUES(?,?)", (key, value))

    def set_admin_password(self, password: str) -> None:
        self.meta_set("admin_password", hash_password(password))
        # パスワードを変えたら、今までのセッションは使えなくする
        self.meta_set("session_secret", secrets.token_hex(32))

    def _secret(self) -> bytes:
        s = self.meta_get("session_secret")
        if s is None:
            s = secrets.token_hex(32)
            self.meta_set("session_secret", s)
        return s.encode()


# ---------------------------------------------------------------- セッション
def _sign(secret: bytes, payload: str) -> str:
    return hmac.new(secret, payload.encode(), hashlib.sha256).hexdigest()


def make_session(core: Core) -> str:
    payload = str(int(time.time()) + SESSION_SECONDS)
    return f"{payload}.{_sign(core._secret(), payload)}"


def session_valid(core: Core, value: str | None) -> bool:
    if not value or "." not in value:
        return False
    payload, sig = value.rsplit(".", 1)
    if not hmac.compare_digest(_sign(core._secret(), payload), sig):
        return False
    try:
        return int(payload) > time.time()
    except ValueError:
        return False


# ---------------------------------------------------------------- インポート
def normalize_rel(name: str) -> str:
    """アップロードされた相対パスを / 区切りにそろえる"""
    return "/".join(p for p in name.replace("\\", "/").split("/") if p not in ("",))


def _excluded(rel: str, patterns: list[str]) -> bool:
    fn = rel.rsplit("/", 1)[-1]
    return any(fnmatch.fnmatchcase(rel, p) or fnmatch.fnmatchcase(fn, p) for p in patterns if p)


def common_top(rels: list[str]) -> str | None:
    """すべてのファイルが同じ最上位フォルダの中にあれば、その名前"""
    tops = {r.split("/", 1)[0] for r in rels}
    if len(tops) == 1 and all("/" in r for r in rels):
        return tops.pop()
    return None


class ImportStore:
    """アップロードされたファイルを、取り込みが終わるまで DATA_DIR/imports/<id>/ に置く"""

    def __init__(self, core: Core):
        self.root = core.data_dir / "imports"

    def cleanup(self) -> None:
        if not self.root.exists():
            return
        for d in self.root.iterdir():
            if d.is_dir() and d.stat().st_mtime < time.time() - IMPORT_TTL:
                shutil.rmtree(d, ignore_errors=True)

    def create(self) -> tuple[str, Path]:
        self.cleanup()
        iid = secrets.token_hex(8)
        d = self.root / iid
        d.mkdir(parents=True)
        return iid, d

    def path(self, iid: str) -> Path:
        if not re.fullmatch(r"[0-9a-f]{16}", iid):
            raise HTTPException(404, "インポートが見つかりません")
        d = self.root / iid
        if not (d / "manifest.json").exists():
            raise HTTPException(404, "インポートが見つかりません（時間が経ったので消えた可能性があります）")
        return d

    @staticmethod
    def manifest(d: Path) -> dict:
        return json.loads((d / "manifest.json").read_text(encoding="utf-8"))

    def delete(self, iid: str) -> None:
        shutil.rmtree(self.path(iid), ignore_errors=True)


class ImportOptions(BaseModel):
    target: str = ""
    prefix: str = ""                 # ターゲットの中の、置き先のフォルダ（空ならターゲットの直下）
    strip_top: bool = True           # 共通の最上位フォルダを外す
    excludes: list[str] = DEFAULT_EXCLUDES
    include_updates: bool = False    # 内容が違う既存のファイルも新しい版として取り込む


def plan_import(core: Core, files: list[dict], opt: ImportOptions) -> dict:
    """取り込みの予定を立てる。files は [{"rel", "size", "sha256", "blob"}]"""
    core.check_target(opt.target)
    prefix = normalize_rel(opt.prefix)
    rels = [f["rel"] for f in files]
    top = common_top(rels) if opt.strip_top else None
    with core.db() as conn:
        current = {
            r["path"]: r for r in conn.execute(
                "SELECT path, version, sha256 FROM files WHERE substr(path, 1, ?) = ?",
                (len(opt.target) + 1, opt.target + "/"),
            )
        }
    items = []
    for f in files:
        rel = f["rel"][len(top) + 1:] if top else f["rel"]
        if prefix:
            rel = f"{prefix}/{rel}"
        path = f"{opt.target}/{rel}"
        item = {"source": f["rel"], "rel": rel, "path": path, "size": f["size"], "sha256": f["sha256"]}
        try:
            core.check_path(path)
        except HTTPException:
            items.append({**item, "status": "invalid", "reason": "使えないパスです"})
            continue
        if f["size"] > core.max_bytes:
            items.append({**item, "status": "invalid", "reason": "大きすぎます"})
            continue
        if _excluded(rel, opt.excludes):
            items.append({**item, "status": "excluded"})
            continue
        cur = current.get(path)
        if cur is None:
            items.append({**item, "status": "new"})
        elif cur["sha256"] == f["sha256"]:
            items.append({**item, "status": "same", "version": cur["version"]})
        else:
            items.append({**item, "status": "update" if opt.include_updates else "skip_update",
                          "version": cur["version"]})
    items.sort(key=lambda i: i["rel"])
    counts: dict[str, int] = {}
    for i in items:
        counts[i["status"]] = counts.get(i["status"], 0) + 1
    return {"target": opt.target, "stripped_top": top, "items": items, "counts": counts}


def upload_ready() -> bool:
    """アップロードの受け取りに必要な python-multipart が入っているか"""
    try:
        import python_multipart  # noqa: F401
        return True
    except ImportError:
        try:
            import multipart  # noqa: F401  （古い版の名前）
            return True
        except ImportError:
            return False


# ---------------------------------------------------------------- ルーター
class LoginReq(BaseModel):
    password: str


class DeviceReq(BaseModel):
    name: str


class RestoreReq(BaseModel):
    version: Annotated[int, Field(ge=-(2 ** 63), le=2 ** 63 - 1)]


def build_router(core: Core) -> APIRouter:
    router = APIRouter(prefix="/admin")
    imports = ImportStore(core)

    def require_admin(request: Request) -> None:
        if not session_valid(core, request.cookies.get(COOKIE)):
            raise HTTPException(401, "ログインしてください")
        # CSRF 対策：変更する操作は、この画面の JavaScript からだけ受け付ける
        if request.method not in ("GET", "HEAD") and request.headers.get("X-Emusync-Admin") != "1":
            raise HTTPException(403, "不正な要求です")

    admin = Depends(require_admin)

    # ---- 画面
    @router.get("", include_in_schema=False)
    def admin_root_redirect():
        # Nginx で /emusync/ の下に置くので、相対パスで /admin/ に送る
        return RedirectResponse("admin/")

    @router.get("/", include_in_schema=False)
    def admin_page():
        return FileResponse(STATIC_DIR / "index.html", headers={"Cache-Control": "no-cache"})

    @router.get("/static/{name}", include_in_schema=False)
    def admin_static(name: str):
        if not re.fullmatch(r"[a-z0-9_-]+\.(js|css|svg)", name) or not (STATIC_DIR / name).is_file():
            raise HTTPException(404, "ありません")
        return FileResponse(STATIC_DIR / name, headers={"Cache-Control": "no-cache"})

    # ---- ログイン
    @router.get("/api/session")
    def session(request: Request):
        return {
            "configured": core.meta_get("admin_password") is not None,
            "logged_in": session_valid(core, request.cookies.get(COOKIE)),
            "upload_ready": upload_ready(),
        }

    @router.post("/api/login")
    def login(req: LoginReq, request: Request, response: Response):
        if request.headers.get("X-Emusync-Admin") != "1":
            raise HTTPException(403, "不正な要求です")
        stored = core.meta_get("admin_password")
        if stored is None:
            raise HTTPException(409, "管理者パスワードが設定されていません")
        if not verify_password(req.password, stored):
            time.sleep(1)  # 総当たりを遅くする
            raise HTTPException(401, "パスワードが違います")
        response.set_cookie(
            COOKIE, make_session(core), max_age=SESSION_SECONDS, httponly=True,
            secure=request.headers.get("x-forwarded-proto", request.url.scheme) == "https",
            samesite="strict", path="/",
        )
        return {"ok": True}

    @router.post("/api/logout")
    def logout(response: Response):
        response.delete_cookie(COOKIE, path="/")
        return {"ok": True}

    # ---- 概要
    @router.get("/api/overview", dependencies=[admin])
    def overview():
        now = time.time()
        with core.db() as conn:
            targets = [dict(r) for r in conn.execute(
                "SELECT substr(path, 1, instr(path, '/') - 1) AS name, COUNT(*) AS files, "
                "SUM(size) AS size, MAX(updated_at) AS updated_at FROM files GROUP BY name ORDER BY name"
            )]
            versions = conn.execute("SELECT COUNT(*) AS n FROM versions").fetchone()["n"]
            devices = conn.execute("SELECT COUNT(*) AS n FROM devices").fetchone()["n"]
            locks = conn.execute("SELECT COUNT(*) AS n FROM locks WHERE expires_at > ?", (now,)).fetchone()["n"]
            shas = [r["sha256"] for r in conn.execute("SELECT DISTINCT sha256 FROM versions")]
        stored = sum(core.blob_path(s).stat().st_size for s in shas if core.blob_path(s).exists())
        return {"targets": targets, "versions": versions, "devices": devices, "locks": locks,
                "stored_bytes": stored}

    # ---- ファイル
    @router.get("/api/files", dependencies=[admin])
    def files(target: str):
        core.check_target(target)
        with core.db() as conn:
            rows = conn.execute(
                "SELECT path, version, sha256, size, updated_at, device FROM files "
                "WHERE substr(path, 1, ?) = ? ORDER BY path",
                (len(target) + 1, target + "/"),
            ).fetchall()
        return {"files": [{**dict(r), "rel": r["path"][len(target) + 1:]} for r in rows]}

    @router.get("/api/history/{path:path}", dependencies=[admin])
    def history(path: str):
        core.check_path(path)
        with core.db() as conn:
            rows = conn.execute(
                "SELECT version, sha256, size, created_at, device FROM versions WHERE path=? ORDER BY version DESC",
                (path,),
            ).fetchall()
        return {"path": path, "versions": [dict(r) for r in rows]}

    @router.get("/api/download/{path:path}", dependencies=[admin])
    def download(path: str, version: Annotated[int | None, Query(ge=-(2 ** 63), le=2 ** 63 - 1)] = None):
        core.check_path(path)
        with core.db() as conn:
            if version is None:
                row = conn.execute("SELECT * FROM files WHERE path=?", (path,)).fetchone()
            else:
                row = conn.execute("SELECT * FROM versions WHERE path=? AND version=?", (path, version)).fetchone()
        if not row or not core.blob_path(row["sha256"]).exists():
            raise HTTPException(404, "ありません")
        name = path.rsplit("/", 1)[-1]
        if version is not None:
            name = f"{name}.v{version}"
        return FileResponse(core.blob_path(row["sha256"]), media_type="application/octet-stream", filename=name)

    @router.post("/api/restore/{path:path}", dependencies=[admin])
    def restore(path: str, req: RestoreReq):
        core.check_path(path)
        return core.restore_version(path, req.version, ADMIN_DEVICE)

    # ---- 端末
    @router.get("/api/devices", dependencies=[admin])
    def devices():
        with core.db() as conn:
            rows = conn.execute("SELECT name, created_at FROM devices ORDER BY name").fetchall()
        return {"devices": [dict(r) for r in rows]}

    @router.post("/api/devices", dependencies=[admin])
    def add_device(req: DeviceReq):
        name = req.name.strip()
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", name) or name in (ADMIN_DEVICE, IMPORT_DEVICE):
            raise HTTPException(400, "端末名には英数字と . _ - だけが使えます（admin と admin-import は使えません）")
        with core.db() as conn:
            exists = conn.execute("SELECT 1 FROM devices WHERE name=?", (name,)).fetchone()
        if exists:
            raise HTTPException(409, "同じ名前の端末があります。発行し直すなら、先に削除してください")
        return {"name": name, "token": core.register_device(name)}

    @router.delete("/api/devices/{name}", dependencies=[admin])
    def remove_device(name: str):
        with core.db() as conn:
            n = conn.execute("DELETE FROM devices WHERE name=?", (name,)).rowcount
        if not n:
            raise HTTPException(404, "その端末はありません")
        return {"removed": True}

    # ---- ロック
    @router.get("/api/locks", dependencies=[admin])
    def locks():
        with core.db() as conn:
            rows = conn.execute(
                "SELECT target, device, expires_at FROM locks WHERE expires_at > ? ORDER BY target", (time.time(),)
            ).fetchall()
        return {"locks": [dict(r) for r in rows]}

    @router.delete("/api/locks/{target}", dependencies=[admin])
    def release_lock(target: str):
        core.check_target(target)
        with core.db() as conn:
            conn.execute("DELETE FROM locks WHERE target=?", (target,))
        return {"released": True}

    # ---- インポート
    @router.post("/api/imports", dependencies=[admin])
    async def upload(request: Request):
        """
        アップロードを受け取って置いておく。フォルダは files（中身）と paths（相対パス）を同じ順に、
        zip は zip にファイルを入れて送る。返り値は id と、既定の設定でのプレビュー
        """
        form = await request.form(max_files=IMPORT_MAX_FILES + 1, max_fields=IMPORT_MAX_FILES + 20)
        iid, d = imports.create()
        entries: list[dict] = []
        total = 0

        def add(rel: str, data: bytes) -> None:
            nonlocal total
            rel = normalize_rel(rel)
            if not rel or rel.startswith("__MACOSX/") or rel.endswith("/"):
                return
            if len(entries) >= IMPORT_MAX_FILES:
                raise HTTPException(413, f"ファイルが多すぎます（{IMPORT_MAX_FILES} 件まで）")
            total += len(data)
            if total > IMPORT_MAX_TOTAL:
                raise HTTPException(413, "合計が大きすぎます")
            blob = f"{len(entries):06d}.bin"
            (d / blob).write_bytes(data)
            entries.append({"rel": rel, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "blob": blob})

        try:
            zf = form.get("zip")
            if zf is not None and hasattr(zf, "file"):
                try:
                    # アップロードは一時ファイルに置かれているので、メモリに読み込まずにそのまま開く
                    with zipfile.ZipFile(zf.file) as z:
                        for info in z.infolist():
                            if info.is_dir():
                                continue
                            if info.file_size > core.max_bytes:
                                # 大きすぎるものは中身を読まず、プレビューで知らせる
                                entries.append({"rel": normalize_rel(info.filename), "size": info.file_size,
                                                "sha256": "", "blob": ""})
                                continue
                            add(info.filename, z.read(info))
                except zipfile.BadZipFile:
                    raise HTTPException(400, "zip として読めません")
            else:
                uploads = form.getlist("files")
                paths = form.getlist("paths")
                if not uploads or len(uploads) != len(paths):
                    raise HTTPException(400, "ファイルがありません")
                for f, rel in zip(uploads, paths):
                    add(str(rel), await f.read())
            if not entries:
                raise HTTPException(400, "取り込めるファイルがありません")
            (d / "manifest.json").write_text(json.dumps({"created_at": time.time(), "files": entries}),
                                             encoding="utf-8")
        except Exception:
            shutil.rmtree(d, ignore_errors=True)
            raise
        target = str(form.get("target") or "")
        preview = None
        if target:
            preview = plan_import(core, entries, ImportOptions(target=target))
        return {"id": iid, "files": len(entries), "bytes": total,
                "top": common_top([e["rel"] for e in entries]), "preview": preview}

    @router.post("/api/imports/{iid}/preview", dependencies=[admin])
    def preview(iid: str, opt: ImportOptions):
        d = imports.path(iid)
        return plan_import(core, ImportStore.manifest(d)["files"], opt)

    @router.post("/api/imports/{iid}/commit", dependencies=[admin])
    def commit(iid: str, opt: ImportOptions):
        d = imports.path(iid)
        plan = plan_import(core, ImportStore.manifest(d)["files"], opt)
        blobs = {f["rel"]: f["blob"] for f in ImportStore.manifest(d)["files"]}
        done = {"new": 0, "update": 0}
        for item in plan["items"]:
            if item["status"] not in ("new", "update"):
                continue
            data = (d / blobs[item["source"]]).read_bytes()
            core.put_version(item["path"], data, IMPORT_DEVICE, None)
            done[item["status"]] += 1
        imports.delete(iid)
        return {"target": opt.target, "imported": done, "counts": plan["counts"]}

    @router.delete("/api/imports/{iid}", dependencies=[admin])
    def cancel(iid: str):
        imports.delete(iid)
        return {"deleted": True}

    return router
