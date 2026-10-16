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
category for OwnTracks and GPSLogger). On 2026-10-14 the benchmark grew to 28 apps of different kinds
(next section); the sections after it are about the first four.

## Twenty-eight apps (2026-10-14)

`apps/apps.tsv` pins 28 F-Droid apps by SHA-256: the four above and 24 more, chosen to cover kinds of
app the first four do not. Location loggers and trackers (OpenTracks, OSMTracker), radio scanners that
upload what they see with where (Tower Collector, WiGLE, SatStat), a map editor (StreetComplete),
weather and public transport by location (omweather, Liberario), a camera (Open Camera), messaging and
SMS (Conversations, Fossify Messages, SMS Import/Export), contacts, dialer and calendar (Fossify
Contacts, Phone, Calendar), contacts and calendar sync (DAVx5), a keyboard and a launcher (FlorisBoard,
KISS), password managers (KeePassDX, Aegis), loyalty cards (Catima), notes (Markor), a firewall
(NetGuard) and an app store (F-Droid).

```sh
benchmarks/privacy/apps/run.sh runs/privacy-apps     # downloads, checks hashes, analyses, scores (JAVA_HOME at JDK 17)
python3 -I benchmarks/privacy/apps/evaluate.py --runs runs/privacy-apps   # scores existing reports
```

`evaluate.py` checks what can be known about each app without a ground truth for every flow:

- **The manifest.** An app that does not request `android.permission.INTERNET` cannot open a network
  connection, so a NETWORK flow reported for it is false. Twelve of the 28 apps do not request it
  (`axml.py` reads the binary manifest).
- **Flows checked against the code** (`apps/flows.tsv`): each with what the code does, read in the
  app's JADX output. A real one must be reported, a false one must not.

Measured on 2026-10-16 with the privacy API model off (its default), on JDK 17, two apps at a time (the
time budget is 240 seconds of wall-clock time, so the two incomplete reports depend on the machine):

| App | Flows | Network | INTERNET | Complete | Analyses | Data in flows | Checked flows found / reported |
|---|---:|---:|---|---|---:|---|---|
| Aegis | 1 | 0 | no | yes | 4,379 | clipboard | 1 / 1 real; 0 / 2 false |
| Catima | 1 | 0 | no | yes | 2,619 | device-id | 0 / 0 real; 1 / 1 false |
| Conversations | 101 | 4 | yes | yes | 19,930 | camera, contacts, location, wifi |  |
| DAVx5 | 754 | 8 | yes | yes | 23,681 | accounts, calendar, contacts |  |
| F-Droid | 62 | 0 | yes | yes | 17,608 | device-id, installed-apps, wifi |  |
| FlorisBoard | 0 | 0 | no | yes | 7,044 | - |  |
| Fossify Calendar | 259 | 0 | no | **no** (time budget) | 46,142 | accounts, calendar, contacts |  |
| Fossify Contacts | 40 | 0 | no | yes | 49,044 | accounts, contacts |  |
| Fossify Messages | 187 | 0 | no | **no** (time budget) | 35,217 | contacts, phone-number, sms |  |
| Fossify Phone | 120 | 0 | no | yes | 46,861 | accounts, contacts |  |
| GPSLogger | 114 | 20 | yes | yes | 13,363 | accounts, device-id, location | 2 / 2 real; 1 / 1 false |
| KeePassDX | 0 | 0 | no | yes | 29,060 | - |  |
| KISS | 38 | 0 | no | yes | 1,512 | clipboard, contacts |  |
| Liberario | 1 | 1 | yes | yes | 8,693 | location | 1 / 1 real; 0 / 0 false |
| Markor | 0 | 0 | yes | yes | 20,310 | - |  |
| NetGuard | 4 | 0 | yes | yes | 3,044 | device-id, wifi | 3 / 3 real; 0 / 0 false |
| omweather | 22 | 6 | yes | yes | 5,894 | device-id, location |  |
| Open Camera | 34 | 0 | no | yes | 3,894 | camera, location | 0 / 0 real; 0 / 1 false |
| OpenTracks | 11 | 0 | no | yes | 4,814 | location |  |
| OSMTracker | 14 | 1 | yes | yes | 7,137 | device-id, location |  |
| OwnTracks | 152 | 14 | yes | yes | 8,514 | location, wifi |  |
| PhoneTrack | 27 | 7 | yes | yes | 10,187 | accounts, device-id, location |  |
| SatStat | 10 | 0 | yes | yes | 6,165 | location | 2 / 2 real; 0 / 1 false |
| SMS Import/Export | 55 | 0 | no | yes | 15,124 | call-log, sms |  |
| StreetComplete | 5 | 0 | yes | yes | 68,577 | location |  |
| Tower Collector | 340 | 27 | yes | yes | 25,787 | device-id, location |  |
| Tusky | 0 | 0 | yes | yes | 50,113 | - |  |
| WiGLE | 90 | 12 | yes | yes | 15,249 | device-id, location, wifi | 3 / 3 real; 1 / 1 false |

