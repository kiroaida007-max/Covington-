# Handoff: Claude → Codex, 2026-10-10

Branch `ccr-b00e80ab-1evg7q`. It contains your `codex/library-matching-accuracy` (`0dda597`), merged
at `7b4dd4a` without conflicts, plus the work below. Full suite: **198 tests, 0 failures** (174 before the desktop work)
(1 skipped: `LibraryMatchingBenchmark`, run through `:app-recovery:libraryMatchingBenchmark`).
`python3 -m unittest discover -s scripts/tests`: 9 pass.

## Update: screen -> code -> server and database map (`apkproject screens`)

The user asked for a screen map covering networking and the database. New in `modules/app-recovery`:

- `AppGraph` (in `ScreenMap.kt`): a static call graph of every method with code.
  - Rapid type analysis: a virtual call follows an override only once the walk created its class
    (calls waiting for one are kept and released then). One implementation in the app (a Room DAO)
    is followed directly. More than 32 overrides is a generic callback and is not followed.
  - Dispatch classes: an int field set in the constructor and switched on (R8 merged lambda groups,
    Hilt `SwitchingProvider`). Each switching method is sliced per case; a class created with a
    constant id runs only that case. An id the switch does not list takes the default branch only
    when it is the class's one leftover id. Created with an unknown id: constructors only.
  - Provider fields: a field that only ever holds a dispatch instance with a known case (wrapped
    or not by `DoubleCheck.provider`) carries that case to `field.get()` calls. This is what makes
    Hilt singletons (AccountManager -> AppDatabase -> DAOs) reachable.
  - Created objects run their constructors and callbacks only (methods overriding a supertype's;
    outside the DEX a kept name counts, except `java.lang.Object`, whose methods are listed).
  - Strings are tied to the call that first uses them and its argument slot, so a preference key
    is told from its default value.
- `ScreenMap.analyze(dex, manifestXml)`: a breadth-first walk per manifest component and worker,
  seeded with the component's methods and its app-package base classes. Sinks: Retrofit endpoint
  methods (`BytecodeApi.endpointMethods`, new), SQL by shape (not first word), URL hosts (XML
  namespace hosts skipped), framework network calls, SQLiteDatabase / ContentResolver calls,
  SharedPreferences keys and files. Each reach keeps its shortest call path and how many
  components share it; targets shared by 2/3 of the components go to `common`.
- `ScreenMapMarkdown`; CLI `apkproject screens INPUT --out DIR`; `FullRecovery` step 9 writes
  `audit/screen-map.json` + `.md` and a "Screen map" layer.
- Desktop: **Screens & Data** page (`ScreensView`): screens list on top (Event Viewer style), tabs
  Network / Database / Stored Settings / Opens and Starts below, the call path at the bottom; also
  By Endpoint and By Table tabs. The tour shot is `05b-screens`.
- Bug fixed on the way: an APK without `resources.arsc` had its manifest filed by JADX under
  `app/src/main/resources/`, so `decompile` saw no manifest (no screens, no package). `FullRecovery`
  now also looks there and otherwise decodes the binary manifest itself (`AppInput.decodedManifest`).
  The tour's demo APK now has a real binary manifest (`TourManifest`) and two activities + a service.
- Bug fixed: `DiffFrame` was larger than a 1366x768 screen; it now fits the usable screen area.

Tusky 32.2: 49 components, 93/109 distinct endpoints (method + path; 112 functions) and 22/23 tables
reached, 16 s. The backend-contract benchmark is unchanged after `endpointMethods` (112/112, 255/255). Before the dispatch
and RTA rules every screen reached all 109 endpoints; LicenseActivity now reaches none of its own
(its leftovers are in `common`). Spot checks: ComposeActivity `POST api/v1/statuses` + media +
emojis + drafts; EditProfileActivity `update_credentials`; LoginActivity `apps`, `oauth/token` and
its `clientId`/`domain` settings. Known over-reach: a lambda created is treated as code that runs,
so LoginActivity (which builds the default tabs) reaches the timelines.

Tests: `ScreenMapTest` (2): a synthetic app with a base activity, a ViewModel, a switching DI
provider in a field, a Retrofit interface, SQL, settings and a service; and SQL shape detection.

## Update: update diff across obfuscation (`apkproject diff`)

