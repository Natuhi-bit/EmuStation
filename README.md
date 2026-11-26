# emusync — エミュのセーブを自宅サーバー経由で同期する

```
[Windows] 同期アプリ ─┐                       ┌ SQLite（どのファイルが何版か・履歴・ロック）
[Android] 同期アプリ ─┼─ VPN ─ Nginx ─ emusync ┤
[ほかのPC] 同期アプリ ─┘   (/emusync/)          └ blobs/（実体。SHA-256名で重複なし保存）
```

- **同期の単位は「エミュのセーブフォルダ」**（ターゲット）。サーバー上では `ターゲット名/フォルダ内の相対パス` で管理する
- **3方向比較**：前回同期した内容を各端末が覚えていて、「手元だけ変わった→送信／サーバーだけ変わった→取得／両方変わった→競合」と判定する。更新日時は使わない
- **競合したら何も上書きしない**。サーバー版を `state_dir/conflicts/` に置いて知らせるので、`--prefer local|remote` で選ぶ
- **サーバーは版を消さない**（既定ではファイルごとに30版）。壊れたセーブが全端末に広がっても `restore` で戻せる
- **プレイ中ロック**：`run` で起動すると「この端末でプレイ中」をサーバーに登録し、他の端末からの起動を止める
- 削除は伝播しない（消したい場合は全端末で手で消す）

---

## 1. サーバー（Ubuntu 26.04）

```bash
sudo useradd --system --home /var/lib/emusync --shell /usr/sbin/nologin emusync
sudo mkdir -p /opt/emusync && sudo cp -r server /opt/emusync/
sudo python3 -m venv /opt/emusync/venv
sudo /opt/emusync/venv/bin/pip install -r /opt/emusync/server/requirements.txt

sudo cp /opt/emusync/server/emusync.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now emusync
systemctl status emusync

# 端末を登録（表示されたトークンを各端末の config.yaml に書く。再表示はできない）
cd /opt/emusync/server
sudo -u emusync EMUSYNC_DATA=/var/lib/emusync /opt/emusync/venv/bin/python app.py add-device win-desktop
sudo -u emusync EMUSYNC_DATA=/var/lib/emusync /opt/emusync/venv/bin/python app.py add-device android-phone
```

### Nginx
`server/nginx-emusync.conf` を `/etc/nginx/snippets/emusync.conf` に置き、Nextcloud を載せている既存の
`server { listen 443 ssl; ... }` ブロックの中に `include snippets/emusync.conf;` の1行を足す
（`sites-available` には置かない。`location` だけのファイルは `server` の外では読めない）。
`allow` 行のアドレス範囲（LAN と WireGuard）は自宅のネットワークに合わせて変える。

```bash
sudo cp server/nginx-emusync.conf /etc/nginx/snippets/emusync.conf
sudo nano /etc/nginx/sites-available/nextcloud   # 443 の server ブロックに include snippets/emusync.conf; を足す
sudo nginx -t && sudo systemctl reload nginx
curl -k -H "Authorization: Bearer <トークン>" https://192.168.50.49/emusync/api/ping
```

### 管理画面（ブラウザ）
`https://<サーバー>/emusync/admin/` で、ブラウザからサーバーを管理できる（Nginx の `allow` の範囲からだけ開ける）。

- **概要**：ターゲットごとのファイル数・容量、版の数、端末の数、プレイ中ロック
- **ファイル**：ターゲットの中のファイルの一覧、版の履歴、版ごとのダウンロード、「この版に戻す」（復元）
- **インポート**：フォルダか zip をアップロードして、ターゲットに取り込む。先にプレビューで「新規・同じ・内容が違う・除外」を確かめられる。
  既定では新規のファイルだけを取り込み、内容が違う既存のファイルは、選んだときだけ新しい版として取り込む（今の版は履歴に残る）。
  取り込んだ版の端末名は `admin-import`
- **端末**：端末の追加（トークンの発行）と削除
- **ロック**：プレイ中ロックの一覧と、強制解除

最初に、管理者パスワードを設定する（変えるときも同じ。変えると、ログイン中のブラウザはログアウトされる）。
```bash
cd /opt/emusync/server
sudo -u emusync EMUSYNC_DATA=/var/lib/emusync /opt/emusync/venv/bin/python app.py set-admin-password
```

サーバーのテスト：
```bash
pip install pytest httpx -r server/requirements.txt
python -m pytest server/tests
```

