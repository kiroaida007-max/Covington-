#!/usr/bin/env python3
"""Score `apkproject diff` class pairing against the R8 mappings of both builds.

Each build's mapping.txt names the original class behind every obfuscated one. A reported pair is
correct when both sides come from the same original class; an original present in both builds is
recovered when the diff pairs its two obfuscated names. Optionally, a list of source files changed
between the two commits (git diff --name-only) shows how many MODIFIED classes trace to an edit.
"""

import argparse
import collections
import json
import re
from pathlib import Path


def read_mapping(path):
    """obfuscated descriptor -> set of original Java names (R8 merges can map several to one)."""
    out = collections.defaultdict(set)
    line_re = re.compile(r"^(\S+) -> (\S+):$")
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith((" ", "#")):
                continue
            m = line_re.match(line.rstrip("\n"))
            if m:
                out["L" + m.group(2).replace(".", "/") + ";"].add(m.group(1))
    return out


def read_method_origins(path, classes):
    """For the obfuscated classes in `classes`: obfuscated method name -> original classes whose
    code it holds, including methods R8 inlined into it (the mapping lists every inline frame)."""
    out = collections.defaultdict(lambda: collections.defaultdict(set))
    class_re = re.compile(r"^(\S+) -> (\S+):$")
    method_re = re.compile(r"^\s+(?:\d+:\d+:)?\S+ ([^\s(]+)\(.*-> (\S+)$")
    current = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            if not line.startswith(" "):
                m = class_re.match(line.rstrip("\n"))
                current = None
                if m:
                    desc = "L" + m.group(2).replace(".", "/") + ";"
                    current = (desc, m.group(1)) if desc in classes else None
                continue
            if current is None or line.lstrip().startswith("#"):
                continue
            m = method_re.match(line.rstrip("\n"))
            if not m:
                continue
            original, obf = m.group(1), m.group(2)
            owner = original.rpartition(".")[0] if "." in original else current[1]
            out[current[0]][obf].add(owner)
    return out


def read_pairs(path):
    """Parse code-map.tsv into (old, new, how, similarity) pairs plus separate added/removed lists."""
    pairs, added, removed = [], [], []
    for line in open(path, encoding="utf-8"):
        if line.startswith("#"):
            continue
        old, new, how, sim = line.rstrip("\n").split("\t")
        if how == "ADDED":
            added.append(new)
        elif how == "REMOVED":
            removed.append(old)
        else:
            pairs.append((old, new, how, float(sim)))
    return pairs, added, removed


def source_file(original):
    """com.x.QuoteKt$Quote$1 -> com/x/Quote (the Kotlin/Java file stem it most likely came from)."""
    outer = original.split("$")[0]
    pkg, _, simple = outer.rpartition(".")
    if simple.endswith("Kt"):
        simple = simple[:-2]
    return pkg.replace(".", "/") + "/" + simple


