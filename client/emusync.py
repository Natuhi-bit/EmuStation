#!/usr/bin/env python3
"""
emusync client - エミュのセーブフォルダを emusync サーバーと同期する

使い方:
  python emusync.py -c config.yaml sync            # 全ターゲットを双方向同期
  python emusync.py -c config.yaml sync ppsspp     # 指定ターゲットだけ
  python emusync.py -c config.yaml sync --dry-run  # 何が起きるかだけ表示
  python emusync.py -c config.yaml pull|push [ターゲット...]
  python emusync.py -c config.yaml sync ppsspp --prefer local|remote   # 競合をどちらかで解決
  python emusync.py -c config.yaml run ppsspp      # ロック→取得→エミュ起動→終了後に送信→ロック解除
  python emusync.py -c config.yaml history ppsspp SAVEDATA/ULJM05800/DATA.BIN
  python emusync.py -c config.yaml restore ppsspp SAVEDATA/ULJM05800/DATA.BIN 3
  python emusync.py -c config.yaml locks

判定ルール（3方向比較）:
  base = 前回このPCで同期したときの内容（state ファイルに記録）
  手元だけ base から変化     → 送信
  サーバーだけ base から変化 → 取得
  両方変化 / 初回で内容が違う → 競合（何も上書きせず、サーバー版を conflicts/ に保存して報告）
  片方にしかない              → もう片方へコピー（削除は伝播しない）
"""
from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import hashlib
import json
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import quote

import requests
import yaml

TMP_SUFFIX = ".emusync-tmp"


# ---------------------------------------------------------------- 設定
@dataclass
class Target:
    name: str
    root: Path                  # セーブフォルダ（file 指定時はその親）
    single_file: str | None     # file 指定時の手元のファイル名
    remote_name: str | None     # file 指定時のサーバー上の名前
    exclude: list[str] = field(default_factory=list)
    launch: list[str] | None = None


@dataclass
class Config:
    server: str
    token: str
    verify: bool | str
    state_dir: Path
    targets: dict[str, Target]
    timeout: int = 30


def load_config(path: str) -> Config:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    targets: dict[str, Target] = {}
    for name, t in (raw.get("targets") or {}).items():
        if "file" in t:
            f = Path(os.path.expandvars(os.path.expanduser(t["file"])))
            targets[name] = Target(name, f.parent, f.name, t.get("remote_name", f.name),
                                   t.get("exclude", []), t.get("launch"))
        else:
            p = Path(os.path.expandvars(os.path.expanduser(t["path"])))
            targets[name] = Target(name, p, None, None, t.get("exclude", []), t.get("launch"))
    verify = raw.get("verify", True)
    if isinstance(verify, str):
        verify = os.path.expanduser(verify)
    return Config(
        server=raw["server"].rstrip("/"),
        token=raw["token"],
        verify=verify,
        state_dir=Path(os.path.expanduser(raw.get("state_dir", "~/.emusync"))),
        targets=targets,
        timeout=int(raw.get("timeout", 30)),
    )


