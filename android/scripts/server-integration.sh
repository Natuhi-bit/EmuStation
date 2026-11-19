#!/usr/bin/env bash
# 本物の emusync サーバー（server/app.py）を一時的に立てて、Android 版の通信部分を確かめる。
#   1. サーバー起動 → 正しいトークンで ping 成功、違うトークンで 401
#   2. サーバー停止 → 「つながらない」（Offline）
# 使い方: android/scripts/server-integration.sh   （python3 と JDK が必要）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
PORT="${EMUSYNC_IT_PORT:-18765}"
trap 'kill "${SERVER_PID:-}" 2>/dev/null || true; rm -rf "$WORK"' EXIT

python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q -r "$ROOT/server/requirements.txt"

export EMUSYNC_DATA="$WORK/data"
TOKEN="$(cd "$ROOT/server" && "$WORK/venv/bin/python" app.py add-device it-android | tail -n 1)"

(cd "$ROOT/server" && exec "$WORK/venv/bin/uvicorn" app:app --host 127.0.0.1 --port "$PORT" --log-level warning) &
SERVER_PID=$!
for _ in $(seq 50); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/api/ping" && break
  sleep 0.2
done

cd "$ROOT/android"
export EMUSYNC_IT_URL="http://127.0.0.1:$PORT" EMUSYNC_IT_TOKEN="$TOKEN" EMUSYNC_IT_DEVICE="it-android"

# テスト結果の XML から、実行（スキップ以外）された件数と失敗件数を出し、期待値と照合する
check() {
  python3 - "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
r = ET.parse("app/build/test-results/testDebugUnitTest/TEST-com.emustation.emusync.data.api.ServerIntegrationTest.xml").getroot()
for c in r.iter("testcase"):
    state = "skip" if c.find("skipped") is not None else ("FAIL" if c.find("failure") is not None or c.find("error") is not None else "pass")
    print(f"  {state:4}  {c.get('name')}")
ran = int(r.get("tests")) - int(r.get("skipped"))
bad = int(r.get("failures")) + int(r.get("errors"))
sys.exit(0 if ran == int(sys.argv[1]) and bad == 0 else 1)
PY
}

echo "== サーバー起動中"
EMUSYNC_IT_DOWN=0 ./gradlew -q --max-workers 1 testDebugUnitTest \
  --tests '*ServerIntegrationTest' --rerun
check 2

echo "== サーバー停止後"
kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true; SERVER_PID=
EMUSYNC_IT_DOWN=1 ./gradlew -q --max-workers 1 testDebugUnitTest \
  --tests '*ServerIntegrationTest' --rerun
check 1

echo "OK"
