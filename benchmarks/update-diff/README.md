# Update diff against two real R8 mappings

Measured 2026-10-10 on two consecutive Tusky releases from F-Droid: 32.1
(`com.keylesspalace.tusky_141.apk`) and 32.2 (`_142.apk`), both R8 8.13.19 full mode.

## Ground truth

Each release was rebuilt from the upstream commit F-Droid built (`5d566769` for 32.1, `ff0c9f58`
for 32.2) with `rebuild-tusky.sh`. Both rebuilt `classes.dex` files are **byte-identical** to the
F-Droid APKs, so each build's `mapping.txt` is the true original name of every obfuscated class in
that exact APK. A diff pair is correct when both sides come from the same original class.

```
benchmarks/update-diff/rebuild-tusky.sh 5d5667694de68c1e15a8bbf5aa31cfabe14d8819 ../tusky-141   # 32.1, ~8 min
apkproject diff tusky-141.apk tusky-142.apk --out diff-141-142
python3 benchmarks/update-diff/evaluate.py mapping-141.txt mapping-142.txt diff-141-142 \
    --changed benchmarks/update-diff/tusky-32.1-32.2-changed-files.txt
```

`tusky-32.1-32.2-changed-files.txt` is `git diff --name-only 5d566769 ff0c9f58`: 23 source files,
22 of them with compiled code.

## Results

| Measure | Result |
|---|---:|
| Classes (old → new) | 7,967 → 7,966 |
| Classes R8 gave another name between the two builds | 163 |
| Names both builds use for **different** classes (what a by-name diff compares wrongly) | 167 |
| Class pairs correct | **7,956 of 7,962 (precision 0.9992)** |
| Classes present in both builds paired correctly | **7,956 of 7,959 (recall 0.9996)** |
| Edited source files whose change the diff reports | **21 of 22** (the miss only edits a comment) |
| Modified classes holding code from an edited file | 36 of 88 |
| Run time (both APKs, 4 cores) | 13 s |

The diff also reports the one API change of 32.2 exactly: five endpoints gained a `min_id` query
parameter (`MastodonApi.kt`). The signer (F-Droid, schemes v1+v2+v3), permissions, components and
risk score are unchanged, so nothing is ranked "review first".

"Reported" follows R8's inlining: `evaluate.py` attributes each changed method to every original
class whose code it holds, using the inline frames in the mapping. R8 inlines small Compose
functions into their callers, so an edit to `StatusText.kt` shows up in the class that calls it.

The other 52 modified classes changed without a source edit: R8 regrouped what it merged (helper
classes, lambda groups), generated code (Hilt components) changed with the dependency graph, or a
method changed through what was inlined into it. The report marks the two R8-specific cases
(`R8-merged`, `static methods only`) and lists them after the others.

## How each step moved the numbers

Every step was found by looking at classes reported as modified whose source had not changed.

| Version | Modified classes | Pair precision |
|---|---:|---:|
| First matcher (names, identical code, similar code) | 296 | 0.9995 |
| + inlined resource ids compared by resource name (a new string renumbers the ids after it) | 165 | 0.9995 |
| + static calls identified by the method reached, static methods paired on their own (R8 merges static helpers into shared classes and reshuffles them between builds); the app's version name ignored inside strings (About screen, User-Agent) | 126 | 0.9992 |
| + same instructions with consistently renamed references counted apart; duplicate static helpers as moves | 119 | 0.9992 |
| + class ids R8 passes to merged lambda groups, and field names R8 writes for reflective field updaters, normalized | 96 | 0.9992 |
| + parameter order of app methods ignored (R8 sorts parameters by type name, so the order follows obfuscated names) | **88** | **0.9992** |

## What is not measured here

- Apps obfuscated with another tool (ProGuard, DexGuard) or a different R8 version on each side.
- Larger updates (a major release with library upgrades); 32.1 → 32.2 is a bug-fix release.
- Whether each of the 52 compiler-made changes is behaviour-neutral: they are reported, not hidden.
