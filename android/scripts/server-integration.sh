#!/usr/bin/env bash
# 本物の emusync サーバー（server/app.py）を一時的に立てて、Android 版の通信と同期を確かめる。
#   1. サーバー起動中：
#      - ping（正しいトークンで成功、違うトークンで 401）、ロック、409、履歴と復元
#      - 同期のシナリオ 1〜6（RealServerScenarioTest）
#      - Python 版クライアントとの相互運用（PythonInteropTest）
#      - プレイの流れ：ロック・423・奪って起動・オフライン・送信（RealServerPlayTest）
#      - 競合のファイルごとの解決、履歴と復元（RealServerResolveTest）
#   2. サーバー停止後：「つながらない」（Offline）
# 使い方: android/scripts/server-integration.sh   （python3 と JDK が必要）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
PORT="${EMUSYNC_IT_PORT:-18765}"
trap 'kill "${SERVER_PID:-}" 2>/dev/null || true; rm -rf "$WORK"; rm -rf "$ROOT/server/__pycache__" "$ROOT/client/__pycache__"' EXIT

python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q -r "$ROOT/server/requirements.txt" requests pyyaml

export EMUSYNC_DATA="$WORK/data"
TOKEN="$(cd "$ROOT/server" && "$WORK/venv/bin/python" app.py add-device it-android | tail -n 1)"
TOKEN_B="$(cd "$ROOT/server" && "$WORK/venv/bin/python" app.py add-device it-windows | tail -n 1)"

(cd "$ROOT/server" && exec "$WORK/venv/bin/uvicorn" app:app --host 127.0.0.1 --port "$PORT" --log-level warning) &
SERVER_PID=$!
for _ in $(seq 50); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/api/ping" && break
  sleep 0.2
done

cd "$ROOT/android"
export EMUSYNC_IT_URL="http://127.0.0.1:$PORT" EMUSYNC_IT_TOKEN="$TOKEN" EMUSYNC_IT_DEVICE="it-android"
export EMUSYNC_IT_TOKEN_B="$TOKEN_B" EMUSYNC_IT_PYTHON="$WORK/venv/bin/python" EMUSYNC_IT_CLIENT="$ROOT/client/emusync.py"

# 共通モジュール（:core）と Android アプリ（:app）のテスト
CORE_CLASSES=(ServerIntegrationTest RealServerScenarioTest PythonInteropTest)
APP_CLASSES=(RealServerPlayTest RealServerResolveTest)
run_tests() {
  local args=(:core:test)
  for c in "${CORE_CLASSES[@]}"; do args+=(--tests "*.$c"); done
  args+=(--rerun :app:testDebugUnitTest)
  for c in "${APP_CLASSES[@]}"; do args+=(--tests "*.$c"); done
  ./gradlew -q --max-workers 2 "${args[@]}" --rerun
}

# テスト結果の XML から、クラスごとに「実行（スキップ以外）された件数」と失敗の件数を出し、期待値と照合する
# 引数: クラス名=期待する実行件数 ...
check() {
  python3 - "$@" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
ok = True
for arg in sys.argv[1:]:
    cls, want = arg.split("=")
    r = ET.parse(glob.glob(f"*/build/test-results/*/TEST-*.{cls}.xml")[0]).getroot()
    for c in r.iter("testcase"):
        state = "skip" if c.find("skipped") is not None else (
            "FAIL" if c.find("failure") is not None or c.find("error") is not None else "pass")
        print(f"  {state:4}  {cls}.{c.get('name')}")
    ran = int(r.get("tests")) - int(r.get("skipped"))
    bad = int(r.get("failures")) + int(r.get("errors"))
    if ran != int(want) or bad:
        print(f"  !! {cls}: 実行 {ran} 件（期待 {want}）、失敗 {bad} 件")
        ok = False
sys.exit(0 if ok else 1)
PY
}

echo "== サーバー起動中"
EMUSYNC_IT_DOWN=0 run_tests
check ServerIntegrationTest=4 RealServerScenarioTest=7 PythonInteropTest=4 RealServerPlayTest=11 RealServerResolveTest=7

echo "== サーバー停止後"
kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true; SERVER_PID=
EMUSYNC_IT_DOWN=1 run_tests
check ServerIntegrationTest=1 RealServerScenarioTest=0 PythonInteropTest=0 RealServerPlayTest=0 RealServerResolveTest=0

echo "OK"
