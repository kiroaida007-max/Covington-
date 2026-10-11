# Desktop loading and lifecycle checks — 2026-10-11

Continues the UI/risk/privacy fixes on `codex/ui-risk-privacy-check` (PR #10),
based on Claude's PR #9 at `7f957045742cc71cbb0f72992147c458762f9240`.
Claude's branch and the separate backend/reliability PR stack remain untouched.

## Reproduced problems and changes

- Open, refresh and post-recovery display constructed `Project` on Swing's event thread,
  parsing every report and aggregating all method results before the window could respond.
  A background reader now handles loading. Close/open/refresh supersede older requests;
  a disposed reader cannot publish a delayed result. A failed load keeps the current project.
- Tree construction walked every source and resource file on the event thread. Directory
  metadata is now collected during background project loading. Audit/backend/report pages
  are constructed on first selection, rather than all being built for the initial Summary.
  Directory symlinks remain visible in the tree, without traversing their targets.
- Folder enumeration, file attributes and preview reads ran on Swing. They now use separate
  readers with one active and at most one waiting request each. Obsolete results cannot
  overwrite a newer folder/file selection. Reveal waits for listing before selecting a file.
- Selecting a folder left the previous file's preview and Open button active. Folder/empty
  selection now clears both, including the previous compiler-error tooltip.
- Preview size was checked against stale folder metadata, followed by an unbounded read.
  It now checks the current size and bounds the actual stream to 4 MiB plus one detection byte;
  oversized or malformed UTF-8 files have explicit preview messages.
- Round-trip aggregation retained the entire JSON string, all method objects and grouped
  method lists. It now decodes one method at a time, retaining four counters per source file.
  Synthetic/null-file exclusions, equivalence categories and relaxed diagnostics are preserved.
  A truncated/malformed report discards the entire counter set rather than verifying a prefix.
- Mock-server compilation read output before its timeout, and startup waited indefinitely
  for a line. Compilation now uses the existing bounded process worker (64 KiB per output
  stream, 120 s deadline); startup has a 30 s deadline and retains at most 4 KiB of its first
  line, draining later output without storing it. Its announcement must contain the chosen
  localhost URL. Stop/close cancels pending compilation/startup and terminates the process
  tree without waiting on Swing. A stale callback cannot revive a closed project's server.
  Temporary classes stay available for lazy loading until the server exits, then are deleted.

## Measurements and verification

`DesktopPerformanceTest` is opt-in with `APKPROJECT_PERFORMANCE=1`. It generates 10,000 files
in 2,000 folders and 200,000 equivalent method records. It reports measurements, without
fragile timing thresholds. Separate, isolated test JVMs on this Linux container measured:

| Measurement | Before | After |
| --- | ---: | ---: |
| Project constructor | 398 ms | 385 ms |
| Allocations on the loading thread | 134,242,528 bytes | 97,346,296 bytes |
| Initial view construction on Swing | 520 ms | 335 ms |

The after constructor includes directory indexing moved from view construction. Those are
single synthetic samples, affected by JVM startup and filesystem caches; they are not a
guarantee for every APK. The main responsiveness change is that project/file I/O now happens
off the event thread. JSON report objects other than method results still load in memory;
very large reports, first-time HTML rendering and table filtering need separate measurements.

Seven loading regressions cover 100 superseding requests, an interruption-resistant old read,
delayed completion after close, failure/retry, malformed reports, counter semantics, current-size
and UTF-8 limits, lazy page creation, reveal and clearing a file preview on folder selection.
Three real-JDK mock-server checks cover silent-startup timeout, cancellation during startup,
large log output, late class loading and stop. The existing eight desktop checks also pass.
The native screenshot tour waits for project/file readers before capturing, with a 30 s deadline.

Fresh local full suite: 221 cases, 218 passed, no failures/errors, three skips (the opt-in
desktop performance probe, optional R8 library benchmark, and absent two-release Tusky diff
fixture). All 18 enabled desktop checks passed. Both synthetic and real Tusky PrivacyMapTest
cases ran with zero skips; the app-recovery tests were forced fresh to avoid a cached fixture
skip. Nine Python checks passed. Windows CI results are recorded in PR #10 after completion.
No engine taint
rules, risk weights, endpoint matching or recovery claims were changed.
