"""
二次バックアップ：サーバーのセーブデータ（DB と実体）を、別の場所に世代を残して写す。

置き場所（どれか、または全部）:
  - Nextcloud（WebDAV）  例 https://cloud.example.com/remote.php/dav/files/<ユーザー>/emusync-backup
  - サーバーの別のディスク（USB の HDD・マウントした NAS など）  例 /mnt/backup/emusync-backup
  - 手元にダウンロード（管理画面から zip）

置き場所の中の形:
  blobs/<sha256 の先頭 2 文字>/<sha256>   実体。中身の SHA-256 の名前なので、同じものは 1 つだけ（変わっていなければ送らない）
  blob-index.txt                         置いてある実体の一覧（毎回すべてを調べなくてよいように）
  snapshots/<ID>/db.sqlite.gz            その時点の DB（バックアップの設定は抜いてある）
  snapshots/<ID>/manifest.json           その時点の最新版の一覧と、使っている実体の一覧。これを最後に置くので、
                                         manifest.json がある世代だけが「できあがった」世代
世代は 1 日ごと 7 個・1 週ごと 4 個・1 か月ごと 6 個を残す。前の世代から何も変わっていなければ作らない。

戻し方:
  - 全体：DB と実体をその世代に戻す（サーバーが壊れた・データを失ったとき）。今の DB は pre-restore-<日時>.db に残す。
    管理画面のパスワードとバックアップの設定は、今のものを残す
  - 一部：ハードやファイルを選んで、その世代の中身を「新しい版」として置く（各端末は次の同期で取得する）
"""
from __future__ import annotations

import contextlib
import datetime as dt
import gzip
import hashlib
import json
import os
import shutil
import sqlite3
import ssl
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Callable, Iterable

BACKUP_DEVICE = "admin-backup"
CONFIG_KEY = "backup_config"
STATUS_KEY = "backup_status"
KEEP_DAILY, KEEP_WEEKLY, KEEP_MONTHLY = 7, 4, 6


class BackupError(Exception):
    pass


def explain(e: Exception, where: str = "") -> str:
    """エラーを、どうすればよいかの分かる文に"""
    import errno
    if isinstance(e, BackupError):
        return str(e)
    if isinstance(e, OSError) and e.errno == errno.EROFS:
        return (f"{where or e.filename} に書き込めません（読み取り専用）。emusync.service が ProtectSystem=strict で動いているときは、"
                "/etc/systemd/system/emusync.service.d/backup.conf に [Service] ReadWritePaths=-<フォルダ> を書いて "
                "systemctl daemon-reload と restart をしてください")
    if isinstance(e, OSError) and e.errno in (errno.EACCES, errno.EPERM):
        return f"{where or e.filename} に書き込む権限がありません（chown emusync: <フォルダ> で emusync のユーザーが書けるようにしてください）"
    return f"{type(e).__name__}: {e}"


# ---------------------------------------------------------------- 置き場所
class LocalDest:
    """サーバーの別のディスク・フォルダ"""

    def __init__(self, root: str | Path, name: str = "disk", label: str = "別のディスク"):
        self.root = Path(root)
        self.name, self.label = name, label

    def _p(self, rel: str) -> Path:
        p = (self.root / rel).resolve()
        if self.root.resolve() not in p.parents and p != self.root.resolve():
            raise BackupError(f"置き場所の外です: {rel}")
        return p

    def check(self, data_dir: Path | None = None) -> None:
        if not self.root.parent.exists():
            raise BackupError(f"フォルダがありません: {self.root.parent}（ディスクがつながっているか確かめてください）")
        # USB の HDD がつながっていない（マウントされていない）と、同じディスクの空のフォルダに書いてしまうので確かめる
        if data_dir is not None and os.environ.get("EMUSYNC_BACKUP_ALLOW_SAME_DISK") != "1":
            if os.stat(self.root.parent).st_dev == os.stat(data_dir).st_dev:
                raise BackupError(f"{self.root.parent} はサーバーのデータと同じディスクです"
                                  "（別のディスクがつながっていない・マウントされていないかもしれません）")
        self.root.mkdir(parents=True, exist_ok=True)
        probe = self._p(".write-test")
        probe.write_bytes(b"ok")
        probe.unlink()

    def exists(self, rel: str) -> bool:
        return self._p(rel).exists()

    def get(self, rel: str) -> bytes:
        try:
            return self._p(rel).read_bytes()
        except FileNotFoundError:
            raise BackupError(f"ありません: {rel}")

    def get_to_file(self, rel: str, out: Path) -> None:
        try:
            shutil.copyfile(self._p(rel), out)
        except FileNotFoundError:
            raise BackupError(f"ありません: {rel}")

    def put_file(self, rel: str, src: Path) -> None:
        p = self._p(rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + ".part")
        shutil.copyfile(src, tmp)
        os.replace(tmp, p)

    def put_bytes(self, rel: str, data: bytes) -> None:
        p = self._p(rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + ".part")
        tmp.write_bytes(data)
        os.replace(tmp, p)

    def list(self, rel: str) -> list[str]:
        d = self._p(rel)
        return sorted(x.name for x in d.iterdir() if not x.name.endswith(".part")) if d.is_dir() else []

    def delete(self, rel: str) -> None:
        p = self._p(rel)
        if p.is_dir():
            shutil.rmtree(p)
        elif p.exists():
            p.unlink()


