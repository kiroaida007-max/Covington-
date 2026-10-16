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
python3 -I scripts/ml/train_api_classifier.py     # about 10 minutes; downloads the data to .tools/dl/susi
./gradlew :app-recovery:test --tests '*PrivacyApiModelTest*'
```

`--cv-out FILE` also writes every hand-labelled method's held-out prediction (the model's mistakes on API
owners it never saw: where labelling helps next).

The training is deterministic (fixed seed). Only numpy is needed.

## Data

| Set | Labels | Use |
|---|---|---|
| The privacy map's own tables (`PrivacyMap.kt`), joined with android.jar for full signatures | 121, hand-made | training and scoring |
| SuSi's hand-labelled training set (`permissionMethodWithLabel.pscout`), public SDK classes only | 162 | training and scoring |
| FlowDroid's `SourcesAndSinks.txt`, `android.*` entries (kind only) | 129 | training and scoring |
| SuSi's categorised source and sink lists for Android 4.2 (`Ouput_Cat*_v0_9.txt`), public SDK only | 12,035, **weak** (SuSi's own SVM output) | training only |
| Public methods of android.jar (API 35) that no list names | 40,000 sampled, **weak** `none` | training only |
| The platform methods 28 F-Droid apps call (`data/app-api-labels.tsv`, below) | 1,966, hand-made | training and scoring; they take precedence |

Where a weak label and a hand label name the same method, the weak one is dropped (1,858 such methods).
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

5-fold cross-validation on the 2,311 hand-labelled methods, **holding out whole API owners** (classes):
a held-out owner's methods are in no training set, weak and negative ones included. Weak labels are
never scored. From `privacy-api-model.json` (int8; float is the same to three decimals):

| Scored on | Methods | Accuracy (16 classes) | Sources: precision / recall / F1 | Sinks: precision / recall / F1 |
|---|---:|---:|---|---|
| The older lists (tables, SuSi, FlowDroid) | 345 | 0.557 | 0.745 / 0.700 / 0.722 | 0.929 / 0.662 / 0.773 |
| The app labels | 1,966 | 0.603 | 0.100 / 0.542 / 0.169 | (none labelled) |
| All | 2,311 | 0.596 | 0.138 / 0.583 / 0.223 | 0.838 / 0.662 / 0.740 |

The app labels are mostly `none` (1,824): getters that look like sources by name. On owners it has not
seen, the model calls 690 of them (38 %) a source, 629 of those `source:other` (precision 0.03), a class
the privacy map never uses. What the map does use is below. Until 2026-10-14 the folds were random and
the numbers here were much higher (0.814 accuracy, sources 0.948 / 0.936); those were optimistic, since
methods of one class sat on both sides of a fold.

## The API corpus of real apps (2026-10-14)

`app_api_corpus.py` lists the platform methods real apps call: the method references of each APK's
DEX files, calls through an app class resolved up the app's superclasses, each resolved in android.jar
to the public class that declares it. On the 28 apps of `benchmarks/privacy/apps/apps.tsv` (location,
messaging, contacts, calendar, keyboards, launchers, password managers, a firewall, an app store, ...):
13,683 distinct methods (`data/app-api-corpus.tsv`). Every one the privacy map could ask the model about
was labelled by hand (`data/app-api-labels.tsv`, 1,966 methods, reviewed against the SDK reference and its
`RequiresPermission` annotations). The rules are in the file's header: a source returns personal data the
app did not pass in; a fix's metadata, satellite status, signal strength and physical cell ids are not
location; a method whose argument decides what it returns (`Settings.Secure.getString`) is `none`.

`evaluate_api_model.py` measures a model on those labels as the privacy map uses it (sources of the
map's kinds at 0.95, outside its tables):

| Model | Methods the map takes | Right kind | Another kind | Not a source | Labelled map sources found (of 58) |
|---|---:|---:|---:|---:|---:|
| Before (trained without the app labels: an independent test) | 41 | 23 (0.56) | 1 | 17 | 23 (0.40) |
| Retrained, held-out owners (cross-validation) | 44 | 31 (0.70) | 1 | 12 | 31 (0.53) |

The earlier model's mistakes included satellite status (`GpsStatus`, `GpsSatellite`) and a fix's metadata
taken for location, all of `java.util.Calendar` for calendar data, `CalendarView.getDate`,
`Contacts.getLookupUri` (a URI builder) and `Geocoder.getFromLocationName` (an address the user typed).
The retrained model knows these (the labels are in its training set); the held-out row is what to expect
on APIs it has not seen. There, with their owners held out, its 13 wrong ones are of the same kinds:
satellite and cell-channel getters, account handles (`AccountManager.get`, `AccountManagerFuture.getResult`),
`getFromLocationName`, `getLookupUri`, `CalendarView.getDate`. Other operating points (held out): at 0.90,
34 of 48 right and 34 found; at 0.98, 26 of 35 and 26 found. Two other class weightings were tried (hand labels balanced among
themselves; hand labels unweighted): both traded recall for precision along the same curve (26 of 34
right and 26 found; 17 of 21 and 17 found), so the weighting stayed as it was.

To extend: add apps to `benchmarks/privacy/apps/apps.tsv`, run `app_api_corpus.py` on them, label the
new methods the map may ask about (start with those `evaluate_api_model.py --out` marks as taken), and
retrain.

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
35 methods the hand lists miss; then the golden file, and Tusky at 0.

## License of the data

SuSi's and FlowDroid's lists are LGPL-2.1. They are downloaded at training time and not stored in
this repository; the weights in `privacy-api-model.bin` are derived from them. Review the LGPL-2.1
terms before redistributing the weights outside this project.