No app without the INTERNET permission gets a network flow; every flow checked as real is found (12 of
12); three of the seven checked false flows are still reported (Catima's, GPSLogger's and WiGLE's, below).
Fossify Calendar and Messages stop at the time budget.

What the new apps found, and what changed for it (each change has a case in `PrivacyMapTest` that fails
without it, or is measured here):

- **Truncation.** SMS Import/Export and WiGLE stopped at the 200-analysis safeguard: a summary joined
  with its previous value alternated between an object widened at its 16-field bound and the same
  object with the next analysis's few new fields. A field whose labels the object as a whole already
  carries is now dropped from the summary (`withoutCoveredFields`); the busiest methods went from 204 and
  214 analyses to 48 and 20, and WiGLE from 85 flows to 90. The notes now tell the time budget
  and the safeguard apart, with the number of methods that reached it.
- **New sources.** Cells reported to a listener (`onCellInfo`, `onCellInfoChanged`,
  `onCellLocationChanged`), NMEA sentences (`onNmeaMessage`, `onNmeaReceived`), SMS from a broadcast
  (`Telephony.Sms.Intents.getMessagesFromIntent`), a contact's photo (`openContactPhotoInputStream`), and
  the consumer `LocationManager.getCurrentLocation` hands a fix to. SatStat's cell logs and GPSLogger's
  NMEA file are found.
- **A platform collection's own iterator runs no app iterator.** A loop over an `ArrayList` called
  `Iterator.next()` on every app class implementing `Iterator` (SatStat bundles Apache Commons Net,
  whose NNTP iterators then put their fields into every cell list): four false SatStat logs of the
  location provider names. The receiver of a call is now traced back through loops (reaching
  definitions); an iterator or view (`iterator()`, `entrySet()`, `subList()`, ...) returned by a call that
  runs no app code, or a platform class created in the method, runs only the platform's methods. In the
  test app this also restores a real flow: with an app iterator in the DEX, a loop over a list of
  positions lost the data the list held.
- **R8's merged lambdas: the case created on joined branches.** Aegis builds its clipboard dialog's
  listener as case 0 of a merged lambda group, with the id loaded before a switch; the id was looked
  for only in the same straight-line block, so every case ran, and the clipboard reached case 3's
  `startActivity` and the default case's `putInt`. The id is now found on every path (both false flows
  are gone).
- **AndroidX's `ActivityResultLauncher.launch`** starts an activity like `startActivityForResult` (an
  IPC sink), also when R8 types the field as the registry's own subclass. Aegis's real flow, the pasted
  `otpauth` URI handed to its entry editor, is found.
- **Unregistering does not run a callback.** Any app object handed to platform code ran as a
  callback, `unregisterReceiver(r)` and `removeUpdates(l)` included. AppCompat's night-mode cleanup
  unregisters its time-change receiver; running it there recomputed the night mode from the last known
  location, and in Fossify Phone (whose AppCompat is renamed, so analysed as app code) that location
  spread through the activity: 116 location flows, and the analysis hit its time budget. Calls named
  `unregister*` or `remove*` (not `removeIf`) no longer run their arguments: Fossify Phone finishes, with
  120 flows instead of 336.
