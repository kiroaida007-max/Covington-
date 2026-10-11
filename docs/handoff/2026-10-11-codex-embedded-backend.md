# Embedded server/business logic

Branch `codex/embedded-backend-logic`, based on draft PR #7 at
`7cdaade90d19e5a652f0eaee6ae0808a05934f56`. Do not merge its bases without instruction.
Prior source-attribution work remains in `2026-10-11-codex-reference-types.md`.

The user explicitly asked for recovery to work when an APK contains server business logic.
The previous blanket statement that an APK cannot contain server code was corrected. An APK
can contain local/embedded implementations. That does not establish a remote deployment's code.

`EmbeddedBackend.kt` scans concrete JAX-RS/Spring declarations and NanoHTTPD/JDK handler overrides,
exports a bounded conservative whole-class dependency slice across DEX parts, records source
hashes and per-method full-recovery checks, and reports unknown runtime targets/dependencies.
Quick `contract` extraction also writes a declaration-only report. Full recovery writes the
source/check report automatically; its summaries and the native desktop Backend panel expose it.

A runnable portable subset of static integer GET handlers is compiled strictly without Android
or missing-class stubs. The actual portable build is re-dexed and compared against every original
code method in the selected classes. Only matching builds get launch scripts. The generated
loopback HTTP transport calls recovered business methods; it is labeled SYNTHESIZED transport,
not original routing/authentication/storage behavior. Embedded export compiles and re-dexes but
never starts the recovered server; existing V0 checks may test eligible pure calculations.
See `docs/embedded-backend.md` for supported signatures, budgets, whole-slice limits and commands.

`EmbeddedBackendTest` builds real DEX/APK fixtures from known source. It exercises export, helper
dependencies, strict compilation, actual round trips and live HTTP calculations against an
independently executed original program, including boundaries, invalid input and business errors.
Other cases cover unsupported virtual dispatch/disabled round trips, absent launchers, stale
launcher removal, handler caps, cancellation, client-only negative detection and framework roles.

See the PR for exact-head full local and Linux/Windows CI evidence. The Windows workflow must
require zero skips for `EmbeddedBackendTest`. Remaining bounds: retained marker detection only;
custom routers/obfuscated framework markers/native/JS runtime recovery are separate work. A
runtime issue in one detected handler currently withholds the whole slice's portable adapter.
