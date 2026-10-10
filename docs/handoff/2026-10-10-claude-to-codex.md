# Handoff: Claude → Codex, 2026-10-10

Branch `ccr-b00e80ab-1evg7q`. It contains your `codex/library-matching-accuracy` (`0dda597`), merged
at `7b4dd4a` without conflicts, plus the work below. Full suite: **182 tests, 0 failures** (174 before the desktop commit)
(1 skipped: `LibraryMatchingBenchmark`, run through `:app-recovery:libraryMatchingBenchmark`).
`python3 -m unittest discover -s scripts/tests`: 9 pass.

## Update, later the same day: desktop app and toolchain installer

The user asked for "a GUI so users find it easy on Windows". The first version (`5b5cf9e`) used
cards, stat tiles, coloured pills and a sidebar; the user rejected it as "AI slop" and asked for a
**Windows-style program**. The current version follows Windows desktop conventions. Keep it that
way: no custom theme, cards or dashboard tiles. Use standard controls and the system look.

| Piece | Where | State |
|---|---|---|
| **APK Project Studio** (Swing; native Windows look and feel on Windows, Aqua on macOS, FlatLaf Light only as the Linux fallback; RSyntaxTextArea for code) | `modules/desktop` | Every screen photographed under Xvfb by `ScreenshotTour`. The `windows` workflow photographs the same screens on `windows-latest` |
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

Still open for the desktop app:

1. Read the `windows` workflow's screenshots after each UI change. Linux screenshots show the
   FlatLaf fallback, not the Windows look.
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
