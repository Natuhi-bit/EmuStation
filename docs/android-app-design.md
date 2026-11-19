# emusync Android クライアント 設計書

- 対象：Android 版の同期アプリ（Kotlin）。サーバーと Windows 用クライアント（Python）は実装済み
- 想定読者：このアプリを実装する開発者（Claude Code）。この文書だけで実装を始められるように書いている
- 参照実装：`reference/client/emusync.py`（Python 版クライアント）。**同期の判定ロジックはこれと同じ動作にすること**。食い違いがあれば Python 版を正とする
- サーバーのソース：`reference/server/app.py`（FastAPI）。API の細部はこれを正とする

---

## 1. 目的と背景

エミュレータのセーブデータを、自宅の Ubuntu サーバー経由で Windows PC と Android 端末の間で同期する。

- 外出先からは VPN（Tailscale）経由でサーバーにつなぐ。サーバーをインターネットへ直接公開はしない
- Windows 側は Python 版クライアントの `run` コマンドで「ロック取得 → 取得 → エミュ起動 → 終了後に送信 → ロック解除」を行う
- Android 側はこの設計書のアプリで同じことを行う
- 配布は自分用の直接インストール（APK）。Google Play には出さない。このため MANAGE_EXTERNAL_STORAGE を使ってよい

### 1.1 対象のエミュレータ（Android）と同期単位

同期の単位は **ターゲット** ＝「ある機種のセーブフォルダ1つ」。ターゲット名はサーバー上のフォルダ名になり、Windows 側と共通にする（エミュ名ではなく機種名にしている）。

| ターゲット名 | Android のエミュ | Windows 側のエミュ | 同期するフォルダ（エミュのデータフォルダからの相対パス） | 既定の除外 |
|---|---|---|---|---|
| `psp` | PPSSPP | PPSSPP | `SAVEDATA`（PPSSPP で選んだ「PSP」フォルダの直下） | なし |
| `vita` | Vita3K | Vita3K | `ux0/user/00/savedata` | なし |
| `ps2` | ARMSX2 | PCSX2 | `memcards` | `*.bak` |
| `ps3` | ARMSX3 | RPCS3 | `dev_hdd0/home/00000001/savedata` | なし |

前提：4つのエミュはいずれも、データフォルダを `Android/data/` の外（例 `/storage/emulated/0/ARMSX2`）に作ってあること。`Android/data/` の中は Android 11 以降、他のアプリから読めない。

ステートセーブ（PPSSPP_STATE、sstates など）は同期しない。上の表のフォルダ指定によって、ステートセーブは自然に対象外になる。

ターゲットはユーザーが追加・編集できるようにし、上の4つはプリセットとして用意する（§6.3）。

---

## 2. システム構成

```
[Android アプリ] ─┐
                  ├─ Tailscale(VPN) ─ Nginx (https://ubuntu-server/emusync/) ─ emusync サーバー (127.0.0.1:8765)
[Windows Python] ─┘                                                        ├ SQLite（パスごとの版・履歴・ロック）
                                                                            └ blobs/（SHA-256 名で実体を保存）
```

- ベース URL 例：`https://ubuntu-server/emusync`（末尾スラッシュなし。API はその下の `/api/...`）
- TLS は自己署名証明書の可能性がある（§8.2）
- 認証は端末ごとの Bearer トークン。サーバー管理者が `python3 app.py add-device <端末名>` で発行する

---

## 3. サーバー API 仕様（実装済み・変更しない）