# ---------------------------------------------------------------- サーバー通信
class Api:
    def __init__(self, cfg: Config):
        self.base = cfg.server
        self.s = requests.Session()
        self.s.headers["Authorization"] = f"Bearer {cfg.token}"
        self.s.verify = cfg.verify
        self.timeout = cfg.timeout

    def _url(self, *parts: str) -> str:
        return self.base + "/api/" + "/".join(quote(p, safe="/") for p in parts)

    def ping(self) -> dict:
        r = self.s.get(self._url("ping"), timeout=self.timeout)
        r.raise_for_status()
        return r.json()

    def manifest(self, prefix: str) -> dict[str, dict]:
        r = self.s.get(self._url("manifest"), params={"prefix": prefix}, timeout=self.timeout)
        r.raise_for_status()
        return {f["path"][len(prefix):]: f for f in r.json()["files"]}

    def download(self, path: str, version: int | None = None) -> tuple[bytes, int, str]:
        params = {"version": version} if version else None
        r = self.s.get(self._url("files", path), params=params, timeout=self.timeout)
        r.raise_for_status()
        data = r.content
        sha = hashlib.sha256(data).hexdigest()
        if sha != r.headers["X-SHA256"]:
            raise RuntimeError(f"ダウンロード内容が壊れています: {path}")
        return data, int(r.headers["X-Version"]), sha

    def upload(self, path: str, data: bytes, base_version: int) -> requests.Response:
        return self.s.put(
            self._url("files", path), data=data, timeout=self.timeout,
            headers={"If-Match": str(base_version),
                     "X-SHA256": hashlib.sha256(data).hexdigest(),
                     "Content-Type": "application/octet-stream"},
        )

    def history(self, path: str) -> list[dict]:
        r = self.s.get(self._url("history", path), timeout=self.timeout)
        r.raise_for_status()
        return r.json()["versions"]

    def restore(self, path: str, version: int) -> dict:
        r = self.s.post(self._url("restore", path), json={"version": version}, timeout=self.timeout)
        r.raise_for_status()
        return r.json()

    def lock(self, target: str, force: bool = False) -> requests.Response:
        return self.s.post(self._url("locks", target), json={"force": force}, timeout=self.timeout)

    def unlock(self, target: str) -> None:
        self.s.delete(self._url("locks", target), timeout=self.timeout)

    def locks(self) -> list[dict]:
        r = self.s.get(self._url("locks"), timeout=self.timeout)
        r.raise_for_status()
        return r.json()["locks"]


# ---------------------------------------------------------------- ローカル
def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def scan(t: Target) -> dict[str, str]:
    """{サーバー上の相対パス: sha256}"""
    if t.single_file:
        f = t.root / t.single_file
        return {t.remote_name: sha256_file(f)} if f.is_file() else {}
    out: dict[str, str] = {}
    if not t.root.is_dir():
        return out
    for dirpath, _dirs, files in os.walk(t.root):
        for fn in files:
            if fn.endswith(TMP_SUFFIX):
                continue
            full = Path(dirpath) / fn
            rel = full.relative_to(t.root).as_posix()
            if any(fnmatch.fnmatch(rel, pat) or fnmatch.fnmatch(fn, pat) for pat in t.exclude):
                continue
            out[rel] = sha256_file(full)
    return out


def local_path(t: Target, rel: str) -> Path:
    if t.single_file:
        return t.root / t.single_file
    return t.root.joinpath(*rel.split("/"))


def atomic_write(p: Path, data: bytes) -> None:
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + TMP_SUFFIX)
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, p)


class State:
    """前回同期時点の {rel: {"sha256", "version"}} をターゲットごとに保存"""

    def __init__(self, state_dir: Path, target: str):
        self.path = state_dir / "state" / f"{target}.json"
        self.data: dict[str, dict] = {}
        if self.path.exists():
            self.data = json.loads(self.path.read_text(encoding="utf-8"))

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, indent=1, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.path)


# ---------------------------------------------------------------- 同期本体
@dataclass
class Result:
    uploaded: list[str] = field(default_factory=list)
    downloaded: list[str] = field(default_factory=list)
    conflicts: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)


