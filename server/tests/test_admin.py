"""管理画面（/admin/）の API"""
import io
import zipfile

import pytest

from conftest import add_device, sha

H = {"X-Emusync-Admin": "1"}


@pytest.fixture()
def admin(client, core):
    """ログイン済みのクライアント"""
    core.ADMIN.set_admin_password("correct horse")
    r = client.post("/admin/api/login", json={"password": "correct horse"}, headers=H)
    assert r.status_code == 200
    return client


def put(client, h, path, data, base):
    return client.put(f"/api/files/{path}", content=data,
                      headers={**h, "If-Match": str(base), "X-SHA256": sha(data)})


# ---------------------------------------------------------------- ログイン
def test_page_and_static(client):
    r = client.get("/admin", follow_redirects=False)
    assert r.status_code == 307 and r.headers["location"] == "admin/"
    assert "emusync 管理" in client.get("/admin/").text
    assert client.get("/admin/static/admin.js").status_code == 200
    assert client.get("/admin/static/..%2Fapp.py").status_code == 404
    assert client.get("/admin/static/nope.js").status_code == 404


def test_login_flow(client, core):
    assert client.get("/admin/api/session").json() == {"configured": False, "logged_in": False, "upload_ready": True}
    assert client.post("/admin/api/login", json={"password": "x"}, headers=H).status_code == 409

    core.ADMIN.set_admin_password("correct horse")
    assert client.get("/admin/api/session").json()["configured"] is True
    assert client.get("/admin/api/overview").status_code == 401
    assert client.post("/admin/api/login", json={"password": "wrong"}, headers=H).status_code == 401
    # ヘッダーがない（ほかのサイトからのフォーム送信など）
    assert client.post("/admin/api/login", json={"password": "correct horse"}).status_code == 403

    r = client.post("/admin/api/login", json={"password": "correct horse"}, headers=H)
    cookie = r.headers["set-cookie"]
    assert "HttpOnly" in cookie and "SameSite=strict" in cookie and "Secure" in cookie
    assert client.get("/admin/api/session").json()["logged_in"] is True
    assert client.get("/admin/api/overview").status_code == 200

    client.post("/admin/api/logout", headers=H)
    assert client.get("/admin/api/overview").status_code == 401


def test_password_change_invalidates_sessions(admin, core):
    assert admin.get("/admin/api/overview").status_code == 200
    core.ADMIN.set_admin_password("another password")
    assert admin.get("/admin/api/overview").status_code == 401


def test_changes_require_header(admin):
    # ログイン済みでも、変更する操作は専用のヘッダーがないと受け付けない（CSRF 対策）
    assert admin.post("/admin/api/devices", json={"name": "x"}).status_code == 403
    assert admin.post("/admin/api/devices", json={"name": "x"}, headers=H).status_code == 200


def test_device_tokens_do_not_open_admin(client, core):
    h = add_device(core, "phone")
    assert client.get("/admin/api/overview", headers=h).status_code == 401


# ---------------------------------------------------------------- 概要・ファイル
def test_overview_files_history_download_restore(admin, core):
    h = add_device(core, "phone")
    for i, d in enumerate([b"one", b"two", b"three"]):
        put(admin, h, "psp/ULJM05800/DATA.BIN", d, i)
    put(admin, h, "ps2/Mcd001.ps2", b"m" * 100, 0)

    o = admin.get("/admin/api/overview").json()
    assert [(t["name"], t["files"]) for t in o["targets"]] == [("ps2", 1), ("psp", 1)]
    assert o["versions"] == 4 and o["devices"] == 1
    assert o["stored_bytes"] == len(b"one") + len(b"two") + len(b"three") + 100

    f = admin.get("/admin/api/files", params={"target": "psp"}).json()["files"]
    assert [(x["rel"], x["version"]) for x in f] == [("ULJM05800/DATA.BIN", 3)]

    h2 = admin.get("/admin/api/history/psp/ULJM05800/DATA.BIN").json()
    assert [v["version"] for v in h2["versions"]] == [3, 2, 1]
    r = admin.get("/admin/api/download/psp/ULJM05800/DATA.BIN", params={"version": 1})
    assert r.content == b"one" and "DATA.BIN.v1" in r.headers["content-disposition"]
    assert admin.get("/admin/api/download/psp/ULJM05800/DATA.BIN").content == b"three"

    r = admin.post("/admin/api/restore/psp/ULJM05800/DATA.BIN", json={"version": 1}, headers=H)
    assert r.json()["version"] == 4
    m = admin.get("/api/manifest", params={"prefix": "psp/"}, headers=h).json()["files"][0]
    assert m["sha256"] == sha(b"one") and m["device"] == "admin(restore v1)"


