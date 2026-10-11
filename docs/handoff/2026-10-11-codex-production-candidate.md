# Integrated production candidate

Branch `codex/production-candidate` combines backend/reliability head `5ba6f94`, native UI head
`46a58eb` and Claude privacy head `43781ff` in an isolated worktree. Original branch work remains
intact. Review the candidate against `main`; do not merge the older stacked PRs independently.
The GitHub PR records exact-head native Windows, Linux and Android runtime validation.

## Changes

- Reconcile file-backed input with update diff and signing inventory. Close nested-archive
  spools for audit/screens/privacy/SBOM/diff commands. Read signing metadata from bounded file
  windows rather than full APK arrays; this observes identity, not signature validity.
- Attach Windows Job Objects or delegated Linux cgroup quotas before analysis starts. Add
  strict refusal, startup cleanup, native direct-memory and fail-closed tests.
- Bound report parsing and indexes, expose incomplete/stale report states, hash checked preview
  bytes, and label source checks as recorded at recovery. SHA-256 is local integrity evidence.
- Page folder rows in groups of 500, filter across the cached directory off the UI thread,
  preserve requested previews during concurrent listing, and debounce edits.
- Generate and independently validate the product's JVM BOM against shipped JAR hashes; ship
  notices, checksum installer artifacts, and add clean MSI lifecycle checks.
- Add an independent Android fixture runtime gate that must accept strictly rebuilt source
  and reject a seeded visible-behavior mutation. See the fixture README for exact scope.

## Local evidence

JDK 17 with the pinned toolchain. Combined `./gradlew test` and final changed desktop/CLI checks:
281 Kotlin cases, zero failures, one optional skip (external library benchmark, measured separately
on all eight R8 workloads/settings with zero wrong class or method names). The two-release Tusky
diff case ran against the official APKs. The opt-in desktop performance case ran. Python: 28 checks passed. Product BOM:
CycloneDX schema and exact hashes for 21 packaged JARs pass.

On the synthetic 200,000-method/10,000-file/2,000-folder workload: observed 328–684 ms project load,
about 88.8 MB thread-allocated bytes, 10–34 ms initial Swing view. Desktop tests now use the same
512 MiB heap as the shipped GUI. These are observations on this host,
not fixed service-level guarantees. Paging, cross-page reveal/search, malformed/truncated
reports, token/nesting bounds and changed-file inventory regressions pass.

Five repeated process/job failure cycles passed, including 100 noisy subprocess executions,
JVM crashes/OOM, ignored stdin, descendant cancellation, killed-worker resume and changed-input
refusal. These are short repeated failure checks, not a prolonged large-APK soak.
Test inputs now include host OS, the performance opt-in and installed Tusky data, preventing
cross-OS result reuse and cached dataset skips. Generated benchmark timing files were retained
in the local evidence folder rather than changing committed baselines.

Pinned Tusky 32.2/source regression: 112/112 endpoint declarations, 255/255 parameters,
112/112 response types, 378/378 wire fields/names/nullability, 377/378 original field types,
17/17 genuine enum fields. Custom notification string shape 1/1, known values 14/14 and client
status-response selection 1/1. 109 routes/119 HTTP probes: 115 successful synthesized examples
pass independent OpenAPI/completeness/hash validation. No original server behavior is claimed.

## Remaining release requirements

Native screenshots, MSI lifecycle, Job Object enforcement and emulator scenarios require their
GitHub runners; use exact-head job results rather than the previous branches' successful runs.
The initial native memory-quota probe passed. Windows identified a short-lived-process
descendant snapshot including its supervisor; cleanup now protects supervisor/ancestor PIDs,
checks creation times and avoids enumeration after worker exit. Android setup uses supported
SDK packages and firewall commands wait for the emulator's xtables lock. Rerun both complete
jobs after these fixes before treating the integrated candidate as validated.
Publisher signing still needs the owner's Authenticode certificate and configured Ed25519 update
identity in the protected signing environment. The manual signed candidate workflow already
refuses missing/mismatched identities; no release is published by this branch.

A broad held-out corpus, longer soak runs, complete runtime/toolchain BOM, per-method checkpoints,
cross-project caching, stronger hostile-input isolation, native/Flutter/Unity source recovery,
arbitrary serializers/framework handlers and whole-app behavioral equivalence remain separate
work. Do not label this candidate universally bug-free or claim all APKs compile. Source hash
checks detect edits relative to the supplied inventory, not maliciously replaced inventories.
