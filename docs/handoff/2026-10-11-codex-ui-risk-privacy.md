# UI, risk and privacy checks for Claude — 2026-10-11

Base: Claude's `ccr-4d79fa37-cuhjzy` at `7f957045742cc71cbb0f72992147c458762f9240`
(PR #9). Fixes are on a separate `codex/ui-risk-privacy-check` branch. Claude's branch is
untouched. These changes do not merge the separate Codex backend/reliability PR stack.

## Reproduced gaps and fixes

- Windows artifact `windows-screenshots` from run `38153741000` showed the UI & Risk form
  extending beyond its viewport: fixed-width HTML labels and the findings table forced it wide.
  The form now follows the viewport width; text wraps and the findings table has its own
  horizontal scrollbar. Summary values wrap too. The score is explicitly a static score.
- Full finding detail and the proposed fix were available only in the status bar, where they
  could be clipped. A selectable, wrapped detail pane now includes the complete detail, fix,
  location, evidence and reference. Sorted selection uses model indices.
- The Privacy page showed the time budget but hid bounded summaries and all the report's
  coverage notes. Both stopping conditions now appear next to the results, with an Analysis
  Notes tab. The absence of a flow is explicitly not proof that data stays on the device.
  Unknown manifest permissions are labelled unknown, and negative cells say Not observed.
  Data Safety is described as static clues requiring runtime and policy review, including
  sharing exceptions, rather than a ready-to-submit declaration.
- With skipped/failed OSV lookup, the empty Vulnerable Components tab still said
  "No component has a known vulnerability." Its text now distinguishes those states and
  scopes a successful empty lookup to queried packages. Native/unidentified components are
  outside that lookup.
- Desktop recovery always inherited `FullRecoveryOptions.vulnerabilities = true`, without
  a visible control. Recover APK now has a persistent OSV checkbox, off for a new setting,
  explaining that Maven package names and versions are sent to OSV.dev, and that the APK and
  recovered source are not uploaded. Turning it off disables that lookup only; tool/Maven
  download options still have their existing controls. CLI defaults are unchanged.
- UI & Risk reads `audit/ui-audit.json`, falling back to the embedded frontend report for
  older projects, so it can show the audit even if the recovery report is unavailable.

## Checks

The first two AuditViewsTest cases failed against the unchanged base and passed with the
fixes. Added checks cover bounded/truncated privacy coverage, unknown permissions, skipped
and failed lookup, the actual recovery option mapping, viewport width at 800x700, full
finding detail after sorting, explicit negative Data Safety cells, and a missing flow path.
The screenshot tour now also captures Data Safety, Data Read and Analysis Notes.

Local `./gradlew test :desktop:installDist`: 210 cases, 207 passed, no failures or errors,
three skipped (the optional library-matching benchmark and the two local Tusky-fixture cases).
All eight desktop tests, including the five new audit-view checks, passed. The synthetic privacy
test covering nine leaks and six precision traps ran successfully. Python checks: 9 passed.
Native Windows validation of this branch is recorded in the pull request after CI finishes.

The first native follow-up captures found a further Swing layout issue: read-only caret updates
scrolled the risk form below its heading, and wrapped-text relayout compressed the Screens list.
Caret updates now scroll only a focused field; selecting a finding resets its own detail pane.
The two table groups retain usable minimum heights. The regression checks run after queued caret
events and check both the form's initial scroll position and visible screen rows. The tour records
both the initial risk overview and an explicitly scrolled detail view. Tusky's privacy case was
also run separately using the existing 32.2 APK: both PrivacyMapTest cases passed with zero skips.

Screenshot generation now fails if the demo recovery or the second comparison build fails,
addressing the existing CodeRabbit comment on PR #9. Windows screenshots upload immediately
after the tour, before packaging, and absence of screenshots is an error.

No taint rules, risk weights, library matching or endpoint recovery were changed. The engine's
existing exclusions remain: callbacks/async, reflection, native code and cross-run storage
need more work and recall measurements on positive examples. This UI work makes those limits
visible; it does not solve those analysis gaps or establish that an app is safe.

CodeRabbit CLI authentication is unavailable in this environment (`environment_unsupported`,
browser login unavailable). No CodeRabbit CLI review was run or claimed. The checks above are
regression tests and inspection of actual Windows captures.
