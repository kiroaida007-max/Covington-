# apkproject

APK in, editable project out, and every layer of the result says how far it can be trusted.

`apkproject decompile app.apk --out app-src` recovers what an APK contains:

- UI resources and the manifest;
- the app's Java/Kotlin logic as Java source;
- its third-party libraries as real Gradle dependencies;
- web and JavaScript front ends;
- native-code boundaries;
- the **client's view of its backend**: an API contract, an OpenAPI draft and a compilable server
  skeleton. A server's own code never ships inside an APK, so it cannot be recovered from one; see
  [ADR 0003](docs/decisions/0003-whole-app-recovery.md).

What sets it apart from running a decompiler is that **the output is checked against the input**.
Every recovered method is compiled again and compared with the original bytecode. Methods that do
not match are where decompiler mistakes live, and the tool fixes the ones it can, keeping a fix
only when recompiling proves it.

## Desktop app (APK Project Studio)

For people who would rather not use a terminal, most of all on Windows. It is laid out like a
standard Windows program, with native Windows controls on Windows: a menu bar, a toolbar, a status
bar and standard dialogs.

- **File > Recover APK…** (Ctrl+O) asks for the app, the output folder and the options, then
  shows a progress dialog. You can also drag an APK onto the window.
- The open project shows a tree on the left with Summary, Source Code, Resources, UI & Risk,
  Screens & Data, Privacy, Components, Backend API, Recovered Names and Report.
- Source folders list their files with each file's status: Verified, Partly verified or Does not
  compile, with method counts. Selecting a file shows its code below the list.
- **Backend API** lists the endpoints and starts the generated mock server.
- **UI & Risk** shows the front-end audit: toolkit, screens, findings and the risk score.
- **Screens & Data** lists every screen with the endpoints, servers, database tables and settings
  its code reaches; selecting one shows the call path. Also by endpoint and by table.
- **Privacy** lists the flows of personal data to the network, SDKs, logs, storage and other apps,
  with the path of the selected one, a Data safety summary, and the personal-data APIs the code calls.
- **Components** lists the libraries and native files, the vulnerable ones with the version to
  upgrade to, and each advisory with its source (Open Advisory shows it on osv.dev or nvd.nist.gov).
- **Tools > Compare Versions…** (Ctrl+D) compares two builds of an app in a separate window: the
  versions side by side, the ranked changes, the changed code, and the capabilities of each.
- **Tools > Tools…** installs the pinned tools and checks their SHA-256 hashes.

```
./gradlew :desktop:run                                   # from a checkout (JDK 17)
./gradlew :desktop:packageApp -Ptype=app-image           # folder with the program and its own Java runtime
./gradlew :desktop:packageApp -Ptype=exe                 # Windows installer, built on Windows (WiX 3; also msi)
./gradlew :desktop:screenshots                           # recover a demo app and photograph every screen
```

The packaged app carries its own Java runtime, javac included, which the round trip needs, so
users install nothing else. The `windows` workflow builds it on Windows and uploads:

- `windows-app`: a portable zip and, when WiX is available, an installer;
- `windows-screenshots`: every screen with the native look.

A file passed on the command line, or opened through the installer's `.apk` association, is
offered for recovery. A recovered project folder opens directly. Tools and settings live in:

- `%LOCALAPPDATA%\apkproject` on Windows;
- `~/Library/Application Support/apkproject` on macOS;
- `$XDG_DATA_HOME/apkproject` on Linux.

On Windows the app uses the `android-windows` profile (`z3.exe`; REA/Node is not used).

## Decompile an APK

```
scripts/setup-toolchain.sh --full          # JDK tools, D8, Z3, JADX, ECJ, android.jar, REA + Wakaru; SHA-256 pinned
./gradlew :cli:installDist
alias apkproject=modules/cli/build/install/apkproject/bin/apkproject

apkproject decompile app.apk --out app-src  # also .apks, .xapk, .apkm, .aab, .dex
```

Two faster companions:

```
apkproject contract app.apk --out api/      # backend contract + OpenAPI + runnable mock server, from the bytecode, in seconds
apkproject audit app.apk --out audit/       # UI toolkit + screens + static UI-quality & security findings with a risk score
apkproject diff old.apk new.apk --out diff/ # update review: what changed between two builds, code matched across obfuscation
apkproject screens app.apk --out screens/   # screen -> code -> server and database: what each screen's code reaches
apkproject names app.apk --out names.json   # original names the compilers left in the bytecode, with evidence
apkproject roundtrip app.apk app-src        # re-verify a recovered (or hand-edited) project, method by method
```

