#!/usr/bin/env bash
# 管理画面のモンキーテスト（ブラウザ）。サーバーを一時的に立て、ファイルと端末を用意してから、
# admin_ui_monkey.js ででたらめに操作する。
#   server/tests/admin-ui-monkey.sh [回数] [乱数の種]
# 必要なもの：server/requirements.txt を入れた python（PYTHON で指定）、node と playwright（Chromium）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SERVER="$(cd "$HERE/.." && pwd)"
PY="${PYTHON:-python3}"
STEPS="${1:-300}"
SEED="${2:-1}"
PORT="${EMUSYNC_MONKEY_PORT:-18766}"
WORK="$(mktemp -d)"
trap 'kill "${SERVER_PID:-}" 2>/dev/null || true; rm -rf "$WORK" "$SERVER/__pycache__"' EXIT

export EMUSYNC_DATA="$WORK/data" EMUSYNC_KEEP_VERSIONS=5
TOKEN="$(cd "$SERVER" && "$PY" app.py add-device phone | tail -n 1)"
(cd "$SERVER" && "$PY" app.py add-device pc >/dev/null)
(cd "$SERVER" && "$PY" -c 'import app; app.init_storage(); app.ADMIN.set_admin_password("monkey password")')

(cd "$SERVER" && exec "$PY" -m uvicorn app:app --host 127.0.0.1 --port "$PORT" --log-level warning) &
SERVER_PID=$!
for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$PORT/api/ping" && break; sleep 0.2; done

# ファイルを置く（名前に HTML を混ぜて、画面に出たときに実行されないか確かめる）
"$PY" - "$PORT" "$TOKEN" "$WORK/uploads" <<'PY'
import hashlib, io, os, sys, urllib.parse, urllib.request, zipfile
port, token, up = sys.argv[1], sys.argv[2], sys.argv[3]
XSS = "<img src=x onerror=window.__xss=1>"
def put(path, data, base):
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/api/files/{urllib.parse.quote(path)}", data=data, method="PUT",
        headers={"Authorization": f"Bearer {token}", "If-Match": str(base), "X-SHA256": hashlib.sha256(data).hexdigest()})
    urllib.request.urlopen(req).read()
for i in range(4):
    put("psp/ULJM05800/DATA.BIN", f"v{i}".encode(), i)
put(f"psp/{XSS}/DATA.BIN", b"x", 0)
put("ps2/Mcd001.ps2", os.urandom(1000), 0)
put("vita/日本語 セーブ/😀.sav", b"y", 0)
# アップロードに使うファイル
os.makedirs(up)
def mkzip(name, entries):
    with zipfile.ZipFile(os.path.join(up, name), "w") as z:
        for n, d in entries:
            z.writestr(n, d)
mkzip("savedata.zip", [("SAVEDATA/ULJM05800/DATA.BIN", b"new"), ("SAVEDATA/ULJM05800/ICON0.PNG", b"i"), ("SAVEDATA/Thumbs.db", b"t")])
mkzip("xss.zip", [(f"{XSS}.sav", b"x"), ("\"'><script>window.__xss=1</script>/a", b"y")])
mkzip("evil.zip", [("../../etc/passwd", b"x"), ("/abs/x", b"y"), ("a//b", b"z")])
mkzip("empty.zip", [])
with open(os.path.join(up, "broken.zip"), "wb") as f:
    f.write(b"PK\x03\x04" + os.urandom(200))
with open(os.path.join(up, "Mcd001.ps2"), "wb") as f:
    f.write(os.urandom(500))
# フォルダごと選ぶ用
def mkfile(p, data):
    p = os.path.join(up, "..", "folders", p)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(data)
mkfile("SAVEDATA/ULJM05800/DATA.BIN", b"folder")
mkfile("SAVEDATA/ULJM05800/ICON0.PNG", b"icon")
mkfile("SAVEDATA/desktop.ini", b"x")
mkfile(f"xss/{XSS}/a.sav", b"x")
mkfile("memcards/Mcd001.ps2", os.urandom(300))
PY

node "$HERE/admin_ui_monkey.js" "http://127.0.0.1:$PORT/admin/" "monkey password" "$STEPS" "$SEED" "$WORK/uploads"
