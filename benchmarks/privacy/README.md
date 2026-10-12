# Privacy map: measurements

`apkproject privacy` (`PrivacyMap`, module `:app-recovery`) measured on four F-Droid apps, 2026-10-11:

| App | APK (F-Droid) | SHA-256 | What it does with personal data |
|---|---|---|---|
| Tusky 32.2 | `com.keylesspalace.tusky_142.apk` | `3e8fcc49…caffa` | nothing: no flow is correct |
| PhoneTrack 0.1.0 | `net.eneiluj.nextcloud.phonetrack_20.apk` | `394601e6…1f4c` | logs location to SQLite, uploads it, sends it by SMS, exports GPX |
| OwnTracks 2.5.9 | `org.owntracks.android_420509001.apk` | `b12ca465…0d9f` | publishes location (and Wi-Fi SSID/BSSID) over MQTT or HTTP |
| GPSLogger 137 | `com.mendhak.gpslogger_137.apk` | `1aa6e7ce…950a` | writes GPX/KML/CSV, uploads to custom URLs and services |

Tusky has no leak, so it measures false positives only; the three location apps have real flows,
checked against their decompiled code (JADX 1.5.6 output read method by method for PhoneTrack, by
category for OwnTracks and GPSLogger).

## Tusky (false positives)

Tusky is a Mastodon client. It does not request the location permission, has no analytics or
advertising SDK, and its source contains no code that reads the location, device identifiers,
contacts or the clipboard on its own. Two personal-data APIs are referenced, both inside libraries:

- `LocationManager.getLastKnownLocation` in AppCompat's night-mode helper (TwilightManager, renamed
  `k.w`), which computes sunrise and sunset times;
- `ClipboardManager.getPrimaryClip` in AppCompat's and Compose's text fields, when the user pastes.

So the correct report has those two sources and **no flows**.

| Version | Flows | Shared fields tainted | Time | What it fixed |
|---|---:|---:|---:|---|
| Earlier session (context-insensitive; never pushed) | 238 | – | – | reported in that session; taint spread through identity helpers and container fields |
| Summaries, 32 virtual targets, no bounds | – | – | > 240 s | did not finish: `compare(Object, Object)` summaries reached 807 field writes and 142 sink edges |
| + bounded summaries, 8 virtual targets, no `int`/`boolean` shared fields | 907 (129 high) | 5,212 | 37 s | finishes; the remaining flows spread through shared fields |
| + data read back from a shared field is not stored into another; no `float`; a `TextView` reading the clipboard is a paste | 88 | 6 | 10 s | AppCompat's EditText paste and the field-to-field cascade |
| + no `long` shared fields; clipboard data does not survive a shared field | **0** | 0 | 10 s | AppCompat's sunrise times (long) reached Compose's packed longs; Compose's paste kept in text-field state |
| + summaries joined with their previous value | **0** | 0 | 10–12 s | the iteration converges (it had hit a 60-visit safeguard on four tiny methods) |
| + a value read from a field remembers its holder; return values keep their object identity; sink edges per parameter and sink; shared fields only for flat data classes | **0** | 0 | 16–17 s | recall: a builder appending to its own `StringBuilder` field (new test case) |
| + callbacks, channels, SharedPreferences and SQLite read back (recall) | **0** | 0 | 30 s | registration-time callbacks; only for objects that carry data in |
| + two-level field paths, inherited platform methods by platform class | 533 | 202 | 105 s | (a regression) the activity's object graph now reaches AppCompat's TwilightManager state |
| + a Context passed to platform code carries no app data | 12 | 0 | 57 s | location → night mode → theme → Material drawables |
| + R8 merged lambda groups per case; a call's targets are alternatives; bounded summaries saturate; canonical widening | **0** | 0 | 63 s | Compose's paste lambda merged with a SharedFlow collector; summaries oscillating at their size bound |
| + field type sets, exact local receivers, platform streams, callback results, MQTT, privacy API model (sources) | **0** | 0 | 65 s | recall on the location apps (below) without new Tusky flows |

