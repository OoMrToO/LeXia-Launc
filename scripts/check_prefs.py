#!/usr/bin/env python3
"""Stops a build whose settings keys clash.

Android keeps each setting under a name with one type (true/false, text, number, list). If new code
reads a name that a TV already holds with another type, LeXia crashes at every start (#119: the
"Son baktıkların" list was written under "recent", the old true/false switch of the app order).

scripts/prefs_keys.txt lists every name LeXia has ever used, with its type, including ones no
longer in the code (old TVs still hold them). This check fails when:
  - the code uses one name with two types,
  - the code uses a name with a type other than the one listed,
  - the code uses a name that is not listed yet (add it, after making sure it is really new).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"
LIST = ROOT / "scripts/prefs_keys.txt"

TYPES = {
    "bool": "bool", "putBool": "bool", "getBoolean": "bool", "putBoolean": "bool",
    "str": "text", "putStr": "text", "getString": "text", "putString": "text",
    "int": "int", "putInt": "int", "getInt": "int",
    "long": "long", "putLong": "long", "getLong": "long",
    "getStringSet": "set", "putStringSet": "set",
    "getFloat": "float", "putFloat": "float",
}
CALL = re.compile(r'\b(' + "|".join(TYPES) + r')\(\s*"([^"]+)"')

known = {}
for line in LIST.read_text(encoding="utf-8").splitlines():
    line = line.split("#", 1)[0].strip()
    if line:
        k, t = line.split()
        known[k] = t

used = {}
for f in sorted(SRC.rglob("*.kt")):
    for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        for fn, key in CALL.findall(line):
            used.setdefault(key, {}).setdefault(TYPES[fn], f"{f.relative_to(ROOT)}:{n}")

bad = []
for key, types in sorted(used.items()):
    if len(types) > 1:
        bad.append(f'"{key}" is used as {" and ".join(f"{t} ({w})" for t, w in types.items())}')
        continue
    (t, where), = types.items()
    if key not in known:
        bad.append(f'"{key}" ({t}, {where}) is new: add "{key} {t}" to scripts/prefs_keys.txt if no older build used this name')
    elif known[key] != t:
        bad.append(f'"{key}" is {t} at {where}, but TVs may hold it as {known[key]}: pick another name')

if bad:
    print("Settings key check failed:")
    for b in bad:
        print("  - " + b)
    sys.exit(1)
print(f"Settings keys OK ({len(used)} in code, {len(known)} known)")
