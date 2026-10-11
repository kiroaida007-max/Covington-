# Privacy map: precision on a real app

Measured 2026-10-11 on Tusky 32.2 (F-Droid `com.keylesspalace.tusky_142.apk`, SHA-256
`3e8fcc49…caffa`, R8 full mode), with `apkproject privacy` (`PrivacyMap`, module `:app-recovery`).

## Ground truth

Tusky is a Mastodon client. It does not request the location permission, has no analytics or
advertising SDK, and its source contains no code that reads the location, device identifiers,
contacts or the clipboard on its own. Two personal-data APIs are referenced, both inside libraries:

- `LocationManager.getLastKnownLocation` in AppCompat's night-mode helper (TwilightManager, renamed
  `k.w`), which computes sunrise and sunset times;
- `ClipboardManager.getPrimaryClip` in AppCompat's and Compose's text fields, when the user pastes.

So the correct report has those two sources and **no flows**. A flow found here is a false positive.

## How each rule moved the result

| Version | Flows | Shared fields tainted | Time | What it fixed |
|---|---:|---:|---:|---|
| Earlier session (context-insensitive; never pushed) | 238 | – | – | reported in that session; taint spread through identity helpers and container fields |
| Summaries, 32 virtual targets, no bounds | – | – | > 240 s | did not finish: `compare(Object, Object)` summaries reached 807 field writes and 142 sink edges |
| + bounded summaries, 8 virtual targets, no `int`/`boolean` shared fields | 907 (129 high) | 5,212 | 37 s | finishes; the remaining flows spread through shared fields |
| + data read back from a shared field is not stored into another; no `float`; a `TextView` reading the clipboard is a paste | 88 | 6 | 10 s | AppCompat's EditText paste and the field-to-field cascade |
| + no `long` shared fields; clipboard data does not survive a shared field | **0** | 0 | 10 s | AppCompat's sunrise times (long) reached Compose's packed longs; Compose's paste kept in text-field state |
| + summaries joined with their previous value | **0** | 0 | 10–12 s | the iteration converges (it had hit a 60-visit safeguard on four tiny methods) |
| + a value read from a field remembers its holder; return values keep their object identity; sink edges per parameter and sink; shared fields only for flat data classes | **0** | 0 | 16–17 s | recall: a builder appending to its own `StringBuilder` field (new test case); summaries at their size bound fell from 2,819 to 1,167 of 31,764 |

The shared-field (field-based) model is where precision is won or lost. One tainted `long` in
AppCompat's `TwilightState` was enough to reach Compose's layout code. Compose packs offsets,
sizes and constraints into longs, so the taint then reached every screen. The rules now:

- shared across instances only for value types: strings, `double`, `Location`, `Account` and flat
  data classes (concrete, every instance field a value: `LatLng`, `User(id, email)`); never
  `Object`, collections, interfaces, arrays, classes that hold other objects (renamed library
  internals such as Compose's nodes), or the state types `boolean`, `int`, `float` and `long`;
- one hop: data read back from a shared field can reach a sink but is not stored into another
  shared field ("saved in a callback, sent on a click" still works);
- every object also keeps its own fields (one level, per instance), which do not have these limits.

## Recall

`PrivacyMapTest` compiles an app against the real `android.jar` with nine leaks and six traps:

- **leaks:**
  - location through a helper into a Retrofit query;
  - location added to a builder that keeps it in its own `StringBuilder` field, then posted;
  - location kept by `onLocationChanged` in a field and written to an HTTP request body on a click;
  - ANDROID_ID logged;
  - ANDROID_ID kept in a static field and saved to SharedPreferences elsewhere;
  - the advertising ID handed to Firebase Analytics;
  - contact names into an OkHttp form body;
  - the IMEI in a broadcast;
  - the clipboard read in the background and posted.
- **traps:**
  - the same identity helper with a clean value;
  - the same builder with clean values;
  - two instances of a class with an `Object` field;
  - a continuation-like state object with data in `L$0`;
  - location used only to choose a theme;
  - a text field pasting.

The test requires exactly the nine flows. The IMEI flow is marked "cannot run" because that
manifest does not request `READ_PHONE_STATE`.

## Reproduce

```sh
scripts/setup-toolchain.sh --full && ./gradlew :cli:installDist
curl -fLo .tools/dl/tusky/tusky-142.apk https://f-droid.org/repo/com.keylesspalace.tusky_142.apk
modules/cli/build/install/apkproject/bin/apkproject privacy .tools/dl/tusky/tusky-142.apk --out runs/tusky-privacy
./gradlew :app-recovery:test --tests '*PrivacyMapTest*'   # includes the Tusky check when the APK is there
```

Diagnostics (environment variables):

- `APKPROJECT_PRIVACY_DEBUG=1` prints progress, the most re-analysed methods, the largest
  summaries and the types of the tainted shared fields;
- `APKPROJECT_PRIVACY_WATCH=<method substring>` prints that method's summary on each analysis;
- `APKPROJECT_PRIVACY_BUDGET_MS` sets the time budget (default 240,000).

## Known limits

- Not followed:
  - callbacks the platform calls later (listeners, LiveData, `Handler.post`, coroutine resumption);
  - reflection and native code;
  - data a later run reads back from storage.
- Library internals are modelled, not analysed, when their package name survives (androidx, kotlin,
  okhttp3, ...). Renamed library code is analysed as app code.
- A source inside a library is attributed by package; renamed libraries show as `app`.
- Summaries are bounded. Sink edges beyond 48 and field writes beyond 128 per method are dropped
  (recall can only go down; on Tusky 1,167 of 31,764 summaries reached a bound, reported in
  `stats.boundedSummaries` and the notes). More than 16 fields of one object, or more than 4 field labels of one parameter,
  are widened to the whole object or parameter (that can only add flows).
