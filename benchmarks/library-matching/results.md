# Library naming validation

Measured 2026-10-09. Baseline: `ac84fd256a2b9015abc7c3e40eacaf0f350761af`.
Names inferred by this matcher remain INFERRED; they are not verified artifact identities.

## R8 ground truth

The same generated fixture and reference DEX files were used for both runs. R8 8.7.18 shrinks and
renames an app using Retrofit 2.11.0, OkHttp 4.12.0, Moshi 1.15.1, Okio 3.9.0 and Kotlin 2.0.21.
Its mapping contains 339 unambiguous renamed library classes present in the APK.

| Measurement | Baseline | Updated |
|---|---:|---:|
| Correct class names | 201 | 201 |
| Incorrect class names | 12 | 7 |
| Precision | 0.944 | 0.966 |
| Recall | 0.593 | 0.593 |
| Correct / total structural matches | 34 / 36 | 33 / 33 |

The seven remaining false positives are listed in [eval.txt](eval.txt). This development fixture
is not a held-out corpus; its precision is not a guarantee for other APKs. No score threshold was
increased to obtain these results.

Reproduce with JDK 17 and the pinned toolchain:

```sh
scripts/setup-toolchain.sh --full
APKPROJECT_KEEP_WORK=1 benchmarks/library-matching/run.sh
```

The script prints the retained fixture directory. Set `APKPROJECT_LIBMATCH` to that directory when
running `:app-recovery:test --tests '*LibraryMatchingBenchmark*'` on another checkout to compare
the same input. The evaluator retains all origins when R8 maps several class headers to one
descriptor, rather than overwriting the ground truth.

## Tusky 32.2 check

Input: F-Droid `com.keylesspalace.tusky_142.apk`, SHA-256
`3e8fcc49a80d4c30ab6f6037e51402c77e2694d27ec19ae5b8a93cd08b6caffa`.
The APK contains 7,966 classes and 39,616 declared methods; 91 declared library versions were dexed
for reference.

- `La/a;` has 53 methods and two fields. It receives no library identity, fixing the reported
  incorrect `TypefaceCompatBaseImpl` name. Java name hygiene may still move its package to `a_`.
- The final matcher infers 2,008 library class names. This count has no external ground truth.
- The final name mapping contains 11,760 method entries across 3,036 owners; 6,829 entries belong
  to 1,747 owners with neither a class match nor a kept reference-library class name. Entries
  include inherited receiver aliases as well as declarations.
- The recovery run extracts 112 backend endpoints and 83 models. Cross-checking, compilation,
  repairs, REA and JavaScript recovery were disabled for this focused naming/extraction run. This
  is not a whole-project compile or runtime success claim.

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

The full test run has 140 passing tests and one skipped optional R8 benchmark; that benchmark
passes separately with `APKPROJECT_LIBMATCH` set. The V0 benchmarks retain 7,221 independent
checks with zero mismatches and reject all 21 seeded wrong candidates.

`MethodNamingIntegrationTest` builds known source, applies mappings through JADX 1.5.6, recompiles
the recovered source, executes both projects in separate JVMs, and round-trip checks every method.
It verifies subclass dispatch through base and inheriting receivers, and two recovered overloads
whose original obfuscated names differ. Unit regressions cover merged helpers, synthetic and
superclass mismatches, multiple DEX files, interfaces, bridges, private/static/package access,
conflicting names and Java signature collisions.

The full `doctor --profile profiles/android-full.json` check passes (JDK 17, D8, Z3, JADX, ECJ,
android.jar, REA and its engine; compile and solver smoke checks). CodeRabbit review was not run:
the installed CLI reports `environment_unsupported` for browser login and has no authenticated
Agentic API key in this environment.
