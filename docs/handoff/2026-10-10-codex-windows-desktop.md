# Handoff: native desktop GUI

This continues `2026-10-10-claude-to-codex.md`, from Claude's desktop commit `5b5cf9e`.
The recovery, naming and Tusky measurements in that handoff remain applicable. The PDF is
background, not a replacement implementation specification. The current user request is to
finish the native Windows desktop redesign and its actual Windows screenshots/packaging.

Branch: `codex/windows-desktop-gui`; PR: https://github.com/kiroaida007-max/Covington-/pull/3
(targets `ccr-b00e80ab-1evg7q`, which already includes the earlier Codex recovery work).

## Interface

- Swing's system look and feel; WindowsLookAndFeel on Windows. FlatLaf and the custom dark
  theme, sidebar, rounded cards, colored pills, gradient mark and metric tiles are removed.
- File/Recovery/View/Help menus, shared toolbar actions, keyboard shortcuts, native tabs,
  group boxes and dialogs, status bar with activity indicator. System font and file/folder icons.
- Recovery retains drag-and-drop, native file dialog, output chooser, remembered options,
  progress stages, details log, cancellation and the same FullRecovery engine. Preflight runs
  off the event thread. Input/options are locked during recovery and restored afterwards.
- Project retains Overview/Source/Backend/Names/Report. Overview is a normal verification
  table and layer table. Source retains its package tree and read-only syntax viewer, adds an
  unchecked filter and a filtered count, and makes long file paths/statuses readable.
- Reports preserve OBSERVED/INFERRED/SYNTHESIZED and other evidence distinctions. Missing
  reports stay unavailable; generated mock code is not claimed as original server source.
- Tool hashing runs in the background; folder checks have a generation guard. Installation
  has cancellation. Project close/reopen discards the old view. Mock-server startup also has
  a generation guard so it cannot attach to a closed/replaced project.

## Checks and packaging

Local Linux validation: 182 tests, zero failures, one skipped opt-in library benchmark;
9 Python checks. Pinned tools installed by the Java installer. Real `broad.dex` recovery
produced the GUI input; this fixture deliberately exercises a source compile failure and is
not described as a successfully rebuilt APK.

A Linux app-image was built and launched with its bundled Java 17. Its `javac` compiled and
executed a Java probe. CLI doctor using that runtime passed javac/D8 compilation and Z3
solver smoke checks. GUI capture under Xvfb exercised report values, source filtering and
source contents, project close/reopen, mock-server start/close including pending compilation, menu state, recovery start/cancel and the absence of a
published cancelled output. Linux images use Metal; they are not Windows screenshots.

`.github/workflows/windows-desktop.yml` runs on a Windows 2022 host. It builds the app,
installs/checks the Windows tools, runs tests, recovers the real D8 fixture, builds an app-image,
checks the bundled compiler/runtime and doctor, captures native Windows screens, and builds
EXE/MSI installers using WiX 3. See the PR's **Windows desktop** check for the run result.
The first Windows run exposed slash/CRLF assumptions in MethodNamingIntegrationTest; its
fixture source keys and process output are now normalized without weakening the checks.
Artifacts: `windows-desktop-screenshots` (10 PNGs plus runtime facts),
`windows-desktop-packages` (portable ZIP, EXE, MSI, reports). `runtime.txt` records the actual
look-and-feel class, OS, font and bundled runtime used for the images.

Reproduce locally (a display is required):

```sh
./gradlew :desktop:installDist :desktop:test
# Use a real recovered project, optionally an input file to exercise recovery cancellation.
modules/desktop/build/install/apkproject-studio/bin/apkproject-studio --capture PROJECT_DIR IMAGE_DIR [INPUT_FILE]
./gradlew :desktop:packageApp -Ptype=app-image
./gradlew :desktop:generateIcons
```

Packaging artwork is generated from Theme.appImage; PNG and multi-size ICO are committed.
The installed app uses user settings; capture uses isolated settings and app-data.
No APK-only Tusky accuracy or compilation improvements are claimed by this GUI change.
