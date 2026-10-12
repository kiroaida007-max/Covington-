# Privacy API model (model card)

`train_api_classifier.py` trains the classifier the privacy map consults for platform methods its
own tables do not name. From a method's signature alone, it predicts one of 16 classes: `none`,
personal-data sources by kind (`source:location`, `source:device-id`, `source:phone-number`,
`source:accounts`, `source:contacts`, `source:calendar`, `source:sms`, `source:wifi`,
`source:other`) and sinks by kind (`sink:network`, `sink:log`, `sink:file`, `sink:sms`, `sink:ipc`,
`sink:other`).

Output:

- `modules/app-recovery/src/main/resources/dev/apkproject/app/privacy-api-model.bin`: the int8 weights
  (512 KiB);
- `privacy-api-model.json` next to it: training counts, cross-validated accuracy and the file's
  SHA-256;
- `modules/app-recovery/src/test/resources/privacy-api-model-golden.tsv`: the golden cases that
  `PrivacyApiModelTest` checks against the Kotlin inference (`PrivacyApiModel.kt`).

```sh
scripts/setup-toolchain.sh --full                 # android.jar (API 35) in .tools
python3 -I scripts/ml/train_api_classifier.py     # about 3 minutes; downloads the data to .tools/dl/susi
./gradlew :app-recovery:test --tests '*PrivacyApiModelTest*'
```

The training is deterministic (fixed seed). Only numpy is needed.

## Data

| Set | Labels | Use |
|---|---|---|
| The privacy map's own tables (`PrivacyMap.kt`), joined with android.jar for full signatures | 121, hand-made | training and scoring |
| SuSi's hand-labelled training set (`permissionMethodWithLabel.pscout`), public SDK classes only | 162 | training and scoring |
| FlowDroid's `SourcesAndSinks.txt`, `android.*` entries (kind only) | 129 | training and scoring |
| SuSi's categorised source and sink lists for Android 4.2 (`Ouput_Cat*_v0_9.txt`), public SDK only | 12,035, **weak** (SuSi's own SVM output) | training only |
| Public methods of android.jar (API 35) that no list names | 40,000 sampled, **weak** `none` | training only |

SuSi: Rasthofer, Arzt, Bodden, *A Machine-learning Approach for Classifying and Categorizing Android
Sources and Sinks*, NDSS 2014. SuSi and FlowDroid are by the Secure Software Engineering group
(Paderborn / Fraunhofer IEM).

Label rules applied to SuSi's and FlowDroid's source labels, to match what the privacy map means by
personal data:

- a "source" returning `boolean` or `void` (a status, an action), or a manager, factory or the declaring
  class itself (`AccountManager.get`, `SmsManager.getDefault`), is `none`;
- location sources about providers, criteria, listeners or status (`getProviders`) are `source:other`;
- SuSi's `NETWORK_INFORMATION` (any network state: connectivity type, URIs) is `source:other`; Wi-Fi
  comes from the hand-labelled sets only.

## Model

Multinomial logistic regression over hashed signature tokens: package parts, the class name and its
camel-case words, the method name, its words and its first word (the verb), the return type, its
words and kind (void, primitive, string, array, object), the parameter types and count, and pairs
(verb with class, verb with return type, class with method, verb with return kind). Tokens are hashed
with 32-bit FNV-1a into 2^15 buckets. Training uses AdaGrad with L2, 12 epochs, class weights by
inverse square-root frequency, and hand labels weighted three times weak ones.

Weights are quantized to int8 with one scale per class (`w = q * scale`); the Kotlin inference sums
int8 weights in integers, multiplies by the class scale, adds the bias and takes a softmax in double
precision, reproducing the script's probabilities to 1e-6.

## Accuracy

5-fold cross-validation on the 371 hand-labelled methods (weak labels train every fold but are
never scored), from `privacy-api-model.json`:

| | float | int8 |
|---|---:|---:|
| Accuracy (16 classes) | 0.814 | 0.814 |
| Source vs. not: precision / recall / F1 | 0.948 / 0.936 / 0.942 | 0.948 / 0.936 / 0.942 |
| Sink vs. not: precision / recall / F1 | 0.931 / 0.931 / 0.931 | 0.931 / 0.931 / 0.931 |

Telling sources and sinks from other methods works; the **kind** is much less certain where few
hand-labelled examples exist (location 3, Wi-Fi 4, network sinks 7: precision 0.33, 0.0, 0.3 in this
cross-validation). Quantization costs nothing measurable here.

**These numbers are optimistic.** The folds are random, so methods of the same class (and the same
method in a hand and a weak list) are on both sides. Codex's corrected trainer on
`codex/production-candidate` removes the 169 hand/weak overlaps and holds out whole API owners (and
their weak labels) per fold: int8 category accuracy 0.528, sources 0.859 / 0.705 / 0.775 (precision /
recall / F1), sinks 0.884 / 0.594 / 0.711 (its `scripts/ml/privacy-api-model.md`). When the branches
meet, take that trainer and artifact. The planned permission features (below) go on top of it.

## How the privacy map uses it

- **Sources only**, for platform methods (`android.*`, `java.net.*`) that no table names and that
  return a value other than `boolean`, when the model gives `location`, `device-id`, `phone-number`,
  `accounts`, `contacts`, `calendar` or `sms` with probability at least 0.95. Wi-Fi and the other
  kinds stay with the tables.
- Not sinks: the model's sink classes follow SuSi's broad notion (any method that changes state; at
  0.996 it called `InputMethodManager.updateSelection` a log), and the sink tables already cover the
  channels that carry data out. In a trial with model sinks, Tusky went from 0 to 1 false flow, and its
  analysis grew by a third and reached the re-analysis safeguard.
- Sources and flows from the model carry `model` (its confidence) in `privacy.json`, and the notes say
  so. A model-based flow is dropped where a table-named source of the same data already reaches the
  same sink call.
- Off by default since 2026-10-12 (as on Codex's branch): `APKPROJECT_PRIVACY_MODEL=1` or
  `PrivacyMap.analyze(useModel = true)` turns it on.

Effect, measured 2026-10-11 (`benchmarks/privacy/README.md`): Tusky 0 flows with or without it;
PhoneTrack unchanged (32); OwnTracks +2 (a reverse-geocoded address logged); GPSLogger +26 (its account
auth token and account data reaching network, storage and logs). Model sources seen that are wrong for
personal data: `GpsStatus.getSatellites` (satellite status taken for location).

## Permission data

Used outside the model, so Codex's corrected artifact stays untouched: `scripts/privacy/platform_permissions.py`
turns the SDK 35 `RequiresPermission` annotations (platform-35's `data/annotations.zip`, extracted to
`.tools/dl/sdk/ann`) into `modules/app-recovery/src/main/resources/dev/apkproject/app/platform-permissions.tsv`
(803 methods: anyOf/allOf, the API levels a requirement applies to, conditional flags), which the privacy
map reads per API (`PlatformPermissions`). Axplorer's maps (`.tools/dl/axplorer/`) list system-service
internals (`PhoneInterfaceManager`), not public APIs, so they are not used.

Still planned for the model, on top of Codex's trainer: `perm:` feature tokens from that table (in both
`train_api_classifier.py` and `PrivacyApiModel.kt`) and labels derived from dangerous permissions for API
35 methods the hand lists miss; then the owner-held-out CV, the golden file, and Tusky at 0.

## License of the data

SuSi's and FlowDroid's lists are LGPL-2.1. They are downloaded at training time and not stored in
this repository; the weights in `privacy-api-model.bin` are derived from them. Review the LGPL-2.1
terms before redistributing the weights outside this project.