def sync_target(cfg: Config, api: Api, t: Target, *, do_pull=True, do_push=True,
                prefer: str | None = None, dry_run=False) -> Result:
    res = Result()
    prefix = t.name + "/"
    local = scan(t)
    remote = api.manifest(prefix)
    if t.single_file:  # 単一ファイル指定のときは、その名前以外は扱わない
        remote = {k: v for k, v in remote.items() if k == t.remote_name}
    state = State(cfg.state_dir, t.name)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")

    def backup_local(rel: str) -> None:
        src = local_path(t, rel)
        dst = cfg.state_dir / "backups" / t.name / stamp / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    def do_download(rel: str, r: dict) -> None:
        if dry_run:
            res.downloaded.append(rel)
            return
        data, ver, sha = api.download(prefix + rel)
        atomic_write(local_path(t, rel), data)
        state.data[rel] = {"sha256": sha, "version": ver}
        res.downloaded.append(rel)

    def do_upload(rel: str, base_version: int) -> None:
        if dry_run:
            res.uploaded.append(rel)
            return
        data = local_path(t, rel).read_bytes()
        resp = api.upload(prefix + rel, data, base_version)
        if resp.status_code == 409:
            res.conflicts.append(f"{rel}（送信中に他の端末が更新。もう一度 sync してください）")
            return
        resp.raise_for_status()
        j = resp.json()
        state.data[rel] = {"sha256": j["sha256"], "version": j["version"]}
        res.uploaded.append(rel)

    def conflict(rel: str, r: dict) -> None:
        if prefer == "local" and do_push:
            do_upload(rel, r["version"])          # サーバーには履歴が残るので失われない
        elif prefer == "remote" and do_pull:
            if not dry_run:
                backup_local(rel)                 # 手元の版は backups/ に退避
            do_download(rel, r)
        else:
            if not dry_run:
                data, ver, _ = api.download(prefix + rel)
                dst = cfg.state_dir / "conflicts" / t.name / f"{rel}.server-v{ver}"
                dst.parent.mkdir(parents=True, exist_ok=True)
                dst.write_bytes(data)
            res.conflicts.append(rel)

    for rel in sorted(set(local) | set(remote)):
        try:
            l_sha = local.get(rel)
            r = remote.get(rel)
            b = state.data.get(rel)
            b_sha = b["sha256"] if b else None

            if l_sha and r and l_sha == r["sha256"]:
                state.data[rel] = {"sha256": r["sha256"], "version": r["version"]}
                continue
            if r is None:                                   # サーバーに無い
                if do_push:
                    do_upload(rel, 0)
                continue
            if l_sha is None:                               # 手元に無い
                if do_pull:
                    do_download(rel, r)
                continue

            local_changed = l_sha != b_sha
            remote_changed = b is None or r["sha256"] != b_sha
            if local_changed and not remote_changed:
                if do_push:
                    do_upload(rel, r["version"])
            elif remote_changed and not local_changed:
                if do_pull:
                    do_download(rel, r)
            else:
                conflict(rel, r)
        except Exception as e:  # 1ファイルの失敗で全体を止めない
            res.errors.append(f"{rel}: {e}")

    if not dry_run:
        state.save()
    return res


def print_result(name: str, res: Result, dry_run: bool) -> None:
    tag = "[dry-run] " if dry_run else ""
    if not (res.uploaded or res.downloaded or res.conflicts or res.errors):
        print(f"{tag}{name}: 変更なし")
        return
    print(f"{tag}{name}:")
    for x in res.uploaded:
        print(f"  ↑ 送信  {x}")
    for x in res.downloaded:
        print(f"  ↓ 取得  {x}")
    for x in res.conflicts:
        print(f"  ! 競合  {x}")
    for x in res.errors:
        print(f"  × 失敗  {x}")
    if res.conflicts and not dry_run:
        print("  → 競合は何も上書きしていません。サーバー版は state_dir/conflicts/ に保存しました。")
        print("    どちらを残すか決めたら: sync <ターゲット> --prefer local（手元優先） または --prefer remote（サーバー優先）")


# ---------------------------------------------------------------- コマンド
def pick_targets(cfg: Config, names: list[str]) -> list[Target]:
    if not names:
        return list(cfg.targets.values())
    unknown = [n for n in names if n not in cfg.targets]
    if unknown:
        sys.exit(f"設定にないターゲット: {', '.join(unknown)}")
    return [cfg.targets[n] for n in names]


def cmd_sync(cfg, api, args, do_pull=True, do_push=True) -> int:
    rc = 0
    for t in pick_targets(cfg, args.targets):
        res = sync_target(cfg, api, t, do_pull=do_pull, do_push=do_push,
                          prefer=args.prefer, dry_run=args.dry_run)
        print_result(t.name, res, args.dry_run)
        if res.conflicts or res.errors:
            rc = 2
    return rc