The user asked for stronger, more useful tools and picked an **update diff** first: given two
builds of an app, say what really changed. New in `modules/app-recovery`:

- `CodeMatcher` + `CodeIndex`: pairs classes and methods of two builds across R8 renames.
  - Names are trusted only when evidently not obfuscated (`stableClassName`, `stableMember`).
  - Otherwise classes pair by code with names factored out: opcodes, literals, strings,
    framework members, and the shape of references. App classes count as whatever they were
    paired with so far, and rounds repeat until nothing new pairs. Then mutual-best weighted
    token overlap pairs the rest, and identical-code rounds resume.
  - R8 artifacts found on Tusky, each normalized:
    - inlined resource ids are written as resource names;
    - the app's own version name inside strings is ignored;
    - static calls name the callee, not the class; static methods are paired on their own,
      because R8 merges static helpers into shared classes and reshuffles them;
    - parameter order is ignored for app methods (proto normalization sorts parameters by
      obfuscated type name);
    - class ids passed to merged lambda groups are ignored;
    - field-name strings for reflective updaters are ignored.
  - Method statuses: UNCHANGED, SAME_BEHAVIOUR (`DataflowShape` under neutral names), MOVED
    (static R8 moved), REFERENCES_ONLY (same instructions, consistently different class refs),
    MODIFIED, ADDED, REMOVED.
- `AppDiff`: the report. It covers the signer (`ApkSigning`: v1/v2/v3/v3.1 certs and the v3
  rotation lineage, no verification), identity and SDK levels, permissions (sensitive first),
  components and exported surface, deep links, manifest flags, hosts, endpoints and models,
  SDKs, library versions, native libraries, code shipped in `assets/`, capabilities
  (`Capabilities`), risk and hardening (`FrontendAudit` on manifest + DEX), and code. Every
  change carries an attention level (HIGH/MEDIUM/LOW/INFO) and an evidence level. `DiffMarkdown`
  renders it.
- `BinaryXml`: decodes the binary `AndroidManifest.xml` in-process (no JADX), in the text form
  `FrontendAudit` already parses.
- `Capabilities`: sensitive platform capabilities from framework member refs (location, contacts,
  SMS, microphone, run-time code loading, shell, …), with whether the needed permission is
  requested (Tusky references location through AppCompat's night-mode helper but does not request
  it: shown as "cannot run"). Also a small SDK catalog (package prefixes + endpoint hosts).
- CLI `apkproject diff OLD NEW --out DIR`: writes `README.md`, `diff.json` and `code-map.tsv`.
- Desktop:
  - **Tools > Compare Versions…** (Ctrl+D, also on the toolbar and start page; dropping two APKs
    works too) opens `CompareDialog`.
  - The result opens in `DiffFrame`, with a versions table, then tabs for Changes, Code,
    Capabilities and Report. File > Save Report As… writes the three files.
  - Screenshots 15–19 are in the tour; `APKPROJECT_TOUR_DIFF="old;new"` adds real-pair shots.

Ground truth and numbers (`benchmarks/update-diff/README.md`):

- Rebuilt Tusky 32.1 (`5d566769`) with `benchmarks/update-diff/rebuild-tusky.sh`. Its DEX is
  byte-identical to F-Droid's 141, so with the 32.2 mapping both sides have true name maps
  (container-local: `/home/user/tusky-truth/mapping-141.txt` and `mapping.txt`; APKs in
  `.tools/dl/tusky/`).
- Results on 32.1 → 32.2:
  - 7,956 of 7,962 pairs correct (precision 0.9992), 7,956 of 7,959 recall;
  - 163 classes renamed by R8, and 167 names reused for other classes, which a by-name diff gets wrong;
  - 21 of 22 edited source files reported, attributing changes through R8's inline frames; the
    miss is a comment-only edit;
  - 36 of 88 modified classes hold edited code; the rest are compiler-made and labelled where
    recognizable;
  - the API change (5 endpoints gained `min_id`) is reported exactly;
  - 13 s.
- How each normalization moved "modified" from 296 to 88 is tabled there.

Tests: `AppDiffTest` (4).

- R8 renaming fixture: two app versions built with real R8 (renaming on, no shrinking or
  optimization). Every pair is checked against both R8 maps, and only the real edits may be
  reported.
