"""
モンキーテスト：でたらめな要求（変なパス・壊れたヘッダー・古い版・管理画面の操作・壊れた zip など）を
大量に送っても、

  - 500（サーバー内部のエラー）にならない
  - 受け付けた書き込みだけが反映され、版の番号が飛んだり戻ったりしない（楽観ロックが守られる）
  - 最新版・履歴・実体（blob）・データベースの整合が保たれる
  - 同時に書き込んでも、同じ版が二重にできない（更新が失われない）

ことを確かめる。

  MONKEY_SEED=7 pytest tests/test_monkey.py      … その乱数で再現する（カンマ区切りで複数）
  MONKEY_STEPS=5000 pytest tests/test_monkey.py  … 回数を増やす
"""
import hashlib
import importlib
import io
import os
import random
import sys
import threading
import zipfile
from urllib.parse import quote, unquote

import httpx

try:  # 新しい Starlette の TestClient は httpx2 を使う
    import httpx2
    INVALID_URL: tuple[type[Exception], ...] = (httpx.InvalidURL, httpx2.InvalidURL)
except ImportError:
    INVALID_URL = (httpx.InvalidURL,)
import pytest
from fastapi.testclient import TestClient

from conftest import sha

STEPS = int(os.environ.get("MONKEY_STEPS", "600"))
SEEDS = [int(s) for s in os.environ.get("MONKEY_SEED", "1,2,3").split(",")]
KEEP = 3
MAX_BYTES = 4096
ADMIN_H = {"X-Emusync-Admin": "1"}
PASSWORD = "monkey password"


@pytest.fixture()
def mcore(tmp_path, monkeypatch):
    """上限を小さくした（413 も試せる）サーバー"""
    monkeypatch.setenv("EMUSYNC_DATA", str(tmp_path / "data"))
    monkeypatch.setenv("EMUSYNC_KEEP_VERSIONS", str(KEEP))
    monkeypatch.setenv("EMUSYNC_MAX_BYTES", str(MAX_BYTES))
    for name in ("app", "admin"):
        sys.modules.pop(name, None)
    mod = importlib.import_module("app")
    mod.init_storage()
    mod.ADMIN.set_admin_password(PASSWORD)
    return mod


# ---------------------------------------------------------------- でたらめな値
TARGETS = ["psp", "ps2", "vita", "ps3"]
WEIRD_PATHS = [
    "", "/", "psp", "psp/", "/psp/x", "psp//x", "psp/./x", "psp/../x", "../etc/passwd", "psp/..",
    "psp\\x", "psp/a\\b", "psp/\x00", "psp/a\x00b", " psp/x", "psp/x ", "psp/日本語 セーブ.bin",
    "psp/" + "a" * 300, "psp/" + "/".join(["d"] * 60), "psp/a\nb", "psp/a\r\nX-Evil: 1", "psp/%00", "psp/%2e%2e",
    "psp/#frag", "psp/?q=1", "psp/%25", "psp/😀.sav", "psp/a;b", "psp/\"quoted\"", "psp/'q'", "psp/\t",
    ".", "..", "psp/.hidden", "psp/con", "psp/aux.txt", "psp/a:b", "psp/*", "psp/[x]",
]
WEIRD_TARGETS = ["", "a b", "../x", "psp/x", "ｐｓｐ", "x" * 300, "psp\x00", "-", ".", "..", "😀"]
WEIRD_INTS = ["", "abc", "-1", "0", "1", "2", "999999", "1.5", " 1", "0x1", "9" * 30, "-" + "9" * 30, "１"]
BIG = [2 ** 63, 2 ** 64, -(2 ** 63) - 1, 10 ** 30]