def cmd_run(cfg, api, args) -> int:
    t = pick_targets(cfg, [args.target])[0]
    if not t.launch:
        sys.exit(f"{t.name} に launch が設定されていません")
    cmd = t.launch + args.extra

    online = True
    try:
        resp = api.lock(t.name, force=args.force)
        if resp.status_code == 423:
            info = resp.json()["detail"]
            sys.exit(f"{info['message']}。終わってから起動するか、--force で奪ってください。")
        resp.raise_for_status()
    except requests.RequestException as e:
        online = False
        print(f"サーバーにつながりません（{e.__class__.__name__}）。同期せずに起動します。"
              "次に sync したときに反映されます。")

    try:
        if online:
            res = sync_target(cfg, api, t, prefer=args.prefer)
            print_result(t.name, res, False)
            if res.conflicts and not args.ignore_conflicts:
                print("競合があるため起動を中止しました（--ignore-conflicts で無視して起動）")
                return 2
        print("起動:", " ".join(cmd))
        proc = subprocess.run(cmd)
        print(f"エミュ終了（終了コード {proc.returncode}）")
        if online:
            res = sync_target(cfg, api, t, do_pull=False)
            print_result(t.name, res, False)
            return 2 if (res.conflicts or res.errors) else 0
        return 0
    finally:
        if online:
            try:
                api.unlock(t.name)
            except requests.RequestException:
                print("ロック解除に失敗しました（期限切れで自動解除されます）")


def cmd_history(cfg, api, args) -> int:
    t = pick_targets(cfg, [args.target])[0]
    for v in api.history(f"{t.name}/{args.path}"):
        when = dt.datetime.fromtimestamp(v["created_at"]).strftime("%Y-%m-%d %H:%M:%S")
        print(f"v{v['version']:<4} {when}  {v['size']:>10} bytes  {v['device']}  {v['sha256'][:12]}")
    return 0


def cmd_restore(cfg, api, args) -> int:
    t = pick_targets(cfg, [args.target])[0]
    j = api.restore(f"{t.name}/{args.path}", args.version)
    print(f"v{args.version} の内容を v{j['version']} として最新にしました。各端末で sync すると反映されます。")
    return 0


def cmd_locks(cfg, api, args) -> int:
    ls = api.locks()
    if not ls:
        print("プレイ中のロックはありません")
    for l in ls:
        until = dt.datetime.fromtimestamp(l["expires_at"]).strftime("%m/%d %H:%M")
        print(f"{l['target']}: {l['device']}（{until} まで）")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="emusync client")
    ap.add_argument("-c", "--config", default=os.environ.get("EMUSYNC_CONFIG", "config.yaml"))
    sub = ap.add_subparsers(dest="cmd", required=True)

    for name in ("sync", "pull", "push"):
        p = sub.add_parser(name)
        p.add_argument("targets", nargs="*")
        p.add_argument("--prefer", choices=["local", "remote"])
        p.add_argument("--dry-run", action="store_true")

    p = sub.add_parser("run")
    p.add_argument("target")
    p.add_argument("extra", nargs="*", help="エミュに追加で渡す引数（ROMのパスなど）")
    p.add_argument("--force", action="store_true", help="他端末のロックを奪う")
    p.add_argument("--prefer", choices=["local", "remote"])
    p.add_argument("--ignore-conflicts", action="store_true")

    p = sub.add_parser("history")
    p.add_argument("target")
    p.add_argument("path")
    p = sub.add_parser("restore")
    p.add_argument("target")
    p.add_argument("path")
    p.add_argument("version", type=int)
    sub.add_parser("locks")
    sub.add_parser("ping")

    args = ap.parse_args()
    cfg = load_config(args.config)
    api = Api(cfg)

    if args.cmd == "sync":
        return cmd_sync(cfg, api, args)
    if args.cmd == "pull":
        return cmd_sync(cfg, api, args, do_push=False)
    if args.cmd == "push":
        return cmd_sync(cfg, api, args, do_pull=False)
    if args.cmd == "run":
        return cmd_run(cfg, api, args)
    if args.cmd == "history":
        return cmd_history(cfg, api, args)
    if args.cmd == "restore":
        return cmd_restore(cfg, api, args)
    if args.cmd == "locks":
        return cmd_locks(cfg, api, args)
    if args.cmd == "ping":
        print(api.ping())
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