- **A callback that receives personal data runs where it is registered.** A registration ran its
  callback only when the call carried data into it; the platform's data (the fix a location listener
  gets) did not count, so what the listener keeps in its own fields reached no one who reads it.
  WiGLE's Wi-Fi receiver reads its GNSS listener's location: those 15 flows had come only from the
  listener being run where it is unregistered. A callback the source tables name (`onLocationChanged`,
  `onCellInfoChanged`, ...) now always runs at its registration, except on an object registering itself:
  there the fix went into every field of `this` the registering method read next (six false SatStat
  flows, its map's per-provider styles among them).
- **Copies of a register.** D8 copies arguments into consecutive registers for a range call
  (`move-object v5, v6`); what the callee put into the copy was lost to the original when nothing was
  known about the object yet. The two now share one value.
- **The app's own LiveData classes, and a mediator's sources.** Liberario's position controller posts
  each fix to its own `NotifyingLiveData` (a `MutableLiveData` subclass), which a `MediatorLiveData`
  hands to the reverse geocoder; the geocoder sends the position to
  `nominatim.openstreetmap.org/reverse?lat=..&lon=..`. Three things stopped it: only AndroidX's own
  LiveData types were channels; `addSource(source, observer)` gave the observer what the mediator holds;
  and the source was a local object stored into its field before the call. Now a subclass of a channel
  type is one, the observer gets what the source is posted, and an object stored into a channel field
  is that field's channel from there on. The flow was found before only with the privacy API model on.

Not settled here:

- Fossify Calendar and Fossify Messages reach the 240-second budget when two apps run at a time. Alone,
  with 900 seconds, Calendar needs 254 seconds (58,341 analyses, 394 flows) and is still incomplete: four
  methods reach the 200-analysis safeguard (`az0.k`, `mi3.setAdapter`, `mi3.s`, `xa3.l`) and 1,189
  summaries reach their size bound. Their flows are marked incomplete.
- OwnTracks reports 152 flows, not 145: location and Wi-Fi reach the waypoints table through the
  geofence-transition coroutine, whose `this` holds the captured location beside the waypoint and is
  widened (the false logs of the same kind that went on 2026-10-12, at the table now). The deregistration
  rule changes the order of analysis enough for that widening to happen; with it off the count is 145.
- GPSLogger reports the age of the DGPS data and the DGPS station id, which it reads from an NMEA
  sentence into `Location` extras and writes to its GPX file, as location; they are fix metadata. Its Dawarich queue file
  (`buffer.json`) is not listed apart: a summary keeps one place per label and sink kind, and that
  label's place is the CSV logger's.
- Catima's ACRA logs a device ID only in a `catch` for a `JSONException` that `JSONObject.put` does not
  throw for a string under a non-null key; exceptions are not modelled.
- WiGLE's location reaches MapLibre's log of a style icon name (`LocationLayerController.buildIconString`)
  through the fields its `LatLng` and `Network` classes share across instances (flat data classes are
  shared so that a value stored in one place is found where another reads it).
- DAVx5 (754 flows, mostly contacts and account names in `java.util.logging` logs), Tower Collector
  (340) and Conversations (101) are not reviewed flow by flow.

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
| + a sink place per label, system services hold no app data, whole numbers read from a table carry no location | **0** | 0 | 69,131 analyses | not truncated |
| + collections keep their elements' fields apart (also Android's SparseArray, ArrayMap, LruCache) | **0** | 0 | 69,167 analyses | not truncated; writing back through an element (below) truncated it at 70,383 |
| + a merged lambda's case depends only on the calls its own branch makes; sink edges keep 8 field labels; collection copies; a callback's result is the call's result | **0** | 0 | 50,426 analyses | 93 s alone on JDK 17; the busiest method is analysed 93 times (was about 200, at its safeguard), 9 fall back to flattening (was 105) |

