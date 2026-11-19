# emusync Android クライアント

設計書：[`docs/android-app-design.md`](../docs/android-app-design.md)

## 進み具合

| 段階 | 内容 | 状態 |
|---|---|---|
| 1 | 設定画面、URL とトークンの保存、CA の取り込み、接続テスト | 完了 |
| 2 | 同期エンジン（UI なし）と単体テスト | 完了 |
| 3 | ホーム、ターゲット編集、権限の要求、dry-run、手動同期、同期ログ（Room、WorkManager） | 完了 |
| 4 | プレイの流れ（ロック、423・オフラインのダイアログ、起動、通知、戻ったときの確認、送信ジョブ） | 完了 |
| 5 | 競合（ファイルごと・一括で解決）、履歴と復元の画面 | 完了 |
| 6 | 定期送信（1時間ごと、PUSH のみ）、送信し忘れの通知、エラー表示の整理 | 完了 |

## ビルド

JDK 17 以上と Android SDK（compileSdk 37）が必要。

```bash
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties   # ANDROID_HOME を設定していれば不要
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM の単体テスト
```

本物のサーバー（`server/app.py`）に対する確認：

```bash
android/scripts/server-integration.sh
```

サーバーを一時フォルダに立てて、次を確かめる。
- ping（正しいトークンで成功、違うトークンで 401、サーバー停止で「つながらない」）、ロック、409、履歴と復元
- 同期のシナリオ 1〜6（設計書 §11）
- Python 版クライアント（client/emusync.py）との相互運用：互いに送ったファイルを取得できる、競合を同じように検出する
- プレイの流れ（§11 シナリオ 7・8 を含む）：ロック、423、奪って起動、オフラインで起動して復帰後に送信
- 競合のファイルごとの解決（シナリオ 4）、履歴と復元のあとの取得（シナリオ 9）

## 同期エンジンが Python 版と同じ動作をすることの確認

- `PythonParityTest`：1つのファイルについて、手元・サーバー・base の状態、モード、prefer、dry-run、
  「manifest のあとで他の端末が更新した」の全組み合わせ（1134 通り）を Python 版の `sync_target` で実際に動かした結果と照合する。
  期待値は `scripts/gen_decision_cases.py` で作り直せる
- `FnmatchTest`：除外パターンの照合を Python の `fnmatch` の結果と照合する（`scripts/gen_fnmatch_cases.py`）
- `FakeServerScenarioTest` / `RealServerScenarioTest`：§11 のシナリオ 1〜6

## 画面のテスト

エミュレーターが無くても画面を確かめられるように、Robolectric で Compose の画面を動かしている
（`HomeScreenTest`, `TargetEditScreenTest`, `SyncLogScreenTest`）。画面の画像は `app/build/screenshots/` に出る。

## 構成

```
app/src/main/java/com/emustation/emusync/
  data/api/ApiException.kt     # エラー型（Unauthorized, Conflict409, Locked423, Offline, Tls, Network, Http, Protocol）
  data/api/EmusyncApi.kt       # §3 の HTTP 呼び出し（RemoteApi の実装）、パスのエンコード
  data/api/TlsConfig.kt        # CA の読み込み・保存、その CA だけを信頼する TrustManager、OkHttp の設定
  data/storage/JavaLocalFs.kt  # 走査・ハッシュ・アトミックな書き込み（LocalFs の実装）
  data/storage/Fnmatch.kt      # 除外パターン（Python の fnmatch 相当）
  data/storage/HashCache.kt    # ハッシュのキャッシュ（段階 3 で Room にする）
  domain/sync/SyncEngine.kt    # §4 の同期エンジン（Android に依存しない）
  domain/sync/Ports.kt         # RemoteApi / LocalFs / BaseStateStore
  data/db/                     # Room（targets, base_state, hash_cache, play_sessions, conflicts, sync_log）
  data/SyncRepository.kt       # 同期の実行と記録（競合・同期ログ）、手元の状態、ターゲットの削除
  data/storage/TreeUriPaths.kt # フォルダ選択の URI → 実パス
  data/apps/LauncherApps.kt    # ランチャーアプリの一覧
  domain/Presets.kt            # psp / vita / ps2 / ps3
  domain/play/PlaySessionManager.kt # §5 のプレイの流れ（ロック → 同期 → 記録、送信して終了）
  domain/periodic/PeriodicPushRunner.kt # §5.3 の定期送信（PUSH のみ、送信し忘れの判定、通知の重複を防ぐ）
  work/PeriodicPushWorker.kt   # 定期送信のジョブ（periodic-push、1時間ごと、ネットワーク接続時）
  work/Notifications.kt        # プレイ中・結果の通知
  work/NotificationActionReceiver.kt # 通知の [送信して終了] / [再試行]
  work/SyncWorker.kt           # 手動同期のジョブ（sync-<target>、KEEP、expedited）
  data/prefs/SettingsStore.kt  # DataStore
  data/prefs/TokenCipher.kt    # Android Keystore の AES-GCM 鍵でトークンを暗号化
  ui/home/                     # ホーム（ターゲット一覧、状態、メニュー、プレビュー）
  ui/target/                   # ターゲットの追加・編集（保存前の dry-run）
  ui/conflict/                 # 競合（手元とサーバーを並べ、ファイルごと・一括で解決）
  ui/history/                  # 履歴（ファイル → 版の一覧 → この版に戻す → 取得）
  ui/log/                      # 同期ログ
  ui/settings/                 # 設定画面
  AppContainer.kt, EmusyncApp.kt, MainActivity.kt
```
