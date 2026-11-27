"""サーバーのテスト用の準備。データフォルダを一時フォルダにして app を読み込み直す。"""
import hashlib
import importlib
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))


@pytest.fixture()
def core(tmp_path, monkeypatch):
    monkeypatch.setenv("EMUSYNC_DATA", str(tmp_path / "data"))
    monkeypatch.setenv("EMUSYNC_KEEP_VERSIONS", "3")
    monkeypatch.setenv("EMUSYNC_NO_SCHEDULER", "1")
    # テストでは別のディスクを用意できないので、同じディスクへのバックアップを許す
    monkeypatch.setenv("EMUSYNC_BACKUP_ALLOW_SAME_DISK", "1")
    for name in ("app", "admin"):
        sys.modules.pop(name, None)
    mod = importlib.import_module("app")
    mod.init_storage()
    return mod


@pytest.fixture()
def client(core):
    with TestClient(core.app, base_url="https://testserver") as c:
        yield c


def add_device(core, name: str) -> dict:
    """端末を登録して、その端末の Authorization ヘッダーを返す"""
    token = core.register_device(name)
    return {"Authorization": f"Bearer {token}"}


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()