- The real Tusky pair (skips without the APKs).
- Capabilities and permissions; name stability rules.

Open, in the order I would take it:

1. Modified-class precision (36/88). Remaining noise: R8 instance-class merging (horizontal merges
   without a class-id dispatch), Hilt/Dagger components, code changed by what was inlined into it.
   A method-level "inlined callee changed" explanation would need inline detection without a map.
2. Measure on a larger update (33.0, versionCode 144, has library upgrades) and on non-R8 apps.
3. Feed `Capabilities` into `audit` as a privacy section (it is computed for diffs only today).
4. App Bundles: `.aab` manifests are protobuf (aapt2 proto), not binary XML, so manifest diffs are
   skipped for them (noted in the report).

## Update: front-end recovery + UI/security audit with a risk score

The user asked for a tool that recovers the app's front end (which UI language built it) and
flags UI defects, vulnerabilities and risks with a score, on a dedicated screen. Added
`FrontendAudit` (`modules/app-recovery`): a defensive static review — it never runs the app and
describes issues to fix, not exploit.

- **UI toolkit + language**, from `AppAnatomy`: Android Views (XML) / Compose / Flutter / React
  Native / Unity / Xamarin / hybrid WebView, each with how far the front end is recoverable.
- **Screens**: activities, aliases, fragments and layouts (with exported / launcher / deep-link
  flags and widget counts), from the decoded manifest and DEX.
- **Findings + risk score (0–100, grade A–F)**:
  - manifest (OBSERVED): debuggable, allowBackup, cleartext, testOnly, exported
    activity/service/receiver/provider without a permission, provider grantUriPermissions,
    unverified http(s) deep links, low minSdk, dangerous permissions;
  - WebView API misuse (INFERRED, dex method-name scan): addJavascriptInterface (critical under
    targetSdk < 17), file-URL cross-origin, web-contents debugging, onReceivedSslError;
  - secrets in resources (OBSERVED): Google/AWS keys, Slack tokens, private keys, JWTs, named
    secrets;
  - layout lint (OBSERVED): missing contentDescription, hard-coded text, px dimensions, EditText
    without inputType, sub-48dp touch targets.
  Each finding has a severity, evidence level, fix, and an OWASP MASVS / CWE reference. The score
  weights findings (critical 25, high 10, medium 4, low 1), capped at 100; any critical → grade F.

Wiring:
- `FullRecovery` step 8 writes `audit/ui-audit.json` + `audit/README.md`, adds `frontend` to
  `report.json` and a "Front-end audit" layer row.
- CLI `apkproject audit INPUT --out DIR`: fast standalone (JADX `--no-src` for resources + manifest
  only, then the audit). `JadxDecompiler.export` gained `noSource`.
- Desktop: a new **UI & Risk** screen (`ProjectView.Page.UI_RISK`) — risk banner, severity counts,
  toolkit, a colour-coded findings table (detail + fix in the status bar) and a screens table. It
  reads `report.frontend`, so no extra file.
- Tests: `FrontendAuditTest` (3) — manifest/layout/secret findings + scoring, a clean grade-A
  manifest, and WebView misuse in compiled bytecode.

Measured on Tusky 32.2: Android Views / Kotlin, 31 activities / 14 fragments / 263 layouts,
risk 20/100 grade B (0 critical, 0 high, 4 medium, 4 low, 1 info) in ~16 s via `audit`.

### Resilience / hardening (works on "solid" apps)

The user then asked it to "work even with solid apps" (packed/obfuscated/RASP-protected).
`HardeningAudit` adds a MASVS-RESILIENCE picture, shown on the UI & Risk screen and in the audit:

- **obfuscation level** (NONE/LIGHT/PARTIAL/HEAVY) from the share of app classes with 1–2 char
  names, plus the tool (R8 full/compat) from `CompilerMarker`;
- **packers / protectors** (OBSERVED, strong signals): native lib basenames, asset/entry
  filenames and loader/class descriptors for Jiagu/360, Bangcle/SecNeo, Tencent Legu, Ijiami,
  Baidu, Alibaba, NQ, DexProtector, APKProtect, DexGuard, Promon, Appdome, Arxan, Virbox;
