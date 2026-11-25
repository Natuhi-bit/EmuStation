"""二次バックアップ：別のディスク・Nextcloud（WebDAV）・ダウンロードへの写しと、全体・一部の復元"""
import base64
import io
import json
import shutil
import threading
import time
import urllib.parse
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from conftest import add_device, sha

H = {"X-Emusync-Admin": "1"}


def put(client, h, path, data, base):
    r = client.put(f"/api/files/{urllib.parse.quote(path)}", content=data,
                   headers={**h, "If-Match": str(base), "X-SHA256": sha(data)})
    assert r.status_code == 200, r.text
    return r.json()


def state(client, h):
    files = client.get("/api/manifest", headers=h).json()["files"]
    return {f["path"]: (f["version"], f["sha256"]) for f in files}


@pytest.fixture()
def admin(client, core):
    core.ADMIN.set_admin_password("correct horse")
    assert client.post("/admin/api/login", json={"password": "correct horse"}, headers=H).status_code == 200
    return client


@pytest.fixture()
def disk(tmp_path):
    (tmp_path / "usb").mkdir()  # つないだ USB の HDD のつもり
    return tmp_path / "usb" / "emusync-backup"


def seed(client, core):
    h = add_device(core, "phone")
    put(client, h, "psp/ULJM05800/DATA.BIN", b"psp-v1", 0)
    put(client, h, "psp/ULJM05800/DATA.BIN", b"psp-v2", 1)
    put(client, h, "ps2/Mcd001.ps2", b"ps2-v1", 0)
    put(client, h, "vita/日本語/セーブ.bin", b"vita", 0)
    return h


# ---------------------------------------------------------------- 別のディスク
def test_snapshot_skipWhenUnchanged_andDedup(client, core, disk):
    h = seed(client, core)
    bk = core.ADMIN.backup
    bk.save_config({"local_dir": str(disk)})
    r1 = bk.run()["disk"]
    assert r1["created"] and r1["uploaded"] == 4  # 実体 4 つ（psp の 2 版・ps2・vita）
    assert (disk / "snapshots" / r1["id"] / "manifest.json").exists()
    # 変わっていなければ作らない
    r2 = bk.run()["disk"]
    assert not r2["created"] and r2["id"] == r1["id"]
    # 変わったら、増えた実体だけ送る
    time.sleep(1.1)
    put(client, h, "ps2/Mcd001.ps2", b"ps2-v2", 1)
    r3 = bk.run()["disk"]
    assert r3["created"] and r3["uploaded"] == 1 and r3["id"] != r1["id"]
    assert len(list((disk / "blobs").rglob("*"))) - len(list((disk / "blobs").iterdir())) == 5
    st = bk.status()["disk"]
    assert st["last_snapshot"] == r3["id"] and st["last_error"] is None


def test_fullRestore_afterLosingTheServerData(client, core, disk):
    h = seed(client, core)
    before = state(client, h)
    hist = client.get("/api/history/psp/ULJM05800/DATA.BIN", headers=h).json()["versions"]
    bk = core.ADMIN.backup
    bk.save_config({"local_dir": str(disk)})
    sid = bk.run()["disk"]["id"]

    # サーバーのデータを失う（DB も実体も）→ 入れ直す
    shutil.rmtree(core.DATA_DIR)
    core.init_storage()
    core.ADMIN.set_admin_password("new password")
    assert state(client, add_device(core, "tmp")) == {}

    from backup import LocalDest
    r = bk.restore_all(LocalDest(disk), sid)
    assert r["files"] == 3 and r["fetched_blobs"] == 4
    assert state(client, h) == before  # 前の端末のトークンでそのまま使える
    assert client.get("/api/history/psp/ULJM05800/DATA.BIN", headers=h).json()["versions"] == hist
    assert client.get("/api/files/vita/日本語/セーブ.bin", headers=h).content == b"vita"
    # 管理画面のパスワードは今のもの
    assert client.post("/admin/api/login", json={"password": "new password"}, headers=H).status_code == 200
    # 前の DB を残してある
    assert (core.DATA_DIR / r["saved_db"]).exists()


