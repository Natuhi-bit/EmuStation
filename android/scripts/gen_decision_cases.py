#!/usr/bin/env python3
"""Python 版 sync_target（client/emusync.py）の判定結果を、Kotlin 版 SyncEngine のテストの期待値として書き出す。

1つのファイル "SAVE/f.bin" について、次の組み合わせをすべて試す。
  手元の内容 × サーバーの内容と版 × base（前回同期の状態） × モード × prefer × dry-run × 競り合い
「競り合い」は、manifest を取ったあとで他の端末がサーバーを更新した状態（送信すると 409 になる）。

通信は偽の Api に差し替え、ファイル操作は本物の一時フォルダで行う。

使い方: python3 android/scripts/gen_decision_cases.py > android/app/src/test/resources/decision_cases.json
"""
from __future__ import annotations

import hashlib
import itertools
import json
import os
import sys
import tempfile
import types
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# emusync.py が import する外部ライブラリは、判定には使わないので空のモジュールで代用する
for name in ("requests", "yaml"):
    sys.modules.setdefault(name, types.ModuleType(name))
sys.path.insert(0, str(ROOT / "client"))
import emusync  # noqa: E402

TARGET = "psp"
REL = "SAVE/f.bin"
PATH = f"{TARGET}/{REL}"
CONTENTS = {c: c.encode() * 3 for c in "XYZW"}  # W は競り合いで他の端末が書く内容


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


LETTER = {sha(v): k for k, v in CONTENTS.items()}


class Resp:
    def __init__(self, status: int, body: dict):
        self.status_code = status
        self._body = body

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class FakeApi:
    """manifest は manifest_view を返し、取得・送信は actual（サーバーの本当の状態）に対して行う"""

    def __init__(self, manifest_view: dict, actual: dict):
        self.view = manifest_view
        self.actual = actual
        self.uploads: list[int] = []

    def manifest(self, prefix):
        return {p[len(prefix):]: {"path": p, "version": v, "sha256": sha(d), "size": len(d),
                                  "updated_at": 0.0, "device": "x"}
                for p, (d, v) in self.view.items() if p.startswith(prefix)}

    def download(self, path, version=None):
        d, v = self.actual[path]
        return d, v, sha(d)

    def upload(self, path, data, base_version):
        self.uploads.append(base_version)
        cur = self.actual.get(path)
        cv = cur[1] if cur else 0
        if cv != base_version:
            return Resp(409, {"detail": {"message": "x", "server_version": cv,
                                         "server_sha256": sha(cur[0]) if cur else None}})
        if cur and cur[0] == data:
            return Resp(200, {"path": path, "version": cv, "sha256": sha(data), "unchanged": True})
        self.actual[path] = (data, cv + 1)
        return Resp(200, {"path": path, "version": cv + 1, "sha256": sha(data)})


def run_case(local, remote, base, mode, prefer, dry, race) -> dict:
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        root = tmp / "root"
        state_dir = tmp / "state"
        root.mkdir()
        if local:
            f = root / REL
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(CONTENTS[local])
        if base:
            (state_dir / "state").mkdir(parents=True)
            (state_dir / "state" / f"{TARGET}.json").write_text(json.dumps(
                {REL: {"sha256": sha(CONTENTS[base[0]]), "version": base[1]}}))

        view = {PATH: (CONTENTS[remote[0]], remote[1])} if remote else {}
        actual = dict(view)
        if race and remote:
            actual[PATH] = (CONTENTS["W"], remote[1] + 1)
        api = FakeApi(view, actual)

        cfg = emusync.Config(server="x", token="x", verify=True, state_dir=state_dir, targets={})
        t = emusync.Target(TARGET, root, None, None, [])
        res = emusync.sync_target(cfg, api, t, do_pull=mode != "PUSH", do_push=mode != "PULL",
                                  prefer=prefer.lower() if prefer else None, dry_run=dry)

        st = emusync.State(state_dir, TARGET).data.get(REL)
        lf = root / REL
        conflict_copies = sorted(
            (p.relative_to(state_dir / "conflicts" / TARGET).as_posix(), LETTER[sha(p.read_bytes())])
            for p in (state_dir / "conflicts").rglob("*") if p.is_file())
        backups = sorted(LETTER[sha(p.read_bytes())]
                         for p in (state_dir / "backups").rglob("*") if p.is_file())
        server = actual.get(PATH)
        return {
            "uploaded": res.uploaded,
            "downloaded": res.downloaded,
            # 409 の競合は文言付きで記録されるので、種類に直す
            "conflicts": [[c.split("（")[0], "UPLOAD_REJECTED" if "（" in c else "BOTH_CHANGED"]
                          for c in res.conflicts],
            "errors": len(res.errors),
            "uploadBaseVersions": api.uploads,
            "local": LETTER[sha(lf.read_bytes())] if lf.is_file() else None,
            "server": [LETTER[sha(server[0])], server[1]] if server else None,
            "base": [LETTER[st["sha256"]], st["version"]] if st else None,
            "conflictCopies": [list(c) for c in conflict_copies],
            "backups": backups,
        }


def main():
    locals_ = [None, "X", "Y"]
    remotes = [None, ("X", 1), ("Y", 2), ("Z", 3)]
    bases = [None, ("X", 1), ("Y", 2)]
    modes = ["SYNC", "PULL", "PUSH"]
    prefers = [None, "LOCAL", "REMOTE"]
    cases = []
    for local, remote, base, mode, prefer, dry, race in itertools.product(
            locals_, remotes, bases, modes, prefers, [False, True], [False, True]):
        if race and not remote:
            continue
        cases.append({
            "input": {"local": local, "remote": list(remote) if remote else None,
                      "base": list(base) if base else None, "mode": mode, "prefer": prefer,
                      "dryRun": dry, "race": race},
            "expected": run_case(local, remote, base, mode, prefer, dry, race),
        })
    json.dump({"python": sys.version.split()[0], "target": TARGET, "rel": REL,
               "contents": {k: v.decode() for k, v in CONTENTS.items()}, "cases": cases},
              sys.stdout, ensure_ascii=False, indent=0)


if __name__ == "__main__":
    main()