class Rand:
    def __init__(self, seed: int):
        self.r = random.Random(seed)

    def chance(self, p: float) -> bool:
        return self.r.random() < p

    def pick(self, seq):
        return self.r.choice(list(seq))

    def text(self, n: int = 8) -> str:
        alphabet = "abcXYZ019_-. /\\日本😀\x00\n\"'%?#"
        return "".join(self.r.choice(alphabet) for _ in range(self.r.randint(0, n)))

    def data(self, existing: list[bytes]) -> bytes:
        k = self.r.random()
        if existing and k < 0.25:
            return self.pick(existing)  # 同じ内容（unchanged になる）
        if k < 0.35:
            return b""
        if k < 0.40:
            return os.urandom(MAX_BYTES + self.r.randint(1, 100))  # 大きすぎる
        return os.urandom(self.r.randint(1, 300))

    def valid_path(self) -> str:
        depth = self.r.randint(0, 3)
        segs = [self.pick(["SAVE", "DATA", "ULJM05800", "Mcd001.ps2", "日本語", "a b", "x.bin", "1"]) for _ in range(depth)]
        return "/".join([self.pick(TARGETS), *segs, self.pick(["DATA.BIN", "ICON0.PNG", "s.sav", "x"])])

    def int_like(self):
        k = self.r.random()
        if k < 0.6:
            return self.r.randint(-2, 12)
        if k < 0.8:
            return self.pick(WEIRD_INTS)
        return self.pick(BIG)


def sent_path(r, prefix: str) -> str:
    """
    サーバーが受け取ったパス。httpx は ./ や ../ を送る前に縮め、? や # 以降を外す。
    TestClient はそれをさらに 1 回デコードして渡す（%25 → %）ので、それに合わせる
    """
    p = unquote(r.request.url.path)
    return p[len(prefix):] if p.startswith(prefix) else "\x00not-sent"


def url(path: str, rnd: Rand) -> str:
    """たいていは正しくエンコードし、たまにそのまま送る"""
    return quote(path, safe="/") if rnd.chance(0.85) else path


# ---------------------------------------------------------------- 期待する状態
class Model:
    """受け付けられた書き込みから計算した、サーバーにあるべき状態"""

    def __init__(self):
        self.current: dict[str, tuple[int, str]] = {}     # path → (版, sha)
        self.versions: dict[str, dict[int, str]] = {}     # path → {版: sha}
        self.contents: dict[str, bytes] = {}              # sha → 中身

    def version(self, path: str) -> int:
        return self.current.get(path, (0, ""))[0]

    def accept(self, path: str, version: int, data_sha: str):
        self.current[path] = (version, data_sha)
        self.versions.setdefault(path, {})[version] = data_sha


class Failure(AssertionError):
    pass


