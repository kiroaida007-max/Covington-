# Real-source repair-learning corpus

This directory preserves the first collection and pointwise-model snapshot. Current development
uses the [expanded corpus and pairwise learner](expansion/README.md); the original held-out
families stay held out. The old snapshot's byte hashes and labels have not been rewritten.

This laboratory trains **repair proposal ordering**, not a model that generates arbitrary Java.
Every positive label comes from a complete recompilation/round-trip comparison: more methods
match the original DEX and none get worse. Round-trip matching is not formal proof of every
execution path. Model scores cannot accept a source edit; production verification remains mandatory.

## What was collected

`source-lock.json` pins 13 published artifacts from 12 project families, their source/binary/POM
SHA-256 values, license evidence and the analysis toolchain. The libraries are JOML, libGDX
(core and Android backend kept in one family), Apache Commons Math, Guava, Gson, JTS, EJML,
RxJava, jsoup, Jafama, GPUImage and CameraView. Dependencies are also pinned. These are source
project subsets, not a representative population of Android applications.

The first ten libraries were built in two declared configurations: javac debug information with
D8 release, and no javac debug information with D8 debug. The three Android artifacts used no
javac debug information with D8 release. Each artifact/configuration selected up to 24 original
production classes: half arithmetic-rich, half deterministically sampled by path hash. Original
source was compiled without editing it; source files that did not compile are recorded in local
diagnostics. No original-source fallback or error stubs enter candidate compilation.

| Observation | Count |
|---|---:|
| Artifact/configuration runs | 23 |
| Decompiled files in those runs | 438 |
| Baseline method comparisons | 8,377 |
| Baseline round-trip matches | 7,405 |
| Proposed edits actually attempted | 1,274 |
| Fully checked trial labels | 945 |
| Successful trial labels | 8 |
| Unsuccessful complete trial labels | 937 |
| Compiler-failed attempts withheld from training | 302 |
| Incomplete comparisons withheld from training | 27 |

The eight positive trials occur in four original source units from jsoup and GPUImage; compiler
variants are correlated observations. The baseline method denominator excludes files that could
not be compiled and includes repeated builds. It is **not** an end-to-end decompilation accuracy.
The collection tests only the existing bounded repair proposals; untried edits are not labelled.

`corpus/` contains feature-only feedback, actual source/candidate hashes, input DEX group hashes,
opaque source-unit IDs and token fingerprints. It contains no Java text, APKs, runtime logs or
source paths. Token fingerprints remove generator comments/spacing while retaining literals and
operator boundaries. This avoids treating JADX's local-path comments as new code. Actual source
hashes remain separate. Metadata establishes local integrity, not authenticated attestations.
Source archives, compiled inputs, trial classes and diagnostics stay in the local collection
directory and are not redistributed here. Preserve the pinned projects' license/attribution
records when extending or distributing derived datasets/models.

## Training result

`manifest.json` freezes each feedback file by SHA-256. Training holds out whole project families:
eight training families, Apache Commons/JTS for validation, CameraView/Gson for test. Versions,
forks and compiler variants must remain in the same family. Cross-family candidate duplicates
are excluded before splitting. The archived model uses 14 numeric features and a seeded pointwise logistic learner;
selection uses validation only, and the final test is evaluated once.

The candidate in `model/` is trained but **not eligible for production**. The validation and test
partitions contain no mixed-success ranking queries. Their zero metric fields are placeholders
with zero support, not measured zero-percent accuracy. Ranking improvement and recovery gain are
unmeasured. The loader refuses this model and keeps deterministic repair ordering.

More rows alone cannot correct this imbalance. Next collections need independently maintained
projects with real, compilable decompiler failures that the repair system can fix. Prioritize
diverse successful numeric-conversion and cast repairs, compile-error recovery, other compiler
settings and Java/Kotlin workloads. Keep further frozen projects outside all model selection.
Do not relabel unknowns as failures, treat model guesses as truth, or count synthetic faults as
production evidence. Whole-project test suites, accepted-method regressions and resource costs
must be checked before releasing any promoted model.

## Reproduce and extend

Install JDK 17 and the pinned toolchain, then:

```sh
pip install -r scripts/ml/requirements.txt
./gradlew :benchmarks:installDist
python3 -I scripts/ml/collect_repair_corpus.py \
  --lock benchmarks/repair-learning/source-lock.json --out runs/repair-baseline \
  --jdk "$JAVA_HOME" --tools "$APKPROJECT_TOOLS" --units 24 --trials 12 \
  --experiment baseline-release
```

Repeat into new directories with `--experiment compact-debug` and `--experiment android-release`.
The collector requires a POSIX host for owned process-group cleanup. Each compiler/decompiler
command and the full laboratory run are bounded; a timed-out job kills its owned process group.
It downloads only pinned HTTPS Maven/Google artifacts and runs javac with annotation processing
disabled. It does not execute downloaded application source or its build scripts.

```sh
python3 -I scripts/ml/freeze_repair_corpus.py \
  --runs runs/repair-baseline runs/repair-compact runs/repair-android \
  --lock benchmarks/repair-learning/source-lock.json --out runs/frozen-repairs --jdk "$JAVA_HOME"
python3 -I scripts/ml/train_repair_ranker.py \
  --manifest runs/frozen-repairs/manifest.json --out runs/repair-model
```

Train the committed snapshot directly with `--manifest benchmarks/repair-learning/manifest.json`.
The current trainer defaults to pairwise learning; `--objective pointwise` keeps the old objective
for comparisons. The archived model/card were reproduced byte-for-byte with the previous trainer;
current training emits additional evaluation and provenance fields. Regenerating captures in
another directory changes actual hashes when JADX embeds paths;
token fingerprints, features and checked outcomes can be compared separately. A latest-collector
GPUImage rerun reproduced the same original DEX, 42 attempted proposals and 30 checked outcomes.
Training/inference are portable; Python/NumPy is needed only for the developer training tool.
