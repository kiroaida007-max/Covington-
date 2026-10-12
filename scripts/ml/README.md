# Checked learning for recovery

The software separates three different tasks:

1. **Repair proposal ranking** learns from complete recompilation/round-trip comparisons. It
   changes which proposed edits are tried first within the recovery budget. It cannot authorize
   an edit, generate arbitrary source, or bypass the compiler and bytecode checks.
2. **Privacy API classification** predicts a platform method's source/sink category from its
   signature. It is a static hint, not observed execution or a decompilation-correctness model.
   See [privacy-api-model.md](privacy-api-model.md). It is experimental and opt-in.
3. **Local coding assistance with retrieval** uses optional official Q4_K_M Qwen coding weights
   to propose bounded method-body repairs, including compilation-error repairs. It uses checked
   examples and original DEX/IR context; every accepted edit still passes the engine's complete
   comparison gate. It does not train weights or promote the repair ranker. See
   [setup, privacy, resource limits and measurements](../../docs/local-repair-assistant.md).

The repair engine first attempts bounded V0 integer reconstruction after the known edits.
Those checked deterministic bodies need no model call. Optional weights/native artifacts are
staged only when a usable request needs inference; artifact state/preparation cost are reported.
Controlled improvements and public-source capability limits are recorded in the assistant benchmark.

Install the pinned developer training dependency with `pip install -r scripts/ml/requirements.txt`.
Python/NumPy is needed for training, not for the packaged Kotlin inference or desktop runtime.

The first real-source corpus is preserved in
[`benchmarks/repair-learning`](../../benchmarks/repair-learning/README.md): 12 project families,
945 checked trial rows and pinned origins/toolchain. The
[expanded corpus](../../benchmarks/repair-learning/expansion/README.md) adds independent families
and a pairwise research candidate. Neither snapshot supplies enough independent successful
held-out cases for production promotion; a recovery accuracy gain remains unmeasured.

## Repair feedback and training

When verified repair runs, `recovery/learning-repairs.jsonl` contains bounded feature-only local
feedback. Each label identifies the input group, source/candidate hashes and compiler profile.
Both comparison sides must cover the same known methods. Positives improved at least one checked
method without a regression. Negatives are complete
comparisons that failed that criterion. Failed tools, unknown or missing comparisons and untried
proposals do not become labels. Java text, file paths, identifiers, APK bytes and runtime logs are
not copied into feedback. These hashes provide provenance/integrity, not authenticated attestations.

Collect feedback from independently maintained, authorized source projects; verify source origins
and licenses, keep every version/fork/obfuscation variant of a project in the same `projectGroup`,
and pin each file in a local corpus manifest:

```json
{
  "schemaVersion": 1,
  "sources": [{
    "projectGroup": "one-project-family",
    "sourceUrl": "https://example.org/source-at-pinned-revision",
    "license": "SPDX-license-or-private-authorization",
    "kind": "source-project",
    "path": "feedback/project-a.jsonl",
    "sha256": "replace-with-the-actual-64-character-file-sha256"
  }]
}
```

The example is a template, not a training dataset. Use `generated-fixture` for synthetic fixtures.
At least five independent project families are required after deduplication. Broad source families
should cover Java/Kotlin, different D8/R8/compiler settings, UI frameworks, serializers and
control-flow patterns; the current repair rules still cover only their explicit proposal types.
API-signature lists such as SuSi are not code-recovery success labels.
The loader also caps the combined corpus at 64 MiB/100,000 rows including duplicates, checks
the bytes actually parsed against their pinned hashes, and rejects boolean/non-finite label data.

```sh
python3 -I scripts/ml/train_repair_ranker.py --manifest corpus.json --out models/run-001
apkproject decompile app.apk --out recovered --repair-model models/run-001/repair-priority-model.json
```

The desktop Recover dialog also accepts a local model file. Invalid or unpromoted models fall
back to the deterministic proposal order. A valid model is staged immutably and its content hash
enters the verification checkpoint identity, so replacing a file at the same path invalidates
cached model-dependent verification.

## Evaluation and promotion

- Pin manifest inputs, reject changed/conflicting data, deduplicate repeated trials and remove
  candidate token fingerprints shared by different projects before partitioning. Fingerprints
  ignore tool comments/spacing while preserving literals and operator boundaries; the actual
  source hashes remain as provenance. Input DEX groups stay distinct when identical source has
  different comparison outcomes.
- Declare a `projectPartitions` map (`family`: `train`, `validation` or `test`) before collecting
  outcomes. The expanded source lock and frozen manifest retain it. Adding a family cannot move
  a previous holdout into training. Legacy manifests without the map use the original seeded
  split. Partition assignments enter dataset identity; changed collection locks/assignments are
  rejected during freezing.
- Learn positive-minus-negative feature differences within the same mixed-outcome query using
  a regularized pairwise logistic objective. Families have equal total weight, units share each
  family's weight, and compiler variants share their unit's weight. Single-outcome queries do
  not teach ordering. At most 100,000 pairs are materialized, with deterministic sampling for
  large queries. The linear model still uses 14 features; packaged inference costs are unchanged.
- Select epochs on validation family/unit reciprocal rank only, then evaluate the fixed model
  and predefined baselines on test. Without an informative validation query, use a predefined
  120 epochs and disable promotion. Never label model guesses as ground truth.
- Compare with deterministic order, a training-only smoothed rule prior and the previous
  pointwise logistic learner. Promotion requires at least ten independent mixed-outcome test
  units and five validation units, across at least two informative families in each holdout.
  Query, unit and family mean reciprocal rank must each exceed every baseline by more than
  0.01, with no hit-at-five regression. Older models without these fields fail closed.
- Report hit-at-one/three/five and first-success rank **among fully labelled candidates**.
  Compiler-failed, incomplete and untried candidates are excluded; these ranks are not total
  compiler attempts or elapsed-time savings. Unit/family averages prevent a large easy compiler
  sweep from dominating reported quality. Synthetic corpora cannot be promoted.
- Keep `repair-priority-model-card.json`: sources/licenses/hashes, partitions, counts, selected
  epoch, evaluation and limitations. The score is a ranking, not a calibrated probability.
- Then measure completed repairs, accepted-method regressions, runtime and memory on a further
  frozen end-to-end corpus before a release. Offline ranking alone is not product accuracy.

No promoted repair model or percentage gain is bundled yet. The checked corpus is a first measured
collection, with very few successful repairs. Broader independent success cases and end-to-end
improvement remain necessary. Attempted-proposal feedback has selection bias and excludes untried candidates;
an independent exhaustive benchmark is needed to measure recall. Ordinary runtime UI agreement
is not used as a semantic or privacy-source training label.
