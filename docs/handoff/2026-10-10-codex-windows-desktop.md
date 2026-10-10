# Handoff: native desktop GUI

This continues `2026-10-10-claude-to-codex.md` and incorporates Claude's newer desktop layout
from `03eb6ed`. The recovery, naming and Tusky measurements in that handoff remain applicable.
The PDF is background, not a replacement implementation specification. The current request
is to finish the native Windows desktop redesign with actual Windows screenshots and packaging.

Branch: `codex/windows-desktop-gui`; PR: https://github.com/kiroaida007-max/Covington-/pull/3
(targets `ccr-b00e80ab-1evg7q`, which includes the earlier recovery work).

## Interface

- Swing's system look and feel: WindowsLookAndFeel on Windows. System fonts, file/folder icons,
  native menu/toolbar/status bar, group boxes, dialogs and keyboard shortcuts. No FlatLaf dependency.
- `MainFrame` hosts an Explorer-style project tree with Summary, Source Code, Resources, Backend
  API, Recovered Names and Report, synchronized with native tabs. Recent projects and window/tree
  dimensions are remembered. The capture checks navigation both through tabs and through the tree.
- Source/resource folders have sortable file tables and a read-only syntax viewer. Source files
  can be filtered by name and verification status. Long table cells and summary values have
  tooltips. A compile check that was not run is explicitly shown as not run.
- Recovery retains file association, drag-and-drop, native file dialog, remembered options,
  progress stages, details log and cancellation, using the same FullRecovery engine. Tool
  preflight runs off the event thread. Invalid Windows input/output paths get a validation dialog.
- Tool hashing runs in the background; folder checks have generation guards and stale results
  are discarded. The install location is locked while an installation is running. The newer
  `53e77ec` download watchdog is retained and also closes a stalled response on cancellation;
  the regression check verifies no retry, installed file or partial download is left behind.
- Project close/replacement/disposal stops its mock server. A startup generation guard cleans
  up a server that finishes starting after its project has closed; pending startup keeps the JVM
  alive long enough to clean up, instead of forcing an immediate exit.
- Reports preserve OBSERVED/INFERRED/SYNTHESIZED and all verification distinctions. Generated
  mock code is not presented as original server source. No new APK-only Tusky compile claims.

## Verification and packaging

Local core validation: 184 tests, zero failures, one skipped opt-in library benchmark; nine
Python checks. The three MethodNamingIntegrationTest checks now normalize path separators and
CRLF output, fixing Windows-only fixture assumptions without weakening semantic assertions.

The packaged Linux app was exercised under Xvfb. The capture checks a real `broad.dex` recovery
report and source, filtering, project close/reopen/menu state, actual mock-server startup and
cleanup (including pending startup), and cancellation of an actual recovery without publishing
a project. This fixture intentionally contains a source compile error; it is not a rebuilt APK.
Linux uses Metal and is not evidence of the native Windows appearance. The bundled javac and
runtime passed compilation/execution probes and CLI doctor's javac/D8/Z3 smoke checks.

`.github/workflows/windows-desktop.yml` is the strict Windows pipeline; it supersedes the older
`windows.yml` preview workflow. It runs on Windows 2022, installs/checks pinned tools, runs the
full test suite, recovers the D8 fixture, builds an app-image, verifies its bundled compiler and
doctor, exercises that packaged launcher, captures fourteen native screens/dialogs, and builds
both EXE and MSI installers using WiX 3. No installer failure is ignored.

Artifacts: `windows-desktop-screenshots` (14 PNGs plus runtime facts) and
`windows-desktop-packages` (portable ZIP, EXE, MSI, test and recovery reports). Capture checks
the WindowsLookAndFeel class on Windows; `runtime.txt` records the actual OS, Java, font and
look-and-feel. Consult the PR's Windows desktop check for results on the current commit; earlier
successful Windows runs are not validation of later UI changes. Installers remain unsigned.

## Reproduce

```sh
./gradlew :desktop:installDist :desktop:test
# Real recovered project and input; capture uses isolated settings and app-data.
modules/desktop/build/install/apkproject-studio/bin/apkproject-studio --capture PROJECT_DIR IMAGE_DIR INPUT_FILE
./gradlew :desktop:packageApp -Ptype=app-image
./gradlew :desktop:generateIcons
```

`DesktopCapture` validates the packaged GUI. `:desktop:screenshots` is the separate developer
preview/demo tour inherited from Claude. Launcher PNG/ICO artwork is generated from `AppIcon`.
