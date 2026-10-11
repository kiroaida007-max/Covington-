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
280 Kotlin cases, zero failures, two optional skips (external library benchmark and two-version
Tusky diff data). The opt-in desktop performance case ran. Python: 27 checks passed. Product BOM:
CycloneDX schema and exact hashes for 21 packaged JARs pass.

On the synthetic 200,000-method/10,000-file/2,000-folder workload: 328 ms project load,
88,758,176 thread-allocated bytes, 10 ms initial Swing view. These are observations on this host,
not fixed service-level guarantees. Paging, cross-page reveal/search, malformed/truncated
reports, token/nesting bounds and changed-file inventory regressions pass.

Pinned Tusky 32.2/source regression: 112/112 endpoint declarations, 255/255 parameters,
112/112 response types, 378/378 wire fields/names/nullability, 377/378 original field types,
17/17 genuine enum fields. Custom notification string shape 1/1, known values 14/14 and client
status-response selection 1/1. 109 routes/119 HTTP probes: 115 successful synthesized examples
pass independent OpenAPI/completeness/hash validation. No original server behavior is claimed.

## Remaining release requirements

Native screenshots, MSI lifecycle, Job Object enforcement and emulator scenarios require their
GitHub runners; use exact-head job results rather than the previous branches' successful runs.
Publisher signing still needs the owner's Authenticode certificate and configured Ed25519 update
identity in the protected signing environment. The manual signed candidate workflow already
refuses missing/mismatched identities; no release is published by this branch.

A broad held-out corpus, longer soak runs, complete runtime/toolchain BOM, per-method checkpoints,
cross-project caching, stronger hostile-input isolation, native/Flutter/Unity source recovery,
arbitrary serializers/framework handlers and whole-app behavioral equivalence remain separate
work. Do not label this candidate universally bug-free or claim all APKs compile. Source hash
checks detect edits relative to the supplied inventory, not maliciously replaced inventories.