共通事項
- すべてのリクエストに `Authorization: Bearer <token>` が必要。無効なら **401** `{"detail": "..."}`
- `{path}` はサーバー上のパス ＝ `<ターゲット名>/<セーブフォルダ内の相対パス>`。区切りは `/`。例 `psp/ULJM05800/DATA.BIN`
  - URL に入れるときは、各セグメントをパーセントエンコードし、`/` はそのまま残す（Python の `quote(p, safe="/")` と同じ）
  - 空のセグメント、`.`、`..`、`\`、NUL、先頭の `/` を含むと **400**
- ターゲット名は `[A-Za-z0-9._-]+` のみ
- エラー本文は FastAPI 形式の `{"detail": ...}`。`detail` は文字列のことも、オブジェクトのこともある

### 3.1 一覧
`GET /api/ping` → `200 {"ok": true, "device": "<端末名>", "time": <unix秒>}`

`GET /api/manifest?prefix=<ターゲット名>/`
```json
200 {"files": [
  {"path": "psp/ULJM05800/DATA.BIN", "version": 3, "sha256": "<hex64>",
   "size": 12345, "updated_at": 1763560000.12, "device": "win-desktop"}
]}
```
- `path` は prefix を含む完全なパス。クライアントは prefix を取り除いて相対パスとして扱う
- 削除済みという概念はない（削除は伝播しない設計）

### 3.2 取得
`GET /api/files/{path}` （任意で `?version=N`：過去の版を取得）
- `200`：本文は生バイト列。レスポンスヘッダーに `X-Version: <int>`、`X-SHA256: <hex>`
- `404`：ない
- クライアントは受信後に本文の SHA-256 を計算し、`X-SHA256` と一致しなければエラーにする

### 3.3 送信（楽観ロック）
`PUT /api/files/{path}`
- ヘッダー：`If-Match: <基準の版番号>`（新規作成は `0`）、`X-SHA256: <本文のhex>`、`Content-Type: application/octet-stream`
- 本文：生バイト列（上限は既定 256MB）
- 結果：
  - `200 {"path", "version", "sha256"}`：新しい版ができた
  - `200 {"path", "version", "sha256", "unchanged": true}`：サーバーの内容と同じだったので、版は進んでいない
  - `409 {"detail": {"message", "server_version", "server_sha256"}}`：サーバーの現在の版が `If-Match` と違う ＝ 他の端末が先に更新した
  - `400`：ハッシュ不一致、またはパスが不正
  - `413`：大きすぎる

### 3.4 履歴・復元
`GET /api/history/{path}` → `200 {"path", "versions": [{"version", "sha256", "size", "created_at", "device"}, ...]}`（新しい順。パスごとに既定で30版まで残る）

`POST /api/restore/{path}`、本文 `{"version": N}` → `200 {"path", "version": <新しい版>}`
- 過去の版 N の内容を「新しい版」として最新にする。履歴は消えない
- 指定した版がなければ 404

### 3.5 プレイ中ロック
`POST /api/locks/{target}`、本文 `{"ttl": <秒>, "force": false}`（ttl の既定は 21600）
- `200 {"target", "device", "expires_at"}`：取得できた。自分が既に保持していれば延長になる
- `423 {"detail": {"message", "device", "expires_at"}}`：他の端末が保持していて、まだ期限内
- `force: true` にすると他の端末のロックを奪う

`DELETE /api/locks/{target}` → `200 {"released": true}`（自分のロックだけを消す）

`GET /api/locks` → `200 {"locks": [{"target", "device", "expires_at"}]}`（期限内のものだけ）

---

## 4. 同期アルゴリズム（Python 版 `sync_target` と同じにする）

### 4.1 入力
- `local`：セーブフォルダを再帰的に走査した `{相対パス: sha256}`
  - 相対パスの区切りは `/`
  - ファイル名が `.emusync-tmp` で終わるものは除外する
  - 除外パターン：パターンごとに「相対パス全体」と「ファイル名」の両方に対して glob を照合し、どちらかに一致すれば除外する。照合は Python の `fnmatch` 相当：`*` は `/` を含む任意の文字列、`?` は任意の1文字、`[...]` は文字クラス。大文字と小文字は区別する
- `remote`：`GET /api/manifest?prefix=<target>/` の結果。キーは prefix を除いた相対パス
- `base`：この端末が前回同期した時点の `{相対パス: (sha256, version)}`。ターゲットごとに永続化する（§7）

### 4.2 判定（相対パスの和集合を名前順に1つずつ処理する）
```
l = local[rel]（なければ null）, r = remote[rel]（なければ null）, b = base[rel]（なければ null）

if l != null && r != null && l == r.sha256:
    base[rel] = (r.sha256, r.version)              # 一致。記録だけ更新する
