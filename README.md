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
`server/nginx-emusync.conf` の `location` ブロックを、Nextcloud を載せている既存の `server { listen 443 ssl; ... }` の中に貼り付ける。
`allow` 行の LAN 側のアドレス範囲は自宅のネットワークに合わせて変える。

```bash
sudo nginx -t && sudo systemctl reload nginx
curl -k -H "Authorization: Bearer <トークン>" https://ubuntu-server/emusync/api/ping
```

### 調整用の環境変数（emusync.service の Environment=）
| 変数 | 既定値 | 内容 |
|---|---|---|
| `EMUSYNC_DATA` | `./data` | DB と実体の保存先 |
| `EMUSYNC_KEEP_VERSIONS` | 30 | ファイルごとに残す版の数 |
| `EMUSYNC_MAX_BYTES` | 256MB | 1ファイルの上限 |

バックアップは `/var/lib/emusync` を丸ごとコピーすれば足りる（DB は WAL モードなので、止めてからコピーするか `sqlite3 emusync.db ".backup x.db"` を使う）。

---

## 2. 外出先からの接続（VPN）

ルーターのポートを開けずに済む **Tailscale** をおすすめする。スマホのモバイル回線（キャリアのNAT配下）からもそのままつながる。

```bash
# サーバー
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```
Windows と Android にも Tailscale アプリを入れて、同じアカウントでログインする。
MagicDNS を有効にすると、外出先でも `ubuntu-server` という名前でサーバーを引けるので、
`config.yaml` の `server:` は自宅でも外でも同じ値のままで使える。
Tailscale 経由の通信は 100.64.0.0/10 から来るので、Nginx の `allow` にはこの範囲を入れてある。

WireGuard を自分で立てる場合はルーターで UDP ポートを1つ開ける必要がある。
同期アプリ側は、どちらの VPN でも何も変えなくてよい。

> 証明書が自己署名の場合は、その証明書ファイルを各端末にコピーして `verify: <パス>` を指定する（`false` にはしない）。

---

## 3. Windows クライアント

```powershell
py -m pip install requests pyyaml
copy client\config.example.yaml client\config.yaml   # 中身を編集
py client\emusync.py -c client\config.yaml ping
py client\emusync.py -c client\config.yaml sync --dry-run   # 最初は必ず確認
py client\emusync.py -c client\config.yaml sync
```

### 普段の遊び方：エミュの起動を `run` 経由にする
`run` は「ロック取得 → 取得 → エミュ起動 → 終了を待つ → 送信 → ロック解除」をまとめて行う。
ターゲットごとに `.bat` を作って、デスクトップのショートカットをこれに置き換える：

```bat
@echo off
py "C:\emusync\client\emusync.py" -c "C:\emusync\client\config.yaml" run ppsspp %*
if errorlevel 1 pause
```

- サーバーにつながらないときは、同期せずにそのまま起動する（次に `sync` したとき送信される。その間に別の端末でも遊んでいたら、競合として検出される）
- 他の端末がロック中なら起動を止める。その端末の電源を落とし忘れたときなどは `--force` で奪える

### コマンド一覧
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

## 5. Android について

当面の使い方としては、Termux で同じ `emusync.py` が動く：
```bash
pkg install python && pip install requests pyyaml
termux-setup-storage            # /storage/emulated/0 を読めるようにする
python emusync.py -c config.yaml sync
```
ただし Android 11 以降は、ほかのアプリの `/Android/data/…` を外部アプリから読めない。
**各エミュのセーブ（ユーザーデータ）フォルダを、共有ストレージ側（例 `/storage/emulated/0/Emu/...`）に変更できるか**をまず確認する。
保存先を変更できないエミュは、外部の同期アプリからは扱えない。

本番用には Kotlin のアプリにする予定：WorkManager で定期的に同期し、SAF でフォルダを読み書きし、「エミュを起動する前に取得→戻ってきたら送信」を行うランチャー画面を持たせる。サーバー側の API はこのままでよい。

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