Analyses counted with the CLI on JDK 21 up to the row before the last; the test runs on JDK 17, where
the same build analyses slightly differently (69,965 where JDK 21 gave 69,392, a build that JDK 17 then
truncated; 50,432 and 50,426 for the last row). The cause was not found; neither run depends on
identity hash codes (`-XX:hashCode=3` gives the same result).

Two rules were measured on Tusky and narrowed before they went in: `thread.start()` running the
thread's own `run()` re-analysed ExoPlayer's decoder thread and executor threads until merged-lambda
variants passed their 200-visit safeguard (78,499 analyses, truncated); it now applies only to a thread
created in the method that holds a stream or captured personal data. A first version of keeping a
stored object as a reference (OwnTracks, below) truncated Tusky and removed nothing; the version that
went in also re-expresses references at each call, keeps coroutine spilled locals as local state, and
falls back to flattening for a method re-analysed more than 64 times. Two ways of keeping sink edges
apart per field or per sink method (PhoneTrack's misplaced flows, below) were measured and dropped:
they overflowed summaries (GPSLogger 34 bounded), lost three real PhoneTrack logs and truncated Tusky
(74,777 and 85,703 analyses). What went in keeps one edge per parameter and sink kind, as before, but
each label in it keeps its own sink call and chain, so the summary sizes and the number of analyses do
not change.

Clipboard text put back on the clipboard (a text field's cut and paste) is not reported: it goes
nowhere new.

## PhoneTrack (flow by flow)

27 flows. Against the code:

| Kind | Flows | Real | Why not real |
|---|---:|---:|---|
| location → network (PhoneTrack server, Nextcloud Maps, custom GET/POST URL, JSON) | 6 | 6 | |
| location → Nextcloud Files app (single sign-on: the request serialised, copied by a thread into a pipe a binder call hands over) | 1 | 1 | |
| location → SMS | 2 | 2 | |
| location → SQLite (`locations` table: new locations, and the undo after deleting a log job re-inserting its locations) | 2 | 2 | |
| location → GPX file (`PrintStream` over a file) | 1 | 1 | |
| location → logs (debug logging, location objects and distances) | 12 | 10 | the map's `updateDevicePositions` (timestamps and counts: the last timestamp of a device is read from a map of lists of locations, which the two-level object model keeps as one); osmdroid's zoom-level log (`rescaleCache`) |
| ANDROID_ID → network, SharedPreferences (osmdroid's CloudMade token request) | 2 | 2 | |
| account names → Nextcloud Files app (SSO intent) | 1 | 1 | |
| **Total** | **27** | **25 (93 %)** | |

Corrected on 2026-10-12: the counts before this one (28 real of 32, of 30, of 29) took three logs as
real that are not. The classic workers' `handleLocationChange` logs only the job's settings, a time and
booleans; they were reported because the edge of the worker's `this` reached more than four of its
fields, was widened to all of them, and so took in the worker's last location. `updateDevicePositions`
logs timestamps and counts (row above). Changed last (29 flows to 27): a sink edge keeps eight field
labels of a parameter before it is widened (four for values), which removes the two worker logs.

Changed on 2026-10-12 (30 flows to 29): an object added to a collection keeps its own fields
apart (`ELEMENT`), so the sync's `setLocationSynced(loc.getId())` passes a row id, not the location
around it; `getLocationsCustom` logged that id. A map's `keySet()` is its keys, not its values.

Changed later on 2026-10-12 (32 flows to 30, the same 28 real ones): each label of a sink edge keeps
its own place, so the undo's locations are reported where they are inserted (`addLocation`, not the
job's own `addLogjob`), and the distance check's log at `isMinDistanceOk`, not at the significant-motion
worker's caller, which logs only the job's settings (that report was a second copy of the real log). A
platform system service holds no data the app puts in: `LocationManager.getProviders()` on a
location manager read from a field of the location provider is no longer the provider's location
(osmdroid's provider-name log). A whole number read from a cursor (`getInt`, `getLong`, `getShort`) is
an id, a time, a count or a flag, not a coordinate.

Changed on 2026-10-12: the CloudMade response logged as ANDROID_ID is gone (a server's response is
the server's data), and the single sign-on upload, missed before, is found. It needs five rules
together: a wrapping stream writes through to the in-memory one it wraps (`ObjectOutputStream(bytes)`
then `bytes.toByteArray()`), a pipe's write end (`ParcelFileDescriptor.AutoCloseOutputStream`) is
IPC, `new Copier(in, out).start()` runs the copier's `run()`, a buffer read from a stream an object
holds takes its data, and data a callee writes into a field holding a stream reaches that stream.

The rules that took PhoneTrack from 193 flows to 32 (30 since) while keeping every real one:

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

**OwnTracks** (145 flows, was 289, then 151; 153 with the model, measured at 151): location and the Wi-Fi SSID/BSSID (OwnTracks puts both in its
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
logs, and intents built from other users' contacts; every network flow stayed.

Six more location logs went on 2026-10-12 (151 to 145). Three logged an incoming transition's
description (`processIncomingMessage$3`): `publishLocationMessage` reads the waypoints with
`withContext(io) { waypointsRepo.getAll() }`, and the call's result was the callback's result plus
its arguments, so the lambda (holding the location processor, with everything in it) made every
waypoint "location"; the waypoints written back made the waypoint table location, and the
description of every transition with it. A call that returns only its callback's result (`withContext`,
`async`, `runBlocking`, an executor's `submit`, `lazy`, Room transactions) now leaves the callback object
out. Three logged `"Updated waypoint $waypoint"` in `WaypointsRepo.update`, called from the transition
coroutine whose `this` (with the captured `$location`) was widened to all its fields (eight now). The
remaining debug logs are not reviewed one by one (the emulator can settle them, below).

**GPSLogger** (113 flows; 148 with the model, measured at 119): location to files (GPX, KML, CSV loggers, the
Dawarich queue persisted with an `ObjectOutputStream`), to OkHttp uploads (custom URL, OpenGTS), to
Tasker-style broadcasts and to debug logs, which GPSLogger also writes to a file; the ANDROID_ID and
serial in custom URLs (`%AID`, `%SER` placeholders) and files; with the model, the Google account's
auth token and data to network, preferences and logs. GPSLogger targets API 35, so `Build.SERIAL` is
"unknown" on every device: its 25 serial flows are marked "no data at run time" and left out of Data
safety. A logged OkHttp response prints its URL: the custom-URL worker's log is real (the location is in
the URL), Dawarich's is not (the location is in the POST body) and is no longer reported, since the
request builder keeps the URL's labels apart (`REQUEST_URL`). With a sink place per label (119 to 117
flows), four logs of `setAlarmForNextPoint` and `setupAutoSendTimers`, which log timer settings, are
gone, and two false SQLite flows appeared: logback's `SQLiteAppender` log cleanup (`performLogCleanup`,
a `DELETE` of expired rows) reached from a logger that an earlier call put a location into. That logger
is SLF4J's interface, so the call's targets include `EventRecordingLogger`, whose queue keeps the
event, and logback's `Logger`, whose appenders run the cleanup; one object is not both, but their
summaries are joined. Before, the same edge was reported at the appender's real insert. With eight
field labels per sink edge (117 to 113), four false file flows are gone: location, ANDROID_ID and the
serial reaching the `FileOutputStream` logback's rolling appender opens, that is the log file's name.
Not reviewed flow by flow.

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
  remaining location logs and PhoneTrack's two false ones.
- **Network flows**: the session denies the app's traffic and records the denied packets'
  destinations, which confirms that a request was attempted, not its body.

Record such a result with the emulator image's API level and keep the static flows INFERRED.

## Recall: the test app

`PrivacyMapTest` compiles an app against the real `android.jar` and requires exactly these 50 flows
(the 20 below, plus a binder proxy, a Messenger, extras to another screen and to another app, a named
file read back by line and into a buffer, a decoded message re-sent, a pipe to another process, the
`URL`/endpoint calls of a request whose answer is logged, a lambda logging the location its captured
`this` keeps, an OkHttp GET whose logged response prints the location in its URL and a POST whose body
carries it, a voicemail number whose permission comes from the SDK's annotations, positions kept in a
list and logged, copied into another list and sent, cached in a `SparseArray` and sent, a task's result
logged, cells and NMEA sentences given to listeners, SMS read from a received broadcast, the fix
`getCurrentLocation` hands its consumer, an intent started through AndroidX's activity-result launcher,
the fixes a location listener keeps, read back by the code that registered it, and fixes posted to an app
LiveData subclass that a mediator hands to an observer):

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
another implementation logs; the row ids of a table that holds locations; provider names read three
fields deep beside a kept location; the ids of copied rows; a task's captured data it does not return;
an app iterator that logs itself, beside a loop over a platform list; the cases of a merged-lambda-shaped
class other than the two its id can be (set on joined branches); a receiver that is only unregistered.

## The privacy API model

With `APKPROJECT_PRIVACY_MODEL=1`, platform methods that no table names are classified by an int8
model trained on SuSi's and FlowDroid's labelled API lists and, since 2026-10-14, on hand labels of the
platform methods the 28 apps call (`scripts/ml/README.md`). It is used for sources only, at 95 %
confidence or more. On API owners it has not seen, 31 of the 44 methods it would take are the right
kind of source (the model before those labels: 23 of 41). Effect on the 28 apps (2026-10-16): Tusky 0
flows with and without it; Liberario +98 (the device's position reverse-geocoded into an address that
then reaches its network requests and storage; the tables find only the geocoding request itself, below);
PhoneTrack +54 (SMS bodies read with
`SmsMessage.createFromPdu`); GPSLogger +28 (its account auth token and data, `Location` getters);
OwnTracks +13 (a reverse-geocoded address); SatStat +12; DAVx5 +10. It takes `Location` getters on any
`Location`, also one the app builds itself, so its flows need review like any other. Off by default since
2026-10-12, as on Codex's branch.

Before 2026-10-16's LiveData fix (above), the tables missed even Liberario's geocoding request, which
the model found through `Location.getLatitude`.

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
- Summaries keep one sink edge per parameter and sink kind; each label in it keeps its own sink call
  and shortest chain. More than eight field labels of one parameter in an edge (four in a value) become
  the whole parameter, `p*`, at one place, the first shortest one: PhoneTrack's osmdroid zoom-level
  log, and GPSLogger's log cleanup (above). A method re-analysed more than 64 times keeps four.
- A library call that takes a callback returns its arguments' data and the callback's result, except
  the calls that return only the callback's result (above); a builder's block (`flow { emit(x) }`)
  emits through a parameter, so there the block object stays in the result.
- A call's targets are alternatives whose summaries are joined: what one target puts into the receiver
  can reach a sink in another target on a later call (GPSLogger's SLF4J loggers).
- Cursor reads are column-aware only for whole numbers (an id, a time, a count): any other column read
  from a table carries what was written to the table. Coordinates stored as scaled integers (E6) are
  missed.
- Collections (`java.util`, its concurrent classes, Android's `SparseArray`, `ArrayMap`, `ArraySet` and
  `LruCache` and their androidx versions) keep one element: what is added to them is stored like a
  field (`ELEMENT`), its own fields one level down, and what is read back is read like that field.
  Iterating a map's entries or values also gives its keys. An element read back is not written back
  into its collection (`list.get(0).append(x)` does not reach the list): following that re-analysed
  ConstraintLayout's dependency graph in Tusky past its safeguard.
- The object model is two fields deep; deeper writes count for the second level as a whole, and a
  recursive structure (a proxy of a proxy) collapses there.
- Library internals are modelled, not analysed, when their package name survives (androidx, kotlin,
  okhttp3, ...). Renamed library code is analysed as app code; a source inside a library is attributed
  by package, renamed libraries show as `app`.
- Summaries are bounded: sink edges beyond 48 and field writes beyond 128 per method are dropped (recall
  can only go down; counted in `stats.boundedSummaries`). A list at its bound keeps its entries. More
  than 16 fields of one object, or more than 4 field labels of one parameter, are widened to the whole
  object or parameter (that can only add flows).
