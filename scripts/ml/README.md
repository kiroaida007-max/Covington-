# Checked learning for recovery

The software separates two different learning tasks:

1. **Repair proposal ranking** learns from complete recompilation/round-trip comparisons. It
   changes which proposed edits are tried first within the recovery budget. It cannot authorize
   an edit, generate arbitrary source, or bypass the compiler and bytecode checks.
2. **Privacy API classification** predicts a platform method's source/sink category from its
   signature. It is a static hint, not observed execution or a decompilation-correctness model.
   See [privacy-api-model.md](privacy-api-model.md). It is experimental and opt-in.

Install the pinned developer training dependency with `pip install -r scripts/ml/requirements.txt`.
Python/NumPy is needed for training, not for the packaged Kotlin inference or desktop runtime.

## Repair feedback and training

When verified repair runs, `recovery/learning-repairs.jsonl` contains bounded feature-only local
feedback. Each label identifies the input group, source/candidate hashes and compiler profile.
Positives improved at least one checked method without a regression. Negatives are complete
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
  exact candidate hashes shared by different projects before partitioning.
- Split whole project families into training, validation and test; select weights/epoch using
  validation only and evaluate test once. Never label model guesses as ground truth.
- Compare the ranker's mean reciprocal rank and hit-at-five against observed deterministic order.
  The model requires at least 10 held-out mixed-outcome queries, improvement in reciprocal rank,
  no hit-at-five regression and validation support. Synthetic corpora cannot be promoted.
- Keep `repair-priority-model-card.json`: sources/licenses/hashes, partitions, counts, selected
  epoch, evaluation and limitations. The score is a ranking, not a calibrated probability.
- Then measure completed repairs, accepted-method regressions, runtime and memory on a further
  frozen end-to-end corpus before a release. Offline ranking alone is not product accuracy.

No promoted repair model or percentage gain is bundled yet. The trainer and inference pipeline
are working infrastructure; collecting a broad verified corpus and measuring its improvement
remain necessary. Attempted-proposal feedback has selection bias and excludes untried candidates;
an independent exhaustive benchmark is needed to measure recall. Ordinary runtime UI agreement
is not used as a semantic or privacy-source training label.
