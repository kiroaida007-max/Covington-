#!/usr/bin/env python3
"""Scores recovered names (apkproject names / recovery/recovered-names.json) against an R8 mapping.

The mapping must belong to the exact DEX the names were recovered from (for Tusky 32.2, the one
scripts/restore-tusky.py rebuilds byte-identically). A name is correct when it equals the
original simple name (classes) or member name (fields, methods) R8 recorded for that obfuscated
declaration; classes R8 merged count as correct if any origin has the name.
"""
import json
import re
import sys
from collections import defaultdict

CLASS = re.compile(r"^(\S+) -> (\S+):$")
FIELD = re.compile(r"^\s+(\S+) (\S+) -> (\S+)$")
METHOD = re.compile(r"^\s+(?:\d+:\d+:)?(\S+) ([^\s(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)$")


def load_mapping(path):
    classes = defaultdict(set)   # obfuscated class -> original names
    fields = defaultdict(set)    # (obfuscated class, obfuscated field) -> original names
    methods = defaultdict(set)   # (obfuscated class, obfuscated method) -> original names
    current = None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.lstrip().startswith("#"):
            continue
        m = CLASS.match(line)
        if m:
            current = m.group(2)
            classes[current].add(m.group(1))
            continue
        if current is None:
            continue
        m = METHOD.match(line)
        if m:
            name = m.group(2).rsplit(".", 1)[-1]  # inlined frames name their owner
            if "." not in m.group(2):
                methods[(current, m.group(4))].add(name)
            continue
        m = FIELD.match(line)
        if m:
            fields[(current, m.group(3))].add(m.group(2))
    return classes, fields, methods


def simple(name):
    return re.split(r"[.$]", name)[-1]


def main(mapping, names_json):
    classes, fields, methods = load_mapping(mapping)
    names = json.load(open(names_json, encoding="utf-8"))
    score = defaultdict(lambda: defaultdict(int))
    wrong = defaultdict(list)
    for n in names:
        decl, rule = n["declaration"], n["rule"]
        owner = decl.split("->")[0][1:-1].replace("/", ".")
        if "->" not in decl:
            kind, truth = "class", {simple(o) for o in classes.get(owner, ())}
        else:
            member = decl.split("->", 1)[1]
            if "(" in member:
                kind, truth = "method", methods.get((owner, member.split("(")[0]), set())
            else:
                kind, truth = "field", fields.get((owner, member), set())
        key = (kind, rule)
        if not truth:
            score[key]["unknown"] += 1
        elif n["name"] in truth:
            score[key]["correct"] += 1
        else:
            score[key]["wrong"] += 1
            wrong[key].append(f'{decl} -> {n["name"]} (R8: {", ".join(sorted(truth))})')
    total = defaultdict(int)
    print(f'{"kind":7} {"rule":48} {"correct":>8} {"wrong":>6} {"no truth":>9} {"precision":>10}')
    for (kind, rule), s in sorted(score.items()):
        judged = s["correct"] + s["wrong"]
        print(f'{kind:7} {rule:48} {s["correct"]:8} {s["wrong"]:6} {s["unknown"]:9} {s["correct"] / judged if judged else 0:10.3f}')
        for k in ("correct", "wrong", "unknown"):
            total[k] += s[k]
    judged = total["correct"] + total["wrong"]
    print(f'{"all":56} {total["correct"]:8} {total["wrong"]:6} {total["unknown"]:9} {total["correct"] / judged if judged else 0:10.3f}')
    for key, items in sorted(wrong.items()):
        print(f"\nwrong, {key[0]} by {key[1]} ({len(items)}):")
        for item in items[:15]:
            print("  " + item)


if __name__ == "__main__":
    main(*sys.argv[1:3])