elif r == null:                                     # サーバーにない
    if push: upload(rel, ifMatch = 0)
elif l == null:                                     # 手元にない（削除は伝播しない）
    if pull: download(rel)
else:
    localChanged  = (l != b?.sha256)
    remoteChanged = (b == null) || (r.sha256 != b.sha256)
    if localChanged && !remoteChanged:   if push: upload(rel, ifMatch = r.version)
    elif remoteChanged && !localChanged: if pull: download(rel)
    else:                                conflict(rel, r)   # 両方変化、または初回で内容が違う
```
- 判定に更新日時は使わない
- upload が成功したら `base[rel] = (応答の sha256, 応答の version)`
- upload が 409 になったら、競合として記録する（理由：「送信中に他の端末が更新した。再同期が必要」）。base は更新しない
- download は §4.3 の手順で書き込み、`base[rel] = (sha256, X-Version)`
- 1ファイルで例外が起きても、ほかのファイルの処理は続け、結果にエラーとして記録する
- 最後に base を保存する（dry-run のときは保存しない）

### 4.3 書き込み（アトミックにする）
1. 同じフォルダに `<ファイル名>.emusync-tmp` を書く
2. flush と fsync を行う
3. 本来のファイル名に rename する（既存のファイルを置き換える）
4. 親フォルダがなければ作る

### 4.4 競合の扱い
`prefer` オプションで動作を切り替える。
- `prefer = null`（既定）：**何も上書きしない**。サーバー版をダウンロードし、`<アプリ専用外部領域>/conflicts/<target>/<rel>.server-v<版>` に保存する。競合として一覧に出す
- `prefer = LOCAL`：`upload(rel, ifMatch = r.version)` を行う。サーバーの旧版は履歴に残るので失われない
- `prefer = REMOTE`：手元のファイルを `<アプリ専用外部領域>/backups/<target>/<yyyyMMdd-HHmmss>/<rel>` にコピーしてから download する

アプリ専用外部領域は `Context.getExternalFilesDir(null)`。

### 4.5 モード
- `SYNC`：pull と push の両方
- `PULL`：push を行わない（upload の分岐は何もしない）
- `PUSH`：pull を行わない（download の分岐は何もしない）
- `dryRun`：通信は manifest の取得だけ。予定される操作を結果として返す

### 4.6 ハッシュのキャッシュ（Android 版で追加）
走査のたびに全ファイルの SHA-256 を計算すると重い（PS3 の savedata はファイル数が多い）。そこで `(target, rel, size, lastModified)` → `sha256` を Room に保存し、サイズと更新日時が同じなら再計算しない。これはハッシュ計算の省略にだけ使い、同期の判定には使わない。

---

## 5. プレイの流れ

### 5.1 「遊ぶ」ボタン
```
1. 端末内にこのターゲットのプレイセッションがあれば、先にその送信を終える（§5.2）
2. POST /api/locks/{target} {ttl: 43200}
   - 423 → 「<端末名> がプレイ中」とダイアログで知らせる。[やめる] / [奪って起動 (force)]
   - 通信エラー → 「サーバーにつながりません（VPN を確認）。同期せずに起動しますか？」 [起動] / [やめる]
     → [起動] を選んだら、オフラインのプレイセッションとして記録し、手順 5 へ