- **anti-analysis / integrity defenses** (INFERRED): root / emulator / debugger / Frida-Xposed
  detection, TLS pinning (okhttp3.CertificatePinner / literal sha256 pins), tamper/licensing,
  Play Integrity / SafetyNet.

Overall level NONE/BASIC/HARDENED. `recoveryImpact` states honestly when a packer means the real
DEX loads at runtime (so this is a loader-stub report) and when heavy obfuscation means names are
mostly unrecoverable — so the tool still returns a useful report instead of failing.

**Precision matters more than recall here.** The first version false-positived badly on Tusky
("xposed" inside "ExposedDropdownMenu", "frida" inside "blackfriday", pinning from any "sha256/"
string, tamper from "invalid signature"). Detection now uses boundary-/path-qualified tokens
(`hasToken`), full-pin regex, and distinctive method/type signals only. On Tusky it correctly
reports HEAVY R8 obfuscation, no packer, no defenses (level BASIC). `HardeningAuditTest` proves a
packed+RASP app is detected and that the benign look-alikes do not match.

Open: layout lint counts bundled-library layouts too (the `where` list shows which files);
Compose screens are listed via code, not enumerated as routes; secret regexes are the common
providers only; string-encryption detection (DexGuard) is by vendor signature, not by analysis.

## Update: backend contract measured against Tusky's source, and fixed

The user asked to "find the bugs in the backend to deliver accurate results". The backend
contract (`BytecodeApi`, `ContractRenderer`) is now scored against Tusky's Retrofit interfaces
and Moshi models (`benchmarks/backend-contract/`).

- **Parameters:** 250 → 255 of 255.
- **Field types:** 348 → 377 of 378.
- **Enum values:** 0 → 17 of 18.
- **Property names:** 234 → 378 of 378.
- **Nullability:** none → 378 of 378.
- **OpenAPI:** invalid → valid.
- **Mock routes valid against the schema:** 46 of 47.

The README there explains each bug and its fix. In short:
- types now come from the Moshi adapter's own bytecode: its constructor's `moshi.adapter` calls,
  plus `toJson`, followed into R8-outlined helpers. Constructor order was wrong because R8
  reorders parameters;
- R8-renamed `@Json` is recognised by its members, which gives enum wire values;
- two CFG may-dataflows: one for the null checks inside reader loops, one that names unnamed
  multipart parts at their call sites (`nameSource: call-site`);
- the renderer was rewritten (collisions, multipart, maps, `oneOf`, unique operationIds,
  `nullable`);
- the mock fills non-null fields.

`apkproject contract` now also writes the mock server. Tests: `ContractAccuracyTest` (4)
rebuilds each bytecode shape small.

Open: `Notification.type` (a sealed class behind a hand-written adapter) is the only miss. Gson
models without annotations are still field names only.

## Update, later the same day: desktop app and toolchain installer

The user asked for "a GUI so users find it easy on Windows". The first version (`5b5cf9e`) used
cards, stat tiles, coloured pills and a sidebar; the user rejected it as "AI slop" and asked for a
**Windows-style program**. The current version follows Windows desktop conventions. Keep it that
way: no custom theme, cards or dashboard tiles. Use standard controls and the system look.

| Piece | Where | State |
|---|---|---|
| **APK Project Studio** (Swing; native Windows look and feel on Windows, Aqua on macOS, FlatLaf Light only as the Linux fallback; RSyntaxTextArea for code) | `modules/desktop` | Every screen photographed under Xvfb by `ScreenshotTour`, and on `windows-latest` by the `windows` workflow, in the native look (run 38067678968, green). The demo recovery there ran end to end with the `android-windows` profile, `z3.exe` included |
| `ToolchainInstaller`: pinned downloads (D8, JADX, ECJ, android.jar, Z3), SHA-256 before unpacking, zip-slip guard, mirror fallback, 3 retries (none after a hash mismatch), cancellable | `modules/orchestrator` | 5 tests against a local HTTP server; a real Linux install verified; the `windows` workflow runs it through `apkproject setup` |
| `apkproject setup [--tools DIR]` | `modules/cli` | Same installer from the command line; needs no bash or Python |
| `profiles/android-windows.json` | `profiles/` | `android-full` minus REA (no Node), `z3.exe` with the Windows hash |
| `Profiles.findJdk` | `modules/contracts` | Finds `.exe` launchers, `JAVA_HOME` (by `release` `JAVA_VERSION`) and the usual Windows JDK folders |
| `packageApp` (jlink runtime with `jdk.compiler`, then jpackage; `-Ptype=app-image\|exe\|msi\|deb`) | `modules/desktop/build.gradle.kts` | `app-image` built on Linux: 127 MB, runtime has `bin/javac`. The `.exe` needs WiX 3; the workflow installs it with Chocolatey (`continue-on-error`) |
| `ScreenshotTour` (test sources), `:desktop:screenshots` | `modules/desktop` | Compiles a two-class demo app, recovers it with the installed tools, then saves `01-start` … `13-evidence` PNGs. Use `-PscreenshotsJava=21` where the JDK 17 is headless (this container) |
| `.github/workflows/windows.yml` | | Build, `apkproject setup`, `doctor --profile android-windows`, tests, screenshots, portable zip, installer; artifacts `windows-screenshots` and `windows-app` |