The backend contract is measured against an app's own Retrofit and Moshi source in
[`benchmarks/backend-contract`](benchmarks/backend-contract/README.md). On Tusky 32.2, an R8
full-mode release, it recovers:
- all 112 endpoints that survive in the APK, all 255 parameters with their wire names, and all
  response types;
- 83 of 83 JSON models, with 377 of 378 field types, 378 of 378 nullabilities and 17 of 18 enum
  value sets.

Its `openapi.yaml` validates, and the mock answers 46 of 47 GET routes with JSON valid against it.

### Front end and security (`audit`, and the decompile's `audit/`)

`apkproject audit app.apk --out audit/` reports the app's front end and a static review, for the
app's owner or an authorized tester (it never runs the app):

- **UI toolkit and language** — Android Views (XML), Jetpack Compose, Flutter, React Native,
  Unity, Xamarin or a hybrid WebView — and how far that front end is recoverable;
- **screens**: activities, fragments, Compose entry points and layouts, with which are exported
  or deep-linked;
- **findings** with a **risk score (0–100, grade A–F)**: manifest flags (debuggable, backups,
  cleartext, exported components, deep links), WebView API misuse, secrets in resources, and
  layout accessibility/localization lint. Each finding carries an evidence level (OBSERVED or
  INFERRED), a fix, and an OWASP MASVS / CWE reference;
- **resilience / hardening** (MASVS-RESILIENCE), so it still returns a useful report on "solid",
  well-protected apps: the obfuscation level and tool, commercial packers/protectors (Jiagu,
  Bangcle, Tencent Legu, DexProtector, DexGuard, Promon, …), and anti-analysis / integrity
  defenses the app runs (root / emulator / debugger / Frida detection, TLS pinning, attestation).
  A packed app is reported as such, with the honest note that its real code loads at runtime and
  is not statically present.

It is defensive: it describes protections and issues so they can be fixed, never how to bypass
them. On Tusky 32.2 it reports Android Views / Kotlin, 31 activities, grade B (20/100), and a
resilience level of BASIC — heavily obfuscated with R8 (94% of classes renamed), no packer or
RASP. `decompile` writes the same report to `audit/`, and the desktop app shows it on the
**UI & Risk** screen.

### Compare two versions (`diff`)

`apkproject diff old.apk new.apk --out diff/` is an update review for release checks and supply-chain
review. It answers "what really changed in this update?":

- **who signed it**: the certificates of the v1/v2/v3 signatures, and whether a v3 rotation links them;
- **what it asks for**: permissions (sensitive ones first), new or newly exported components, deep
  links, manifest flags (debuggable, backups, cleartext, network security config);
- **what it talks to**: new server hosts, new or changed API endpoints and parameters, data models;
- **what it can do**: sensitive platform capabilities (location, contacts, SMS, microphone, run-time
  code loading, shell commands, ...) and whether the permission they need is requested; third-party
  SDKs (analytics, ads, attribution, crash reporting); native libraries; code shipped as assets;
- **the static risk score** of each build, and new or resolved findings;
- **which code changed.** R8 renames classes in order, so one added class renames others and a
  by-name diff compares the wrong classes. Classes and methods are matched by what renaming
  cannot change (operations, constants, strings, framework calls, the shape of references),
  with static helpers matched on their own because R8 moves them between classes.

Every change is ranked HIGH / MEDIUM / LOW / INFO and carries an evidence level. Output:
`README.md`, `diff.json` and `code-map.tsv` (every class pair and how it was matched). In the desktop
app: **Tools > Compare Versions…** (Ctrl+D), or drop two APKs on the window.

Measured on two Tusky releases against both real R8 mappings
([`benchmarks/update-diff`](benchmarks/update-diff/README.md)): 7,956 of 7,959 classes paired
correctly (precision 0.9992), 21 of 22 edited source files reported (the miss only edits a
comment), in 13 seconds.

### Screens and the data they reach (`screens`, and the decompile's `audit/screen-map.json`)

`apkproject screens app.apk --out screens/` answers "which screen talks to which server, and which
screen reads or writes which table?" For every activity, service, receiver and worker it lists:

- **network**: the Retrofit endpoints (`POST api/v1/statuses`), server hosts and framework network
  calls its code can reach;
- **database**: the SQL it runs, by operation and table (`SELECT DraftEntity`), and content
  provider calls;
- **stored settings**: SharedPreferences files and keys, read or written (the key, not the
  default value);
- the screens it opens and the services and workers it starts;
- for each, **the call path** from the screen's own code to the call that does it.

It walks a static call graph of the bytecode, built for obfuscated Kotlin apps:

- virtual calls follow only classes the walk has created (rapid type analysis);
- R8's merged lambda classes and Hilt's `SwitchingProvider` are split per case, so one shared
  class does not make every screen reach everything;
- a DI provider stored in a field and called later (`accountManagerProvider.get()`) runs the one
  binding it was created for.

Targets that most components share (base classes, app start-up) are listed once, apart from each
screen's own. Every reach is INFERRED: code the walk reaches can still be guarded at run time.

On Tusky 32.2 (R8 full mode) it maps 49 screens and components in 16 seconds, with 93 of its 109
distinct endpoints (method + path) and 22 of 23 tables reached from at least one. Spot checks against Tusky's source:

- **ComposeActivity**: `POST api/v1/statuses`, media upload, custom emojis, account search and
  drafts (`DraftEntity`);
- **EditProfileActivity**: `PATCH api/v1/accounts/update_credentials`;
- **ListsActivity**: the list endpoints;
- **LoginActivity**: `POST api/v1/apps`, `POST oauth/token` and its `clientId` / `domain` settings.

`decompile` writes the same map to `audit/screen-map.json` and `audit/screen-map.md`, and the
desktop app shows it on the **Screens & Data** page.

### Privacy map (`privacy`, and the decompile's `audit/privacy.json`)

`apkproject privacy app.apk --out privacy` answers "what personal data does this app read, and
where can it go?". Sources are the platform APIs that return personal data: location (including
location callbacks), device identifiers (IMEI, serial, ANDROID_ID, MAC), the advertising ID, phone
number, contacts, call log, SMS and calendar (a content-provider query on their URIs), device
accounts, installed apps, the clipboard, Wi-Fi networks, microphone and camera. Sinks are the app's
Retrofit endpoints, OkHttp, URLConnection (headers, request bodies), WebView, Firebase, analytics and
crash SDKs (Firebase Analytics, Crashlytics, Sentry, Amplitude, Mixpanel, AppsFlyer, Adjust, ...),
logs, SharedPreferences, SQLite, files, broadcasts and intents, the clipboard and SMS.

The output lists each flow with its source call, the method where data and sink meet, and the call
chain to the sink, plus a summary in the shape of Google Play's Data safety form. A flow whose
source needs a permission the manifest does not request is marked "cannot run". Every flow is
INFERRED: a path the bytecode allows, not proof that it runs.

Each source also says what it returns at run time on the Android versions the app supports
(`SourceRuntime`): the platform's documented behaviour changes applied to the manifest's minSdk,
targetSdk and permissions. For example, the IMEI and other hardware IDs return nothing from Android 10,
`Build.SERIAL` is "unknown" for apps targeting API 26 or later, MAC addresses are a constant from
Android 6.0, and the advertising ID is zeros without the `AD_ID` permission for apps targeting API 33.
A flow whose source returns nothing on every supported version is marked "no data at run time", LOW,
and left out of the Data safety summary. Whether the manifest requests what a source needs is decided
per API from the Android SDK's own `RequiresPermission` annotations (`platform-permissions.tsv`,
generated by `scripts/privacy/platform_permissions.py`) together with rules for older versions. This is documented behaviour, not an observation; Codex's
`RuntimeBehavior` engine (branch `codex/production-candidate`) runs APKs on an emulator and observes
them.

It follows the code the platform runs later, not only direct calls:

- callbacks of app objects handed to platform or library code (listeners, `Runnable`s, Kotlin
  lambdas and coroutines, LiveData observers, Task listeners), with the object's captured data, and
  what they return where the call returns it (`withContext(io) { … }`, `executor.submit(callable)`);
- channels kept in fields (LiveData, StateFlow, Channel, blocking queues, Rx subjects);
- data written under a SharedPreferences key, into an SQLite table, into a file or into an intent or
  bundle extra under a constant name, and read back elsewhere;