3. SYNC（pull と push）を実行
4. 競合が出たら起動を止めて、競合画面へ移る（[無視して起動] も選べるようにする）
5. プレイセッションを永続化する {target, startedAt, online}
6. 「<機種> プレイ中」の通知を出す（操作ボタン：[送信して終了]）
7. packageManager.getLaunchIntentForPackage(emuPackage) でエミュを起動する
```

### 5.2 プレイ後の送信（次のいずれかで発火）
- 通知の [送信して終了] ボタン → BroadcastReceiver から WorkManager の即時実行ジョブ（expedited）を投入する
- アプリの画面に戻ってきたとき（MainActivity の onResume）にプレイセッションが残っていれば、「送信しますか？（エミュを終了してから）」と表示し、[送信] / [まだ遊ぶ] を選ばせる
  - 自動では送らない。エミュが裏でまだ動いていてセーブを書き込み中かもしれないため

送信ジョブの中身：
```
PUSH を実行 → 成功したら DELETE /api/locks/{target} → プレイセッションを削除 → 通知を消し、結果を通知する
```
- オフラインのプレイセッションだった場合は、まずロックの取得を試みる（失敗してもよい）。そのうえで **SYNC ではなく PUSH** を行う。競合が出たら競合として通知する
- 送信に失敗したら、プレイセッションは残したままにする。通知に [再試行] を出す

### 5.3 定期ジョブ
- WorkManager の定期実行（1時間ごと、ネットワーク接続を条件にする）
- **PUSH だけを行う**。プレイセッション中のターゲットは対象外
  - 理由：エミュが動いている間に外から取得して書き込むと、エミュが手元に持っている内容でまた上書きしたり、壊したりするおそれがある（特に PS2 のメモリーカードは、エミュがファイルを開いたままにしている）。**取得（pull）は「遊ぶ」ボタンを押したときと、手動の同期だけで行う**
- プレイセッションが 12 時間以上残っていたら、「送信を忘れていませんか」と通知する

---

## 6. 画面（Jetpack Compose、Material 3）

### 6.1 ホーム（ターゲット一覧）
- 1行に1ターゲット。表示する内容：機種名、エミュのアイコンと名前、最後に同期した日時、状態
  - 状態の種類：同期済み／未送信の変更あり／競合 n 件／<端末名> がプレイ中／この端末でプレイ中
- 行のボタン：[遊ぶ]、メニュー（今すぐ同期、取得のみ、送信のみ、プレビュー（dry-run）、履歴、編集）
- 上部：サーバーの接続状態（ping の結果）。つながらないときは「VPN（Tailscale）を確認」と表示する
- 上部のメニュー：すべて同期、設定

### 6.2 設定
- サーバー URL、トークン（表示時は伏せ字）、[接続テスト]（ping）
- CA 証明書の取り込み（§8.2）。取り込んだ証明書の指紋を表示し、削除もできる
- 権限の状態：すべてのファイルへのアクセス、通知。許可されていなければ [許可する] ボタン
- 同期ログ（直近 100 回の結果）

### 6.3 ターゲットの編集
- プリセットから選ぶ：psp / vita / ps2 / ps3 / 自由入力
- ターゲット名（プリセットを選んだら固定。自由入力のときは `[A-Za-z0-9._-]+` で検証する）
- エミュのアプリ：インストール済みのランチャーアプリ一覧から選ぶ（アイコンと名前を表示）。パッケージ名を保存する
- エミュのデータフォルダ：フォルダ選択画面（ACTION_OPEN_DOCUMENT_TREE）で選ぶ → 実パスに変換する（§8.1）。手入力もできる
- 同期するフォルダ ＝ データフォルダ ＋ プリセットの相対パス（§1.1）。実パスを確認のために表示し、存在しなければ警告する
- 除外パターン（1行に1つ）
- 保存の前に dry-run で「送信 n 件、取得 n 件、競合 n 件」を見せる。初めての同期で事故を防ぐため

### 6.4 競合
- ファイルごとに、手元（サイズ、更新日時）とサーバー（版、送った端末、日時、サイズ）を並べて表示する
- [手元を残す（prefer=LOCAL）] / [サーバー版を使う（prefer=REMOTE）] / [あとで]
- ファイル単位で選べるようにする。ターゲット全体に一括で適用するボタンも用意する

### 6.5 履歴
- ターゲット内のファイルを選ぶ → `/api/history` の一覧 → [この版に戻す]（restore を呼んだあと、確認のうえで PULL する）

---

## 7. データの永続化

| データ | 保存先 | 内容 |
|---|---|---|
| アプリ設定 | DataStore（Preferences） | サーバー URL、CA 証明書のファイル名、定期ジョブの有効・無効 |
| トークン | DataStore ＋ Android Keystore の AES-GCM 鍵で暗号化 | Bearer トークン |
| ターゲット | Room `targets` | name(PK), emuPackage, dataDir, relSubdir, excludes(JSON), enabled, sortOrder |
| 前回同期の状態 | Room `base_state` | (target, rel) PK, sha256, version |
| ハッシュキャッシュ | Room `hash_cache` | (target, rel) PK, size, lastModified, sha256 |
| プレイセッション | Room `play_sessions` | target PK, startedAt, online(bool) |
| 競合 | Room `conflicts` | (target, rel) PK, serverVersion, serverSha256, savedCopyPath, detectedAt |
| 同期ログ | Room `sync_log` | id, target, mode, startedAt, uploaded, downloaded, conflicts, errors(JSON) |

---

## 8. Android 固有の実装方針

### 8.1 ストレージ
- `MANAGE_EXTERNAL_STORAGE` を使う。`Environment.isExternalStorageManager()` で確認し、許可されていなければ `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`（`package:` URI 付き）を開く
- ファイル操作は `java.io.File` と `java.nio` で行う（SAF の DocumentFile は遅いので使わない）
- フォルダ選択画面で得た URI を実パスに変換する：
  - `content://com.android.externalstorage.documents/tree/primary:PSP` → `/storage/emulated/0/PSP`
  - `.../tree/XXXX-XXXX:Emu/ARMSX2` → `/storage/XXXX-XXXX/Emu/ARMSX2`（SD カード）
  - それ以外の URI は「手入力してください」と案内する
