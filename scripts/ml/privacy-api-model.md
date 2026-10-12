# Experimental privacy API classifier

This 512 KiB int8 model predicts one of 16 source/sink/none categories from a platform
method signature. It supplements the static privacy tables; it does not reconstruct Java,
observe runtime calls or establish decompilation accuracy. The default analysis uses the
tables. Enable experimental model hints with `apkproject privacy ... --privacy-model`,
the same flag on `decompile`, or the desktop recovery option.

## Reproducible inputs and training

`train_api_classifier.py` uses the engine's own API tables, SuSi hand-labelled methods,
FlowDroid's hand-curated Android methods, SuSi Android 4.2 SVM predictions as weak labels,
and unlisted public Android API 35 methods as weak negatives. Weak negatives are not proof
that an API is harmless. The current training corpus contains 371 distinct hand labels,
11,866 weak SuSi labels after overlap removal, and 40,000 weak negatives.

Input URLs are in the trainer; exact downloaded file SHA-256 values are recorded in the
bundled metadata. Use pinned cached files with `--data` to reproduce those inputs.
The public lists originate from the secure-software-engineering SuSi and FlowDroid
repositories and are documented as LGPL-2.1. Source lists are not bundled; model weights
are derived from them. Preserve their attribution and assess weight-distribution terms
before a release.

Features are hashed signature tokens (32,768 buckets). The learner is multinomial
logistic regression with AdaGrad, class balancing, stronger weight on hand labels and
per-class int8 quantization. The model, metadata and Kotlin golden cases are regenerated
together. Python/NumPy is used for training; Kotlin performs inference without Python.

```sh
python3 -I scripts/ml/train_api_classifier.py --data PINNED_LABEL_DIRECTORY \
  --android-jar .tools/android-35/android.jar
```

## Corrected evaluation

The earlier random method split retained hand-labelled signatures in the weak training
set. The new trainer removes all 169 hand/weak overlaps (153 occur in the SuSi/FlowDroid
hand-label union; additional overlaps involve the engine's tables), keeps each API owner
in one fold, excludes every held-out owner's weak/negative methods, and derives class
weights only from each fold's training data.

Five-fold evaluation on 371 hand-labelled methods, with entire API owners held out:

| Metric | Float | Int8 |
|---|---:|---:|
| 16-category accuracy | 0.526 | 0.528 |
| Source precision / recall / F1 | 0.859 / 0.705 / 0.775 | 0.859 / 0.705 / 0.775 |
| Sink precision / recall / F1 | 0.884 / 0.594 / 0.711 | 0.884 / 0.594 / 0.711 |

This is a stricter evaluation than the old method-level figures, not an independently
measured end-to-end privacy-flow accuracy. Individual categories have very small support
(e.g. three location labels) and weak generalization. A softmax score of 0.95 is not
95% measured accuracy. Calibration and an independently labelled modern API/app corpus
remain necessary before enabling model hints by default.

## Runtime integration and robustness

Only eligible personal-data **source** guesses are used by the static engine, for platform
methods the tables do not identify, at a score of at least 0.95. Model sink guesses remain
disabled because known high-score false positives created spurious flows. Model source
and flow rows retain model provenance; the desktop explains that the score is not accuracy.
Known table flows remain separate from duplicate model hints.

Loading is bounded to 600 KiB, checks the companion SHA-256 and exact dimensions/length,
validates labels and finite scales/biases, and abstains on missing/corrupt resources.
Malformed descriptors also abstain. Hash checks provide local integrity, not authenticated
model provenance. Golden tests verify Python/Kotlin agreement; they do not verify real
world generalization. New real-app effects must be measured with this exact retrained model.