#### モンキーテスト
でたらめな操作・要求を大量に送り、落ちない・固まらない・データが壊れない（版が飛ばない、ロックや送り残しが残らない、
手元とサーバーが一致する）ことを確かめる。GitHub Actions の「Monkey tests」が push ごとに、実行番号を乱数の種にして回す
（手動で回すときは回数と種を指定できる）。失敗したら、ログに出る `seed=` の値で再現できる。

| 対象 | 手元で回すとき |
|---|---|
| サーバーの API（同時書き込みを含む） | `MONKEY_SEED=7 MONKEY_STEPS=5000 python -m pytest server/tests/test_monkey.py` |
| 管理画面（ブラウザ。Playwright が必要） | `server/tests/admin-ui-monkey.sh 1000 7` |
| Windows 版の画面 | `MONKEY_SEED=7 ./gradlew :desktop:test --tests '*MonkeyTest'`（android/ で） |
| Android アプリ全体（本物のサーバーを立てる） | `MONKEY_SEED=7 MONKEY_STEPS=2000 android/scripts/server-integration.sh` |

### 二次バックアップ
管理画面の「バックアップ」で、サーバーのセーブデータ（全端末のファイル・履歴・端末の登録）を別の場所に写す。
毎日決まった時刻に自動で行い、1 日ごと 7 個・1 週ごと 4 個・1 か月ごと 6 個の世代を残す（変わっていない日は作らない）。
中身が同じファイルは 1 回しか送らないので、2 回目からは変わった分だけ。

| 置き場所 | 設定 |
|---|---|
| Nextcloud | WebDAV の URL（`https://<Nextcloud>/remote.php/dav/files/<ユーザー名>/emusync-backup`）、ユーザー名、アプリパスワード（Nextcloud の「個人設定 → セキュリティ」で作る） |
| サーバーの別のディスク | フォルダ（例 `/mnt/backup/emusync-backup`）。サーバーのデータと同じディスクは選べない（つながっていない USB の HDD に気づかず同じディスクへ書くのを防ぐ） |
| 手元にダウンロード | 「手元にダウンロード（zip）」で、今の状態を zip で保存 |

別のディスクは、emusync のユーザーが書けるようにし、emusync.service（ProtectSystem=strict で /var/lib/emusync 以外は読み取り専用）
から書けるように許可する：
```bash
sudo mkdir -p /mnt/backup/emusync-backup && sudo chown emusync: /mnt/backup/emusync-backup
sudo mkdir -p /etc/systemd/system/emusync.service.d
printf '[Service]\nReadWritePaths=-/mnt/backup/emusync-backup\n' | sudo tee /etc/systemd/system/emusync.service.d/backup.conf
sudo systemctl daemon-reload && sudo systemctl restart emusync
```

**戻し方**
- 一部：「世代と復元」で世代を選び、ハード（「ps2 を戻す」）か 1 ファイル（「戻す」）を選ぶ。その時点の中身が新しい版になり、各端末は次の同期で取得する
- 全体：「この世代でサーバー全体を戻す」（世代の ID を打ち込んで確認）。ファイル・履歴・端末がその時点に戻る。
  今の DB は `/var/lib/emusync/pre-restore-<日時>.db` に残し、管理画面のパスワードとバックアップの設定は今のものを残す
- サーバーを入れ直したとき（管理画面に入る前でも）はコマンドで戻せる：
```bash
cd /opt/emusync/server
sudo -u emusync EMUSYNC_DATA=/var/lib/emusync /opt/emusync/venv/bin/python app.py restore-backup --dir /mnt/backup/emusync-backup --list
sudo -u emusync EMUSYNC_DATA=/var/lib/emusync /opt/emusync/venv/bin/python app.py restore-backup --dir /mnt/backup/emusync-backup
```
  Nextcloud からは `--webdav <URL> --user <ユーザー名>`（パスワードは聞かれる）、ダウンロードした zip からは `--zip <ファイル>`。
  `--snapshot <ID>` で世代を選べる（省くと最新）。戻したあとは `sudo systemctl restart emusync`
- 今すぐバックアップ：管理画面の「今すぐバックアップ」、またはコマンド `app.py backup`

### 調整用の環境変数（emusync.service の Environment=）
| 変数 | 既定値 | 内容 |
|---|---|---|
| `EMUSYNC_DATA` | `./data` | DB と実体の保存先 |
| `EMUSYNC_KEEP_VERSIONS` | 30 | ファイルごとに残す版の数 |
| `EMUSYNC_MAX_BYTES` | 256MB | 1ファイルの上限 |

