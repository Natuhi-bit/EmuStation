# emusync Android クライアント

設計書：[`docs/android-app-design.md`](../docs/android-app-design.md)

## 進み具合

| 段階 | 内容 | 状態 |
|---|---|---|
| 1 | 設定画面、URL とトークンの保存、CA の取り込み、接続テスト | 完了 |
| 2 | 同期エンジン（UI なし）と単体テスト | 未着手 |
| 3〜6 | ターゲット管理、プレイの流れ、競合・履歴、定期送信 | 未着手 |

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

サーバーを一時フォルダに立て、正しいトークンでは ping が成功すること、違うトークンでは 401 になること、
サーバーを止めると「つながらない」になることを確かめる。

## 構成（段階 1 の時点）

```
app/src/main/java/com/emustation/emusync/
  data/api/ApiException.kt     # エラー型（Unauthorized, Conflict409, Locked423, Offline, Tls, Network, Http, Protocol）
  data/api/EmusyncApi.kt       # HTTP 呼び出し（段階 1 は ping のみ）、パスのエンコード
  data/api/TlsConfig.kt        # CA の読み込み・保存、その CA だけを信頼する TrustManager、OkHttp の設定
  data/prefs/SettingsStore.kt  # DataStore
  data/prefs/TokenCipher.kt    # Android Keystore の AES-GCM 鍵でトークンを暗号化
  ui/settings/                 # 設定画面
  AppContainer.kt, EmusyncApp.kt, MainActivity.kt
```