class WebDavDest:
    """Nextcloud などの WebDAV"""

    def __init__(self, url: str, user: str, password: str, verify_tls: bool = True, timeout: int = 120,
                 name: str = "nextcloud", label: str = "Nextcloud"):
        self.base = url.rstrip("/") + "/"
        self.name, self.label = name, label
        auth = (f"{user}:{password}").encode()
        import base64
        self.headers = {"Authorization": "Basic " + base64.b64encode(auth).decode(), "User-Agent": "emusync-backup"}
        self.ctx = ssl.create_default_context() if verify_tls else ssl._create_unverified_context()  # noqa: S323
        self.timeout = timeout
        self._made: set[str] = set()

    def _url(self, rel: str) -> str:
        return self.base + "/".join(urllib.parse.quote(p) for p in rel.strip("/").split("/") if p)

    def _req(self, method: str, rel: str, data=None, headers: dict | None = None, length: int | None = None):
        h = dict(self.headers)
        if headers:
            h.update(headers)
        if length is not None:
            h["Content-Length"] = str(length)
        req = urllib.request.Request(self._url(rel) + ("/" if method in ("MKCOL", "PROPFIND") and rel else ""),
                                     data=data, method=method, headers=h)
        try:
            return urllib.request.urlopen(req, timeout=self.timeout, context=self.ctx)
        except urllib.error.HTTPError as e:
            if e.code == 401:
                raise BackupError("Nextcloud にログインできません（ユーザー名とアプリパスワードを確かめてください）")
            raise
        except urllib.error.URLError as e:
            raise BackupError(f"Nextcloud につながりません: {e.reason}")

    def _mkdirs(self, rel_dir: str) -> None:
        parts = [p for p in rel_dir.strip("/").split("/") if p]
        for i in range(len(parts) + 1):
            d = "/".join(parts[:i])
            if d in self._made:
                continue
            try:
                self._req("MKCOL", d).close()
            except urllib.error.HTTPError as e:
                if e.code not in (405, 409):  # 405：もうある
                    raise BackupError(f"フォルダを作れません（{e.code}）: {d or '/'}")
            self._made.add(d)

    def check(self) -> None:
        self._mkdirs("")
        self.put_bytes(".write-test", b"ok")
        if self.get(".write-test") != b"ok":
            raise BackupError("書いたものを読み戻せません")
        self.delete(".write-test")

    def exists(self, rel: str) -> bool:
        try:
            self._req("HEAD", rel).close()
            return True
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            raise

    def get(self, rel: str) -> bytes:
        try:
            with self._req("GET", rel) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            raise BackupError(f"読めません（{e.code}）: {rel}")

    def get_to_file(self, rel: str, out: Path) -> None:
        try:
            with self._req("GET", rel) as r, open(out, "wb") as f:
                shutil.copyfileobj(r, f, 1024 * 1024)
        except urllib.error.HTTPError as e:
            raise BackupError(f"読めません（{e.code}）: {rel}")

    def put_file(self, rel: str, src: Path) -> None:
        self._mkdirs(rel.rsplit("/", 1)[0] if "/" in rel else "")
        with open(src, "rb") as f:
            try:
                self._req("PUT", rel, data=f, length=src.stat().st_size,
                          headers={"Content-Type": "application/octet-stream"}).close()
            except urllib.error.HTTPError as e:
                raise BackupError(f"送れません（{e.code}）: {rel}")

    def put_bytes(self, rel: str, data: bytes) -> None:
        self._mkdirs(rel.rsplit("/", 1)[0] if "/" in rel else "")
        try:
            self._req("PUT", rel, data=data, length=len(data), headers={"Content-Type": "application/octet-stream"}).close()
        except urllib.error.HTTPError as e:
            raise BackupError(f"送れません（{e.code}）: {rel}")

    def list(self, rel: str) -> list[str]:
        body = b'<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>'
        try:
            with self._req("PROPFIND", rel, data=body, headers={"Depth": "1", "Content-Type": "application/xml"}) as r:
                xml = r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return []
            raise BackupError(f"一覧を読めません（{e.code}）: {rel}")
        me = urllib.parse.urlparse(self._url(rel)).path.rstrip("/")
        names = []
        for resp in ET.fromstring(xml).iter("{DAV:}response"):
            href = resp.findtext("{DAV:}href") or ""
            path = urllib.parse.unquote(urllib.parse.urlparse(href).path).rstrip("/")
            if path == urllib.parse.unquote(me):
                continue
            name = path.rsplit("/", 1)[-1]
            if name and not name.endswith(".part"):
                names.append(name)
        return sorted(names)

    def delete(self, rel: str) -> None:
        try:
            self._req("DELETE", rel).close()
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise BackupError(f"消せません（{e.code}）: {rel}")