def test_partialRestore_targetAndSingleFile_becomeNewVersions(client, core, disk):
    h = seed(client, core)
    bk = core.ADMIN.backup
    bk.save_config({"local_dir": str(disk)})
    sid = bk.run()["disk"]["id"]
    put(client, h, "psp/ULJM05800/DATA.BIN", b"broken", 2)
    put(client, h, "ps2/Mcd001.ps2", b"ps2-later", 1)

    from backup import LocalDest
    src = LocalDest(disk)
    r = bk.restore_files(src, sid, targets=["psp"])
    assert r == {"restored": 1, "unchanged": 0}
    got = client.get("/api/files/psp/ULJM05800/DATA.BIN", headers=h)
    assert got.content == b"psp-v2" and got.headers["X-Version"] == "4"
    assert client.get("/api/manifest", params={"prefix": "psp/"}, headers=h).json()["files"][0]["device"] == "admin-backup"
    # ほかのハードは変えていない
    assert client.get("/api/files/ps2/Mcd001.ps2", headers=h).content == b"ps2-later"
    # 1 ファイルだけ
    assert bk.restore_files(src, sid, paths=["ps2/Mcd001.ps2"]) == {"restored": 1, "unchanged": 0}
    assert client.get("/api/files/ps2/Mcd001.ps2", headers=h).content == b"ps2-v1"
    # 同じ内容なら版を進めない
    assert bk.restore_files(src, sid, paths=["ps2/Mcd001.ps2"]) == {"restored": 0, "unchanged": 1}


def test_restore_detectsCorruptBackup(client, core, disk):
    seed(client, core)
    bk = core.ADMIN.backup
    bk.save_config({"local_dir": str(disk)})
    sid = bk.run()["disk"]["id"]
    shutil.rmtree(core.DATA_DIR / "blobs")
    core.init_storage()
    for f in (disk / "blobs").rglob("*"):
        if f.is_file():
            f.write_bytes(b"corrupted")
    from backup import BackupError, LocalDest
    with pytest.raises(BackupError, match="壊れています"):
        bk.restore_all(LocalDest(disk), sid)


# ---------------------------------------------------------------- 世代
def test_keepPolicy():
    from backup import keep_ids, snapshot_id
    day = 86400
    t0 = 1_800_000_000
    ids = [snapshot_id(t0 - i * day) for i in range(400)]  # 毎日 1 個、400 日分
    keep = keep_ids(ids)
    # 7 日分 ＋ 週ごと（重なりを除く）＋ 月ごと 6
    assert ids[0] in keep and all(i in keep for i in ids[:7])
    assert len(keep) <= 7 + 4 + 6 and len(keep) >= 12
    oldest_kept = min(keep)
    assert oldest_kept >= snapshot_id(t0 - 200 * day)  # 半年ほど前まで


def test_prune_removesOldSnapshots_andUnusedBlobs(client, core, disk):
    seed(client, core)
    bk = core.ADMIN.backup
    bk.save_config({"local_dir": str(disk)})
    sid = bk.run()["disk"]["id"]
    from backup import LocalDest, snapshot_id
    dest = LocalDest(disk)
    # 1 年前の世代（もう要らない）と、それだけが使っている実体
    old = snapshot_id(time.time() - 400 * 86400)
    old_blob = "ab" * 32
    dest.put_bytes(f"blobs/ab/{old_blob}", b"x")
    dest.put_bytes("blob-index.txt", dest.get("blob-index.txt") + old_blob.encode() + b"\n")
    for i in range(10):  # 古い月の世代を並べる
        oid = snapshot_id(time.time() - (30 + i * 31) * 86400)
        dest.put_bytes(f"snapshots/{oid}/manifest.json", json.dumps({"id": oid, "created_at": 0, "files": [], "blobs": []}).encode())
    dest.put_bytes(f"snapshots/{old}/manifest.json", json.dumps({"id": old, "created_at": 0, "files": [], "blobs": [old_blob]}).encode())
    r = bk.prune(dest)
    assert old in r["removed_snapshots"] and r["removed_blobs"] == 1
    assert not dest.exists(f"blobs/ab/{old_blob}")
    assert sid in bk.list_ids(dest)
    assert len(bk.list_ids(dest)) <= 1 + 6 + 4


def test_schedule_dueOncePerDay(core, disk, monkeypatch):
    bk = core.ADMIN.backup
    assert not bk.due()  # 置き場所がない
    bk.save_config({"local_dir": str(disk), "hour": 4})
    t = time.mktime((2026, 11, 25, 3, 0, 0, 0, 0, -1))
    assert not bk.due(t)  # まだ 4 時前
    assert bk.due(t + 3600)
    bk._set_status("_all", last_run=t + 3600)
    assert not bk.due(t + 7200)  # 今日はもうした
    assert bk.due(t + 86400 + 3600)  # 次の日
    bk.save_config({"auto": False})
    assert not bk.due(t + 86400 + 3600)


def test_localDest_refusesSameDiskAsData(core, tmp_path, monkeypatch):
    from backup import BackupError, LocalDest
    monkeypatch.delenv("EMUSYNC_BACKUP_ALLOW_SAME_DISK")
    (tmp_path / "mnt").mkdir()  # マウントしていない（空の）フォルダ
    with pytest.raises(BackupError, match="同じディスク"):
        LocalDest(tmp_path / "mnt" / "emusync-backup").check(core.DATA_DIR)
    with pytest.raises(BackupError, match="フォルダがありません"):
        LocalDest(tmp_path / "nothing" / "emusync-backup").check(core.DATA_DIR)