バックアップは `/var/lib/emusync` を丸ごとコピーすれば足りる（DB は WAL モードなので、止めてからコピーするか `sqlite3 emusync.db ".backup x.db"` を使う）。

---

## 2. 外出先からの接続（VPN）

外出先からは **WireGuard** で自宅の LAN につなぐ（ルーターの WireGuard サーバー機能、またはサーバー上の WireGuard）。
サーバーの URL は **LAN の IP アドレス**（例 `https://192.168.50.49/emusync`）にすると、自宅でも外でも同じ値のままで使える。

- **サーバーの IP を固定する**：ルーターの DHCP 設定で、サーバーに同じ IP を割り当てる
- **WireGuard のクライアント設定**：各端末の `[Peer]` の `AllowedIPs` に自宅の LAN（例 `192.168.50.0/24`）を入れる。
  外ではこの範囲が WireGuard を通るので、LAN の IP のままでサーバーに届く。自宅にいるときは WireGuard をオフにする
- **Nginx の `allow`**：WireGuard のクライアントに割り当てる範囲（例 `10.6.0.0/24`）を入れる
- 同期アプリ側は、自宅と外で何も変えなくてよい

> 証明書が自己署名の場合は、その証明書ファイルを各端末にコピーして `verify: <パス>` を指定する（`false` にはしない）。
> Android アプリは証明書のホスト名を確かめるので、URL に IP アドレスを使うなら、証明書の SAN に `IP:192.168.50.49` が入っている必要がある
> （`openssl x509 -in <証明書> -noout -ext subjectAltName` で確かめられる）。

---

## 3. Windows アプリ

`android/desktop/` に Windows 用のアプリ **EmuStation** がある（Kotlin / Compose Desktop。同期の判定は Android 版と同じコードを使う）。

- **Steam のように、アプリを通してエミュを起動する**：ゲームを選んで Enter → ロック → クラウドと同期 → エミュを起動 →
  ゲームを閉じたら自動でクラウドに保存 → ロック解除。ほかの端末がプレイ中なら知らせる（奪って起動もできる）。
  つながらないときは同期せずに起動し、つながったときに自動で送る
- **Alt+F4 でエミュを終了**：エミュの画面で Alt+F4 を押すと、エミュ全体（子プロセスも）を終わらせて、セーブの送信へ進む
  （PCSX2・RPCS3 は Alt+F4 でゲームの画面だけ閉じて本体が残ることがあるため）。まず窓を閉じるよう頼み、8 秒たっても残っていれば強制終了。
  プレイ中の画面の「エミュを終了」でも同じ
- **PS3 の XMB 風の画面**：カテゴリー（設定・PS2・PSP・PS Vita・PS3・セーブデータ・ネットワーク）を横、項目を縦に並べる。
  ゲームはハードごとの列に分かれる（ゲームもエミュもないハードの列は出さない）。
  キーボード（←→↑↓・Enter・Tab でオプション・Esc）とマウスで操作する。F11 で全画面。背景の色はテーマで変えられる
- **ゲームの情報とカバーを自動で取得**：ゲームを追加すると、ROM の中から型番（例 SLPM-66168）を読み、
  日本語のタイトル・地域・カバー画像をインターネットから取る（API キーやアカウントは要らない）。
  自分で書き換えたタイトル・カバーは上書きしない（ゲームのオプションの「情報とカバーを取得し直す」で置き換えられる）

  | 機種 | 型番・タイトル | カバー |
  |---|---|---|
  | PS2 | ISO・BIN・CSO の SYSTEM.CNF。タイトルは PCSX2 のデータベース（日本語） | PCSX2 が使っているカバー集（xlenore/ps2-covers） → libretro |
  | PSP | ISO・CSO・PBP の PARAM.SFO（ROM の中の日本語のタイトル） | libretro → ROM の中のアイコン |
  | PS3 | ゲームのフォルダ・ISO の PARAM.SFO | GameTDB → libretro → ROM の中のアイコン |
  | Vita | Vita3K にインストールしたゲームの param.sfo | libretro → インストールしたゲームのアイコン |

  CHD・ZSO など中を読めない形式は、ファイル名（`[SLPM-66168]` のような型番、または Redump の名前
  `Ryuu ga Gotoku (Japan).chd`）から探す。データベース（PCSX2・libretro）は `%APPDATA%\emusync\metadata` に置き、
  30 日ごとに取り直す（つながらないときは置いてあるものと ROM だけで調べる）
