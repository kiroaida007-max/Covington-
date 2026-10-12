# Expanded repair-learning experiment

This snapshot adds eight independently maintained source families: DOM4J, JDOM, Kryo,
JavaEWAH, Jackson Core, ASM, SnakeYAML and Fastjson. It also samples more jsoup, GPUImage
and Guava units in their existing training families. The original 945-row snapshot remains
intact; this manifest references its pinned files and adds 189 checked rows. The combined
snapshot has **1,134 checked trials: 19 positive and 1,115 negative**.

`source-lock.json` pins 21 published library artifacts and their sources/binaries/POMs across 20 declared families, dependencies,
tool hashes and licensing evidence. Nineteen families supplied labelled rows; ASM supplied
no complete labels. DOM4J's custom notice is in `licenses/dom4j.txt`; `JDOM` denotes the custom
license reproduced in `licenses/jdom.txt`, not an SPDX identifier. Source archives and analysis
binaries are not bundled in the product or redistributed with this feature-only dataset.

## Collection and holdouts

Before collection, the eight new family names were ordered by SHA-256 of `20261012:family`.
The first two were assigned to validation (JDOM/Fastjson), the next two to test (ASM/JavaEWAH),
and the remaining four to training. All original family assignments remain fixed. The lock and
manifest store the same explicit `projectPartitions`; training verifies the actual primary
lock's hash, assignments, origins and license metadata. New outcomes cannot reshuffle holdouts.

The declared experiment uses unchanged original Java, no javac debug information, D8 release,
up to 64 production classes and 24 proposed edits per file. Selection allocates thirds to an
approximate numeric score, cast score and deterministic path-hash sample, without consulting
repair outcomes. The same 14 bounded inference features are retained. Only decompiled source
and published dependency binaries reach candidate compilation; no original-source fallback or
error stubs are used. Public library code/build scripts are not executed.

The 18 local capture attempts include diagnostic reruns after collector fixes. Twelve completed
and six failed. The completed captures recorded:

| Additional observations | Count |
|---|---:|
| Included decompiled files, including correlated reruns | 641 |
| Baseline compiled files | 408 |
| Baseline method comparisons | 5,568 |
| Baseline round-trip matches | 4,983 |
| Actually attempted edits | 650 |
| Complete checked labels | 189 |
| Successful labels | 11 |
| Unsuccessful complete labels | 178 |
| Compiler-failed attempts withheld | 437 |
| Incomplete comparisons withheld | 24 |
| Oversized source files excluded in the bounded rerun | 1 |

The six failed captures are retained in `collection-runs.json`, including tool/collector hashes
and configurations. They contribute no invented labels. A zero-file JDOM baseline was rerun
with 16 compiler isolation passes. Pinned JADX 1.5.6 exit code 3 means saving completed with
method errors; the collector now salvages usable source while rejecting process/load failures.
The first checked Fastjson capture stopped on a 1.8 MiB generated Java file. Its bounded rerun
excluded that file explicitly and kept the 1 MiB per-file, 64 MiB corpus and 256-file limits.
Compilation must make diagnostic progress; preparation has a ten-minute command budget and
the checked laboratory has a thirty-minute process-group budget. These totals describe
compiling subsets and repeated builds, not application recovery accuracy.

## Learning and measured limits

The learner now optimizes positive-minus-negative preferences **within the same failure query**.
Families have equal weight, units share a family's weight, and compiler variants share a unit's
weight. Single-outcome queries cannot teach ordering. Deterministic bounded sampling caps
training at 100,000 pairs and 128 pairs per query. L2 regularization and validation-only epoch
selection keep the model small. Inference remains a 14-term linear score with zero pairwise bias.

| Effective partition support | Training | Validation | Test |
|---|---:|---:|---:|
| Families with labelled rows | 12 | 4 | 3 |
| Complete rows | 725 | 379 | 30 |
| Positive rows | 18 | 1 | 0 |
| Original units containing a positive row | 10 | 1 | 0 |
| Mixed-outcome ranking queries | 5 | 1 | 0 |
| Independent mixed-outcome units | 3 | 1 | 0 |

Training supplies 27 preference pairs from three units in two families. Training MRR rises from
0.80 to 1.00; this is fitted-data performance, **not an accuracy claim**. The single informative
JDOM validation success stays third among checked candidates (MRR 1/3). Test ranking metrics
are unsupported, marked `supported: false`; their numeric zeros are placeholders. This candidate
has **no demonstrated independent ranking or end-to-end recovery gain** and `eligible: false`.
Production rejects it and keeps deterministic verified repair ordering.

The model card includes deterministic order, a training-only smoothed rule prior and the previous
pointwise logistic learner as predefined baselines. It reports query/unit/family MRR, hit-at-one,
three and five, and first-success rank among complete labels. Failed, incomplete and untried
proposals are excluded from those ranks; they cannot establish total compilation cost or time
savings. Promotion requires five independent validation units and ten test units across at
least two informative families per holdout, plus query/unit/family MRR gains greater than 0.01
over every baseline and no hit-at-five regression. Synthetic data cannot qualify. Every proposed
source edit still needs complete compiler/bytecode verification.

More failures alone will not solve the remaining gap. Current successes cover cast removal and
numeric widening; broadly successful compile-error repair, Kotlin/R8 workloads and whole-project
tests remain necessary. Avoid complex models fitted to these few units. Keep a further frozen
end-to-end corpus outside selection before measuring accepted regressions, recovery success,
memory and elapsed time. Local hashes establish integrity, not signed provenance or formal
equivalence of every execution path.

## Reproduce

With the pinned JDK/toolchain and NumPy dependency installed:

```sh
./gradlew :benchmarks:installDist
python3 -I scripts/ml/collect_repair_corpus.py \
  --lock benchmarks/repair-learning/expansion/source-lock.json --out runs/expanded-release \
  --jdk "$JAVA_HOME" --tools "$APKPROJECT_TOOLS" --units 64 --trials 24 \
  --sampling repair-diverse --compiler-passes 16 --experiment expanded-release
python3 -I scripts/ml/freeze_repair_corpus.py \
  --runs runs/expanded-release --lock benchmarks/repair-learning/expansion/source-lock.json \
  --out runs/frozen-expanded --jdk "$JAVA_HOME"
python3 -I scripts/ml/train_repair_ranker.py \
  --manifest benchmarks/repair-learning/expansion/manifest.json --out runs/pairwise-model
```

The committed manifest includes the archived corpus; a newly frozen run alone does not.
Current collection includes the fixes and will not recreate the intentionally preserved failed
historical attempts. Source path comments may alter raw hashes across directories; token
fingerprints remain available for comparison. Retraining the committed combined manifest
reproduced both model and model-card bytes on this host. The card pins the trainer hash and
NumPy version. Training is offline and private APK/source/runtime data is not uploaded.