class ZipSource:
    """手元にダウンロードしたバックアップ（zip）。戻すときだけ使う"""

    def __init__(self, path: str | Path):
        self.zf = zipfile.ZipFile(path)
        self.name, self.label = "zip", "ダウンロードした zip"
        names = self.zf.namelist()
        self.snapshot_id = next((n.split("/")[1] for n in names if n.startswith("snapshots/") and n.endswith("/manifest.json")), None)

    def exists(self, rel: str) -> bool:
        return rel in self.zf.namelist()

    def get(self, rel: str) -> bytes:
        try:
            return self.zf.read(rel)
        except KeyError:
            raise BackupError(f"zip の中にありません: {rel}")

    def get_to_file(self, rel: str, out: Path) -> None:
        out.write_bytes(self.get(rel))

    def list(self, rel: str) -> list[str]:
        pre = rel.strip("/") + "/"
        return sorted({n[len(pre):].split("/")[0] for n in self.zf.namelist() if n.startswith(pre) and n != pre})


# ---------------------------------------------------------------- 世代の決まり
def snapshot_id(t: float | None = None) -> str:
    return dt.datetime.fromtimestamp(t if t is not None else time.time(), dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def parse_id(i: str) -> dt.datetime:
    return dt.datetime.strptime(i, "%Y%m%dT%H%M%SZ").replace(tzinfo=dt.timezone.utc)


def keep_ids(ids: Iterable[str], daily: int = KEEP_DAILY, weekly: int = KEEP_WEEKLY, monthly: int = KEEP_MONTHLY) -> set[str]:
    """残す世代：最新・日ごとの最後を daily 日分・週ごとの最後を weekly 週分・月ごとの最後を monthly か月分"""
    valid = []
    for i in ids:
        with contextlib.suppress(ValueError):
            valid.append((parse_id(i), i))
    valid.sort(reverse=True)
    keep = {valid[0][1]} if valid else set()

    def bucket(n: int, key: Callable[[dt.datetime], object]) -> None:
        seen: list[object] = []
        for d, i in valid:
            k = key(d)
            if k not in seen:
                seen.append(k)
                keep.add(i)
                if len(seen) >= n:
                    break

    bucket(daily, lambda d: d.date())
    bucket(weekly, lambda d: tuple(d.isocalendar())[:2])
    bucket(monthly, lambda d: (d.year, d.month))
    return keep


# ---------------------------------------------------------------- バックアップの本体
class BackupManager:
    def __init__(self, core):
        self.core = core
        self.lock = threading.Lock()
        self.running: str | None = None      # 実行中の処理の説明
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    # ---- 設定と状態
    def config(self) -> dict:
        raw = self.core.meta_get(CONFIG_KEY)
        c = {"auto": True, "hour": 4, "local_dir": "", "nextcloud": {"url": "", "user": "", "password": "", "verify_tls": True}}
        if raw:
            stored = json.loads(raw)
            c.update({k: v for k, v in stored.items() if k != "nextcloud"})
            c["nextcloud"].update(stored.get("nextcloud", {}))
        return c

    def save_config(self, new: dict) -> dict:
        c = self.config()
        if "auto" in new:
            c["auto"] = bool(new["auto"])
        if "hour" in new:
            h = int(new["hour"])
            if not 0 <= h <= 23:
                raise BackupError("時刻は 0〜23 時で指定してください")
            c["hour"] = h
        if "local_dir" in new:
            d = str(new["local_dir"] or "").strip()
            if d and not d.startswith("/"):
                raise BackupError("別のディスクのフォルダは、/ から始まるパスで指定してください（例 /mnt/backup/emusync-backup）")
            data_dir = str(self.core.data_dir.resolve())
            if d and (os.path.realpath(d) + "/").startswith(data_dir + "/"):
                raise BackupError("サーバーのデータのフォルダの中には置けません（同じディスクが壊れると一緒に失われます）")
            c["local_dir"] = d
        if "nextcloud" in new:
            n = new["nextcloud"] or {}
            nc = c["nextcloud"]
            for k in ("url", "user"):
                if k in n:
                    nc[k] = str(n[k] or "").strip()
            if n.get("password"):  # 空なら今のまま
                nc["password"] = str(n["password"])
            if n.get("clear_password"):
                nc["password"] = ""
            if "verify_tls" in n:
                nc["verify_tls"] = bool(n["verify_tls"])
            if nc["url"] and not nc["url"].startswith(("https://", "http://")):
                raise BackupError("Nextcloud の URL は https:// から始めてください")
        self.core.meta_set(CONFIG_KEY, json.dumps(c))
        return c

    def public_config(self) -> dict:
        """画面に出す設定（パスワードは出さない）"""
        c = self.config()
        nc = dict(c["nextcloud"])
        nc["has_password"] = bool(nc.pop("password"))
        return {**c, "nextcloud": nc}

    def status(self) -> dict:
        raw = self.core.meta_get(STATUS_KEY)
        return json.loads(raw) if raw else {}

    def _set_status(self, dest: str, **kw) -> None:
        s = self.status()
        s.setdefault(dest, {}).update(kw)
        self.core.meta_set(STATUS_KEY, json.dumps(s))

    def dests(self, config: dict | None = None) -> list:
        c = config or self.config()
        out = []
        nc = c["nextcloud"]
        if nc["url"] and nc["user"] and nc["password"]:
            out.append(WebDavDest(nc["url"], nc["user"], nc["password"], nc.get("verify_tls", True)))
        if c["local_dir"]:
            out.append(LocalDest(c["local_dir"]))
        return out

    def dest(self, name: str):
        for d in self.dests():
            if d.name == name:
                return d
        raise BackupError(f"置き場所 {name} は設定されていません")

    # ---- 今の状態を写す
    def fingerprint(self) -> str:
        """ファイル・版・端末が同じなら同じ値（ロックや設定は見ない）"""
        h = hashlib.sha256()
        with self.core.db() as conn:
            for q in ("SELECT path, version, sha256 FROM files ORDER BY path",
                      "SELECT path, version, sha256 FROM versions ORDER BY path, version",
                      "SELECT name, token_sha256 FROM devices ORDER BY name"):
                for r in conn.execute(q):
                    h.update(json.dumps(list(r), ensure_ascii=False).encode())
                h.update(b"|")
        return h.hexdigest()

    def _db_copy(self, out: Path) -> None:
        """今の DB を out に写す（動いている間でも一貫した写し）。バックアップの設定・状態は抜く"""
        with self.core.db() as src:
            dst = sqlite3.connect(out)
            try:
                src.backup(dst)
                dst.execute("DELETE FROM meta WHERE key IN (?, ?)", (CONFIG_KEY, STATUS_KEY)) if _has_table(dst, "meta") else None
                dst.commit()
            finally:
                dst.close()

    def _manifest(self, snap_db: Path, sid: str, fp: str) -> dict:
        conn = sqlite3.connect(snap_db)
        conn.row_factory = sqlite3.Row
        try:
            files = [dict(r) for r in conn.execute(
                "SELECT path, version, sha256, size, updated_at, device FROM files ORDER BY path")]
            blobs = sorted({r[0] for r in conn.execute("SELECT sha256 FROM versions UNION SELECT sha256 FROM files")})
        finally:
            conn.close()
        return {"id": sid, "created_at": time.time(), "fingerprint": fp, "files": files, "blobs": blobs,
                "total_bytes": sum(f["size"] for f in files)}

    def snapshot_to(self, dest, force: bool = False) -> dict:
        """dest に新しい世代を作る。前の世代と同じなら作らない。返り値は {created, id, uploaded}"""
        fp = self.fingerprint()
        latest = self.list_ids(dest)
        if latest and not force:
            try:
                last = json.loads(dest.get(f"snapshots/{latest[-1]}/manifest.json"))
                if last.get("fingerprint") == fp:
                    return {"created": False, "id": latest[-1], "uploaded": 0}
            except (BackupError, ValueError):
                pass
        sid = snapshot_id()
        with tempfile.TemporaryDirectory() as td:
            snap_db = Path(td) / "db.sqlite"
            self._db_copy(snap_db)
            manifest = self._manifest(snap_db, sid, fp)
            # 1. 実体（置いていないものだけ）
            index = self._read_index(dest)
            uploaded = 0
            for sha in manifest["blobs"]:
                if sha in index:
                    continue
                src = self.core.blob_path(sha)
                if not src.exists():
                    raise BackupError(f"サーバーに実体がありません: {sha}（データが壊れているかもしれません）")
                dest.put_file(f"blobs/{sha[:2]}/{sha}", src)
                index.add(sha)
                uploaded += 1
                if uploaded % 200 == 0:  # 途中で止まっても、次は続きから
                    self._write_index(dest, index)
            self._write_index(dest, index)
            # 2. DB 3. 一覧（最後に置く＝できあがり）
            gz = Path(td) / "db.sqlite.gz"
            with open(snap_db, "rb") as f, gzip.open(gz, "wb", compresslevel=6) as g:
                shutil.copyfileobj(f, g)
            dest.put_file(f"snapshots/{sid}/db.sqlite.gz", gz)
            dest.put_bytes(f"snapshots/{sid}/manifest.json", json.dumps(manifest, ensure_ascii=False).encode())
        return {"created": True, "id": sid, "uploaded": uploaded}

    @staticmethod
    def _read_index(dest) -> set[str]:
        try:
            return {line.strip() for line in dest.get("blob-index.txt").decode().splitlines() if line.strip()}
        except BackupError:
            return set()

    @staticmethod
    def _write_index(dest, index: set[str]) -> None:
        dest.put_bytes("blob-index.txt", ("\n".join(sorted(index)) + "\n").encode())

    @staticmethod
    def list_ids(src) -> list[str]:
        """できあがった世代（manifest.json があるもの）の ID。古い順"""
        out = []
        for i in src.list("snapshots"):
            with contextlib.suppress(ValueError):
                parse_id(i)
                if "manifest.json" in src.list(f"snapshots/{i}"):
                    out.append(i)
        return sorted(out)

    def prune(self, dest) -> dict:
        """残さない世代を消し、どの世代も使っていない実体を消す"""
        ids = self.list_ids(dest)
        keep = keep_ids(ids)
        removed = [i for i in ids if i not in keep]
        for i in removed:
            dest.delete(f"snapshots/{i}")
        # 作りかけ（manifest.json がない）の世代も消す（1 日以上前のもの）
        for i in dest.list("snapshots"):
            if i not in ids:
                with contextlib.suppress(ValueError):
                    if time.time() - parse_id(i).timestamp() > 86400:
                        dest.delete(f"snapshots/{i}")
        used: set[str] = set()
        for i in keep:
            used |= set(json.loads(dest.get(f"snapshots/{i}/manifest.json"))["blobs"])
        index = self._read_index(dest)
        gone = 0
        for sha in sorted(index - used):
            dest.delete(f"blobs/{sha[:2]}/{sha}")
            gone += 1
        if gone:
            self._write_index(dest, index & used)
        return {"removed_snapshots": removed, "removed_blobs": gone}

    def run(self, only: str | None = None, force: bool = False) -> dict:
        """設定したすべての置き場所（または only）にバックアップする。1 つ失敗しても、ほかは続ける"""
        if not self.lock.acquire(blocking=False):
            raise BackupError("ほかのバックアップ・復元を実行中です")
        results = {}
        try:
            for d in self.dests():
                if only and d.name != only:
                    continue
                self.running = f"{d.label} にバックアップしています"
                try:
                    if isinstance(d, LocalDest):
                        d.check(self.core.data_dir)
                    r = self.snapshot_to(d, force=force)
                    r.update(self.prune(d))
                    self._set_status(d.name, last_ok=time.time(), last_error=None, last_snapshot=r["id"])
                    results[d.name] = r
                except Exception as e:  # noqa: BLE001
                    msg = explain(e, str(getattr(d, "root", "")) or None)
                    self._set_status(d.name, last_error=msg, last_error_at=time.time())
                    results[d.name] = {"error": msg}
            self._set_status("_all", last_run=time.time())
            return results
        finally:
            self.running = None
            self.lock.release()

    def run_in_background(self, only: str | None = None, force: bool = False) -> None:
        if self.lock.locked():
            raise BackupError("ほかのバックアップ・復元を実行中です")
        threading.Thread(target=lambda: self._quiet(self.run, only, force), daemon=True, name="emusync-backup").start()

    @staticmethod
    def _quiet(f, *a):
        with contextlib.suppress(Exception):
            f(*a)

    # ---- 毎日の自動バックアップ
    def due(self, now: float | None = None) -> bool:
        c = self.config()
        if not c["auto"] or not self.dests(c):
            return False
        now = now if now is not None else time.time()
        local = time.localtime(now)
        if local.tm_hour < c["hour"]:
            return False
        last = self.status().get("_all", {}).get("last_run")
        if last is None:
            return True
        ll = time.localtime(last)
        return (ll.tm_year, ll.tm_yday) != (local.tm_year, local.tm_yday)

    def start_scheduler(self, every: int = 300) -> None:
        if self._thread and self._thread.is_alive():
            return

        def loop():
            while not self._stop.wait(every):
                with contextlib.suppress(Exception):
                    if self.due():
                        self.run()
        self._thread = threading.Thread(target=loop, daemon=True, name="emusync-backup-scheduler")
        self._thread.start()

    def stop_scheduler(self) -> None:
        self._stop.set()

    # ---- 世代を見る
    def snapshots(self, src) -> list[dict]:
        out = []
        for i in reversed(self.list_ids(src)):
            try:
                m = json.loads(src.get(f"snapshots/{i}/manifest.json"))
            except (BackupError, ValueError):
                continue
            targets = sorted({f["path"].split("/", 1)[0] for f in m["files"]})
            out.append({"id": i, "created_at": m["created_at"], "files": len(m["files"]),
                        "total_bytes": m.get("total_bytes", 0), "targets": targets})
        return out

    @staticmethod
    def manifest(src, sid: str) -> dict:
        try:
            parse_id(sid)
        except ValueError:
            raise BackupError("その世代はありません")
        return json.loads(src.get(f"snapshots/{sid}/manifest.json"))

    # ---- 戻す
    def _blob_bytes(self, src, sha: str) -> bytes:
        local = self.core.blob_path(sha)
        if local.exists():
            data = local.read_bytes()
            if hashlib.sha256(data).hexdigest() == sha:
                return data
        data = src.get(f"blobs/{sha[:2]}/{sha}")
        if hashlib.sha256(data).hexdigest() != sha:
            raise BackupError(f"バックアップの実体が壊れています: {sha}")
        return data

    def restore_files(self, src, sid: str, paths: Iterable[str] | None = None, targets: Iterable[str] | None = None) -> dict:
        """その世代の中身を、新しい版として置く（paths か targets で選ぶ）。返り値は {restored, unchanged}"""
        m = self.manifest(src, sid)
        want_paths = set(paths or [])
        want_targets = set(targets or [])
        chosen = [f for f in m["files"] if f["path"] in want_paths or f["path"].split("/", 1)[0] in want_targets]
        if not chosen:
            raise BackupError("戻すファイルがありません")
        if not self.lock.acquire(blocking=False):
            raise BackupError("ほかのバックアップ・復元を実行中です")
        try:
            restored = unchanged = 0
            for f in chosen:
                r = self.core.put_version(f["path"], self._blob_bytes(src, f["sha256"]), BACKUP_DEVICE, None)
                if r.get("unchanged"):
                    unchanged += 1
                else:
                    restored += 1
            return {"restored": restored, "unchanged": unchanged}
        finally:
            self.lock.release()

    def restore_all(self, src, sid: str) -> dict:
        """
        サーバー全体をその世代に戻す。実体を先にそろえ、それから DB を入れ替える。
        今の DB は pre-restore-<日時>.db に残し、管理画面のパスワード・バックアップの設定は今のものを残す
        """
        m = self.manifest(src, sid)
        if not self.lock.acquire(blocking=False):
            raise BackupError("ほかのバックアップ・復元を実行中です")
        self.running = "全体を復元しています"
        try:
            # 1. 実体
            fetched = 0
            for sha in m["blobs"]:
                dst = self.core.blob_path(sha)
                if dst.exists() and _sha_file(dst) == sha:
                    continue
                dst.parent.mkdir(parents=True, exist_ok=True)
                tmp = dst.with_name(dst.name + ".restore")
                src.get_to_file(f"blobs/{sha[:2]}/{sha}", tmp)
                if _sha_file(tmp) != sha:
                    tmp.unlink(missing_ok=True)
                    raise BackupError(f"バックアップの実体が壊れています: {sha}")
                os.replace(tmp, dst)
                fetched += 1
            with tempfile.TemporaryDirectory() as td:
                gz = Path(td) / "db.sqlite.gz"
                src.get_to_file(f"snapshots/{sid}/db.sqlite.gz", gz)
                snap = Path(td) / "db.sqlite"
                with gzip.open(gz, "rb") as g, open(snap, "wb") as f:
                    shutil.copyfileobj(g, f)
                check = sqlite3.connect(snap)
                try:
                    if check.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                        raise BackupError("バックアップの DB が壊れています")
                finally:
                    check.close()
                # 2. 今の DB を残す
                saved = self.core.data_dir / f"pre-restore-{snapshot_id()}.db"
                with self.core.db() as live:
                    keep_meta = list(live.execute("SELECT key, value FROM meta")) if _has_table(live, "meta") else []
                    out = sqlite3.connect(saved)
                    try:
                        live.backup(out)
                    finally:
                        out.close()
                # 3. 入れ替える（動いている DB に、バックアップの中身を写す）
                snap_conn = sqlite3.connect(snap)
                try:
                    with self.core.db() as live:
                        snap_conn.backup(live)
                        live.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                        for k, v in keep_meta:
                            live.execute("INSERT OR REPLACE INTO meta VALUES(?,?)", (k, v))
                finally:
                    snap_conn.close()
            return {"fetched_blobs": fetched, "files": len(m["files"]), "saved_db": saved.name}
        finally:
            self.running = None
            self.lock.release()

    # ---- 手元にダウンロード
    def build_zip(self, out: Path) -> str:
        """今の状態を 1 つの世代として zip にする（置き場所と同じ形。そのまま戻せる）。返り値は世代の ID"""
        sid = snapshot_id()
        with tempfile.TemporaryDirectory() as td:
            snap_db = Path(td) / "db.sqlite"
            self._db_copy(snap_db)
            manifest = self._manifest(snap_db, sid, self.fingerprint())
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
                for sha in manifest["blobs"]:
                    src = self.core.blob_path(sha)
                    if src.exists():
                        z.write(src, f"blobs/{sha[:2]}/{sha}")
                z.writestr("blob-index.txt", "\n".join(manifest["blobs"]) + "\n")
                with open(snap_db, "rb") as f:
                    z.writestr(f"snapshots/{sid}/db.sqlite.gz", gzip.compress(f.read(), 6))
                z.writestr(f"snapshots/{sid}/manifest.json", json.dumps(manifest, ensure_ascii=False))
        return sid


def _has_table(conn, name: str) -> bool:
    return conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is not None


def _sha_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()