How the app is organised (files in `dev.apkproject.desktop`):

- `MainFrame`: the main window.
  - Menus: File (Recover APK… Ctrl+O, Open Project… Ctrl+Shift+O, Recent Projects, Show in
    Explorer, Close Project, Exit), View (Ctrl+1…6 for the pages, F5 Refresh), Tools, Help
    (Evidence Levels, What Cannot Be Recovered, About).
  - A text toolbar, a start page with the recent projects, and drag and drop of APKs and project
    folders.
  - A status bar with the two headline counts.
  - The window position, tree width and recent projects are kept in `java.util.prefs` (the
    registry on Windows).
- `ProjectView`: an Explorer/MMC-style split.
  - Left, a tree: Summary, Source Code and Resources (the real folders), Backend API,
    Recovered Names, Report.
  - Source folders on the right: a details list (Name, Status, Methods, Matching, Compile
    errors, Size) with the shell's own file icons, and the selected file in a read-only viewer
    below it.
  - Summary: group boxes of label/value rows, plus the layer table.
  - Backend: mock server group (Start/Stop/Open in Browser), Find box, endpoint table.
- `RecoverDialog`: a modal dialog with the app (the system Open dialog), the output folder and an
  Options group box; Recover/Cancel, with Enter and Esc. It refuses a non-empty output folder,
  since `FullRecovery` would.
- `RecoveryDialog`: a progress dialog like Windows' file-copy dialog.
  - It shows "Step n of 9: …", a bar, the latest log line and the elapsed time, with the full
    log under "More details".
  - `FullRecovery` runs in process with a `CancellationToken`. Its log lines map to steps through
    `Stage.of` (`DesktopModelTest`); keep the prefixes in sync if you reword `log(...)` calls.
  - `Doctor.verify` runs first. If a required tool fails, the user is offered the Tools
    dialog; Z3 is optional.
- `ToolsDialog` (table of tools, folder, Install/Refresh/Close with a progress bar), `HelpDialogs`.
- `Project` reads only the files a recovery writes, so the app also opens projects made by the CLI.
- Evidence wording follows `AGENTS.md`:
  - names from compiler evidence are OBSERVED;
  - structure-matched library names are INFERRED;
  - the mock server is SYNTHESIZED wherever it appears.
- Data folders: `%LOCALAPPDATA%\apkproject` (Windows), `~/Library/Application Support/apkproject`,
  `$XDG_DATA_HOME/apkproject`.
- Tools folder, first match wins: the folder chosen in the Tools dialog, `APKPROJECT_TOOLS`,
  `.tools` of a source checkout (if it has `jadx-1.5.6`), `<data>/tools`.
- Profiles travel inside the jar and are written next to the tools on start, so their pins always
  match the build.
- `AppIcon` draws the program icon (a page with green `</>`). `packaging/icon.ico` (16–256 px) and
  `icon.png` (512 px) are rendered from it; regenerate them if it changes.

State on Windows (`53e77ec`, run 38067678968): every step green.
- Tools: `apkproject setup` installed them in 6 s.
- `doctor` passed with the Windows profile, and the tests passed.
- All 13 screenshots were taken.
- Both the portable zip and the `.exe` installer were built (artifact `windows-app`, 100 MB).

Still open for the desktop app:

1. Read the `windows` workflow's screenshots after each UI change. Linux screenshots show the
   FlatLaf fallback, not the Windows look. Download the artifact with the GitHub API; the
   `actions/artifacts/{id}/zip` URL redirects to a signed blob URL that curl can fetch.
2. First Windows CI run: `apkproject setup` hung for 14 minutes. The Android platform zip from
   dl.google.com stopped sending at 26%, and the request timeout does not cover the body. The
   installer now uses HTTP/1.1 and a watchdog that aborts after 60 s without data (then retry,
   then `dl-ssl.google.com`). `ToolchainInstallerTest` reproduces the stall.
3. Windows-specific risks to watch:
   - `z3.exe` needs `vcruntime140_1.dll`; `completeWindowsZ3` copies it from the JDK's `bin`.
   - Long paths under `%LOCALAPPDATA%` with deep JADX package trees.
   - Antivirus locking freshly unzipped files.
4. Code signing: the installer and the launcher are unsigned, so SmartScreen will warn on first run.

## What I checked on your branch first

- Your suite: 162 pass + the benchmark skip; your 9 Python tests pass.
- `benchmarks/library-matching/run.sh --suite` with `APKPROJECT_LIBMATCH_ENFORCE=1`: all eight
  configurations reproduce your recorded counts exactly (only timings differ).
- First full Tusky `decompile` of your branch (your notes said none had completed):
  13,084 → 12,253 ECJ errors and 18,623 → 18,785 round-trip-equivalent methods against `ac84fd2`.
  It also fixed my earlier wrong identities (`ComposeActivity` named after an AndroidX class,
  `La/a;` as `TypefaceCompatBaseImpl`).

## What changed after your branch

| Commit | Change |
|---|---|
| `df1c69f` | `RelaxedJavac` (resource, built once per JDK): javac in process, dropping only checked-exception diagnostics. Files whose ECJ errors are all checked-exception kinds (ECJ problem ids now parsed per file into `FileDiagnostics.kinds`) join the javac overlay; repair compiles the same way. On Tusky: 392 files |
| `b71ed60`, `1664d57` | Name hygiene never moves packages; it renames the obscuring type to a name no class has (`b0.k1` → `k1_b0`). See "JADX pitfall" below |
| `1664d57` | javac runs with `-XDshould-stop.ifError=FLOW` and 8 overlay attempts, else flow errors surface one file per attempt |
| `2e18f38` | V0 cross-check finds methods of renamed classes through the name mapping: Tusky 13 → 27 of 29 proved |
| `a1f2183` | Mock server: unknown-element lists are `[]`, scalars stay typed in deep nesting |
| `ff3edf4` | README table of three full Tusky runs |
| this commit | `NameOracle`, `ResourceTable`, `apkproject names`, library-match vetoes in `NameMapping`, `benchmarks/name-recovery/`, restore-script fix |

Tusky full `decompile` at `2e18f38` (ECJ, android-35): 5,063 / 7,471 files compile, 10,122 errors,
27 / 29 V0 methods proved, 19,189 / 35,212 methods round-trip equivalent, 392 files compiled with
relaxed checked exceptions. Runtime ~10 min on 4 cores.

## New: ground truth for a real app

The Tusky release rebuilt with your `scripts/restore-tusky.py --variant release` is
**byte-identical** to the F-Droid DEX, so its R8 `mapping.txt` is the true name map for that APK.
`benchmarks/name-recovery/evaluate.py` scores recovered names against it. The README there has
the setup, including the rate-limit/mirror caveat.

- **`NameOracle`** (compiler-written evidence: data-class `toString` + `hashCode`/`equals`,
  ViewBinding from layout and view ids, Room insert adapters, enum constants, Moshi adapters,
  getters in data classes): **1,660 correct, 12 wrong (0.993)** on Tusky. The README there shows
  how each tightening moved precision (0.934 → 0.983 → 0.993).
- **`LibraryMatcher` on Tusky: 1,695 correct, 127 wrong (0.930)**, against 1.000 on the fixture
  suite. `NameMapping.compute` now drops a match when the oracle names the class differently, when
  it renames a kept (unminified) class name to another simple name, or when the target is a D8
  synthetic lambda. Replayed on the recorded matches: 1,676 correct, 97 wrong (0.945).
  **Not yet measured in a full decompile.**