# ---------------------------------------------------------------- 端末・ロック
def test_devices(admin, core):
    r = admin.post("/admin/api/devices", json={"name": "android-phone"}, headers=H).json()
    assert admin.get("/api/ping", headers={"Authorization": "Bearer " + r["token"]}).json()["device"] == "android-phone"
    assert admin.post("/admin/api/devices", json={"name": "android-phone"}, headers=H).status_code == 409
    assert admin.post("/admin/api/devices", json={"name": "bad name"}, headers=H).status_code == 400
    assert admin.post("/admin/api/devices", json={"name": "admin-import"}, headers=H).status_code == 400
    assert [d["name"] for d in admin.get("/admin/api/devices").json()["devices"]] == ["android-phone"]
    assert admin.delete("/admin/api/devices/android-phone", headers=H).json() == {"removed": True}
    assert admin.get("/api/ping", headers={"Authorization": "Bearer " + r["token"]}).status_code == 401
    assert admin.delete("/admin/api/devices/android-phone", headers=H).status_code == 404


def test_locks_force_release(admin, core):
    h = add_device(core, "win")
    admin.post("/api/locks/ps2", json={}, headers=h)
    assert [l["device"] for l in admin.get("/admin/api/locks").json()["locks"]] == ["win"]
    admin.delete("/admin/api/locks/ps2", headers=H)
    assert admin.get("/admin/api/locks").json()["locks"] == []


# ---------------------------------------------------------------- インポート
def upload_folder(admin, files: dict, target="psp"):
    data = {"paths": list(files.keys()), "target": target}
    multi = [("files", (p.rsplit("/", 1)[-1], c, "application/octet-stream")) for p, c in files.items()]
    r = admin.post("/admin/api/imports", data=data, files=multi, headers=H)
    assert r.status_code == 200, r.text
    return r.json()


def test_import_folder_preview_and_commit(admin, core):
    h = add_device(core, "phone")
    put(admin, h, "psp/ULJM05800/DATA.BIN", b"server version", 0)
    put(admin, h, "psp/ULJM05801/DATA.BIN", b"same", 0)

    # ブラウザでフォルダを選ぶと、選んだフォルダの名前（SAVEDATA）が先頭に付く
    r = upload_folder(admin, {
        "SAVEDATA/ULJM05800/DATA.BIN": b"nextcloud version",
        "SAVEDATA/ULJM05801/DATA.BIN": b"same",
        "SAVEDATA/ULJM05802/DATA.BIN": b"new save",
        "SAVEDATA/ULJM05802/ICON0.PNG": b"icon",
        "SAVEDATA/desktop.ini": b"junk",
    })
    assert r["files"] == 5 and r["top"] == "SAVEDATA"
    p = r["preview"]
    status = {i["rel"]: i["status"] for i in p["items"]}
    assert status == {
        "ULJM05800/DATA.BIN": "skip_update",   # 内容が違う既存のファイルは、既定では取り込まない
        "ULJM05801/DATA.BIN": "same",
        "ULJM05802/DATA.BIN": "new",
        "ULJM05802/ICON0.PNG": "new",
        "desktop.ini": "excluded",
    }

    c = admin.post(f"/admin/api/imports/{r['id']}/commit", json={"target": "psp"}, headers=H).json()
    assert c["imported"] == {"new": 2, "update": 0}
    m = {f["path"]: f for f in admin.get("/api/manifest", params={"prefix": "psp/"}, headers=h).json()["files"]}
    assert m["psp/ULJM05802/DATA.BIN"]["device"] == "admin-import"
    assert m["psp/ULJM05800/DATA.BIN"]["sha256"] == sha(b"server version")  # 上書きしていない
    assert "psp/desktop.ini" not in m
    # 取り込んだら、置いておいたファイルは消える
    assert admin.post(f"/admin/api/imports/{r['id']}/preview", json={"target": "psp"}, headers=H).status_code == 404


def test_import_with_updates_keeps_history(admin, core):
    h = add_device(core, "phone")
    put(admin, h, "ps2/Mcd001.ps2", b"old", 0)
    r = upload_folder(admin, {"memcards/Mcd001.ps2": b"imported", "memcards/Mcd001.ps2.bak": b"bak"}, target="ps2")
    opt = {"target": "ps2", "include_updates": True, "excludes": ["*.bak"]}
    p = admin.post(f"/admin/api/imports/{r['id']}/preview", json=opt, headers=H).json()
    assert {i["rel"]: i["status"] for i in p["items"]} == {"Mcd001.ps2": "update", "Mcd001.ps2.bak": "excluded"}
    c = admin.post(f"/admin/api/imports/{r['id']}/commit", json=opt, headers=H).json()
    assert c["imported"] == {"new": 0, "update": 1}
    vs = admin.get("/admin/api/history/ps2/Mcd001.ps2").json()["versions"]
    assert [(v["version"], v["device"]) for v in vs] == [(2, "admin-import"), (1, "phone")]
    # 端末は、普通の 409 の規則どおり（v1 を基準にした送信は拒まれる）
    assert put(admin, h, "ps2/Mcd001.ps2", b"phone edit", 1).status_code == 409


