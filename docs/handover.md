# 引き継ぎ書（実機テスト用）

このリポジトリ（EmuStation／emusync）の作業を、別のセッションで引き継ぐための文書です。
次のセッションの主な仕事は、**実機（Windows の PC・ROG Ally・Android・自宅サーバー）でのテスト**です。
テストの手順と期待する結果は [`real-device-test-spec.md`](real-device-test-spec.md) にあります。

---

## 1. 最初に読むもの・守ること

### 利用者との約束（必ず守る）

- **日本語で答える**。
- **クライアント（Android 版・Windows 版）を直したら、成果物を利用者に送る**。
  - Android：APK（`android/app/build/outputs/apk/debug/app-debug.apk`）。
  - Windows：インストーラー（GitHub Actions の「Windows app」ワークフローの成果物 `EmuStation-setup`）。
  - **exe はそのままでは利用者がダウンロードできない**（ブラウザ・SmartScreen に止められる）。**必ず zip にして送る**。SHA-256 も添える。
- サーバーだけの変更なら、成果物は送らなくてよい（更新のコマンドを伝える）。
- コミットとプッシュは作業用のブランチ **`claude/intelligent-johnson-veyi5w`** に行う（PR は Natuhi-bit/EmuStation#1）。
- コミットのメッセージは日本語。変更の理由も書く。

### 推測で直さない

実機で起きた不具合は、**まず証拠を集めてから直す**こと。以前、RPCS3 の msvcp140.dll のエラーを推測で直して（PATH から外した）外れ、
エラー画面の DLL のパスを利用者に聞いてから本当の原因（jpackage の `SetDllDirectory` が子プロセスに引き継がれる）が分かった。
エラーの画面・ログ・パスを先に確かめる。

---

## 2. 全体の構成

| 部分 | 場所 | 中身 |
|---|---|---|
| 同期サーバー | `server/` | FastAPI＋SQLite＋実体ファイル。管理画面（`/admin/`）、二次バックアップ（`backup.py`） |
| Android 版 | `android/app` | Kotlin・Jetpack Compose。同期・プレイの流れ（エミュを閉じたら自動で送る） |
| Windows 版 | `android/desktop` | Compose Desktop。PS3 の XMB 風の画面、タスクトレイ常駐、エミュの起動、コントローラー、リモートプレイ |
| 共通 | `android/core` | 同期エンジン・通信（Android 版と Windows 版で共有） |
| Python 版 | `client/emusync.py` | コマンドで使う版 |
| 設計 | `docs/android-app-design.md` | Android 版の設計書 |

アプリの表示名は **EmuStation**。ただし内部の名前は emusync のまま（パッケージ名・データの場所 `%APPDATA%\emusync`・サーバー）。

---

## 3. 利用者の環境

| もの | 内容 |
|---|---|
| 自宅サーバー | Ubuntu。`https://192.168.50.49/emusync`（Nginx の下、Nextcloud と同居）。本体は `/opt/emusync/server`、データは `/var/lib/emusync`、systemd の `emusync` |
| リポジトリの置き場所（サーバー） | `~/workspace/EmuStation` |
| Windows の PC | EmuStation（Windows 版）。エミュ：PCSX2（PS2）・PPSSPP（PSP）・Vita3K（Vita）・RPCS3（PS3） |
| ROG Ally | Windows 版をコントローラーで使う |
| Android | Android 版。外出先では WireGuard で自宅につなぐ |
| リモートプレイ | PC に Sunshine、Android に Moonlight。家の外からは WireGuard（Tailscale ではない） |
| 二次バックアップ | Nextcloud（WebDAV）と別のディスク（`/mnt/share/Documents/Emuleter/SaveDataBackup`） |

### サーバーの更新（利用者が実行する）

```bash
cd ~/workspace/EmuStation && git pull origin claude/intelligent-johnson-veyi5w
```

```bash
sudo cp -r ~/workspace/EmuStation/server/. /opt/emusync/server/
```

