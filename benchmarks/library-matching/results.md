# Library naming validation

Measured 2026-10-09–10. Current enhancement baseline: `91f9630b45c86ffeff8f5eb30915930ca1f8533a`.
Names inferred by this matcher remain INFERRED; they are not verified artifact identities.

## R8 ground truth

R8 8.7.18 renames an app using Retrofit 2.11.0, OkHttp 4.12.0, Moshi 1.15.1, Okio 3.9.0 and
Kotlin 2.0.21. A second workload uses JSON collections, compression, hashing and request construction
without Retrofit. Each comparison uses the same APK/reference DEX and R8 map. Four configurations
per workload exercise shrinking, optimization and repackaging separately. These are development
fixtures, not a held-out APK corpus. The codec workload initially recovered fewer than 50% of names;
the reference-array check brought it above the unchanged coverage gate.

| Configuration | Correct classes, before → after | Incorrect classes, before → after | Precision, before → after | Recall, before → after |
|---|---:|---:|---:|---:|
| API: full optimization | 191 → 192 | 2 → 0 | 0.990 → 1.000 | 0.563 → 0.566 |
| API: optimization and repackaging | 188 → 189 | 2 → 0 | 0.989 → 1.000 | 0.573 → 0.576 |
| API: shrinking without optimization | 548 → 547 | 0 → 0 | 1.000 → 1.000 | 0.878 → 0.877 |
| API: neither shrinking nor optimization | 1,414 → 1,414 | 0 → 0 | 1.000 → 1.000 | 0.861 → 0.861 |
| Codec: full optimization | 41 → 42 | 10 → 0 | 0.804 → 1.000 | 0.488 → 0.500 |
| Codec: optimization and repackaging | 40 → 41 | 10 → 0 | 0.800 → 1.000 | 0.494 → 0.506 |
| Codec: shrinking without optimization | 179 → 178 | 0 → 0 | 1.000 → 1.000 | 0.895 → 0.890 |
| Codec: neither shrinking nor optimization | 1,414 → 1,414 | 0 → 0 | 1.000 → 1.000 | 0.861 → 0.861 |

Incompatible final return types now veto misleading matches, and empty static facades prevent
inherited code from falsely identifying an ancestor. This also blocks downstream erroneous
signature votes in the codec workload. Reference-array storage excludes primitive-array rivals
with similar Collection APIs. These rules do not contain Kotlin class names. The facade guard
withholds one additional correct class in each shrink-only configuration. Feature score/margin
thresholds remain 0.75/0.15. Earlier method-body fingerprinting remains enabled.

| Configuration | Correct method names, before → after | Incorrect method names, before → after | Unevaluable method names, after |
|---|---:|---:|---:|
| API: full optimization | 285 → 285 | 0 → 0 | 9 |
| API: optimization and repackaging | 278 → 278 | 0 → 0 | 16 |
| API: shrinking without optimization | 1,481 → 1,480 | 0 → 0 | 28 |
| API: neither shrinking nor optimization | 6,262 → 6,262 | 0 → 0 | 5 |
| Codec: full optimization | 43 → 43 | 0 → 0 | 1 |
| Codec: optimization and repackaging | 43 → 43 | 0 → 0 | 0 |
| Codec: shrinking without optimization | 412 → 411 | 0 → 0 | 3 |
| Codec: neither shrinking nor optimization | 6,262 → 6,262 | 0 → 0 | 5 |

Only method maps with one exact, unambiguous R8 descriptor/name are evaluated. Ambiguous local
inline frames, foreign inline frames and changed prototypes stay unevaluable. Zero incorrect
method names among evaluable entries does not establish correctness of the unevaluable entries.
Generated methods are withheld except bridges. Constructors and initializers are excluded from
ordinary method candidates. The preceding enhancement recovered 871 additional correct method
names in the unshrunk API fixture relative to `d4b98a0`; that improvement remains.

The two remaining API class mistakes (`IntProgressionIterator` as `UIntProgressionIterator`, and
`StringsKt` as `StringsKt__StringsKt`) are now withheld. This does not recover their original names.
Current optimized diagnostics are in [eval.txt](eval.txt), all eight current metrics in
[suite.json](suite.json), the four preceding API metrics in [previous.json](previous.json), and
the preceding codec diagnostics in [codec-baseline.txt](codec-baseline.txt). The older `d4b98a0`
metrics remain in [baseline.json](baseline.json). Before the first accuracy pass,
`ac84fd256a2b9015abc7c3e40eacaf0f350761af` had 201 correct and 12 incorrect optimized API class names.

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

CI runs all eight configurations and requires class precision ≥0.98, class recall ≥0.50, method
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
  against the same retained APK/reference DEX and through the final Tiny mapping; the updated
  matcher check took 14.6 seconds including profile extraction and dispatch expansion. This
  single timing is informational; it is not a controlled speed comparison.

The earlier complete Java export was subsequently compiled with ECJ 3.45.0, Java 8 settings,
Android platform 35, and its verified emoji2-views artifact. It failed with **13,352 errors in
2,614 of 7,471 files**, including 105 of 226 app files. Common categories include undefined
constructors/members, checked exceptions, abstract-method implementations and malformed code.
Tusky targets API 36, so this platform-35 diagnostic run cannot establish API-36 build readiness.

That check exposed fields hiding imported types, such as an integer `R` hiding a resource class.
Name hygiene now protects imports, external reference types and package roots, and avoids
collisions between allocated names. The end-to-end execution regression verifies this fix on a
small known program. A fresh full Tusky export did not complete, so no before/after Tusky compile
improvement or whole-app runtime success is claimed.

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

The final full test run has 163 passing tests with the optional R8 benchmark enabled; its dedicated
task also passes all eight configurations and quality gates. The V0 benchmarks retain 7,221 independent
checks with zero mismatches and reject all 21 seeded wrong candidates.

`MethodNamingIntegrationTest` builds known source, applies mappings through JADX 1.5.6, recompiles
the recovered source, executes both projects in separate JVMs, and round-trip checks every method.
It verifies subclass dispatch through base and inheriting receivers, two recovered overloads whose
original obfuscated names differ, and names actually inferred from distinct method bodies before
JADX recompilation and independent execution. Regressions also cover small merge discriminators,
imported-resource field collisions, return bounds across branches and exception handlers,
unknown/nullable results, static-facade ambiguity and reference-array storage,
constructor arguments, contradictory favourite candidates, weak structural votes, named facades,
ambiguous/merged method fingerprints, generated names, constructors, initializers, inline-frame
ground truth, multiple DEX files, access rules, bridges and method collisions.

The full `doctor --profile profiles/android-full.json` check passes (JDK 17, D8, Z3, JADX, ECJ,
android.jar, REA and its engine; compile and solver smoke checks). CodeRabbit review was not run:
the installed CLI reports `environment_unsupported` for browser login and has no authenticated
Agentic API key in this environment.