Clipboard text put back on the clipboard (a text field's cut and paste) is not reported: it goes
nowhere new.

## PhoneTrack (flow by flow)

32 flows. Against the code:

| Kind | Flows | Real | Why not real |
|---|---:|---:|---|
| location → network (PhoneTrack server, Nextcloud Maps, custom GET/POST URL, JSON) | 6 | 6 | |
| location → SMS | 2 | 2 | |
| location → SQLite (`locations` table) | 2 | 1 | the undo after deleting a log job re-inserts its locations, reported at `addLogjob` instead of `addLocation` |
| location → GPX file (`PrintStream` over a file) | 1 | 1 | |
| location → logs (debug logging, location objects and distances) | 17 | 14 | a row id read from the table logged as location; two osmdroid map-controller logs (zoom levels, provider names) |
| ANDROID_ID → network, SharedPreferences (osmdroid's CloudMade token request) | 2 | 2 | |
| ANDROID_ID → log (CloudMade's response message) | 1 | 0 | the response, not the id |
| account names → Nextcloud Files app (SSO intent) | 1 | 1 | |
| **Total** | **32** | **27 (84 %)** | |

Missed: in Nextcloud single sign-on mode positions go to the Files app through AIDL (a binder call),
which is not modelled.

The rules that took PhoneTrack from 193 flows to 32 while keeping every real one:

- `CorrectingLocation extends Location` overrides `getTime()`: a subclass's override keeps the
  meaning of the platform method (a fix's time is not where the device is);
- osmdroid's `LocationListenerProxy` is never created in PhoneTrack: its `mListener` field is only ever
  set from a parameter nothing passes, so a call on it runs nothing (field type sets); before, class
  hierarchy analysis sent every listener's location through it into PhoneTrack's `logjobs` table;
- a write into an object held in a field (`this.worker.lastLocation = loc`) is a two-level path,
  not "everything in `this.worker`";
- SQLite writes count the stored `ContentValues`, not WHERE arguments (`setLocationSynced(id)`);
- a stream's mark is on the stream itself: an object holding one in a field (a map view and its tile
  archive) is not a file;
- `new BufferedOutputStream(conn.getOutputStream())` is the platform's stream, not commons-io's
  `ThresholdingOutputStream` (exact local receivers, platform streams);
- a platform method on an object of unknown class may run the platform's own implementation as
  well as the app's overrides: this found the Nextcloud Maps and batch uploads (recall).

## OwnTracks and GPSLogger (by category)

**OwnTracks** (291 flows): location and the Wi-Fi SSID/BSSID (OwnTracks puts both in its location
messages) reach MQTT `publish` (12) and OkHttp (6), the Room message queue (36), and debug logs. Found
only after: a callback's result is the call's result (`dequeue` returns the message from
`withContext(io) { … }`), lambdas created in a coroutine carry the fields they capture
(`job$1.$message`), and Paho MQTT `publish` is a network sink. Suspect: about 70 flows through
waypoint and geofence fields, `Contact.id`, the MQTT persistence `clientId` and the message `topic`,
where incoming and outgoing messages share parser and message state.

**GPSLogger** (149 flows): location to files (GPX, KML, CSV loggers), to OkHttp uploads (custom URL,
OpenGTS), to Tasker-style broadcasts and to debug logs, which GPSLogger also writes to a file; the
ANDROID_ID and serial in custom URLs (`%AID`, `%SER` placeholders) and files; the Google account's
auth token and data (found by the privacy API model) to network, preferences and logs. Not reviewed
flow by flow.

## Recall: the test app

`PrivacyMapTest` compiles an app against the real `android.jar` and requires exactly these 20 flows:

- **direct:** location through a helper into a Retrofit query; location added to a builder that keeps
  it in its own `StringBuilder` field, then posted; location kept by `onLocationChanged` in a field and
  written to an HTTP request body on a click; ANDROID_ID logged; ANDROID_ID kept in a static field and
  saved to SharedPreferences elsewhere; the advertising ID handed to Firebase Analytics; contact names
  into an OkHttp form body; the IMEI in a broadcast (marked "cannot run": no `READ_PHONE_STATE`);
  the clipboard read in the background and posted;
- **later:** location posted from a `Runnable` run on another thread; a fused-location `Task`'s
  `onSuccess`; a LiveData observer logging what was posted; a `Callable`'s result through
  `ExecutorService.submit(…).get()`;
- **stored and read back:** ANDROID_ID under a SharedPreferences key, then uploaded; location into an
  SQLite table, then uploaded;
- **sinks:** a `PrintStream` over a file (storage) and `System.out` (log);
- **model:** a Geocoder address (found only with the privacy API model, and marked model-based).

Traps (no flow allowed): the same identity helper with a clean value; the same builder with clean
values; two instances of a class with an `Object` field; a continuation-like object with data in `L$0`;
location used only to choose a theme; a text field pasting; a click listener logging a constant; a
library class's instance field; an SQLite `update` whose WHERE argument is a device id; a `Location`
subclass's own `getTime()`; a delegate whose field only ever holds the quiet implementation while
another implementation logs.

## The privacy API model

Platform methods that no table names are classified by an int8 model trained on SuSi's and
FlowDroid's labelled API lists (`scripts/ml/README.md`). It is used for sources only, at 95 %
confidence or more. Effect: Tusky 0 flows with and without it; PhoneTrack unchanged; OwnTracks +2
(a reverse-geocoded address logged); GPSLogger +26 (its account auth token and data). Wrong for
personal data among its sources here: `GpsStatus.getSatellites` taken for location.

## Reproduce

```sh
scripts/setup-toolchain.sh --full && ./gradlew :cli:installDist
for apk in com.keylesspalace.tusky_142 net.eneiluj.nextcloud.phonetrack_20 org.owntracks.android_420509001 com.mendhak.gpslogger_137; do
  curl -fLo .tools/dl/privacy/$apk.apk https://f-droid.org/repo/$apk.apk
  modules/cli/build/install/apkproject/bin/apkproject privacy .tools/dl/privacy/$apk.apk --out runs/privacy-$apk
done
./gradlew :app-recovery:test --tests '*PrivacyMapTest*'   # includes the Tusky check when .tools/dl/tusky/tusky-142.apk is there
```

Diagnostics (environment variables):

- `APKPROJECT_PRIVACY_DEBUG=1` prints progress, the most re-analysed methods, the largest
  summaries, merged-lambda variants, field types and the tainted shared fields;
- `APKPROJECT_PRIVACY_WATCH=<method substring>` prints that method's summary (with its sink edges)
  on each analysis;
- `APKPROJECT_PRIVACY_TRACE=<field substring>` prints each write of concrete data into a matching
  shared field, SharedPreferences key (`pref:`) or table (`db:`);
- `APKPROJECT_PRIVACY_BUDGET_MS` sets the time budget (default 240,000);
- `APKPROJECT_PRIVACY_MODEL=0` turns the privacy API model off.

## Known limits

- Not followed: reflection, native code, files and extras named at run time, stream marks of a stream
  held in a field (PhoneTrack's single sign-on pipe), and callback or channel types under renamed
  library names. Followed since 2026-10-12: binder calls, intent/bundle extras and files read back under
  a constant key or name (measurements above predate this; see the handoff).
- The object model is two fields deep; deeper writes count for the second level as a whole, and a
  recursive structure (a proxy of a proxy) collapses there.
- Shared state of a parser or message object used for both incoming and outgoing data mixes them
  (OwnTracks above).
- Library internals are modelled, not analysed, when their package name survives (androidx, kotlin,
  okhttp3, ...). Renamed library code is analysed as app code; a source inside a library is attributed
  by package, renamed libraries show as `app`.
- Summaries are bounded: sink edges beyond 48 and field writes beyond 128 per method are dropped (recall
  can only go down; counted in `stats.boundedSummaries`). A list at its bound keeps its entries. More
  than 16 fields of one object, or more than 4 field labels of one parameter, are widened to the whole
  object or parameter (that can only add flows).