- binder calls (an AIDL proxy's `transact`, a `Messenger`) and pipes handed to another process, with
  the thread that copies into the pipe (Nextcloud single sign-on);
- MQTT clients' `publish` as a network send; a server's response is the server's data, not the
  request's (a logged OkHttp response carries its URL, not its body); JSON decoded into an object
  carries its data as a whole, not in a field read by name.

It is a static taint analysis built for precision on obfuscated Kotlin apps:

- each method gets a summary over symbolic labels for its parameters, instantiated at each call, so
  data passing through a shared helper comes back only to the caller that passed it;
- objects are tracked two fields deep (`x.a.b`), so storing data in one field does not taint the
  whole object or its neighbours; a lambda or coroutine capturing `this` keeps a reference to it, so
  a field read through the capture is that field only, not everything `this` holds;
- R8's merged lambda classes are analysed per case, so lambdas merged into one class do not share data;
- a virtual call on an object created in the method, or read from a field whose stored classes are
  known, runs those classes only; the targets of a call are alternatives, not a sequence;
- fields shared across instances carry data only when their type holds a value (strings, `double`,
  `Location`, flat data classes such as `LatLng`), and data read back from one is not stored into another;
- a Context passed to platform code carries no app data; inherited platform methods are modelled by
  their platform class;
- well-known libraries (androidx, kotlin, okhttp3, retrofit2, ...) are modelled at their API.

With `APKPROJECT_PRIVACY_MODEL=1`, platform methods that no table names are checked against a
small int8 classifier trained on SuSi's and FlowDroid's labelled API lists (`scripts/ml/README.md`).
It adds sources only (a Geocoder address, account data), at 95 % confidence or more, marked
model-based with the model's confidence. It is off by default: with whole API owners held out of
training, its data-kind accuracy is too low to add sources unasked.

On Tusky 32.2 it analyses 31,764 methods (5,617 more of well-known libraries are modelled; 50,426
method analyses, about 90 seconds here) and reports **no flows**, which is
correct. Location and the clipboard are read only inside AppCompat's night-mode helper and Compose's
paste handling, and Tusky does not request the location permission. On three location apps checked
against their source (PhoneTrack, OwnTracks, GPSLogger), it finds their uploads, SMS, files,
databases, logs and PhoneTrack's single sign-on upload to the Nextcloud app; on PhoneTrack 25 of 27
flows are real, and OwnTracks went from 289 flows to 145 (151 when captured objects became references),
keeping every upload. `benchmarks/privacy/README.md` records the measurements and how each rule moved
them, and the test app in `PrivacyMapTest` checks 43 real leaks and the traps around them.

### Components and known vulnerabilities (`sbom`, and the decompile's `audit/sbom.cdx.json`)

`apkproject sbom app.apk --out sbom` writes a software bill of materials as CycloneDX 1.5
(`sbom.cdx.json`, validated against the official schema in the tests) and looks up each
component's known vulnerabilities: libraries on OSV.dev (by version, or by git commit for
Conscrypt's BoringSSL), native libraries in the NVD by CPE. Components come only from evidence in
the package, each with where it was read:

- library version files (`META-INF/<group>_<artifact>.version`, Maven `pom.properties`, Firebase
  and Play services `<artifact>.properties`, Conscrypt's properties);
- version strings compiled into code, which survive R8 renaming: OkHttp's User-Agent
  (`okhttp/5.3.2`), Jackson's PackageVersion constants, Bouncy Castle's provider name, and the Kotlin
  standard library's version constant;
- every native `.so` with its SHA-256, and the native projects that stamp their version (OpenSSL,
  zlib, libpng, libjpeg-turbo, curl, FFmpeg, Mbed TLS, SQLite, libavif, Expat, libvpx, Opus,
  libarchive, libssh2, OpenCV, SQLCipher), with a CPE.

OSV has no version-based ecosystem for C libraries, so native projects are matched against the NVD
(API 2.0): every CVE recorded for the product, each CVE's configurations evaluated against the
installed version (exact versions and version ranges), with the fix from `versionEndExcluding`. A
zlib 1.2.11 gets CVE-2018-25032 (fixed in 1.2.12), CVE-2022-37434 and CVE-2023-45853 (fixed in 1.3.1);
an OpenSSL 1.1.1k gets 41 CVEs from CVE-2021-3711 on. `NVD_API_KEY` raises the NVD's rate limit
(5 requests in 30 seconds without one); responses are cached for a day; `--no-nvd` skips it.

Each advisory lists the versions that fix it **for the installed version's line**: for
jackson-databind 2.9.8 the fix is 2.9.10.4, not 2.6.7.4 or 2.13.4.2. Each vulnerable component gets
the smallest upgrade that fixes all its advisories. Severity comes from GitHub's review or the CVSS v3
base score, which is computed from the vector. Advisory records are cached by their modified time.
`--offline` skips the lookup, and `--fail-on high` exits 5 when an advisory at or above that
severity is found, for CI. A lookup that fails is reported and exits 4, never shown as clean.

On Tusky 32.2: 105 libraries (OkHttp 5.3.2 from its User-Agent string, Conscrypt 2.5.3 and its
BoringSSL commit, libavif 1.4.2 from the native library) and 16 native files, with no known
vulnerabilities, in about 3 seconds. A test APK carrying Gson 2.8.5, OkHttp 3.12.0 and
jackson-databind 2.9.8 gets 61 advisories (12 critical, 43 high).

Options for `decompile`: `--deobfuscate`, `--no-repair`, `--no-cross-check`, `--no-compile-check`, `--no-rea`,
`--no-js`, `--no-library-names`, `--no-osv` (SBOM without the vulnerability lookup), `--max-checked-methods N`, `--threads N`. Library verification downloads artifacts from
Google Maven or Maven Central (SHA-1 checked, cached under `.tools/maven-cache`).

```
app-src/
  README.md                 per-layer table: where each layer is and its evidence level
  app/                      Gradle module: res/, AndroidManifest.xml, src/main/java, build.gradle
                            (verified libraries as `implementation` dependencies, not decompiled copies)
  backend/                  api-contract.json, openapi.yaml, server-skeleton/ (SYNTHESIZED mock: example JSON per route)
  native/                   jni-map.json: native methods and the ELF exports that implement them
  audit/                    ui-audit.json + README.md (front end, findings, risk), screen-map.json + .md (screens -> data),
                            privacy.json + .md (personal data flows), sbom.cdx.json, supply-chain.json + sbom.md
  web/recovered/            JavaScript modules recovered from bundles (REA + Wakaru)
  recovery/RECOVERY.md      what was recovered, how it was checked, what could not be recovered
  recovery/round-trip.json  per-method verdict; repairs.json, libraries.json, compile-diagnostics.json, ...
```

### How it decides what to trust

| Evidence | Meaning |
|---|---|
| PROVED | Z3 found no input on which the method and the bytecode differ (methods inside the V0 model) |
| ROUND-TRIP EQUIVALENT | Recompiled with javac and D8, the method has the same dataflow as the original |
| REPAIRED | A decompiler mistake was corrected, and the correction verified by round trip |
| VERIFIED IDENTITY | A library matched, class by class and method by method, to its published artifact |
| DECODED / SOURCE AS SHIPPED | Resources and web files, exact |
| OBSERVED / INFERRED | Read from the package / derived from code (URLs, endpoints, JNI bindings) |
| SYNTHESIZED | Generated scaffolding (the server skeleton), never presented as recovered |

### Restore a complete Tusky 32.2 source build

For this open-source app, an additional [source-restoration workflow](benchmarks/tusky-32.2/README.md)
uses the exact upstream commit recorded by F-Droid. It checks the input APK hash, preserves the
original Kotlin/Compose project and dependency versions, builds Android APKs, and records source
provenance plus DEX/native payload comparisons. This is published-source restoration; it does
not label the remaining errors in APK-only Java decompilation as fixed.

```sh
# Full JDK 21 and Android SDK platform 36 / build-tools 35.0.0 required.
python3 scripts/restore-tusky.py tusky-32.2.apk --out runs/tusky-restored --test --fix-test-harness
```

### Measured on a real app

`ApiDemos-debug.apk`: 5,700 classes, Kotlin plus AndroidX. Each step was measured on the same
input as it was added:

| Step | Effect |
|---|---|
| JADX 1.5.6 export | 2,860 Java files; 88 methods JADX could not decompile |
| Remove toolchain artifacts | 414 D8 platform stubs (and unreferenced desugaring helpers) deleted |
| Verify libraries | 39 libraries matched to Maven artifacts; 2,015 decompiled library files replaced by dependencies; 427 files remain (407 app) |
| Compile check (ECJ) | 412/427 files compile; app 398/407 |
| Round trip, strict comparison | 2,801/3,862 methods equivalent: most mismatches were the verifier's own noise (ECJ vs javac, D8 mode, anonymous class order) |
| Round trip, javac + both D8 modes + layout-free comparison | 3,477/3,696 equivalent (94.1%) |
| Verified repair | 45 edits kept of 373 tried, including 11 integer divisions JADX printed where the bytecode divides floats |
| **Final** | **3,522/3,696 methods round-trip equivalent (95.3%)**; app code 3,261/3,414 |

The 117 methods still reported as different are listed by name in `recovery/round-trip.json`; that
is where a reviewer should look. Round-trip equivalence is strong evidence, not proof. Proof (Z3)
covers only the V0 subset.

### Measured on an obfuscated release build

Tusky 32.2 from F-Droid (`com.keylesspalace.tusky_142.apk`): R8 8.13 full mode, 7,966 classes,
39,616 methods, Kotlin and Compose. Full `decompile` runs on the same APK, ECJ against android-35:

| Build | Library classes named | Files compiling | ECJ errors | V0 methods proved | Round-trip equivalent | Not compiled |
|---|---:|---:|---:|---:|---:|---:|
| `ac84fd2` | 2,034 | 4,857 / 7,471 | 13,084 | 13 / 29 | 18,623 / 35,287 (52.8%) | 6,485 |
| `0dda597` safer library identities | 1,822 | 4,927 / 7,471 | 12,253 | 13 / 29 | 18,785 / 35,200 (53.4%) | 6,042 |
| `2e18f38` type-renaming hygiene, relaxed checked exceptions, cross-check on renamed classes | 1,822 | 5,063 / 7,471 | 10,122 | 27 / 29 | 19,189 / 35,212 (54.5%) | 4,888 |

The first build named Tusky's own `ComposeActivity`, and an R8-merged class of 53 methods, after
AndroidX classes; the second names fewer classes and drops those identities. In the third, 392 files whose only errors are Java's
checked-exception rules (Kotlin has none) are compiled with those rules relaxed for the comparison.
The backend contract is the same in all three: 112 endpoints, 30 hosts and 83 JSON models, and the
generated mock server answers `GET /api/v1/timelines/home` with statuses in Mastodon's field names.
A complete, buildable Tusky comes from its published source instead (above); these numbers measure
what can be recovered from the APK alone.

## The V0 method laboratory

The engine underneath started as the first vertical slice of the *APK to Project: Architecture &
Implementation Plan* (v1.0, 08 October 2026). That slice, the **V0 method laboratory**, covers
static, side-effect-free 32-bit integer methods. They are recovered as Java source from our own
lifter and accepted only after formal and runtime validation. Inside `decompile`, it
proves (or replaces) the decompiled versions of such methods. Anything outside the V0 model is reported
as a capability gap with its byte offset; nothing is stubbed, and the original DEX is never shipped
as a stand-in for missing source.

### What a V0 recovery looks like

Input bytecode, as the engine's Dalvik disassembler renders it (`recovery/disassembly/classes.dasm`):

```
.method public static clamp(III)I
    .registers 3
    0000: if-ge v0, v1, :L0003    ; @0x2b0
    0002: return v1               ; @0x2b4
  :L0003
    0003: if-le v0, v2, :L0006    ; @0x2b6
    0005: return v2               ; @0x2ba
  :L0006
    0006: return v0               ; @0x2bc
.end method
```

Recovered source (`src/main/java/com/example/fixtures/Pure.java`):

```java
public static int clamp(int p0, int p1, int p2) {
    if (p0 < p1) {
        return p1;
    }
    if (p0 > p2) {
        return p2;
    }
    return p0;
}
```

The evidence recorded for it in `recovery/report.json`: `OBSERVED` (from bytecode), `PROVED` (Z3
found no 32-bit input on which the outcomes differ), `TESTED` (the recompiled candidate agreed on
every generated input in a separate JVM). Its recompiled bytecode is `IDENTICAL` to the original.
Parameter names are `p0..p2` because this APK has no debug names. The engine does not guess
names and present them as recovered.

### V0 fixture results

Fixture benchmark (`./gradlew :benchmarks:run`). These numbers cover the two development fixtures
in `fixtures/` only, and say nothing about arbitrary APKs.

| Fixture | Job state | Accepted / in scope | Identical bytecode on recompile | Independent runs (mismatches) | Seeded wrong candidates rejected |
|---|---|---|---|---|---|
| v0-pure | ACCEPTED (exit 0) | 22 / 22 | 22 | 6,759 (0) | 19 / 19 |
| v0-mixed | PARTIAL (exit 4) | 3 / 9 | 3 | 462 (0) | 2 / 2 |

"Independent runs" execute the hidden original source and the recovered project, each compiled
separately, on the same inputs. They share no code with the lifter. `v0-mixed` stops at PARTIAL
by design: its loop, `long` method, call, field read, instance method and packed switch are
reported as gaps, not approximated. Full per-method tables are in `benchmarks/results/`.

## All commands

Requires JDK 17, Python 3 (to fetch the pinned Z3 wheel) and, for `--full`, Node.js (REA).

```
scripts/setup-toolchain.sh                 # pinned D8 8.7.18 + Z3 4.13.3 into .tools/, SHA-256 verified
apkproject setup [--tools DIR]             # same pinned tools without bash or Python (any OS, Windows included)
./gradlew test                             # unit and integration tests
./gradlew :cli:installDist
alias apkproject=modules/cli/build/install/apkproject/bin/apkproject

apkproject decompile app.apk --out app-src # whole app, verified (see above)
apkproject contract app.apk --out api      # backend contract from bytecode (Retrofit, Moshi; obfuscated or not)
apkproject audit app.apk --out audit       # front end + static security review with a risk score
apkproject diff old.apk new.apk --out diff # update review across obfuscator renames
apkproject screens app.apk --out screens   # screen -> code -> server and database map
apkproject privacy app.apk --out privacy   # personal data the code reads and where it can go
apkproject sbom app.apk --out sbom         # CycloneDX SBOM + known vulnerabilities (OSV.dev, NVD); --offline, --fail-on high
apkproject roundtrip app.apk app-src       # recompile a recovered project and compare every method again
apkproject doctor                          # binaries, versions, pinned hashes, smoke compile and solve
apkproject inspect app.apk --out runs/app  # inventory, disassembly, per-method capability gaps
apkproject recover app.apk --out runs/app  # candidate loop with checkpoints and budgets
apkproject export runs/app --out recovered # atomic Gradle project + report + source map
apkproject validate runs/app --suite extra-inputs.json
apkproject resume runs/app
apkproject cancel runs/app
apkproject disasm app.apk                  # Dalvik assembly listing
apkproject asm prog.dasm --out prog.dex    # Dalvik assembler (test kit)
```

Exit codes follow section 07: 0 policy satisfied, 2 invalid configuration, 3 malformed input,
4 unsupported or partial, 5 build or verification failure, 6 worker failure, 130 cancelled.

## How it works

### Whole-app pipeline (`decompile`, module `:app-recovery`)

```
input ─► AppInput ─► anatomy ─► JADX export ─► artifact cleanup ─► library verification ─► V0 cross-check
 .apk/.aab  bounded    frameworks,  code +         D8 stubs and      Maven artifacts,          Z3 proof or
 .xapk/.dex ZIP        libraries,   resources      desugaring        >=98% signature           replacement
                       natives                     helpers removed   agreement
   ─► ECJ compile check ─► javac recompile ─► D8 (release + debug) ─► dataflow comparison ─► verified repair
      per-file errors      like-for-like      both modes, best        strict, then modulo      edit, recompile,
                                              per method              layout; anon classes     keep if better
   ─► backend contract (URLs, Retrofit, models, config) ─► OpenAPI + server skeleton
   ─► native JNI map (ELF exports) ─► REA evidence + JS module recovery ─► atomic project + RECOVERY.md
```

- **Generate with mature tools, accept with our own checks.** JADX produces readable source fast;
  the engine's DEX reader, lifter and dataflow comparison decide how much of it to trust.
- **Libraries are not decompiled when the real artifact can be proved to match.** Identities come
  from what the APK itself says: version files, pom.properties, the kotlin-stdlib constant, POMs of
  libraries already verified, and newer versions when Gradle resolved upwards.
- **Measurement noise was removed before claiming errors.** ECJ vs javac codegen, D8 debug vs
  release, anonymous class numbering, synthetic accessor names, constant placement and array
  literal encoding all produce different bytecode for the same source. The comparison abstracts
  exactly these, and each abstraction has a unit test that it still tells real differences apart.
- **Repairs are experiments.** A rule only proposes an edit. Recompilation decides whether it stays.

### V0 method laboratory

```
APK ─► :archive ─► :formats-dex ─► :lift-dex ─► :reconstruct ─► :compiler-runner ─► :validate ─► :export
        bounded     DEX reader,     CFG, SSA,    Java AST via     javac + D8 in       Z3 BV32 proof,
        ZIP reader  decoder,        V0 opcode    post-dominator   bounded             runtime agreement,
                    disassembler    manifest     structuring      subprocesses        re-lift consistency
                                                       ▲                                  │
                                                       └──── :experiments (probes) ◄──────┘
                                                             counterexamples, refinement
```

- **Acceptance is separate from generation.** `:reconstruct` proposes source and `:validate`
  decides. A candidate is compiled from source alone, its D8 output is lifted again, and Z3
  checks that no 32-bit input separates it from the original, exceptions included. It is then
  executed in a separate JVM on boundary, constant-derived and random inputs.
- **Three independent semantic models** (IR evaluator, SMT encoding, real JVM) are cross-checked
  in tests and on every validation run.
- **Local compiler experiments.** If an accepted candidate does not recompile to the original
  bytecode, the engine probes semantically equal variants (branch polarity, operand order,
  ternary form), compiles them in one batch, and keeps the closest match. Probes count as
  fidelity evidence only.
- **Whole-bundle gate.** Before a job is marked ACCEPTED, the accepted methods are compiled again
  as the real classes, and every one is validated again from that build.

Module ownership follows dossier sections 08–09. See `settings.gradle.kts` for the list and
`docs/semantics/v0-opcodes.md` for the modelled semantics.

## Assembly language

Short answer to "would assembly make it better?": Dalvik assembly, yes; native assembly, no.

- The engine renders all input bytecode as **Dalvik assembly**. That listing is the observed
  evidence layer, and recovered lines map back to it by byte offset. A matching **Dalvik
  assembler** (`:dex-asm`) builds exact and deliberately malformed DEX fixtures without the
  Android SDK, and lets D8's output round-trip through disassembly and reassembly as a decoder
  test.
- **Native x86/ARM assembly** would speed up the part that is already negligible (engine-side
  parsing and lifting of the fixture takes 0.75 s, including JVM start-up, out of a 19.6 s
  recovery). It would also add memory-unsafe parsing of untrusted input and per-platform builds.

Details, measurements and the conditions for revisiting: `docs/decisions/0002-assembly-language.md`.

## Repository map

| Path | Contents |
|---|---|
| `modules/` | 18 Gradle modules (contracts, store, archive, formats-dex, dex-asm, ir, lift-dex, source-model, reconstruct, compiler-runner, validate, experiments, export, orchestrator, app-recovery, cli, desktop, benchmarks) |
| `profiles/` | Versioned toolchain profiles with pinned tool hashes (`v0-jvm-pure`, `android-full`, `android-windows`) |
| `rules/` | Reconstruction rule catalogue (ids recorded per method and per source line) |
| `fixtures/` | Hidden-source ground truth; only `:benchmarks` reads `hidden/` |
| `benchmarks/results/` | Measured results |
| `docs/decisions/` | Architecture decisions and deviations from the dossier |
| `docs/roadmap.md` | Milestone gates M0–M7 and their status |
| `schemas/` | Record contracts and encoding rules |
| `scripts/` | Toolchain setup, fixture regeneration, opcode-table extraction |

## Limits that matter

- **The backend's code is not in the APK.** `decompile` recovers the client's contract with the
  server and a skeleton marked SYNTHESIZED, nothing more is possible from an APK alone.
- **Native code and Flutter/Unity IL2CPP stay binary.** The tool maps JNI boundaries and exports;
  pseudocode needs a native disassembler (REA can drive Ghidra or Hopper). Hermes bytecode is
  inventoried, not decompiled.
- **Obfuscated apps** get original names back where the compilers wrote them into the bytecode:
  Kotlin data classes print their name and properties in `toString`, ViewBinding classes and
  fields follow their layout and view ids, Room adapters name their tables and columns, enum
  constants carry their names. Measured against Tusky's real R8 mapping: 1,660 names correct,
  12 wrong (0.993; [details](benchmarks/name-recovery/README.md)). Names R8 erased without such
  evidence stay obfuscated.
- **Obfuscated apps** can also recover inferred library names from declared artifact versions
  (0.945 precision on Tusky after the vetoes in `NameMapping`). Matching
  rejects oversized classes, synthetic helpers posing as ordinary classes, and known superclass
  mismatches. Primitive storage and constructor checks catch smaller R8 merges; weak signature
  votes must agree with code evidence. Incompatible final return types and ambiguous static
  facades veto misleading identities; array storage distinguishes boxed and primitive collections.
  Distinctive strings and framework calls can restore method
  names even when several library methods share a signature. Virtual method names are carried
  through app subclasses, interfaces and bridges;
  conflicting names or new member collisions are left unchanged. These names are **INFERRED**, not
  verified artifact identities, and their decompiled source remains in the project. The round trip
  undoes the supplied mappings, including overloaded methods. `--no-library-names` disables this;
  `--deobfuscate` uses JADX's generated names and disables this library-naming path.
  `benchmarks/library-matching/run.sh --suite` checks class and method naming against R8 ground
  truth for two workloads under four optimization settings; [measured results](benchmarks/library-matching/results.md)
  include the precision/coverage tradeoff and compile limitations.
- **Round-trip equivalence is not proof.** It is a dataflow comparison of recompiled bytecode; only
  V0 methods are proved with Z3.
- V0 proof scope: static `(I...)I` methods with no loops, heap, calls, exception handlers,
  `long`, floating point or switches. Everything else is reported, method by method.
- Without an Android runtime, the runtime check for an arbitrary APK compares the candidate with
  the original's *lifted* semantics. Fully independent execution of the original currently exists
  only in the fixture benchmark (hidden source). An ADB/emulator worker is planned for V1.
- The proposed 98% project-level target (section 27) has not been measured. No held-out corpus
  exists yet.
- Recovered source can be correct and still differ from the historical source. Comments, local
  names and erased code are not in the APK and are never invented.