- **コントローラーで操作できる**（PlayStation の DualShock 4・DualSense、Xbox・ROG Ally、Switch のプロコン など。SDL3 で読む）：
  十字キー・左スティックで選ぶ、下のボタン（A・×）で決定・右のボタン（B・○）で戻る（設定で入れ替えられる）、
  LB・RB（L1・R1）で列の切り替え、Y（△）・メニュー（OPTIONS）でオプション、ビュー（CREATE）で全画面。
  画面の下の説明は、最後に触ったコントローラーの記号で出る。パネルやダイアログでは上下でボタンや入力欄を移る（選んでいるものは白い枠）。
  エミュで遊んでいる間はコントローラーの操作はエミュにだけ届き、ゲームを終えると emusync が前に出る。文字の入力は画面のキーボード（タッチ）を使う
- **操作音**（設定 → 操作音）：カーソル・決定・戻る・オプションを開く、で音を鳴らす。音のファイルはアプリに入っていないので、
  持っている MP3・WAV を選ぶ（`%APPDATA%\emusync\sounds` に写して使う。`cursor.mp3`・`decide.mp3`・`back.mp3`・`options.mp3` を直接置いてもよい）。
  音の大きさは 4 段階
- **PS3 に近い操作感**：すっと動いてやわらかく止まる動き、列を切り替えると項目の列が横から入る、選んだ項目の後ろが
  ゆっくり明滅して文字が白くにじむ、コントローラーのくり返しは押して 0.3 秒後から 0.08 秒ごと
- **タスクトレイに常駐**：ウィンドウを閉じてもトレイに残る。Windows の起動時に起動できる。1時間ごとに手元の変更を送る
- **設定はすべて画面で**：サーバー（URL・トークン・CA 証明書・接続テスト）、エミュ（実行ファイル・引数・セーブのフォルダ）、
  起動と常駐、テーマ。ゲームは1本ずつ追加するか、フォルダからまとめて探す
- データは `%APPDATA%\emusync`。トークンは Windows の DPAPI で守る。前回同期の記録は Python 版と同じ形（`state\<ターゲット>.json`）

### インストール
GitHub の Actions の「Windows app」の実行結果から、Artifacts の **EmuStation-setup** をダウンロードして実行する
（管理者の権限は要らない。`%LOCALAPPDATA%\Programs\EmuStation` に入る。前の版（emusync の名前のもの）の上からそのまま入れ替わる）。
インストーラーで「Windows の起動時に起動する」と「デスクトップのアイコン」を選べる。

最初に、設定 → サーバーで URL（例 `https://192.168.50.49/emusync`）とトークン（管理画面の「端末」で発行）を入れ、
自己署名の証明書なら CA 証明書を取り込む。次に、設定 → 各機種で、エミュの実行ファイルとセーブのフォルダを選ぶ。

| 機種 | エミュ | 既定の引数 | 備考 |
|---|---|---|---|
| psp | PPSSPP | `"{rom}"` | |
| vita | Vita3K | `-r {rom}` | ROM の欄はタイトル ID（例 PCSG00001） |
| ps2 | PCSX2 | `-batch -- "{rom}"` | `-batch`：ゲームを閉じたら PCSX2 も終わる |
| ps3 | RPCS3 | `--no-gui "{rom}"` | ROM の欄は EBOOT.BIN か ISO |

### 自分でビルドする
```powershell
cd android
.\gradlew.bat :desktop:run                     # そのまま動かす
.\gradlew.bat :desktop:createDistributable     # build\compose\binaries\main\app\emusync にアプリ
iscc /DAppVersion=1.0.0 desktop\packaging\emusync.iss   # インストーラー（Inno Setup 6）
```

### Python 版（コマンドで使う）
同期の動きの「正」として残してある（Android 版・Windows 版は、Python 版と全組み合わせで照合している）。

```powershell
py -m pip install requests pyyaml
copy client\config.example.yaml client\config.yaml   # 中身を編集
py client\emusync.py -c client\config.yaml sync --dry-run   # 最初は必ず確認
py client\emusync.py -c client\config.yaml run ps2          # ロック → 取得 → エミュ起動 → 終了後に送信
```

| コマンド | 内容 |
|---|---|
| `sync [ターゲット...]` | 双方向同期（`--dry-run`、`--prefer local/remote`） |
| `pull` / `push` | 取得だけ／送信だけ |
| `run <ターゲット> [ROMなど]` | ロック付きでエミュを起動 |
| `history <ターゲット> <パス>` | 版の一覧 |
| `restore <ターゲット> <パス> <版>` | 過去の版を最新に戻す |
| `locks` | いまプレイ中の端末 |

