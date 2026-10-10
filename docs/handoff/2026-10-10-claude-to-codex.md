# Handoff: Claude → Codex, 2026-10-10

Branch `ccr-b00e80ab-1evg7q`. It contains your `codex/library-matching-accuracy` (`0dda597`), merged
at `7b4dd4a` without conflicts, plus the work below. Full suite: **182 tests, 0 failures** (174 before the desktop commit)
(1 skipped: `LibraryMatchingBenchmark`, run through `:app-recovery:libraryMatchingBenchmark`).
`python3 -m unittest discover -s scripts/tests`: 9 pass.

## Update, later the same day: desktop app and toolchain installer

The user asked for "a GUI so users find it easy on Windows". This commit adds it. The name and
library work below is unchanged.

| Piece | Where | State |
|---|---|---|
| **APK Project Studio** (Swing, FlatLaf 3.7.2, RSyntaxTextArea 3.6.3) | `modules/desktop` | Runs on Linux under Xvfb; Recover and Results pages checked by screenshot against the recorded Tusky output. **Never run on Windows** |
| `ToolchainInstaller`: pinned downloads (D8, JADX, ECJ, android.jar, Z3), SHA-256 before unpacking, zip-slip guard, mirror fallback, 3 retries (none after a hash mismatch), cancellable | `modules/orchestrator` | 5 tests against a local HTTP server; a real Linux install verified. Windows Z3 zip and hashes pinned but **never downloaded on Windows** |
| `apkproject setup [--tools DIR]` | `modules/cli` | Same installer from the command line; needs no bash or Python |
| `profiles/android-windows.json` | `profiles/` | `android-full` minus REA (no Node), `z3.exe` with the Windows hash |
| `Profiles.findJdk` | `modules/contracts` | Now finds `.exe` launchers, `JAVA_HOME` (by `release` `JAVA_VERSION`) and the usual Windows JDK folders |
| `packageApp` (jlink runtime with `jdk.compiler`, then jpackage; `-Ptype=app-image\|exe\|msi\|deb`) | `modules/desktop/build.gradle.kts` | Written, **not yet run**. jpackage only builds installers for the OS it runs on, so the `.exe`/`.msi` needs a Windows machine or CI |

How the app works:

- **Recover**: drop zone or file chooser, then an output folder (a fresh `<name>-recovered`) and
  five options (names, verify, repair, prove, libraries). The options are remembered with
  `java.util.prefs`.
- `FullRecovery` runs in process on a daemon thread with a `CancellationToken`. Its log lines map
  to a checklist of steps through `Stage.of` (see `DesktopModelTest`; keep those prefixes in sync
  if you reword `log(...)` calls in `FullRecovery`).
- Before starting, `Doctor.verify` checks the required tools. If any fail, the user is sent to
  **Tools**; Z3 is optional.
- **Results** reads only the files a recovery writes (`report.json`, `round-trip.json`,
  `compile-diagnostics.json`, `javac-relaxed.txt`, `recovered-names.json`, `api-contract.json`,
  `RECOVERY.md`), so it also opens projects made by the CLI. Its tabs:
  - Overview: six tiles and the layer table;
  - Source: package tree with a per-file verdict and a read-only Java viewer;
  - Backend: endpoint table, and a button that compiles and runs `BackendSkeleton.java` with the
    JDK's javac on a chosen port;
  - Names; Report.
- Evidence wording follows `AGENTS.md`. The names tile labels compiler-evidence names OBSERVED
  and structure-matched library names INFERRED. The mock server is called SYNTHESIZED wherever
  it appears.
- Data: `%LOCALAPPDATA%\apkproject` (Windows), `~/Library/Application Support/apkproject`,
  `$XDG_DATA_HOME/apkproject`. Tools folder, first match wins: the folder chosen on the Tools
  page, `APKPROJECT_TOOLS`, `.tools` of a source checkout (if it has `jadx-1.5.6`), `<data>/tools`.
- Profiles travel inside the jar and are written next to the tools on start, so their pins always
  match the build.

Still open for the desktop app (task order):

1. A GitHub Actions workflow on `windows-latest`: `apkproject setup`, `./gradlew test`, a smoke
   recovery of a fixture APK, then `:desktop:packageApp -Ptype=exe` (and `msi`) uploaded as
   artifacts. This is the first time any of the Windows paths would really run.
2. Icons are committed: `modules/desktop/packaging/icon.ico` (16–256 px) and `icon.png`
   (512 px). They were rendered from `Theme.appImage(size)`, so regenerate them from there if the
   logo changes.
3. Run `:desktop:packageApp -Ptype=app-image` on Linux once to check the jlink module list. The
   runtime must contain `bin/javac`; `Environment.jdkHome()` prefers the bundled runtime when it
   is Java 17 with javac.
4. Windows-specific risks to watch in that first run:
   - `z3.exe` needs `vcruntime140_1.dll`; `completeWindowsZ3` copies it from the JDK's `bin`.
   - Long paths under `%LOCALAPPDATA%` with deep JADX package trees.
   - Antivirus locking freshly unzipped files.

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