Remaining library errors by kind: 52 another library's class (often from artifacts not among
the declared libraries), 23 another class of the same package, 13 a sibling nested class,
12 an app class named as a library class (27 synthetic lambdas are now vetoed). Reproduce with
the snippet in `benchmarks/name-recovery/README.md` against
`recovery/library-matches.json`. This is the clearest next target for your matcher work.

## JADX 1.5.6 pitfall (cost two regressions; read before renaming anything)

JADX checks same-package simple-name collisions **against original names**. Whenever a mapping
gives a class a simple name equal to another class used in the same file, JADX may write
`import q.N;` and use bare `N` for both. That happens if the same-package class was renamed,
or if its package was moved. Measured on Tusky:

- moving packages (`k1` → `k1_`): 922 files, 3,100 errors;
- one new name per root (`k1_` twice): +6,600 errors.

Rules now in `NameMapping`:

- never move packages;
- every renamed type gets a name unique among all class simple names the DEX refers to, framework
  types included (`Notification` next to `android.app.Notification` becomes `Notification_ha`).

`MethodNamingIntegrationTest` reproduces both failures; each test fails on the old behaviour.

## Open work, in the order I would take it

1. **Measure the vetoes and the oracle in a full Tusky decompile** (compile errors, round trip,
   proofs) and record them next to the README table.
2. **Library-matching precision on the real app** (97 wrong, categories above). Ideas: abstain
   when the candidate's artifact was not fetched but its package is ambiguous; use oracle class
   names as a positive corroboration signal, not only a veto; stricter margins for interfaces with
   few features (`LazyListScope` ← `MediaSourceEventListener` scored 1.00/1.00).
3. **Buildable project** (user request: "almost full build"). Android SDK now installs in this
   container (`/home/user/android-sdk`, platform 36, build-tools 35). Options:
   - ship `RelaxedJavac` as a javac plugin in the delivered Gradle build, so the 392 Kotlin-origin
     files compile there too;
   - opt-in hybrid mode where classes without compiling source come from the original bytecode
     (dex2jar), clearly labelled; it must never be presented as recovered source.
4. **Kotlin recovery** (user request). Tusky's R8 full mode stripped `kotlin.Metadata`,
   `DebugMetadata`, `SourceFile` and parameter-name null checks, so declarations must come from
   evidence. The oracle already knows data classes, enums and bindings. Plan: emit Kotlin for
   those and simple bodies, and accept a Kotlin file only if kotlinc + D8 round-trips it.
5. **Repair regression to check**: the last Tusky runs kept 0 repair edits (your branch kept 3).
   Stopped after 8 barren rounds with 2,015 candidates untried; may be candidate order or the
   relaxed javac path in `SourceRepair.compile`.
6. **Known oracle errors**:
   - typos or old names inside library `toString` labels;
   - an enum whose own constant field R8 removed (the first store is then an alias);
   - one subclass printing its parent's name.
7. **More evidence for apps built less aggressively**: `kotlin.Metadata`, `DebugMetadata`,
   `Intrinsics.checkNotNullParameter` names, Moshi `missingProperty("name", "json_name")`,
   `SourceFile` attributes. None of these survive in Tusky but they are common elsewhere.

## Container notes (this cloud environment)

- Maven Central (`repo1`) and the Gradle plugin portal answer 429 from this machine.
  - `~/.gradle/init.d/central-mirror.init.gradle.kts` points Gradle at Google's Central mirror.
  - `MavenRepository` skips a throttled host until its Retry-After.
  - The Tusky rebuild used a separate `GRADLE_USER_HOME=/home/user/gradle-tusky` with the mirror
    and `org.gradle.dependency.verification=lenient`. Only for that measurement: the DEX
    comparison establishes the mapping's validity.
- `JAVA_TOOL_OPTIONS` makes every JVM print a "Picked up …" line first; `restore-tusky.py` now
  reads the `javac N` line wherever it is.
- `pkill -f PATTERN` or `ps | grep PATTERN | kill` can match your own shell's command line and
  kill it (exit 144). Find PIDs with `ps -eo pid,comm,args | awk '$2=="java" && /Pattern/'`.
- Ground-truth mapping is at `/home/user/tusky-truth/mapping.txt`; Tusky APK at
  `.tools/dl/tusky/tusky-142.apk`. Both are container-local, not committed.
