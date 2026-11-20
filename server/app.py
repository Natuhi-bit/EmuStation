"""
emusync server - エミュレータのセーブデータ同期サーバー（最小構成）

保存方式:
  - ファイル実体は SHA-256 をファイル名にして DATA_DIR/blobs/ に保存（同じ内容は1つだけ）
  - どのパスがどの版かは SQLite で管理。版は消さずに履歴として残す
  - 送信時は If-Match ヘッダーで「手元が知っている版番号」を必須にし、
    サーバー側が先に進んでいたら 409 を返す（楽観ロック）

起動:
  EMUSYNC_DATA=/var/lib/emusync uvicorn app:app --host 127.0.0.1 --port 8765
端末登録:
  EMUSYNC_DATA=/var/lib/emusync python3 app.py add-device win-desktop
"""
from __future__ import annotations

import hashlib
import os
import re
import secrets
import sqlite3
import sys
import time
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel

DATA_DIR = Path(os.environ.get("EMUSYNC_DATA", "./data")).resolve()
BLOB_DIR = DATA_DIR / "blobs"
DB_PATH = DATA_DIR / "emusync.db"
MAX_BYTES = int(os.environ.get("EMUSYNC_MAX_BYTES", str(256 * 1024 * 1024)))
KEEP_VERSIONS = int(os.environ.get("EMUSYNC_KEEP_VERSIONS", "30"))  # パスごとの履歴保持数