---

## 4. 端末ごとにフォルダをそろえる際の注意

- **ターゲット名とフォルダ内の構造をそろえる。** 例：PPSSPP なら `SAVEDATA` フォルダを Windows と Android の両方でターゲット `ppsspp` に割り当てると、中の `ULJM05800/...` がそのまま一致する
- **大文字・小文字に注意。** Windows は区別しないが Android とサーバーは区別する。フォルダ名の大文字・小文字が端末ごとに違うと、別のファイルとして扱われる
- **ファイル名が端末ごとに違う単一ファイル**（例：mGBA の `.sav` と RetroArch の `.srm`）は、`file:` と `remote_name:` で名前をそろえる
- **ステートセーブ（どこでもセーブ）は同期しない方が安全。** エミュのバージョンやCPUアーキテクチャ（x64 と ARM）が違うと読めないことがある。ゲーム内セーブ（メモリーカード・SAVEDATA など）を中心に同期する
- **BIOS・キャッシュ・ログ**は `exclude:` で外す

## 5. Android アプリ

`android/` に Kotlin のアプリがある（設計書：[`docs/android-app-design.md`](docs/android-app-design.md)、開発者向け：[`android/README.md`](android/README.md)）。

### ビルドと導入
```bash
cd android
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```
自分用なので Google Play には出さず、APK を端末に直接入れる（「提供元不明のアプリ」を許可する）。

### 最初の設定
1. **設定**：サーバー URL（例 `https://192.168.50.49/emusync`）とトークン（`app.py add-device android-phone` で発行）を入れ、[接続テスト]。証明書が自己署名なら CA 証明書（`.crt` / `.pem`）を取り込む
2. **権限**：「すべてのファイルへのアクセス」と「通知」を許可する
3. **ターゲットの追加**（右下の ＋）：機種（psp / vita / ps2 / ps3）、エミュのアプリ、エミュのデータフォルダを選ぶ。保存の前に「送信・取得・競合が何件になるか」を確認できる
   - 各エミュのデータフォルダは、`Android/data/` の外（例 `/storage/emulated/0/ARMSX2`）に作っておく。`Android/data/` の中は、ほかのアプリから読めない

### 使い方
- **[遊ぶ]**：ロック → 取得と送信 → エミュを起動。ほかの端末がプレイ中なら知らせる（奪って起動もできる）。つながらないときは、同期せずに起動できる
- **遊び終わったら（自動）**：エミュを閉じて、そのまま 2 分戻ってこなければ、自動で送信してロックを解除する（Steam のクラウドセーブのような動き）。
  「使用状況へのアクセス」の許可が必要。画面が消えている間は数えないので、スリープで中断しても送らない。
  送信のあと 10 分以内にエミュに戻れば、プレイ中に戻る。待ち時間（1・2・5・10 分）と、自動送信を使うかは設定画面で変えられる
- **遊び終わったら（手動）**：エミュを終了してから、通知の **[送信して終了]**、またはアプリに戻って [送信]。送信してロックを解除する
- **Windows**：Python 版の `run` で起動すると、エミュの終了を待って自動で送信する（`python emusync.py -c config.yaml run ps2`）
- **競合**：両方で変わったファイルは上書きしない。ホームの「競合 n 件」から、ファイルごとに「手元を残す」「サーバー版を使う」を選ぶ
- **履歴**：行のメニューの「履歴」から、ファイルの過去の版に戻せる
- **定期送信**：1時間ごとに、手元の変更を送信する（取得はしない。プレイ中のターゲットは送らない）。設定で止められる

---

## API（クライアントを自作するとき用）
すべて `Authorization: Bearer <トークン>` が必要。

| メソッド | パス | 内容 |
|---|---|---|
| GET | `/api/manifest?prefix=ppsspp/` | `{files:[{path,version,sha256,size,updated_at,device}]}` |
| GET | `/api/files/{path}[?version=N]` | 本体。レスポンスヘッダー `X-Version`, `X-SHA256` |
| PUT | `/api/files/{path}` | ヘッダー `If-Match: <基準版。新規は0>`, `X-SHA256`。版がずれていれば **409** |
| GET | `/api/history/{path}` | 版の一覧 |
| POST | `/api/restore/{path}` | `{"version":N}` → その内容を新しい版として最新にする |
| POST / DELETE | `/api/locks/{target}` | `{"ttl":秒,"force":bool}`。他の端末が保持中なら **423** |
| GET | `/api/locks` | 有効なロックの一覧 |