def main():
    """Compare apkproject diff's class pairing against both builds' R8 mappings and print accuracy stats."""
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("old_mapping")
    p.add_argument("new_mapping")
    p.add_argument("diff_dir", help="output directory of apkproject diff (code-map.tsv, diff.json)")
    p.add_argument("--changed", help="file with source paths changed between the commits (git diff --name-only)")
    p.add_argument("--app-prefix", default="com.keylesspalace.tusky.")
    p.add_argument("--details", action="store_true")
    a = p.parse_args()

    old_map, new_map = read_mapping(a.old_mapping), read_mapping(a.new_mapping)
    pairs, added, removed = read_pairs(Path(a.diff_dir) / "code-map.tsv")
    diff = json.loads((Path(a.diff_dir) / "diff.json").read_text())
    old_present = {o for o, *_ in pairs} | set(removed)
    new_present = {n for _, n, *_ in pairs} | set(added)

    def origins(mapping, desc):
        # Classes R8 did not rename are listed as "X -> X:"; classes it synthesized are not listed.
        return mapping.get(desc) or {desc[1:-1].replace("/", ".")}

    by_how = collections.defaultdict(lambda: [0, 0])
    wrong = []
    for old, new, how, sim in pairs:
        ok = bool(origins(old_map, old) & origins(new_map, new))
        by_how[how][0 if ok else 1] += 1
        if not ok:
            wrong.append((how, sim, old, sorted(origins(old_map, old))[:2], new, sorted(origins(new_map, new))[:2]))

    # Recall: originals in both builds, and whether the diff paired their obfuscated names.
    old_by_origin = {o: d for d in old_present for o in origins(old_map, d)}
    new_by_origin = {o: d for d in new_present for o in origins(new_map, d)}
    pair_of = {o: n for o, n, *_ in pairs}
    common = old_by_origin.keys() & new_by_origin.keys()
    recovered = sum(1 for o in common if pair_of.get(old_by_origin[o]) == new_by_origin[o])
    false_added = [d for d in added if any(o in old_by_origin for o in origins(new_map, d))]
    false_removed = [d for d in removed if any(o in new_by_origin for o in origins(old_map, d))]

    # Baseline: comparing by obfuscated name.
    same_name = old_present & new_present
    same_name_same_class = sum(1 for d in same_name if origins(old_map, d) & origins(new_map, d))

    print(f"classes: {len(old_present)} old, {len(new_present)} new; originals in both builds: {len(common)}")
    print(f"renamed by R8 between the builds: {sum(1 for o in common if old_by_origin[o] != new_by_origin[o])}")
    print(f"by-name baseline: {len(same_name)} names in both builds, {same_name_same_class} name the same class "
          f"({len(same_name) - same_name_same_class} reused for another class)")
    total_ok = sum(v[0] for v in by_how.values())
    total_bad = sum(v[1] for v in by_how.values())
    print(f"pairs: {total_ok} correct, {total_bad} wrong (precision {total_ok / max(1, total_ok + total_bad):.4f})")
    for how, (ok, bad) in sorted(by_how.items()):
        print(f"  {how:26s} {ok:6d} correct {bad:4d} wrong")
    print(f"recall: {recovered} / {len(common)} originals paired correctly ({recovered / max(1, len(common)):.4f})")
    print(f"reported ADDED but present in the old build: {len(false_added)} of {len(added)}; "
          f"REMOVED but present in the new build: {len(false_removed)} of {len(removed)}")

    if a.changed:
        changed = {re.sub(r"\.(kt|java)$", "", line.strip().split("/java/", 1)[-1])
                   for line in open(a.changed) if line.strip().endswith((".kt", ".java")) and "/test/" not in line}
        modified = [c for c in diff["classes"] if c["status"] == "MODIFIED"]
        app_mod = []
        for c in modified:
            desc = "L" + c["newName"].replace(".", "/") + ";"
            names = origins(new_map, desc)
            app_mod.append((c, names, any(source_file(n) in changed for n in names), any(n.startswith(a.app_prefix) for n in names)))
        in_app = [m for m in app_mod if m[3]]
        traced = sum(1 for m in in_app if m[2])
        print(f"source files changed: {len(changed)}; MODIFIED classes: {len(modified)} "
              f"({len(in_app)} app, {len(modified) - len(in_app)} library); app MODIFIED classes in a changed file: {traced}")
        # Recall over classes of changed files present in both builds.
        flagged = {"L" + c["newName"].replace(".", "/") + ";" for c in modified} | set(added)
        candidates = [o for o in common if source_file(o) in changed]
        hit = sum(1 for o in candidates if new_by_origin[o] in flagged)
        print(f"classes from changed files (in both builds): {len(candidates)}, flagged MODIFIED: {hit}")
        # Per edited file: is code compiled from it reported? Changed methods are attributed to
        # every original class whose code they contain, including what R8 inlined into them.
        changed_new = {"L" + c["newName"].replace(".", "/") + ";": c for c in diff["classes"] if c["newName"] and c["status"] in ("MODIFIED", "ADDED")}
        changed_old = {"L" + c["oldName"].replace(".", "/") + ";": c for c in diff["classes"] if c["oldName"] and c["status"] in ("MODIFIED", "REMOVED")}
        inl_new = read_method_origins(a.new_mapping, set(changed_new))
        inl_old = read_method_origins(a.old_mapping, set(changed_old))
        flagged_files = set()
        for desc, c in changed_new.items():
            for m in c["methodsModified"] + c["methodsAdded"]:
                flagged_files |= {source_file(o) for o in inl_new[desc].get(m.split("(")[0], set())}
            if c["status"] == "ADDED":
                flagged_files |= {source_file(n) for n in origins(new_map, desc)}
        for desc, c in changed_old.items():
            for m in c["methodsRemoved"]:
                flagged_files |= {source_file(o) for o in inl_old[desc].get(m.split("(")[0], set())}
            if c["status"] == "REMOVED":
                flagged_files |= {source_file(n) for n in origins(old_map, desc)}
        compiled = {source_file(o) for o in old_by_origin.keys() | new_by_origin.keys()}
        edited = sorted(f for f in changed if f in compiled)
        found = [f for f in edited if f in flagged_files]
        print(f"edited source files with compiled classes: {len(edited)}, reported by the diff: {len(found)}")
        # Precision side: modified classes whose changed methods hold code from an edited file.
        explained = 0
        for desc, c in changed_new.items():
            if c["status"] != "MODIFIED":
                continue
            files = set()
            for m in c["methodsModified"] + c["methodsAdded"]:
                files |= {source_file(o) for o in inl_new[desc].get(m.split("(")[0], set())}
            if files & set(edited):
                explained += 1
        n_mod = sum(1 for c in diff["classes"] if c["status"] == "MODIFIED")
        print(f"MODIFIED classes holding code from an edited file: {explained} of {n_mod}; the rest changed through the compiler "
              f"(inlining, merging, generated DI code) or library regrouping")
        if a.details:
            for f in edited:
                print("   ", "found  " if f in found else "MISSED ", f)
        if a.details:
            print("\napp MODIFIED classes not traced to a changed file:")
            for c, names, ok, app in in_app:
                if not ok:
                    print("  ", sorted(names)[0], c["matchedBy"], len(c["methodsModified"]), len(c["methodsAdded"]), len(c["methodsRemoved"]))
            print("\nlibrary MODIFIED classes:")
            for c, names, ok, app in app_mod:
                if not app:
                    print("  ", sorted(names)[0], c["matchedBy"], len(c["methodsModified"]), len(c["methodsAdded"]), len(c["methodsRemoved"]))

    if a.details:
        print("\nwrong pairs:")
        for w in sorted(wrong)[:200]:
            print("  ", w)


if __name__ == "__main__":
    main()