def test_config_validation(core, tmp_path):
    from backup import BackupError
    bk = core.ADMIN.backup
    with pytest.raises(BackupError, match="同じディスク|データのフォルダ"):
        bk.save_config({"local_dir": str(core.DATA_DIR / "backup")})
    with pytest.raises(BackupError, match="/ から"):
        bk.save_config({"local_dir": "relative/dir"})
    with pytest.raises(BackupError, match="https://"):
        bk.save_config({"nextcloud": {"url": "cloud.example.com"}})
    with pytest.raises(BackupError, match="0〜23"):
        bk.save_config({"hour": 25})


# ---------------------------------------------------------------- Nextcloud（WebDAV）
class FakeDav(BaseHTTPRequestHandler):
    """Nextcloud のような WebDAV（Basic 認証・親フォルダがないと 409・あるフォルダの MKCOL は 405）"""
    root: Path
    prefix = "/remote.php/dav/files/me/"
    auth = "Basic " + base64.b64encode(b"me:app-pass").decode()

    def log_message(self, *a):
        pass

    def _fs(self) -> Path | None:
        path = urllib.parse.unquote(urllib.parse.urlparse(self.path).path)
        if not path.startswith(self.prefix.rstrip("/")):
            return None
        return self.root / path[len(self.prefix):].strip("/") if len(path) > len(self.prefix) else self.root

    def _ok(self) -> bool:
        if self.headers.get("Authorization") != self.auth:
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return False
        return True

    def _reply(self, code: int, body: bytes = b"", ctype="application/octet-stream"):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Type", ctype)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_MKCOL(self):
        if not self._ok():
            return
        p = self._fs()
        if p.exists():
            return self._reply(405)
        if not p.parent.exists():
            return self._reply(409)
        p.mkdir()
        self._reply(201)

    def do_PUT(self):
        if not self._ok():
            return
        p = self._fs()
        data = self.rfile.read(int(self.headers["Content-Length"]))
        if not p.parent.exists():
            return self._reply(409)
        p.write_bytes(data)
        self._reply(201)

    def do_GET(self):
        if not self._ok():
            return
        p = self._fs()
        if not p.is_file():
            return self._reply(404)
        self._reply(200, p.read_bytes())

    do_HEAD = do_GET

    def do_DELETE(self):
        if not self._ok():
            return
        p = self._fs()
        if not p.exists():
            return self._reply(404)
        shutil.rmtree(p) if p.is_dir() else p.unlink()
        self._reply(204)

    def do_PROPFIND(self):
        if not self._ok():
            return
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        p = self._fs()
        if not p.exists():
            return self._reply(404)
        items = [p] + (sorted(p.iterdir()) if p.is_dir() else [])
        out = ['<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">']
        for it in items:
            rel = it.relative_to(self.root).as_posix() if it != self.root else ""
            href = self.prefix + "/".join(urllib.parse.quote(x) for x in rel.split("/") if x) + ("/" if it.is_dir() else "")
            rt = "<d:collection/>" if it.is_dir() else ""
            out.append(f"<d:response><d:href>{href}</d:href><d:propstat><d:prop><d:resourcetype>{rt}</d:resourcetype></d:prop>"
                       f"<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        out.append("</d:multistatus>")
        self._reply(207, "".join(out).encode(), "application/xml")


@pytest.fixture()
def nextcloud(tmp_path, monkeypatch):
    for k in ("http_proxy", "HTTP_PROXY", "https_proxy", "HTTPS_PROXY"):
        monkeypatch.delenv(k, raising=False)
    root = tmp_path / "nextcloud-files"
    root.mkdir()
    handler = type("H", (FakeDav,), {"root": root})
    srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_port}/remote.php/dav/files/me/emusync-backup", root
    srv.shutdown()


def test_nextcloud_backupAndRestore(client, core, nextcloud):
    url, root = nextcloud
    h = seed(client, core)
    before = state(client, h)
    bk = core.ADMIN.backup
    bk.save_config({"nextcloud": {"url": url, "user": "me", "password": "app-pass"}})
    for d in bk.dests():
        d.check()
    r = bk.run()["nextcloud"]
    assert r["created"], r
    assert (root / "emusync-backup" / "snapshots" / r["id"] / "manifest.json").exists()
    assert [s["id"] for s in bk.snapshots(bk.dest("nextcloud"))] == [r["id"]]
    assert not bk.run()["nextcloud"]["created"]

    shutil.rmtree(core.DATA_DIR)
    core.init_storage()
    bk.save_config({"nextcloud": {"url": url, "user": "me", "password": "app-pass"}})
    bk.restore_all(bk.dest("nextcloud"), r["id"])
    assert state(client, h) == before