```bash
sudo /opt/emusync/venv/bin/pip install -r /opt/emusync/server/requirements.txt
```

```bash
sudo systemctl restart emusync
```

systemd は `ProtectSystem=strict` なので、バックアップ先に書くには
`/etc/systemd/system/emusync.service.d/backup.conf` に `ReadWritePaths=` の追加が要る（README の「二次バックアップ」）。

---

## 4. 最近の変更（実機で確かめたいもの）

新しい順。★はまだ実機で確かめていないもの。

| コミット | 内容 | 実機での確認 |
|---|---|---|
| 729cbae | リモートプレイ：コントローラーが効かない（窓が入力を受け取れていなかった）→ `AttachThreadInput` で前に出す。Moonlight で「アプリを終了」したら最小化（Sunshine の登録を detached＋prep-cmd の undo に） | ★ |
| 71e6e4b | リモートプレイ（Sunshine＋Moonlight）：設定→リモートプレイで Sunshine に登録、`--remote` で全画面。Android 版のメニューに「リモートプレイ（Moonlight）」 | ★ Sunshine の実物とはまだ試していない |
| 3fa46c3 | Alt+F4 でエミュ全体（子プロセスも）を終了。プレイ中の画面に「エミュを終了」 | ★ |
| 4c61e94 | XMB で下に動かしたときの動き（位置の飛び）・マウスホイール 1 目盛りで 1 つ | ★ |
| 48eb372 / 71ffa3d | RPCS3 が EmuStation 同梱の msvcp140.dll を読んで止まる → 起動の瞬間だけ `SetDllDirectory` を外す | 済（利用者が「直った」と確認） |
| a161638 | サーバー：取り込みの zip に同じ名前が重なると 500 | ★（サーバーの更新が要る） |
| b6e77b7 | アイコンをゲームらしいものに | ★（見た目） |
| 1d39354 | アプリ名を EmuStation に（Windows はインストール先も EmuStation、前の emusync の起動時の登録は消す） | ★（上書きインストール） |
| a2ddca3 | 操作音（利用者が選んだ MP3・WAV）と PS3 に近い操作感 | 一部済 |
| a0d06ef / 59c4750 | サーバー：二次バックアップと復元 | 一部済（書けないエラーは利用者の環境で出て、systemd の設定で対処） |
| 015d51b / 16fb42d | コントローラー：XInput（ROG Ally・Xbox）と SDL3（DualShock 4・DualSense・Switch Pro） | 一部済 |

---

## 5. 大事な仕組み（Windows 版）

