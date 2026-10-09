# Library naming validation

Measured 2026-10-09. Enhancement baseline: `d4b98a00e5bd2ef55cb42049ba7ebc14e73a5d83`.
Names inferred by this matcher remain INFERRED; they are not verified artifact identities.

## R8 ground truth

R8 8.7.18 renames an app using Retrofit 2.11.0, OkHttp 4.12.0, Moshi 1.15.1, Okio 3.9.0 and
Kotlin 2.0.21. Each comparison uses the same APK/reference DEX and R8 map. The four configurations
exercise shrinking, optimization and repackaging separately; they use the same app source and
are development fixtures, not a held-out APK corpus.

| Configuration | Correct classes, before → after | Incorrect classes, before → after | Precision, before → after | Recall, before → after |
|---|---:|---:|---:|---:|
| Full optimization | 201 → 191 | 7 → 2 | 0.966 → 0.990 | 0.593 → 0.563 |
| Full optimization and repackaging | 198 → 188 | 10 → 2 | 0.952 → 0.989 | 0.604 → 0.573 |
| Shrinking without optimization | 550 → 548 | 0 → 0 | 1.000 → 1.000 | 0.881 → 0.878 |
| Neither shrinking nor optimization | 1,416 → 1,414 | 0 → 0 | 1.000 → 1.000 | 0.862 → 0.861 |

The stricter layout and weak-vote checks deliberately sacrifice some naming coverage. Feature
score/margin thresholds remain 0.75/0.15. Ordinary methods with ambiguous signatures can now be
named using distinctive code anchors rather than signature uniqueness alone.

| Configuration | Correct method names, before → after | Incorrect method names, before → after | Unevaluable method names, after |
|---|---:|---:|---:|
| Full optimization | 287 → 285 | 0 → 0 | 11 |
| Full optimization and repackaging | 283 → 278 | 0 → 0 | 18 |
| Shrinking without optimization | 1,404 → 1,481 | 2 → 0 | 28 |
| Neither shrinking nor optimization | 5,391 → 6,262 | 2 → 0 | 5 |

Only method maps with one exact, unambiguous R8 descriptor/name are evaluated. Ambiguous local
inline frames, foreign inline frames and changed prototypes stay unevaluable. Zero incorrect
method names among evaluable entries does not establish correctness of the unevaluable entries.
The two old mistakes were D8-generated lambda names; generated methods are now withheld except
bridges. Constructors and initializers are excluded from ordinary method candidates.

Two incorrect class identities remain in each fully optimized configuration: `IntProgressionIterator`
as `UIntProgressionIterator`, and the `StringsKt` facade as `StringsKt__StringsKt`. They illustrate
the unresolved effects of vertical merging and code movement. Current optimized diagnostics are
in [eval.txt](eval.txt), all current metrics in [suite.json](suite.json), and the preceding
implementation's metrics in [baseline.json](baseline.json). Before the first accuracy pass, commit
`ac84fd256a2b9015abc7c3e40eacaf0f350761af` had 201 correct and 12 incorrect optimized class names.

Reproduce with JDK 17 and the pinned toolchain:

```sh
scripts/setup-toolchain.sh --full
APKPROJECT_KEEP_WORK=1 benchmarks/library-matching/run.sh --suite
```

The script prints the retained fixture directory. Set `APKPROJECT_LIBMATCH` to that directory when
running `:app-recovery:libraryMatchingBenchmark` on another checkout to compare the same input.
A single fixture directory also works. The dedicated task preserves the ordinary test reports.
The evaluator retains all origins when R8 maps several class headers to one descriptor, rather
than overwriting the ground truth.

CI runs all four configurations and requires class precision ≥0.98, class recall ≥0.50, method
precision among evaluable entries ≥0.99, and ground truth for ≥80% of proposed method names.
These gates apply to these fixtures, not arbitrary APKs. Text and JSON reports are retained even
on a gate failure. Set `APKPROJECT_LIBMATCH_ENFORCE=1` when replaying fixtures to enforce the gates.

## Tusky 32.2 check

Input: F-Droid `com.keylesspalace.tusky_142.apk`, SHA-256
`3e8fcc49a80d4c30ab6f6037e51402c77e2694d27ec19ae5b8a93cd08b6caffa`.
The APK contains 7,966 classes and 39,616 declared methods; 91 declared library versions were dexed
for reference.

- `La/a;` has 53 methods and two fields. It receives no library identity, fixing the reported
  incorrect `TypefaceCompatBaseImpl` name. Java name hygiene may still move its package to `a_`.
- The final matcher infers 1,822 library class names, versus 2,008 before this enhancement. These
  counts have no external ground truth and cannot establish Tusky naming precision.
- The final name mapping contains 13,103 method entries across 2,999 owners, versus 11,760 across
  3,036 before. 7,887 entries belong to 1,780 owners with neither a class match nor a kept
  reference-library class name. Entries
  include inherited receiver aliases as well as declarations.
- The preceding focused recovery export extracted 112 backend endpoints and 83 models. Cross-checking, compilation,
  repairs, REA and JavaScript recovery were disabled for this focused naming/extraction run. This
  is not a whole-project compile or runtime success claim. The updated counts were checked directly
  against the same retained APK/reference DEX and through the final Tiny mapping; that check took
  13.4 seconds including profile extraction and dispatch expansion. This single timing is informational.

To inspect a new run:

```sh
./gradlew :cli:installDist
APKPROJECT_KEEP_WORK=1 modules/cli/build/install/apkproject/bin/apkproject decompile tusky-32.2.apk \
  --out runs/tusky-naming --no-cross-check --no-compile-check --no-repair --no-rea --no-js --threads 2
```

Check `recovery/library-matches.json` and `recovery/name-mapping.json` for the helper and inherited
method mappings. The final mapping counts above were also checked directly against the retained
APK/reference DEX files after the final dispatch-collision safeguards were added.

## Regression and execution checks

The final full test run has 153 passing tests with the optional R8 benchmark enabled; its dedicated
task also passes all four configurations and quality gates. The V0 benchmarks retain 7,221 independent
checks with zero mismatches and reject all 21 seeded wrong candidates.

`MethodNamingIntegrationTest` builds known source, applies mappings through JADX 1.5.6, recompiles
the recovered source, executes both projects in separate JVMs, and round-trip checks every method.
It verifies subclass dispatch through base and inheriting receivers, two recovered overloads whose
original obfuscated names differ, and names actually inferred from distinct method bodies before
JADX recompilation and independent execution. Regressions also cover small merge discriminators,
constructor arguments, contradictory favourite candidates, weak structural votes, named facades,
ambiguous/merged method fingerprints, generated names, constructors, initializers, inline-frame
ground truth, multiple DEX files, access rules, bridges and method collisions.

The full `doctor --profile profiles/android-full.json` check passes (JDK 17, D8, Z3, JADX, ECJ,
android.jar, REA and its engine; compile and solver smoke checks). CodeRabbit review was not run:
the installed CLI reports `environment_unsupported` for browser login and has no authenticated
Agentic API key in this environment.