def test_nextcloud_wrongPassword_isReported(core, nextcloud):
    url, _ = nextcloud
    bk = core.ADMIN.backup
    bk.save_config({"nextcloud": {"url": url, "user": "me", "password": "wrong"}})
    r = bk.run()["nextcloud"]
    assert "ログインできません" in r["error"]
    assert "ログインできません" in bk.status()["nextcloud"]["last_error"]


# ---------------------------------------------------------------- 管理画面
def test_adminApi_configTestRunListRestore(admin, core, disk):
    h = seed(admin, core)
    r = admin.put("/admin/api/backup/config", json={"local_dir": str(disk), "hour": 3,
                                                   "nextcloud": {"url": "https://x/remote.php/dav", "user": "u", "password": "secret"}}, headers=H)
    assert r.status_code == 200
    body = r.json()
    assert body["config"]["nextcloud"]["has_password"] is True
    assert "secret" not in json.dumps(body)  # パスワードは画面に返さない
    # パスワードを空で送ると、今のまま
    admin.put("/admin/api/backup/config", json={"nextcloud": {"url": "https://x/remote.php/dav", "user": "u", "password": ""}}, headers=H)
    assert core.ADMIN.backup.config()["nextcloud"]["password"] == "secret"
    admin.put("/admin/api/backup/config", json={"nextcloud": {"url": "", "user": "", "clear_password": True}}, headers=H)

    t = admin.post("/admin/api/backup/test", headers=H).json()
    assert t == {"disk": {"ok": True}}
    assert admin.post("/admin/api/backup/run", json={}, headers=H).json() == {"started": True}
    for _ in range(100):
        if not admin.get("/admin/api/backup").json()["running"] and core.ADMIN.backup.status().get("disk", {}).get("last_ok"):
            break
        time.sleep(0.05)
    info = admin.get("/admin/api/backup").json()
    assert info["dests"][0]["status"]["last_ok"]
    snaps = admin.get("/admin/api/backup/disk/snapshots").json()["snapshots"]
    assert len(snaps) == 1 and snaps[0]["files"] == 3 and snaps[0]["targets"] == ["ps2", "psp", "vita"]
    sid = snaps[0]["id"]
    files = admin.get(f"/admin/api/backup/disk/snapshots/{sid}").json()["files"]
    assert {f["path"] for f in files} == {"psp/ULJM05800/DATA.BIN", "ps2/Mcd001.ps2", "vita/日本語/セーブ.bin"}

    put(admin, h, "ps2/Mcd001.ps2", b"later", 1)
    r = admin.post(f"/admin/api/backup/disk/snapshots/{sid}/restore", json={"mode": "files", "paths": ["ps2/Mcd001.ps2"]}, headers=H)
    assert r.json() == {"restored": 1, "unchanged": 0}
    # 全体は ID を打ち込まないと戻さない
    assert admin.post(f"/admin/api/backup/disk/snapshots/{sid}/restore", json={"mode": "all"}, headers=H).status_code == 400
    r = admin.post(f"/admin/api/backup/disk/snapshots/{sid}/restore", json={"mode": "all", "confirm": sid}, headers=H)
    assert r.status_code == 200 and r.json()["files"] == 3
    # 変な世代・置き場所
    assert admin.get("/admin/api/backup/disk/snapshots/../../etc").status_code in (400, 404)
    assert admin.get("/admin/api/backup/nextcloud/snapshots").status_code == 400
    # ログインしていなければ使えない
    admin.post("/admin/api/logout", headers=H)
    assert admin.get("/admin/api/backup").status_code == 401


def test_download_zip_andRestoreFromIt(admin, core):
    h = seed(admin, core)
    before = state(admin, h)
    r = admin.get("/admin/api/backup/download")
    assert r.status_code == 200 and r.headers["content-type"] == "application/zip"
    assert "emusync-backup-" in r.headers["content-disposition"]
    zpath = core.DATA_DIR.parent / "dl.zip"
    zpath.write_bytes(r.content)
    names = zipfile.ZipFile(io.BytesIO(r.content)).namelist()
    assert any(n.endswith("/manifest.json") for n in names)
    assert not list(core.DATA_DIR.glob("tmp*.zip"))  # 一時ファイルは消す

    shutil.rmtree(core.DATA_DIR)
    core.init_storage()
    from backup import ZipSource
    src = ZipSource(zpath)
    core.ADMIN.backup.restore_all(src, src.snapshot_id)
    assert state(admin, h) == before
