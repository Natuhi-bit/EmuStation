#!/usr/bin/env python3
"""Python の fnmatch の結果を、Kotlin 版（GlobPattern）のテストの期待値として書き出す。

使い方: python3 android/scripts/gen_fnmatch_cases.py > android/app/src/test/resources/fnmatch_cases.json
"""
import json
import sys
from fnmatch import fnmatchcase

PATTERNS = [
    "*.log", "*.bak", "*", "?", "??.bin", "a*b", "*/x.log", "SAVE*/*.BIN", "[abc]*", "[!abc]*",
    "[a-c]x", "[z-a]x", "[!z-a]x", "[a-]x", "[-a]x", "[!-a]x", "[]a]x", "[!]a]x", "[a-c-e]x",
    "[a-c-e-g]x", "[c-a-e]x", "[a--]x", "[!a--]x", "[x", "x]", "*[0-9].ps2", "Mcd00?.ps2",
    "**.log", "a*b*c", "*.LOG", "\\*x", "[\\]x", "[^a]x", "[あ-ん]*", "*.emusync-tmp", "[[]x",
    "[a[]x", "[!!]x",
]
NAMES = [
    "debug.log", "sub/trace.log", "x.log", "a", "ab", "ab.bin", "abc.bin", "acb", "a/b", "aXb",
    "SAVEDATA/DATA.BIN", "SAVEDATA/sub/DATA.BIN", "bx", "dx", "-x", "ex", "fx", "]x", "!x", "[x",
    "x]", "Mcd001.ps2", "Mcd001.ps2.bak", "Mcd00a.ps2", "DEBUG.LOG", "\\x", "*x", "^x", "あいう",
    "ンx", "a\nb", "[]", "axbxc", "acb c", "x.emusync-tmp", "f.LOG", "🎮x",
]

cases = [[p, n, fnmatchcase(n, p)] for p in PATTERNS for n in NAMES]
json.dump({"python": sys.version.split()[0], "cases": cases}, sys.stdout, ensure_ascii=False, indent=0)
