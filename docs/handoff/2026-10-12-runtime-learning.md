# Runtime observation and checked learning

Continue on `codex/production-candidate`, draft PR #11. This work builds on `d27ef66` and keeps
the original backend/UI worktrees intact. Review the combined candidate against `main`; do not
merge the old stacked PRs independently. The previous candidate handoff remains useful for
backend/Tusky scores, recovery supervision, release signing and packaging.

## Product changes

- Add the Kotlin Runtime Behavior engine, CLI `runtime` command and native Swing page. A bounded
  scenario actually installs and executes an APK on a rooted API 30+ disposable emulator. Reports
  contain UI observations, UID-column-filtered logs/crash excerpts, processes/PSS, private-file
  inventories/deltas, permission grants and socket/denied-packet metadata with explicit coverage.
- Stage/hash the exact installed APK, serialize sessions with a per-emulator lock, refuse physical
  devices/existing installs/shared UIDs, and deny app-UID IPv4/IPv6 traffic before launch. Each
  session owns a foreground loopback ADB server; cleanup does not kill the existing SDK server.
  Journal install attempts before sending them. Confirm uninstall before removing owned firewall
  chains; failed uninstall retains denial and prevents a successful cleanup result.
- Bound commands, XML depth/entities/nodes, output, file sampling, scenarios and session duration.
  Cancelled/failed runs still perform bounded cleanup and write evidence. Android ANR/crash overlays
  fail the scenario rather than being silently dismissed. UI taps require an unambiguous match,
  including Android's case-insensitive all-caps button rendering.
- Compare two completed, clean sessions only for canonical visible UI under the same scenario.
  Incomplete captures cannot become a match. Storage/log/network metadata are observations,
  not part of this equality test. See `docs/runtime-behavior.md` for the precise boundary.
- Emit bounded, feature-only repair feedback from complete bytecode comparisons. The optional
  local repair model changes proposal order only. Staged model bytes and their hash participate
  in verification checkpoint identity; every accepted edit still needs the original checks.
- Add a pinned-input local trainer with project-family partitions, cross-project duplicate
  removal, validation-only model selection, held-out ranking metrics and promotion gates.
  Generated fixtures cannot promote a production model. No promoted model or measured
  decompilation gain is bundled. See `scripts/ml/README.md`.
- Reuse selected privacy-classifier work from Claude commit
  `bc1a01722907247a3f7bdcc6c779514e97902328`, then correct hand/weak-label overlap and owner-fold
  leakage, retrain the actual artifact, harden its parser, regenerate golden cases and default
  model hints off. The candidate's stronger PrivacyView bounds/unknown-state reporting remain.
  This is not a merge of Claude's later NVD/SBOM work.
- Refuse imported runtime reports exceeding capture bounds before populating Swing tables/text.
  Fix the folder-filter pending-timer race. Windows capture now expects seventeen images.
  The mock-server test's fixture-readiness budget is 30 seconds; its cancellation assertion and
  product worker bounds are unchanged.

## Independent Android execution

Official Android SDK archives were checked against repository archive SHA-1 values. Local API 30
AOSP x86_64 ran using software acceleration, without KVM. The disposable test SDK and reports are
under `/workspace/scratch/android-sdk` and `/workspace/scratch/runtime-local`; these are local
evidence, not committed distributions or test signing identities.

The fixture original was built from hidden source. The candidate was strictly rebuilt from
exported Java with original resources and no hidden-source fallback or missing-class stubs.
The mutation changed the visible result label. Product-engine results:

- `behavior-matched/comparison.json`: `SCENARIO_MATCHED` for original versus strict rebuild.
- `behavior-mutant/comparison.json`: `DIFFERENT`; CLI exit 5 for the intentional mutation.
- All four underlying sessions: `COMPLETED`, cleanup complete, no reported issues. Independent
  assertions checked UI 42→44, app log, persisted preferences, positive PSS, INTERNET grant and
  observed denied IPv4 traffic. The existing SDK server survived; owned foreground servers ended.
- `final-parser-session/report.json`: latest hardened parser also completed on the real APK,
  with two observations, clean cleanup and no issues.

Earlier probes exposed Android 30's missing `logcat --uid`, ADB 37's rejected hostname socket
syntax and native button capitalization. The implementation now filters the logger's UID column,
uses supported loopback server syntax and matches UI text case-insensitively. Failed probe reports
were preserved, not relabelled as successful runs. Software-emulator timings are not an SLA.

## Model evidence

The retrained privacy artifact uses 371 distinct hand labels, 11,866 weak SuSi labels and
40,000 weak negatives. All 169 hand/weak overlaps are removed; entire API owners are held out and
held-out owners' weak/negative labels are excluded from each fold. Int8 category accuracy is
0.528; source precision/recall/F1 is 0.859/0.705/0.775 and sink is 0.884/0.594/0.711. This small
signature benchmark is not end-to-end privacy-flow or source-recovery accuracy. A model score
is not calibrated confidence. Source hints alone are opt-in; sink hints remain disabled.
Input/model hashes, limitations and attribution are in the bundled metadata and model card.
Assess derived-weight distribution terms before a release; attribution is not a license opinion.

## Verification and release limits

JDK 17, pinned tools and `APKPROJECT_PERFORMANCE=1`: the full Gradle suite passed with
298 cases, zero failures/errors and one optional external library-benchmark skip. Python:
36 checks passed. Product BOM: schema and exact hashes for 21 packaged JARs passed. The final
desktop-only rerun passed all 29 cases, including the imported-report bounds added after the full
suite; the rebuilt BOM also passed. Native Windows
results are separate from these headless/Linux checks. The independent Android evidence checker is
`benchmarks/android-runtime/check-behavior-evidence.py`.

The earlier candidate's Windows/Android GitHub jobs failed without a runner or any steps. The
runtime commit's Linux and Android push jobs also failed with no runner or steps. Windows now
includes `codex/production-candidate` in its push filter, so candidate pushes request native
validation even when no pull-request run starts. Inspect each commit's jobs before assigning a cause;
accessible metadata did not
establish the reason. Native Windows screenshots, Job Objects and MSI lifecycle still require
their Windows runner. Keep PR #11 draft until those exact-commit checks pass. Signing credentials
remain owner-provided; this branch publishes no release.

CodeRabbit review did not start: CLI authentication reported `environment_unsupported` because
browser login was unavailable and no Agentic API key was provisioned. Do not claim a successful
CodeRabbit review. Local tests and manual implementation review are separate evidence.

Method/API hooks, request-body/TLS tracing, continuous native crash capture, coverage-guided
exploration, deterministic full-state comparison and broad held-out repair training remain work.
The engine is not a sandbox for hostile APKs: use a disposable host VM with external network
controls. Root/shared emulator state and abrupt host termination are outside guaranteed cleanup.
Runtime execution cannot recover business logic existing only on a remote server. Do not claim
universal compilation, a bug-free product, a best-ever engine or an unmeasured ML accuracy gain.