class Monkey:
    def __init__(self, core, client: TestClient, seed: int):
        self.core = core
        self.c = client
        self.rnd = Rand(seed)
        self.seed = seed
        self.model = Model()
        self.devices: dict[str, dict] = {}   # 名前 → ヘッダー（削除済みは None）
        self.revoked: dict[str, dict] = {}
        self.imports: list[str] = []
        self.log: list[str] = []
        for name in ("phone", "pc"):
            self.devices[name] = {"Authorization": f"Bearer {core.register_device(name)}"}

    # ---- 補助
    def note(self, s: str):
        self.log.append(s)
        del self.log[:-40]

    def check(self, cond: bool, msg: str):
        if not cond:
            raise Failure(f"{msg}\n  seed={self.seed}\n  直前の操作:\n    " + "\n    ".join(self.log[-15:]))

    def send(self, method: str, u: str, **kw):
        self.note(f"{method} {u!r} {({k: v for k, v in kw.items() if k != 'content'})!r}"[:300])
        if "headers" in kw:  # ASCII 以外もそのまま（UTF-8 のバイト列で）送る
            kw = {**kw, "headers": {k: v.encode() if isinstance(v, str) else v for k, v in kw["headers"].items()}}
        try:
            try:
                r = self.c.request(method, u, **kw)
            except INVALID_URL:  # 送る側（httpx）が断った URL は、エンコードして送り直す
                r = self.c.request(method, quote(u, safe="/%"), **kw)
        except Exception as e:  # サーバーの中で例外（TestClient はそのまま投げる）
            raise Failure(f"サーバーで例外: {type(e).__name__}: {e}\n  seed={self.seed}\n  直前の操作:\n    "
                          + "\n    ".join(self.log[-15:])) from e
        self.check(r.status_code < 500, f"{r.status_code} が返った: {r.text[:300]}")
        return r

    def device(self):
        """有効な端末（たまに削除済みや、でたらめなトークン）"""
        k = self.rnd.r.random()
        if k < 0.05:
            return "bad", {"Authorization": self.rnd.pick(["", "Bearer", "Bearer ", "Basic x", "Bearer nope", "bearer x"])}
        if self.revoked and k < 0.10:
            name = self.rnd.pick(self.revoked)
            return "revoked", self.revoked[name]
        name = self.rnd.pick(self.devices)
        return name, self.devices[name]

    def known_or_new_path(self) -> str:
        k = self.rnd.r.random()
        if self.model.current and k < 0.55:
            return self.rnd.pick(self.model.current)
        if k < 0.85:
            return self.rnd.valid_path()
        return self.rnd.pick(WEIRD_PATHS)

    def path_ok(self, path: str) -> bool:
        try:
            self.core.check_path(path)
            return True
        except Exception:
            return False

    # ---- 端末の API
    def op_put(self):
        who, h = self.device()
        path = self.known_or_new_path()
        data = self.rnd.data(list(self.model.contents.values()))
        cur = self.model.version(path)
        k = self.rnd.r.random()
        base = cur if k < 0.7 else (self.rnd.int_like() if k < 0.95 else None)
        sha_h = sha(data) if self.rnd.chance(0.9) else self.rnd.pick(["", "zz", sha(b"other"), sha(data).upper()])
        headers = {**h, "X-SHA256": sha_h}
        if base is not None:
            headers["If-Match"] = str(base)
        r = self.send("PUT", f"/api/files/{url(path, self.rnd)}", content=data, headers=headers)
        if sent_path(r, "/api/files/") != path:
            path = sent_path(r, "/api/files/")
            cur = self.model.version(path)
        if r.status_code == 200:
            j = r.json()
            self.check(who not in ("bad", "revoked"), "無効なトークンで書き込めた")
            self.check(j["path"] == path, f"別のパスに書かれた: {j['path']!r} != {path!r}")
            self.check(base is not None, "If-Match なしで受け付けた")
            self.check(int(base) == cur, f"基準の版 {base} が今の版 {cur} と違うのに受け付けた")
            self.check(len(data) <= MAX_BYTES, "上限より大きいのに受け付けた")
            self.check(j["sha256"] == sha(data), "記録されたハッシュが送った中身と違う")
            if j.get("unchanged"):
                self.check(self.model.current.get(path, (0, ""))[1] == sha(data), "内容が違うのに unchanged")
                self.check(j["version"] == cur, "unchanged なのに版が変わった")
            else:
                self.check(j["version"] == cur + 1, f"版が {cur} → {j['version']}（1 つずつ進むはず）")
                self.model.contents[sha(data)] = data
                self.model.accept(path, j["version"], sha(data))
                for v in [v for v in self.model.versions[path] if v <= j["version"] - KEEP]:
                    del self.model.versions[path][v]
        elif r.status_code == 409:
            d = r.json()["detail"]
            self.check(int(base) != cur, f"基準の版が今の版と同じ（{cur}）なのに 409")
            self.check(d["server_version"] == cur, f"409 の server_version {d['server_version']} != {cur}")
        elif r.status_code == 401:
            self.check(who in ("bad", "revoked"), f"有効な端末 {who} が 401")

    def op_get(self):
        who, h = self.device()
        path = self.known_or_new_path()
        params = {}
        if self.rnd.chance(0.3):
            vs = self.model.versions.get(path)
            params["version"] = self.rnd.pick(vs) if vs and self.rnd.chance(0.6) else self.rnd.int_like()
        r = self.send("GET", f"/api/files/{url(path, self.rnd)}", params=params, headers=h)
        path = sent_path(r, "/api/files/")
        if r.status_code == 200 and who not in ("bad", "revoked"):
            got = sha(r.content)
            self.check(r.headers["X-SHA256"] == got, "X-SHA256 と中身が違う")
            if "version" in params:
                v = int(params["version"])
                want = self.model.versions.get(path, {}).get(v)
                if want is not None:
                    self.check(got == want, f"版 {v} の中身が違う")
            else:
                self.check(self.model.current.get(path) == (int(r.headers["X-Version"]), got),
                           f"最新版が違う: {path!r}")
        elif r.status_code == 200:
            self.check(False, "無効なトークンで読めた")
        if r.status_code == 404 and who not in ("bad", "revoked") and "version" not in params:
            self.check(path not in self.model.current, f"あるはずのファイルが 404: {path!r}")

    def op_manifest(self):
        _, h = self.device()
        prefix = self.rnd.pick(["", "psp/", "ps2", "x", self.rnd.text(), "%", "_", "日本"])
        r = self.send("GET", "/api/manifest", params={"prefix": prefix}, headers=h)
        if r.status_code == 200:
            for f in r.json()["files"]:
                self.check(f["path"].startswith(prefix), f"prefix {prefix!r} の外のファイル {f['path']!r}")

    def op_history(self):
        _, h = self.device()
        path = self.known_or_new_path()
        r = self.send("GET", f"/api/history/{url(path, self.rnd)}", headers=h)
        path = sent_path(r, "/api/history/")
        if r.status_code == 200:
            for v in r.json()["versions"]:
                want = self.model.versions.get(path, {}).get(v["version"])
                self.check(want is None or want == v["sha256"], f"履歴の版 {v['version']} のハッシュが違う")

    def op_restore(self, admin: bool = False):
        path = self.known_or_new_path()
        vs = self.model.versions.get(path)
        version = self.rnd.pick(vs) if vs and self.rnd.chance(0.7) else self.rnd.int_like()
        body = {"version": version} if self.rnd.chance(0.9) else self.rnd.pick([{}, {"version": None}, [], "x", {"v": 1}])
        cur = self.model.version(path)
        if admin:
            who = "admin"
            r = self.send("POST", f"/admin/api/restore/{url(path, self.rnd)}", json=body, headers=self.admin_headers())
        else:
            who, h = self.device()
            r = self.send("POST", f"/api/restore/{url(path, self.rnd)}", json=body, headers=h)
        path = sent_path(r, "/admin/api/restore/" if admin else "/api/restore/")
        cur = self.model.version(path)
        if r.status_code == 200:
            self.check(who not in ("bad", "revoked"), "無効なトークンで復元できた")
            j = r.json()
            v = int(body["version"])
            self.check(v in self.model.versions.get(path, {}), f"ないはずの版 {v} を復元できた")
            self.check(j["version"] == cur + 1, f"復元で版が {cur} → {j['version']}")
            self.model.accept(path, j["version"], self.model.versions[path][v])

    def op_lock(self):
        who, h = self.device()
        target = self.rnd.pick(TARGETS) if self.rnd.chance(0.8) else self.rnd.pick(WEIRD_TARGETS)
        if self.rnd.chance(0.6):
            body = {}
            if self.rnd.chance(0.5):
                body["ttl"] = self.rnd.pick([1, 60, 3600, 0, -5, 10 ** 12, 2 ** 63, 10 ** 400, "x", None, 1.5])
            if self.rnd.chance(0.3):
                body["force"] = self.rnd.pick([True, False, "yes", 1, None])
            r = self.send("POST", f"/api/locks/{url(target, self.rnd)}", json=body, headers=h)
            if r.status_code == 200:
                self.check(r.json()["device"] == who, "ロックの持ち主が違う")
            if r.status_code == 423:
                self.check(r.json()["detail"]["device"] != who, "自分のロックで 423")
        elif self.rnd.chance(0.5):
            self.send("DELETE", f"/api/locks/{url(target, self.rnd)}", headers=h)
        else:
            r = self.send("GET", "/api/locks", headers=h)
            if r.status_code == 200:
                ts = [l["target"] for l in r.json()["locks"]]
                self.check(len(ts) == len(set(ts)), "同じターゲットにロックが 2 つ")

    def op_garbage(self):
        """でたらめな URL・メソッド・本文"""
        _, h = self.device()
        method = self.rnd.pick(["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"])
        u = self.rnd.pick(["/", "/api", "/api/", "/api/files", "/api/files/", "/api/locks/", "/api/restore/",
                           "/admin/api/", "/admin/api/imports/x", "/admin/static/", "/nope"]) + quote(self.rnd.text(), safe="/")
        kw = {"headers": {**h, **(ADMIN_H if self.rnd.chance(0.5) else {})}}
        if method in ("POST", "PUT", "PATCH"):
            kw["content"] = self.rnd.pick([b"", b"{", b"null", b"[]", b'{"version": "x"}', os.urandom(50)])
            kw["headers"]["Content-Type"] = self.rnd.pick(["application/json", "multipart/form-data", "text/plain"])
        self.send(method, u, **kw)

    # ---- 管理画面
    def admin_headers(self):
        return ADMIN_H if self.rnd.chance(0.95) else {}

    def op_admin_read(self):
        k = self.rnd.r.random()
        if k < 0.2:
            r = self.send("GET", "/admin/api/overview")
            if r.status_code == 200:
                self.check(r.json()["versions"] >= len(self.model.current), "概要の版の数が少ない")
        elif k < 0.4:
            target = self.rnd.pick(TARGETS) if self.rnd.chance(0.8) else self.rnd.pick(WEIRD_TARGETS)
            r = self.send("GET", "/admin/api/files", params={"target": target})
            if r.status_code == 200:
                got = {f["path"]: (f["version"], f["sha256"]) for f in r.json()["files"]}
                want = {p: v for p, v in self.model.current.items() if p.startswith(target + "/")}
                self.check(got == want, f"管理画面のファイル一覧（{target}）が違う")
        elif k < 0.6:
            path = self.known_or_new_path()
            self.send("GET", f"/admin/api/history/{url(path, self.rnd)}")
        elif k < 0.85:
            path = self.known_or_new_path()
            params = {"version": self.rnd.int_like()} if self.rnd.chance(0.4) else {}
            r = self.send("GET", f"/admin/api/download/{url(path, self.rnd)}", params=params)
            path = sent_path(r, "/admin/api/download/")
            if r.status_code == 200 and not params:
                self.check(self.model.current.get(path, (0, ""))[1] == sha(r.content), "管理画面のダウンロードの中身が違う")
        else:
            self.send("GET", "/admin/api/" + self.rnd.pick(["devices", "locks", "session"]))

    def op_admin_devices(self):
        k = self.rnd.r.random()
        if k < 0.5:
            name = self.rnd.pick(["tablet", "laptop", "phone", "pc", "admin", "admin-import", "a b", "", "x" * 70,
                                  " tablet ", "日本", "new-" + str(self.rnd.r.randint(0, 9))])
            r = self.send("POST", "/admin/api/devices", json={"name": name}, headers=self.admin_headers())
            if r.status_code == 200:
                n = r.json()["name"]
                self.check(n not in self.devices, f"既にある端末 {n} を作り直せた")
                self.devices[n] = {"Authorization": f"Bearer {r.json()['token']}"}
                self.revoked.pop(n, None)
        else:
            name = self.rnd.pick([*self.devices, "nope", "", "a/b"]) if self.devices else "nope"
            if name in ("phone", "pc") and self.rnd.chance(0.8):
                return  # 主な端末はあまり消さない
            r = self.send("DELETE", f"/admin/api/devices/{quote(name)}", headers=self.admin_headers())
            if r.status_code == 200:
                self.check(name in self.devices, f"ないはずの端末 {name!r} を消せた")
                self.revoked[name] = self.devices.pop(name)
        if not self.devices:  # 端末が 1 つもないと続けられない
            self.devices["phone"] = {"Authorization": f"Bearer {self.core.register_device('phone')}"}

    def op_admin_locks(self):
        target = self.rnd.pick(TARGETS) if self.rnd.chance(0.8) else self.rnd.pick(WEIRD_TARGETS)
        r = self.send("DELETE", f"/admin/api/locks/{url(target, self.rnd)}", headers=self.admin_headers())
        if r.status_code == 200:
            locks = self.send("GET", "/admin/api/locks").json()["locks"]
            self.check(target not in [l["target"] for l in locks], "管理画面で外したロックが残っている")

    def random_zip(self) -> bytes:
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            top = self.rnd.pick(["", "SAVEDATA/", "memcards/", "../", "/abs/", "a\\b\\"])
            for _ in range(self.rnd.r.randint(0, 6)):
                name = top + self.rnd.pick(["ULJM05800/DATA.BIN", "Mcd001.ps2", "x", "dir/", "__MACOSX/._x",
                                            "../evil", "a//b", "日本.sav", "Thumbs.db", "", "a\x00b", self.rnd.text()])
                if name.endswith("/"):
                    z.writestr(zipfile.ZipInfo(name), b"")
                else:
                    z.writestr(name or "empty", self.rnd.data(list(self.model.contents.values())))
        data = buf.getvalue()
        if self.rnd.chance(0.1):
            data = data[: len(data) // 2]  # 壊れた zip
        return data

    def op_admin_import(self):
        k = self.rnd.r.random()
        if k < 0.4 or not self.imports:
            files = {}
            data = {}
            if self.rnd.chance(0.6):
                files["zip"] = ("x.zip", self.random_zip(), "application/zip")
            else:
                n = self.rnd.r.randint(0, 4)
                files = [("files", (f"f{i}", self.rnd.data([]), "application/octet-stream")) for i in range(n)]
                data["paths"] = [self.rnd.pick(["SAVE/DATA.BIN", "a/b", "../x", "", "c\\d", "日本/x", "\x00"])
                                 for _ in range(n + (1 if self.rnd.chance(0.1) else 0))]
            if self.rnd.chance(0.5):
                data["target"] = self.rnd.pick(TARGETS + WEIRD_TARGETS)
            r = self.send("POST", "/admin/api/imports", files=files or None, data=data, headers=self.admin_headers())
            if r.status_code == 200:
                self.imports.append(r.json()["id"])
            return
        iid = self.rnd.pick(self.imports) if self.rnd.chance(0.9) else self.rnd.pick(["0" * 16, "x", "../../x", "%2e%2e"])
        opt = {}
        if self.rnd.chance(0.9):
            opt["target"] = self.rnd.pick(TARGETS) if self.rnd.chance(0.85) else self.rnd.pick(WEIRD_TARGETS)
        if self.rnd.chance(0.4):
            opt["prefix"] = self.rnd.pick(["", "SAVEDATA", "../x", "/", "a//b", "日本", self.rnd.text()])
        if self.rnd.chance(0.3):
            opt["strip_top"] = self.rnd.pick([True, False])
        if self.rnd.chance(0.3):
            opt["excludes"] = self.rnd.pick([[], ["*"], ["["], ["*.db", ""], ["**/x"], "x", [None]])
        if self.rnd.chance(0.5):
            opt["include_updates"] = self.rnd.pick([True, False])
        what = self.rnd.pick(["preview", "preview", "commit", "cancel"])
        if what == "cancel":
            r = self.send("DELETE", f"/admin/api/imports/{quote(iid)}", headers=self.admin_headers())
            if r.status_code == 200 and iid in self.imports:
                self.imports.remove(iid)
            return
        before = dict(self.model.current)
        r = self.send("POST", f"/admin/api/imports/{quote(iid)}/{what}", json=opt, headers=self.admin_headers())
        if r.status_code != 200:
            return
        if what == "preview":
            for item in r.json()["items"]:
                if item["status"] in ("new", "update", "skip_update", "same"):
                    self.check(self.path_ok(item["path"]), f"使えないパス {item['path']!r} が取り込み予定に入った")
                    self.check(item["path"].startswith(opt.get("target", "") + "/"), "ターゲットの外に取り込もうとしている")
            return
        # 取り込んだ：サーバーの状態を読み直す（版が戻ったり、ファイルが消えたりしていないこと）
        self.imports.remove(iid)
        self.resync()
        for p, (v, s) in before.items():
            self.check(p in self.model.current, f"取り込みでファイルが消えた: {p!r}")
            nv, ns = self.model.current[p]
            self.check(nv >= v, f"取り込みで版が戻った: {p!r} {v} → {nv}")
            if not opt.get("include_updates"):
                self.check((nv, ns) == (v, s), f"更新を取り込まない設定なのに変わった: {p!r}")
        for p in self.model.current:
            if p not in before:
                self.check(p.startswith(opt["target"] + "/"), f"ターゲットの外にファイルができた: {p!r}")

    def op_admin_session(self):
        k = self.rnd.r.random()
        if k < 0.5:
            self.send("POST", "/admin/api/logout", headers=ADMIN_H)
            r = self.send("GET", "/admin/api/overview")
            self.check(r.status_code == 401, "ログアウトしたのに管理画面の API が使えた")
        self.login()

    def login(self):
        r = self.send("POST", "/admin/api/login", json={"password": PASSWORD}, headers=ADMIN_H)
        self.check(r.status_code == 200, "ログインできない")

    # ---- 全体の照合
    def resync(self):
        """サーバーの状態をモデルに読み込む（取り込みの後）"""
        _, h = self.any_valid_device()
        for f in self.c.get("/api/manifest", headers=h).json()["files"]:
            if self.model.current.get(f["path"]) != (f["version"], f["sha256"]):
                self.model.accept(f["path"], f["version"], f["sha256"])
                body = self.c.get(f"/api/files/{quote(f['path'], safe='/')}", headers=h).content
                self.model.contents[f["sha256"]] = body

    def any_valid_device(self):
        name = sorted(self.devices)[0]
        return name, self.devices[name]

    def verify_all(self):
        _, h = self.any_valid_device()
        files = self.c.get("/api/manifest", headers=h).json()["files"]
        got = {f["path"]: (f["version"], f["sha256"]) for f in files}
        missing = set(self.model.current) - set(got)
        extra = set(got) - set(self.model.current)
        self.check(not missing and not extra, f"一覧が違う: 足りない {sorted(missing)[:5]} 余分 {sorted(extra)[:5]}")
        for p, want in self.model.current.items():
            self.check(got[p] == want, f"{p!r} の最新版が違う: {got[p]} != {want}")
            r = self.c.get(f"/api/files/{quote(p, safe='/')}", headers=h)
            self.check(r.status_code == 200 and sha(r.content) == want[1], f"{p!r} の中身が違う")
            hist = self.c.get(f"/api/history/{quote(p, safe='/')}", headers=h).json()["versions"]
            self.check(hist and hist[0]["version"] == want[0], f"{p!r} の履歴の先頭が最新版でない")
            for v in hist:
                known = self.model.versions[p].get(v["version"])
                self.check(known is None or known == v["sha256"], f"{p!r} の履歴 v{v['version']} が違う")
        with self.core.db() as conn:
            self.check(conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "データベースが壊れている")
            shas = {r[0] for r in conn.execute("SELECT sha256 FROM versions UNION SELECT sha256 FROM files")}
            dup = conn.execute("SELECT path, version, COUNT(*) FROM versions GROUP BY path, version HAVING COUNT(*) > 1").fetchall()
        self.check(not dup, f"同じ版が二重にある: {[tuple(d) for d in dup][:5]}")
        for s in shas:
            bp = self.core.blob_path(s)
            self.check(bp.exists(), f"実体（blob）がない: {s}")
            self.check(hashlib.sha256(bp.read_bytes()).hexdigest() == s, f"実体（blob）の中身が名前と違う: {s}")

    def run(self, steps: int):
        self.login()
        ops = [
            (self.op_put, 30), (self.op_get, 14), (self.op_manifest, 5), (self.op_history, 6),
            (self.op_restore, 6), (lambda: self.op_restore(admin=True), 3), (self.op_lock, 8),
            (self.op_garbage, 6), (self.op_admin_read, 8), (self.op_admin_devices, 3),
            (self.op_admin_locks, 2), (self.op_admin_import, 8), (self.op_admin_session, 1),
        ]
        fns, weights = zip(*ops)
        for i in range(steps):
            self.rnd.r.choices(fns, weights)[0]()
            if i % 100 == 99:
                self.verify_all()
        self.verify_all()


@pytest.mark.parametrize("seed", SEEDS)
def test_monkey(mcore, seed):
    with TestClient(mcore.app, base_url="https://testserver") as c:
        m = Monkey(mcore, c, seed)
        m.run(STEPS)
        # ちゃんと色々な結果が出ていること（でたらめすぎて全部はじかれていないか）
        assert len(m.model.current) >= 5, "書き込みがほとんど成功していない"


def test_monkey_concurrent_writes(mcore):
    """同じファイルを複数の端末が同時に読み書きしても、同じ版が二重にできず、受け付けた更新は失われない"""
    tokens = {n: {"Authorization": f"Bearer {mcore.register_device(n)}"} for n in ("w1", "w2", "w3", "w4")}
    paths = ["psp/A/DATA.BIN", "psp/B/DATA.BIN", "ps2/Mcd001.ps2"]
    accepted: list[tuple[str, int, str]] = []
    lock = threading.Lock()
    errors: list[str] = []
    steps = max(STEPS // 4, 60)

    def worker(name: str, seed: int):
        rnd = random.Random(seed)
        h = tokens[name]
        with TestClient(mcore.app, base_url="https://testserver") as c:
            for _ in range(steps):
                p = rnd.choice(paths)
                r = c.get("/api/manifest", params={"prefix": p}, headers=h)
                cur = next((f["version"] for f in r.json()["files"] if f["path"] == p), 0)
                data = f"{name}-{rnd.random()}".encode()
                r = c.put(f"/api/files/{p}", content=data, headers={**h, "If-Match": str(cur), "X-SHA256": sha(data)})
                if r.status_code == 200:
                    with lock:
                        accepted.append((p, r.json()["version"], sha(data)))
                elif r.status_code != 409:
                    errors.append(f"{r.status_code} {r.text[:200]}")

    threads = [threading.Thread(target=worker, args=(n, i)) for i, n in enumerate(tokens)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors, errors[:5]
    with TestClient(mcore.app, base_url="https://testserver") as c:
        h = tokens["w1"]
        for p in paths:
            vs = sorted(v for q, v, _ in accepted if q == p)
            assert vs == list(range(1, len(vs) + 1)), f"{p}: 受け付けた版が連番でない（二重・欠け）: {vs[:20]}"
            final = next(f for f in c.get("/api/manifest", headers=h).json()["files"] if f["path"] == p)
            last = max((a for a in accepted if a[0] == p), key=lambda a: a[1])
            assert (final["version"], final["sha256"]) == (last[1], last[2]), f"{p}: 最後に受け付けた更新が最新になっていない"