| 仕組み | 場所 | 要点 |
|---|---|---|
| エミュの起動と終了待ち | `platform/Launcher.kt` | 子孫のプロセスが全部終わるまで待つ。起動の瞬間だけ `DllDirectory.withoutOwn` で `SetDllDirectory` を外す。PATH から自分のフォルダも外す |
| 起動中のエミュ | `platform/RunningEmulator.kt` | Alt+F4 の見張り（`AltF4Detector`、30 ms ごとに `GetAsyncKeyState`）。終了は WM_CLOSE → 8 秒待って強制終了 |
| 前に出す | `platform/WindowActivator.kt` | `AttachThreadInput` で前の窓の入力に相乗りしてから `SetForegroundWindow` |
| 二重起動 | `platform/Platform.kt` の `SingleInstance` | ポート 47615 の localhost。`Show`／`Remote`（全画面）／`RemoteEnd`（最小化） |
| リモートプレイ | `platform/RemotePlay.kt` | `SunshineClient`：`https://localhost:47990/api/apps` に Basic 認証で登録（ブラウザ以外なので CSRF のトークンは不要）。cmd は空、detached に `--remote`、prep-cmd の undo に `--remote-end` |
| コントローラー | `platform/Gamepad.kt`・`GamepadSdl.kt` | SDL3（LWJGL）→ だめなら XInput。ボタンを AWT のキーに直して窓に送る。**窓が入力を受け取っているときだけ送る**（リモートプレイ中は例外：エミュで遊んでいなければ前に出して受け取る） |
| XMB | `ui/Xmb.kt` | 位置の式 `xmbItemY`（つながった形）、ホイール `WheelStepper` |
| データの場所 | `%APPDATA%\emusync` | `config.json`、`sounds\`、`metadata\`（カバーなど）、`remote\emustation-poster.png` |

---

## 6. ビルドとテスト

### この環境（Linux）で

```bash
cd android && ./gradlew :core:test :desktop:test :app:testDebugUnitTest
```

```bash
cd android && ./gradlew :app:assembleDebug
```

```bash
cd server && python -m pytest tests -q
```

- 最後の全体の結果：desktop 70 件（スキップ 3）、Android のホーム画面 13 件、サーバー 36 件が通過。
- 画面のテストは画像を `android/desktop/build/screenshots/`・`android/app/build/screenshots/` に出す。
- Maven Central が 429 を返すときは `~/.gradle/init.d/maven-mirror.gradle`（gcp のミラー）を使う。

### GitHub Actions

| ワークフロー | 中身 |
|---|---|
| `windows-app.yml` | Linux でテスト → Windows で `LauncherAndConfigTest`・`RunningEmulatorTest`（Windows でしか確かめられないもの）→ インストーラー（Inno Setup）。成果物 `EmuStation-setup` |
| `monkey.yml` | サーバー・Windows 版・Android 版のモンキーテスト（seed は run_number） |

成果物のダウンロード先は `productionresultssaN.blob.core.windows.net`（N は毎回変わる）。クラウドのセッションでは、ネットワークの許可に足す必要がある。

---

## 7. 実機テストで調べるときの手がかり

| 調べたいこと | 見る場所 |
|---|---|
| EmuStation の設定 | `%APPDATA%\emusync\config.json` |
| 同期の結果 | Windows 版の「ネットワーク」の列→「同期ログ」、サーバーの管理画面 `https://192.168.50.49/emusync/admin/` |
| RPCS3 のエラー | RPCS3 のフォルダの `log\RPCS3.log` |
| Sunshine | 設定画面 `https://localhost:47990`、ログ `C:\Program Files\Sunshine\config\sunshine.log`、登録内容 `C:\Program Files\Sunshine\config\apps.json` |
| 起動中のプロセス | タスクマネージャーの詳細（エミュが残っていないか） |
| サーバー | `journalctl -u emusync -n 200` |

---

## 8. 分かっている制限・未確認のこと

- **リモートプレイ**
  - Sunshine の実物では未確認。登録は Sunshine のソースで仕様を確かめ、偽の Sunshine 相手のテストで確かめただけ。
  - Moonlight を「アプリを終了」せずに閉じた（切断しただけ）ときは、Sunshine がセッションを続けるので最小化されない。
  - Sunshine の仮想コントローラーは ViGEmBus が要る。入っていなければ、どのアプリでもコントローラーが効かない。
  - `WindowActivator` の前に出す処理は、Windows の実機ではまだ確かめていない。
- **Alt+F4**
  - エミュが終了の確認を出すと、8 秒後に強制終了する。
  - 管理者として動くエミュには、キーの見張りや WM_CLOSE が届かないことがある。
- **macOS・Linux 版**はない（Windows と Android のみ）。

---

## 9. 不具合が見つかったら

1. 利用者に、エラーの画面・ログ・再現の手順を聞く（推測で直さない）。
2. 原因を特定して直し、テストを足す（Windows でしか起きないものは `windows-app.yml` の Windows のテストに入れる）。
3. コミットしてプッシュ。CI（Windows app・Monkey tests）が通るのを待つ。
4. クライアントの変更なら、APK・インストーラーの zip を SHA-256 付きで利用者に送る。
5. テスト仕様書の該当項目の結果を更新する。
