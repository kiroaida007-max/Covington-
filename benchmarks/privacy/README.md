# Privacy map: measurements

`apkproject privacy` (`PrivacyMap`, module `:app-recovery`) measured on four F-Droid apps, 2026-10-11,
and again on 2026-10-12 after the engine-gap work (numbers below are the 2026-10-12 ones unless a
table row says otherwise; the privacy API model off, its default since then):

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
| + binder calls, extras and files read back, responses, decoded JSON, pipes, copying threads, run-time behaviour (2026-10-12) | **0** | 0 | 68,326 analyses | the same build as the row above took 101 s on this day's machine (70,131 analyses), so the time column is not comparable |
| + captured objects kept as references, coroutine spilled locals, OkHttp URL apart from the body, permission table (2026-10-12, later) | **0** | 0 | 69,133 analyses | about 13 % more time than the row above on the same machine; not truncated |

Two rules were measured on Tusky and narrowed before they went in: `thread.start()` running the
thread's own `run()` re-analysed ExoPlayer's decoder thread and executor threads until merged-lambda
variants passed their 200-visit safeguard (78,499 analyses, truncated); it now applies only to a thread
created in the method that holds a stream or captured personal data. A first version of keeping a
stored object as a reference (OwnTracks, below) truncated Tusky and removed nothing; the version that
went in also re-expresses references at each call, keeps coroutine spilled locals as local state, and
falls back to flattening for a method re-analysed more than 64 times. Two ways of keeping sink edges
apart per field or per sink method (PhoneTrack's misplaced flows, below) were measured and dropped:
they overflowed summaries (GPSLogger 34 bounded), lost three real PhoneTrack logs and truncated Tusky
(74,777 and 85,703 analyses).

Clipboard text put back on the clipboard (a text field's cut and paste) is not reported: it goes
nowhere new.

## PhoneTrack (flow by flow)

32 flows. Against the code:

| Kind | Flows | Real | Why not real |
|---|---:|---:|---|
| location → network (PhoneTrack server, Nextcloud Maps, custom GET/POST URL, JSON) | 6 | 6 | |
| location → Nextcloud Files app (single sign-on: the request serialised, copied by a thread into a pipe a binder call hands over) | 1 | 1 | |
| location → SMS | 2 | 2 | |
| location → SQLite (`locations` table) | 2 | 1 | the undo after deleting a log job re-inserts its locations, reported at `addLogjob` instead of `addLocation` |
| location → GPX file (`PrintStream` over a file) | 1 | 1 | |
| location → logs (debug logging, location objects and distances) | 17 | 14 | a row id read from the table logged as location; two osmdroid map-controller logs (zoom levels, provider names) |
| ANDROID_ID → network, SharedPreferences (osmdroid's CloudMade token request) | 2 | 2 | |
| account names → Nextcloud Files app (SSO intent) | 1 | 1 | |
| **Total** | **32** | **28 (88 %)** | |

Changed on 2026-10-12: the CloudMade response logged as ANDROID_ID is gone (a server's response is
the server's data), and the single sign-on upload, missed before, is found. It needs five rules
together: a wrapping stream writes through to the in-memory one it wraps (`ObjectOutputStream(bytes)`
then `bytes.toByteArray()`), a pipe's write end (`ParcelFileDescriptor.AutoCloseOutputStream`) is
IPC, `new Copier(in, out).start()` runs the copier's `run()`, a buffer read from a stream an object
holds takes its data, and data a callee writes into a field holding a stream reaches that stream.

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

**OwnTracks** (151 flows; was 289): location and the Wi-Fi SSID/BSSID (OwnTracks puts both in its
location messages) reach MQTT `publish` and OkHttp (every one of them through the queued message's
`messageJson`), the Room message queue, and debug logs. Found only after: a callback's result is the
call's result (`dequeue` returns the message from `withContext(io) { … }`), lambdas created in a
coroutine carry the fields they capture (`job$1.$message`), and Paho MQTT `publish` is a network sink.

The 138 flows removed on 2026-10-12 came from captured objects. The endpoint's constructor builds
`NetworkTrackingCallback(lambda(this), ...)`, and storing `this` in each lambda's `this$0` stored
everything known about the endpoint, flattened (all ten constructor arguments and, later, its Wi-Fi
callback's SSID); the message processor's coroutine lambdas did the same with the processor, so every
value read through `this$0` (the outgoing queue, a message's topic) carried the Wi-Fi data. Now a
stored parameter object is a reference (`REF`), re-expressed in the caller's terms at each call, and a
read through it is that object's own field. Gone: topic, `clientId`, `Contact.id`, host and waypoint
logs, and intents built from other users' contacts; every network flow stayed. Left to check: three
location logs of an incoming transition's description (`processIncomingMessage$3`) that appeared, and
the remaining debug logs, not reviewed one by one (the emulator can settle them, below).

**GPSLogger** (119 flows; with the model, measured before 2026-10-12's later changes, about 30 more): location to files (GPX, KML, CSV loggers, the
Dawarich queue persisted with an `ObjectOutputStream`), to OkHttp uploads (custom URL, OpenGTS), to
Tasker-style broadcasts and to debug logs, which GPSLogger also writes to a file; the ANDROID_ID and
serial in custom URLs (`%AID`, `%SER` placeholders) and files; with the model, the Google account's
auth token and data to network, preferences and logs. GPSLogger targets API 35, so `Build.SERIAL` is
"unknown" on every device: its 25 serial flows are marked "no data at run time" and left out of Data
safety. A logged OkHttp response prints its URL: the custom-URL worker's log is real (the location is in
the URL), Dawarich's is not (the location is in the POST body) and is no longer reported, since the
request builder keeps the URL's labels apart (`REQUEST_URL`). Not reviewed flow by flow.

## Run-time behaviour by Android version

`SourceRuntime` applies the platform's documented behaviour changes to each app's minSdk, targetSdk
and permissions (sources in its KDoc). On these apps: GPSLogger (minSdk 21, targetSdk 35) gets no
serial (`Build.SERIAL`), sees only the accounts the user lets it see on Android 8+, and only the
packages its two `<queries>` name on Android 11+; OwnTracks (24/36) gets the Wi-Fi name only while
location services are on; PhoneTrack (18/29) and Tusky (24/36) get notes for ANDROID_ID scoping,
accounts and the clipboard. `SourceRuntimeTest` checks each rule; `PrivacyMapTest` re-analyses its app
for minSdk 33 / targetSdk 34, where the IMEI and the advertising ID (no `AD_ID` permission) return
nothing.

## Checking on an emulator (Codex)

Codex tests apps on a disposable Android emulator: `RuntimeBehavior` and `apkproject runtime` on
`codex/production-candidate` (API 30+ AOSP image, software acceleration, `docs/runtime-behavior.md`).
A session records UI, the app's own logcat lines, private files and their changes, permission grants
and denied network packets. That is OBSERVED evidence for what this static map can only infer:

- **`SourceRuntime`**: on API 29+ images, `getImei()` fails or returns null without
  READ_PRIVILEGED_PHONE_STATE; `WifiInfo.getMacAddress()` returns 02:00:00:00:00:00; the SSID is
  `<unknown ssid>` without a location permission; `Build.SERIAL` is "unknown" for targetSdk 26+.
- **Log and file flows**: with a mock location (`adb emu geo fix`) and a known SSID, search the
  session's logcat and private-file deltas for the coordinates and SSID. That settles OwnTracks'
  suspect `topic`/`Contact.id`/`clientId` log flows and PhoneTrack's four false ones.
- **Network flows**: the session denies the app's traffic and records the denied packets'
  destinations, which confirms that a request was attempted, not its body.

Record such a result with the emulator image's API level and keep the static flows INFERRED.

## Recall: the test app

`PrivacyMapTest` compiles an app against the real `android.jar` and requires exactly these 39 flows
(the 20 below, plus a binder proxy, a Messenger, extras to another screen and to another app, a named
file read back by line and into a buffer, a decoded message re-sent, a pipe to another process, the
`URL`/endpoint calls of a request whose answer is logged, a lambda logging the location its captured
`this` keeps, an OkHttp GET whose logged response prints the location in its URL and a POST whose body
carries it, and a voicemail number whose permission comes from the SDK's annotations):

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

Traps (no flow allowed): a lambda logging a clean field of the `this` it captured; a logged response to a
POST; another file in the same directory; text received under the platform's
share key; a server's answers; a decoded message's app-set topic; a pipe copying a constant; the same
identity helper with a clean value; the same builder with clean values; two instances of a class with an `Object` field; a continuation-like object with data in `L$0`;
location used only to choose a theme; a text field pasting; a click listener logging a constant; a
library class's instance field; an SQLite `update` whose WHERE argument is a device id; a `Location`
subclass's own `getTime()`; a delegate whose field only ever holds the quiet implementation while
another implementation logs.

## The privacy API model

With `APKPROJECT_PRIVACY_MODEL=1`, platform methods that no table names are classified by an int8
model trained on SuSi's and FlowDroid's labelled API lists (`scripts/ml/README.md`). It is used for
sources only, at 95 % confidence or more. Effect: Tusky 0 flows with and without it; PhoneTrack
unchanged; OwnTracks +2 (a reverse-geocoded address logged); GPSLogger +29 (its account auth token and
data, Location getters). Wrong for personal data among its sources here: `GpsStatus.getSatellites`
taken for location. Off by default since 2026-10-12, as on Codex's branch, whose owner-held-out
evaluation shows the data kind is often wrong.

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
  shared field, SharedPreferences key (`pref:`), table (`db:`), file (`file:`) or extra (`extra:`);
- `APKPROJECT_PRIVACY_BUDGET_MS` sets the time budget (default 240,000);
- `APKPROJECT_PRIVACY_MODEL=1` turns the privacy API model on (off by default).

## Known limits

- Not followed: reflection, native code, files and extras named at run time, callback or channel types
  under renamed library names, a thread started elsewhere than where it is created (or one that holds
  no stream and captured no personal data), and a buffer read from a stream passed as a parameter (a
  library decoding persisted bytes through a helper: Paho's restore path spread its data through
  every object of the MQTT client).
- Objects passed to libraries are flattened (a library call sees everything in its arguments); objects
  stored in fields are references when they are a parameter's object or one read from a parameter's
  field path, and flattened otherwise (and in a method re-analysed more than 64 times).
- Summaries keep one sink edge per parameter and sink kind, at the shortest chain: two parts of one
  object reaching the same kind of sink in different methods are reported at one of them. PhoneTrack's
  log-job undo re-inserts the job's locations (`addLocation`, real) and is reported at the job's own
  insert (`addLogjob`); its osmdroid zoom-level and provider-name logs are the same merge.
- Cursor reads are not column-aware: any column read from a table carries what was written to the
  table (PhoneTrack logs a location's row id as location).
- The object model is two fields deep; deeper writes count for the second level as a whole, and a
  recursive structure (a proxy of a proxy) collapses there.
- Library internals are modelled, not analysed, when their package name survives (androidx, kotlin,
  okhttp3, ...). Renamed library code is analysed as app code; a source inside a library is attributed
  by package, renamed libraries show as `app`.
- Summaries are bounded: sink edges beyond 48 and field writes beyond 128 per method are dropped (recall
  can only go down; counted in `stats.boundedSummaries`). A list at its bound keeps its entries. More
  than 16 fields of one object, or more than 4 field labels of one parameter, are widened to the whole
  object or parameter (that can only add flows).