- `Android/data/` 配下のパスが指定されたら、「エミュの設定でデータフォルダを移してください」と警告する

### 8.2 通信と TLS
- OkHttp ＋ kotlinx.serialization を使う（Retrofit は使っても使わなくてもよい）
- タイムアウト：接続 10 秒、読み書き 120 秒（PS2 のメモリーカードは 8MB ある）
- 自己署名の CA：ユーザーがファイル選択画面で `.crt` / `.pem` を取り込む → アプリ内部に保存 → その CA だけを信頼する `X509TrustManager` を作り、OkHttp に設定する
  - **ホスト名の検証は無効にしない。証明書の検証を全部通す実装も禁止する**
  - CA を取り込んでいないときは、OS 標準の信頼ストアを使う
- 接続エラーの区別：`UnknownHostException`、`ConnectException`、`SocketTimeoutException` は「サーバーにつながらない（VPN を確認）」として扱う。HTTP エラーとは別に扱う

### 8.3 アプリの起動とパッケージの可視性
- マニフェストの `<queries>` に `<intent><action MAIN/><category LAUNCHER/></intent>` を書き、ランチャーアプリの一覧を取得できるようにする
- 起動は `getLaunchIntentForPackage(pkg)`。null ならアンインストールされているので、エラーを出す

### 8.4 バックグラウンド処理
- WorkManager を使う
  - ターゲットごとの手動同期・送信：一意の作業名 `sync-<target>`、ExistingWorkPolicy.KEEP、expedited
  - 定期実行：`periodic-push`（§5.3）
- 同じターゲットの同期が同時に走らないように、プロセス内ではターゲットごとに Mutex を使う
- 常駐の Foreground Service は使わない（プレイ中の表示は通常の通知で行う）
- 通知の権限（POST_NOTIFICATIONS、Android 13 以上）を要求する

### 8.5 必要な権限
`INTERNET`, `ACCESS_NETWORK_STATE`, `MANAGE_EXTERNAL_STORAGE`, `POST_NOTIFICATIONS`

---

## 9. 技術スタックとモジュール構成

- Kotlin、Jetpack Compose（Material 3）、minSdk 30、targetSdk は最新の安定版、Gradle Kotlin DSL ＋ version catalog
- コルーチンと Flow、Room、DataStore、WorkManager、OkHttp、kotlinx.serialization
- DI は手動の `AppContainer` で十分（Hilt は使わなくてよい）
- 文言は日本語のみ（`strings.xml` にまとめる）

