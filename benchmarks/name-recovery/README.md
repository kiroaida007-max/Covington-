# Name recovery against a real R8 mapping

Measured 2026-10-10 on Tusky 32.2 (F-Droid `com.keylesspalace.tusky_142.apk`, SHA-256
`3e8fcc49…caffa`, R8 8.13.19 full mode).

## Ground truth

`scripts/restore-tusky.py --variant release` rebuilds the release from the upstream commit F-Droid
built. Its `classes.dex` is **byte-identical** to the APK's (`dexPayloadsIdentical: true`,
`mappingValidForInputApk: true` in `.restoration/build.json`). So the build's
`app/build/outputs/mapping/blueRelease/mapping.txt` holds the true original name of every
obfuscated declaration in this exact APK. It is 86 MB and not committed; rebuild it as below.

## What is scored

`apkproject names APK --out names.json` writes every name `NameOracle` recovered, with its rule and
evidence. `evaluate.py MAPPING names.json` compares each with the mapping: a class name with the
original simple name, a field or method name with the original member name. For classes R8 merged,
any origin counts.

| Rule | Declarations | Correct | Wrong | Precision |
|---|---|---:|---:|---:|
| Kotlin data class `toString`, corroborated by `hashCode`/`equals` | classes | 128 | 1 | 0.992 |
| | fields | 450 | 3 | 0.993 |
| `toString` labels (hand-written, same format) | fields | 742 | 7 | 0.991 |
| ViewBinding (layout and view ids) | classes | 42 | 0 | 1.000 |
| | fields | 244 | 0 | 1.000 |
| Room insert adapter (alone or agreeing with toString) | classes / fields | 3 / 7 | 0 / 0 | 1.000 |
| Enum constant names | fields | 30 | 1 | 0.968 |
| Getters of named fields (data classes only) | methods | 14 | 0 | 1.000 |
| **All** | | **1,660** | **12** | **0.993** |

The remaining errors are mostly genuine typos or old names in library `toString` labels
(`paragraphSyle`, `extralargeIncreased`), one subclass printing its parent's name, and an enum
whose own constant field R8 removed, so the first store is an alias (`DEFAULT = AUTO_SYSTEM`).

How the rules got here (each step measured with this evaluator):

| Version | Correct | Wrong | Precision |
|---|---:|---:|---:|
| First rules | 1,994 | 141 | 0.934 |
| + label attributed to the value appended next; class names need `hashCode`/`equals`; binding needs `getRoot()`; one field per enum constant; getters only in data classes | 1,660 | 29 | 0.983 |
| + binding classes may not carry other supertypes (R8 merges); Moshi model must be both constructed and received | 1,660 | 12 | 0.993 |

Rejected along the way: naming a class from `toString` alone when R8 removed `hashCode` and `equals`
(5 correct, 1 wrong); Moshi model by majority vote (an enum cast five times outvoted the model).

## Library naming on the same app

The same mapping scores `LibraryMatcher` (from the full `decompile` of `0dda597`/`2e18f38`, whose
matches are identical): **1,695 correct, 127 wrong (0.930)**. The fixture benchmark in
`benchmarks/library-matching` reports 1.000 there, so the real app is the harder test. Errors by kind:

| Kind | Count |
|---|---:|
| Another library's class (often one whose artifact was not among the declared libraries) | 52 |
| D8 synthetic lambda with another number (`$$ExternalSyntheticLambda0` vs `2`) | 27 |
| Another class of the same package | 23 |
| Another nested class of the same outer class | 13 |
| An app class named as a library class | 12 |

`NameMapping` now drops a library match when compiler evidence names the class differently, when
it renames a class whose (unminified) name R8 kept, or when the target is a synthetic lambda.
Replayed on the same matches that removes 30 wrong and 19 correct names: **1,676 correct, 97 wrong
(0.945)**. This is a replay of the rules on recorded matches; a full `decompile` run with them is
still to be measured.

## Reproduce

```sh
scripts/setup-toolchain.sh --full && ./gradlew :cli:installDist
# Ground truth (Android SDK platform 36 + build-tools 35.0.0, JDK 21):
JAVA_HOME=/path/to/jdk-21 python3 scripts/restore-tusky.py tusky-142.apk --out runs/tusky --variant release
modules/cli/build/install/apkproject/bin/apkproject names tusky-142.apk --out runs/names.json
python3 benchmarks/name-recovery/evaluate.py runs/tusky/app/build/outputs/mapping/blueRelease/mapping.txt runs/names.json
```

Behind a rate-limited Maven Central, the rebuild needs a mirror; Tusky's dependency-verification
file was written against the plugin portal, so with a mirror Gradle needs
`org.gradle.dependency.verification=lenient` (set in a separate `GRADLE_USER_HOME`). The DEX
comparison above is what establishes the mapping's validity, not the dependency checksums.