# パス: "ppsspp/SAVEDATA/ULJM05800/DATA.BIN" のような相対パスのみ許可
PATH_RE = re.compile(r"^[^/\\\x00][^\\\x00]*$")

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    name TEXT PRIMARY KEY,
    token_sha256 TEXT NOT NULL UNIQUE,
    created_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS files (
    path TEXT PRIMARY KEY,
    version INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    size INTEGER NOT NULL,
    updated_at REAL NOT NULL,
    device TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS versions (
    path TEXT NOT NULL,
    version INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    size INTEGER NOT NULL,
    created_at REAL NOT NULL,
    device TEXT NOT NULL,
    PRIMARY KEY (path, version)
);
CREATE TABLE IF NOT EXISTS locks (
    target TEXT PRIMARY KEY,
    device TEXT NOT NULL,
    expires_at REAL NOT NULL
);
"""


# ---------------------------------------------------------------- DB
def init_storage() -> None:
    BLOB_DIR.mkdir(parents=True, exist_ok=True)
    with db() as conn:
        conn.executescript(SCHEMA)


@contextmanager
def db():
    conn = sqlite3.connect(DB_PATH, timeout=30, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    try:
        yield conn
    finally:
        conn.close()


def blob_path(sha: str) -> Path:
    return BLOB_DIR / sha[:2] / sha


def check_path(path: str) -> str:
    parts = path.split("/")
    if not PATH_RE.match(path) or any(p in ("", ".", "..") for p in parts):
        raise HTTPException(400, f"不正なパス: {path}")
    return path


def check_target(target: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", target):
        raise HTTPException(400, f"不正なターゲット名: {target}")
    return target


# ---------------------------------------------------------------- 認証
def current_device(authorization: str = Header(default="")) -> str:
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "トークンがありません")
    token_sha = hashlib.sha256(authorization[7:].strip().encode()).hexdigest()
    with db() as conn:
        row = conn.execute(
            "SELECT name FROM devices WHERE token_sha256=?", (token_sha,)
        ).fetchone()
    if not row:
        raise HTTPException(401, "トークンが無効です")
    return row["name"]


# ---------------------------------------------------------------- API
@asynccontextmanager
async def lifespan(_app):
    init_storage()
    yield


app = FastAPI(title="emusync", lifespan=lifespan)


@app.get("/api/ping")
def ping(device: str = Depends(current_device)):
    return {"ok": True, "device": device, "time": time.time()}


@app.get("/api/manifest")
def manifest(prefix: str = "", device: str = Depends(current_device)):
    """prefix（例 'ppsspp/'）以下の全ファイルの最新版一覧"""
    with db() as conn:
        rows = conn.execute(
            "SELECT path, version, sha256, size, updated_at, device FROM files "
            "WHERE substr(path, 1, ?) = ? ORDER BY path",
            (len(prefix), prefix),
        ).fetchall()
    return {"files": [dict(r) for r in rows]}


@app.get("/api/files/{path:path}")
def get_file(path: str, version: int | None = None, device: str = Depends(current_device)):
    check_path(path)
    with db() as conn:
        if version is None:
            row = conn.execute("SELECT * FROM files WHERE path=?", (path,)).fetchone()
        else:
            row = conn.execute(
                "SELECT * FROM versions WHERE path=? AND version=?", (path, version)
            ).fetchone()
    if not row:
        raise HTTPException(404, "ありません")
    return FileResponse(
        blob_path(row["sha256"]),
        media_type="application/octet-stream",
        headers={"X-Version": str(row["version"]), "X-SHA256": row["sha256"]},
    )


@app.put("/api/files/{path:path}")
async def put_file(
    path: str,
    request: Request,
    if_match: str = Header(..., description="手元の基準版番号。新規は 0"),
    x_sha256: str = Header(...),
    device: str = Depends(current_device),
):
    check_path(path)
    try:
        base_version = int(if_match)
    except ValueError:
        raise HTTPException(400, "If-Match は版番号（整数）")

    body = await request.body()
    if len(body) > MAX_BYTES:
        raise HTTPException(413, "大きすぎます")
    sha = hashlib.sha256(body).hexdigest()
    if sha != x_sha256.lower():
        raise HTTPException(400, "転送中に内容が壊れました（ハッシュ不一致）")
    return put_version(path, body, device, base_version)


def write_blob(data: bytes) -> str:
    """実体を SHA-256 の名前で書く（同じ内容なら既にある）。返り値は SHA-256"""
    sha = hashlib.sha256(data).hexdigest()
    bp = blob_path(sha)
    if not bp.exists():
        bp.parent.mkdir(parents=True, exist_ok=True)
        tmp = bp.with_suffix(".tmp" + secrets.token_hex(4))
        tmp.write_bytes(data)
        os.replace(tmp, bp)
    return sha


def put_version(path: str, data: bytes, device: str, base_version: int | None) -> dict:
    """
    新しい版を作る。内容が今の版と同じなら版を進めない（unchanged）。
    base_version を指定すると楽観ロック（今の版と違えば 409）。None なら確かめない（管理画面のインポート用）
    """
    # 先に実体を書く（同じ内容なら既にある）
    sha = write_blob(data)
    body = data

    now = time.time()
    with db() as conn:
        conn.execute("BEGIN IMMEDIATE")
        try:
            row = conn.execute("SELECT version, sha256 FROM files WHERE path=?", (path,)).fetchone()
            current = row["version"] if row else 0
            if base_version is not None and current != base_version:
                conn.execute("ROLLBACK")
                raise HTTPException(
                    409,
                    {"message": "サーバー側が更新されています", "server_version": current,
                     "server_sha256": row["sha256"] if row else None},
                )
            if row and row["sha256"] == sha:  # 内容が同じなら版を進めない
                conn.execute("ROLLBACK")
                return {"path": path, "version": current, "sha256": sha, "unchanged": True}
            new_version = current + 1
            conn.execute(
                "INSERT INTO files(path, version, sha256, size, updated_at, device) "
                "VALUES(?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET "
                "version=excluded.version, sha256=excluded.sha256, size=excluded.size, "
                "updated_at=excluded.updated_at, device=excluded.device",
                (path, new_version, sha, len(body), now, device),
            )
            conn.execute(
                "INSERT INTO versions(path, version, sha256, size, created_at, device) "
                "VALUES(?,?,?,?,?,?)",
                (path, new_version, sha, len(body), now, device),
            )
            conn.execute(
                "DELETE FROM versions WHERE path=? AND version <= ?",
                (path, new_version - KEEP_VERSIONS),
            )
            conn.execute("COMMIT")
        except HTTPException:
            raise
        except Exception:
            conn.execute("ROLLBACK")
            raise
    return {"path": path, "version": new_version, "sha256": sha}


@app.get("/api/history/{path:path}")
def history(path: str, device: str = Depends(current_device)):
    check_path(path)
    with db() as conn:
        rows = conn.execute(
            "SELECT version, sha256, size, created_at, device FROM versions "
            "WHERE path=? ORDER BY version DESC",
            (path,),
        ).fetchall()
    return {"path": path, "versions": [dict(r) for r in rows]}


class RestoreReq(BaseModel):
    version: int


@app.post("/api/restore/{path:path}")
def restore(path: str, req: RestoreReq, device: str = Depends(current_device)):
    """過去の版の内容を「新しい版」として最新にする（履歴は消さない）"""
    check_path(path)
    return restore_version(path, req.version, device)


def restore_version(path: str, version: int, device: str) -> dict:
    """版 version の内容を新しい版として最新にする。記録する端末名は「<device>(restore v<版>)」"""
    req = RestoreReq(version=version)
    now = time.time()
    with db() as conn:
        conn.execute("BEGIN IMMEDIATE")
        old = conn.execute(
            "SELECT sha256, size FROM versions WHERE path=? AND version=?", (path, req.version)
        ).fetchone()
        cur = conn.execute("SELECT version FROM files WHERE path=?", (path,)).fetchone()
        if not old or not cur:
            conn.execute("ROLLBACK")
            raise HTTPException(404, "その版はありません")
        v = cur["version"] + 1
        conn.execute(
            "UPDATE files SET version=?, sha256=?, size=?, updated_at=?, device=? WHERE path=?",
            (v, old["sha256"], old["size"], now, f"{device}(restore v{req.version})", path),
        )
        conn.execute(
            "INSERT INTO versions VALUES(?,?,?,?,?,?)",
            (path, v, old["sha256"], old["size"], now, f"{device}(restore v{req.version})"),
        )
        conn.execute("COMMIT")
    return {"path": path, "version": v}


# ------------------------------------------------ プレイ中ロック
class LockReq(BaseModel):
    ttl: int = 6 * 3600  # 秒。プレイ時間より長めに
    force: bool = False


@app.post("/api/locks/{target}")
def acquire_lock(target: str, req: LockReq, device: str = Depends(current_device)):
    check_target(target)
    now = time.time()
    with db() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute("SELECT device, expires_at FROM locks WHERE target=?", (target,)).fetchone()
        if row and row["device"] != device and row["expires_at"] > now and not req.force:
            conn.execute("ROLLBACK")
            raise HTTPException(
                423, {"message": f"{row['device']} がプレイ中です", "device": row["device"],
                      "expires_at": row["expires_at"]},
            )
        conn.execute(
            "INSERT INTO locks(target, device, expires_at) VALUES(?,?,?) "
            "ON CONFLICT(target) DO UPDATE SET device=excluded.device, expires_at=excluded.expires_at",
            (target, device, now + req.ttl),
        )
        conn.execute("COMMIT")
    return {"target": target, "device": device, "expires_at": now + req.ttl}


@app.delete("/api/locks/{target}")
def release_lock(target: str, device: str = Depends(current_device)):
    check_target(target)
    with db() as conn:
        conn.execute("DELETE FROM locks WHERE target=? AND device=?", (target, device))
    return {"released": True}


@app.get("/api/locks")
def list_locks(device: str = Depends(current_device)):
    with db() as conn:
        rows = conn.execute(
            "SELECT * FROM locks WHERE expires_at > ?", (time.time(),)
        ).fetchall()
    return {"locks": [dict(r) for r in rows]}


# ---------------------------------------------------------------- 管理コマンド
def register_device(name: str) -> str:
    """端末を登録（同じ名前なら置き換え）して、新しいトークンを返す。トークンは保存しない（ハッシュだけ）"""
    token = secrets.token_urlsafe(32)
    with db() as conn:
        conn.execute(
            "INSERT OR REPLACE INTO devices VALUES(?,?,?)",
            (name, hashlib.sha256(token.encode()).hexdigest(), time.time()),
        )
    return token


def _cli() -> None:
    init_storage()
    if len(sys.argv) >= 3 and sys.argv[1] == "add-device":
        name = sys.argv[2]
        token = register_device(name)
        print(f"端末 '{name}' を登録しました。このトークンをクライアント設定に書いてください（再表示できません）:")
        print(token)
    elif len(sys.argv) >= 3 and sys.argv[1] == "remove-device":
        with db() as conn:
            conn.execute("DELETE FROM devices WHERE name=?", (sys.argv[2],))
        print("削除しました")
    elif len(sys.argv) >= 2 and sys.argv[1] == "list-devices":
        with db() as conn:
            for r in conn.execute("SELECT name, created_at FROM devices"):
                print(r["name"], time.strftime("%Y-%m-%d %H:%M", time.localtime(r["created_at"])))
    elif len(sys.argv) >= 2 and sys.argv[1] == "set-admin-password":
        import getpass
        pw = getpass.getpass("管理画面のパスワード（8文字以上）: ")
        if len(pw) < 8:
            sys.exit("8文字以上にしてください")
        if getpass.getpass("もう一度: ") != pw:
            sys.exit("一致しません")
        ADMIN.set_admin_password(pw)
        print("設定しました。ブラウザで https://<サーバー>/emusync/admin/ を開いてください")
    else:
        print("使い方: python3 app.py add-device <名前> | remove-device <名前> | list-devices | set-admin-password")


# ---------------------------------------------------------------- 管理画面（/admin/）
import admin as _admin  # noqa: E402

ADMIN = _admin.Core(
    db=db, data_dir=DATA_DIR, blob_path=blob_path, check_path=check_path, check_target=check_target,
    put_version=put_version, restore_version=restore_version, register_device=register_device,
    max_bytes=MAX_BYTES,
)
app.include_router(_admin.build_router(ADMIN))


if __name__ == "__main__":
    _cli()