```
app/src/main/java/<pkg>/
  data/
    api/EmusyncApi.kt          # §3 の HTTP 呼び出し、エラー型（Unauthorized, Conflict409, Locked423, Offline, Http）
    api/TlsConfig.kt           # §8.2 の CA 取り込みと TrustManager
    db/ ...                    # Room の Entity・Dao・Database
    prefs/SettingsStore.kt     # DataStore とトークンの暗号化
    storage/LocalScanner.kt    # 走査、除外の照合、ハッシュキャッシュ
    storage/AtomicWriter.kt
    storage/TreeUriPaths.kt    # §8.1 の URI → 実パス変換
  domain/
    SyncEngine.kt              # §4。Android に依存しない純粋な Kotlin にして、単体テストできるようにする
    PlaySessionManager.kt      # §5
    Presets.kt                 # §1.1 のプリセット
  work/  SyncWorker.kt, PeriodicPushWorker.kt, NotificationActionReceiver.kt
  ui/    home/, settings/, target/, conflict/, history/
  AppContainer.kt, MainActivity.kt, EmusyncApp.kt
```

`SyncEngine` は Android の API に依存させず、ファイル操作（`LocalFs` インターフェース）と通信（`RemoteApi` インターフェース）を差し替えられる形にする。

---

## 10. 実装の段階と完成条件

**段階 1：土台**
- 設定画面、トークンと URL の保存、CA の取り込み、接続テスト（ping）
- 完成条件：ping が成功する。トークンが違うと「トークンが無効」と表示される。サーバーが止まっていると「つながらない」と表示される

**段階 2：同期エンジン（UI なし）**
- `SyncEngine` とその単体テスト（JVM。MockWebServer、またはインメモリの偽サーバーを使う）
- 完成条件：§11 のシナリオ 1〜6 がすべて通る

**段階 3：ターゲット管理と手動同期**
- ホーム画面、ターゲット編集、権限の要求、dry-run、手動同期、同期ログ

**段階 4：プレイの流れ**
- §5 のすべて（ロック、423 と オフラインのダイアログ、起動、通知、戻ってきたときの確認、送信ジョブ）

**段階 5：競合・履歴・復元の画面**

**段階 6：定期送信と仕上げ**
- 送信し忘れの通知、エラー表示の整理

---

## 11. テストシナリオ（Android 版はすべて満たすこと。1〜4 と 6〜9 は Python 版で動作確認済み）

端末 A・B（トークンは別々）、ターゲット `psp`。
1. **初回の送信**：A にだけファイルがある → A が同期 → 送信。B が同期 → 取得。中身が一致する
2. **片側だけの変更**：B で変更して同期 → 送信。A で同期 → 取得
3. **競合**：A と B の両方で変更する → B が先に同期（送信）→ A が同期すると競合になる。A の手元は変わらず、conflicts/ にサーバー版 `*.server-v<版>` ができる
4. **競合の解決**：A で prefer=REMOTE → 手元が backups/ に退避され、サーバー版になる。prefer=LOCAL → 送信され、サーバーの版が1つ進む
5. **409**：base の版が古いまま upload する → 409 → 競合として記録される（base は更新されない）
6. **除外**：`*.log` が除外され、`.emusync-tmp` も走査に入らない
7. **ロック**：A がロック中に B が「遊ぶ」→ 423 のダイアログが出る。force で奪える
8. **オフライン**：サーバーが止まっているときに「遊ぶ」→ 同期せずに起動できる。復帰後に送信される
9. **履歴と復元**：版 2 を restore → 新しい版ができる → PULL で手元が版 2 の内容になる
10. **Windows との相互運用**：Windows（Python 版）で送信したファイルを Android が取得でき、その逆もできる

---

## 12. 未確認の事項（実機で確認する）

- ARMSX3 のデータフォルダの中の構成が、RPCS3 と同じ `dev_hdd0/home/00000001/savedata` かどうか
- ARMSX2 のメモリーカードのファイル名が、PCSX2 側（`Mcd001.ps2` など）と同じかどうか
- 各エミュのパッケージ名（ランチャーアプリ一覧から選ぶので、ハードコードはしない）
- Vita3K の Windows 版と Android 版で、savedata の形式に互換性があるかどうか

## 13. 範囲外（今回は作らない）

- 削除の伝播
- ステートセーブの同期
- ROM を指定してエミュを直接起動すること（エミュの起動だけを行う）
- サーバー API の変更
