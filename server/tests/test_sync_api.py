"""同期 API（クライアントが使うもの）の動き。管理画面を足しても変わらないことを確かめる。"""
from conftest import add_device, sha


def put(client, h, path, data, base):
    return client.put(f"/api/files/{path}", content=data,
                      headers={**h, "If-Match": str(base), "X-SHA256": sha(data)})


def test_auth(client, core):
    assert client.get("/api/ping").status_code == 401
    assert client.get("/api/ping", headers={"Authorization": "Bearer nope"}).json()["detail"] == "トークンが無効です"
    h = add_device(core, "phone")
    assert client.get("/api/ping", headers=h).json()["device"] == "phone"


def test_put_versions_conflict_unchanged(client, core):
    h = add_device(core, "phone")
    r = put(client, h, "psp/A/DATA.BIN", b"v1", 0)
    assert r.json() == {"path": "psp/A/DATA.BIN", "version": 1, "sha256": sha(b"v1")}
    assert put(client, h, "psp/A/DATA.BIN", b"v2", 1).json()["version"] == 2
    # 同じ内容は版を進めない
    assert put(client, h, "psp/A/DATA.BIN", b"v2", 2).json() == {
        "path": "psp/A/DATA.BIN", "version": 2, "sha256": sha(b"v2"), "unchanged": True}
    # 基準の版が古い → 409
    r = put(client, h, "psp/A/DATA.BIN", b"v3", 1)
    assert r.status_code == 409
    assert r.json()["detail"]["server_version"] == 2
    # 新規なのに 0 以外 → 409
    assert put(client, h, "psp/B", b"x", 5).status_code == 409
    # ハッシュ不一致 → 400
    r = client.put("/api/files/psp/C", content=b"x", headers={**h, "If-Match": "0", "X-SHA256": sha(b"y")})
    assert r.status_code == 400
    # 不正なパス → 400
    # （httpx は .. を送る前に正規化するので、エンコードして送る）
    assert put(client, h, "psp/%2E%2E/x", b"x", 0).status_code == 400


def test_manifest_download_history_restore(client, core):
    h = add_device(core, "phone")
    for i, data in enumerate([b"one", b"two", b"three", b"four"]):
        put(client, h, "ps2/Mcd001.ps2", data, i)
    put(client, h, "psp/x", b"x", 0)

    files = client.get("/api/manifest", params={"prefix": "ps2/"}, headers=h).json()["files"]
    assert [(f["path"], f["version"], f["device"]) for f in files] == [("ps2/Mcd001.ps2", 4, "phone")]

    r = client.get("/api/files/ps2/Mcd001.ps2", headers=h)
    assert r.content == b"four" and r.headers["X-Version"] == "4" and r.headers["X-SHA256"] == sha(b"four")
    assert client.get("/api/files/ps2/Mcd001.ps2", params={"version": 3}, headers=h).content == b"three"
    assert client.get("/api/files/ps2/none", headers=h).status_code == 404

    # 版は 3 つまで残る（テストでは EMUSYNC_KEEP_VERSIONS=3）
    assert [v["version"] for v in client.get("/api/history/ps2/Mcd001.ps2", headers=h).json()["versions"]] == [4, 3, 2]

    r = client.post("/api/restore/ps2/Mcd001.ps2", json={"version": 2}, headers=h)
    assert r.json() == {"path": "ps2/Mcd001.ps2", "version": 5}
    assert client.get("/api/files/ps2/Mcd001.ps2", headers=h).content == b"two"
    assert client.get("/api/manifest", params={"prefix": "ps2/"}, headers=h).json()["files"][0]["device"] == "phone(restore v2)"
    assert client.post("/api/restore/ps2/Mcd001.ps2", json={"version": 1}, headers=h).status_code == 404


def test_locks(client, core):
    a = add_device(core, "a")
    b = add_device(core, "b")
    assert client.post("/api/locks/psp", json={"ttl": 100}, headers=a).json()["device"] == "a"
    r = client.post("/api/locks/psp", json={}, headers=b)
    assert r.status_code == 423 and r.json()["detail"]["device"] == "a"
    assert client.post("/api/locks/psp", json={"force": True}, headers=b).json()["device"] == "b"
    client.delete("/api/locks/psp", headers=a)  # 他の端末のロックは消せない
    assert [l["device"] for l in client.get("/api/locks", headers=a).json()["locks"]] == ["b"]
    client.delete("/api/locks/psp", headers=b)
    assert client.get("/api/locks", headers=a).json()["locks"] == []
    assert client.post("/api/locks/bad name", json={}, headers=a).status_code == 400