def test_import_zip_strip_prefix_and_bad_paths(admin, core):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("DATA.BIN", b"d")
        z.writestr("sub\\ICON0.PNG", b"i")           # Windows の区切り
        z.writestr("../../etc/passwd", b"x")          # ターゲットの外に出ようとする
        z.writestr("__MACOSX/._DATA.BIN", b"mac")
        z.writestr("dir/", b"")
    r = admin.post("/admin/api/imports", files={"zip": ("save.zip", buf.getvalue(), "application/zip")}, headers=H).json()
    assert r["top"] is None and r["preview"] is None
    opt = {"target": "psp", "prefix": "ULJM05800"}
    p = admin.post(f"/admin/api/imports/{r['id']}/preview", json=opt, headers=H).json()
    got = {i["path"]: i["status"] for i in p["items"]}
    assert got == {
        "psp/ULJM05800/DATA.BIN": "new",
        "psp/ULJM05800/sub/ICON0.PNG": "new",
        "psp/ULJM05800/../../etc/passwd": "invalid",
    }
    c = admin.post(f"/admin/api/imports/{r['id']}/commit", json=opt, headers=H).json()
    assert c["imported"]["new"] == 2
    o = admin.get("/admin/api/files", params={"target": "psp"}).json()["files"]
    assert [f["rel"] for f in o] == ["ULJM05800/DATA.BIN", "ULJM05800/sub/ICON0.PNG"]


def test_import_rejects_bad_input(admin, core):
    assert admin.post("/admin/api/imports", files={"zip": ("x.zip", b"not a zip", "application/zip")}, headers=H).status_code == 400
    assert admin.post("/admin/api/imports", data={"target": "psp"}, headers=H).status_code == 400
    r = upload_folder(admin, {"a.bin": b"a"})
    assert admin.post(f"/admin/api/imports/{r['id']}/preview", json={"target": "bad name"}, headers=H).status_code == 400
    assert admin.post("/admin/api/imports/zzzz/preview", json={"target": "psp"}, headers=H).status_code == 404
    assert admin.delete(f"/admin/api/imports/{r['id']}", headers=H).json() == {"deleted": True}


def test_import_too_large_file_is_reported(admin, core, monkeypatch):
    monkeypatch.setattr(core.ADMIN, "max_bytes", 10)
    r = upload_folder(admin, {"big.bin": b"x" * 11, "ok.bin": b"y"})
    st = {i["rel"]: (i["status"], i.get("reason")) for i in r["preview"]["items"]}
    assert st == {"big.bin": ("invalid", "大きすぎます"), "ok.bin": ("new", None)}


def test_import_zip_with_duplicate_names_uses_the_last(admin, core, monkeypatch):
    """zip に同じ名前が重なっていても 500 にならず、展開したときと同じく後のものを取り込む（モンキーテストで見つかった）"""
    monkeypatch.setattr(core.ADMIN, "max_bytes", 10)
    for later_is_big in (True, False):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z, pytest.warns(UserWarning):
            z.writestr("a.bin", b"small" if later_is_big else b"x" * 11)
            z.writestr("a.bin", b"x" * 11 if later_is_big else b"later")
            z.writestr("b.bin", b"b1")
            z.writestr("b.bin", b"b2")
        r = admin.post("/admin/api/imports", files={"zip": ("x.zip", buf.getvalue(), "application/zip")},
                       data={"target": "psp"}, headers=H).json()
        assert r["files"] == 2
        st = {i["rel"]: i["status"] for i in r["preview"]["items"]}
        assert st == {"a.bin": "invalid" if later_is_big else "new", "b.bin": "new"}
        c = admin.post(f"/admin/api/imports/{r['id']}/commit", json={"target": "psp", "prefix": f"t{later_is_big}"}, headers=H)
        assert c.status_code == 200, c.text
        o = {f["rel"]: f for f in admin.get("/admin/api/files", params={"target": "psp"}).json()["files"]}
        assert o[f"t{later_is_big}/b.bin"]["sha256"] == sha(b"b2")
        if not later_is_big:
            assert o["tFalse/a.bin"]["sha256"] == sha(b"later")
        else:
            assert "tTrue/a.bin" not in o
