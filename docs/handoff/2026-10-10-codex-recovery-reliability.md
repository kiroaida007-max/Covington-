# Recovery reliability handoff

Branch: `codex/recovery-reliability`, stacked on `codex/backend-contract-assurance` (draft #4,
head `caa2d258483a6522c1b167a1edf41eeda047b6a8`). Do not merge either base without user instruction.
The base's Linux and native Windows checks passed on that exact head.

The user accepted all seven reliability recommendations and asked to implement them one by one.
Six checkpoints of work are committed separately: supervised full recovery; validated resume;
shared resource planning; file-backed archive reads; failure/load tests; source accuracy gates.
The final product change adds native job history, diagnostic export, release signing workflow and
pinned-key package verification. Product version defaults to 0.3.0; `-PappVersion` controls both
the engine build information and desktop package version.

## Architecture and concrete limits

- `RecoveryWorker.kt`: shared desktop/CLI supervisor and explicit serialized request/result protocol.
  Paths and tool profile cross the boundary; parent credentials/environment are not serialized.
  Bounded progress logs rotate on resume. Cooperative cancellation has a five-second fallback;
  tracked descendants are terminated, with a reaping interval before terminating the parent.
- `RecoveryCheckpoints.kt`: names, full JADX tree, combined verification/repair tree. The complete
  file set, sizes, hashes and payload checksum must validate; identity includes input, modules,
  profile, options and library artifact hashes. Pinned tools are checked before cache reuse.
  Copy failures propagate rather than mixing a partial snapshot with new decompiler output.
- Jobs retain a checked input snapshot, work folder and partial project. A publication manifest
  precedes the final project rename, allowing recovery after a lost final worker result. Edited
  completed projects are protected. CLI: `decompile --job-dir`, `resume-full`, `cancel-full`.
- `ResourceGovernor`: physical/cgroup-aware heap planning, host reserve, per-user cross-process
  file lock, one compiler slot per active full job, capped Java/javac heaps and CPU count. Resume
  recalculates limits against the current host. Disk monitoring tolerates compiler file-removal
  races. Disk limits are monitored, not OS quotas; heap caps are not hard native RSS limits.
- ZIP inventory uses a fixed 64 KiB window. Extraction streams bounded data and validates CRC.
  Nested APKs are spooled and cleaned by `AppInput.close`; production callers use it. Bare DEX
  avoids wrapping/copying. Full recovery still holds cross-DEX models; this is not wholly lazy DEX.
  Input/entry/expansion bounds and ZIP64 limits are described in `docs/recovery-reliability.md`.
- The broader D8 fixture found a real bug: Moshi adapter/model lookup was local to one DEX. It now
  indexes unique model descriptors across DEX and uses the model owner's annotation/constructor
  indices. This preserves types across multidex APKs, AAB modules and split bundles.
- `FullJobHistory`/`RecoveryDiagnostics`: lightweight durable state, native table, resume, bounded
  diagnostic ZIP. No APK/source/profile paths or environment are included; logs can include
  symbols and diagnostic text. Diagnostics preserve evidence limits for partial results.

## Validation and remaining activation

The final full local suite passed: 216 successes, one intentional opt-in benchmark skip, no
failures/errors. This includes queue cancellation, an abandoned supervisor and Python-to-Java
signed-manifest interoperability. Exact-head CI results are recorded in the reliability PR.
Python: 16 tests passed. A CI setup download hit a connection reset before any test ran;
tool downloads now use bounded retries and publish bytes only after the pinned checksum passes.
Windows CI also caught Git's CRLF conversion of signed/hashed fixture bytes. `.gitattributes`
now disables text conversion for those fixtures; signature and asset checks stay exact-byte.

A 96 MiB stored-asset archive passed inventory/extraction in a separate JVM limited to 32 MiB.
Real subprocess tests cover blocked stdin, runtime halt, child heap exhaustion, descendant
cancellation, twenty noisy runs and subsequent usability. APK tests cover killed-worker resume,
changed input rejection, malformed input, preserved verified repairs and edited-output protection.
The packaged Linux runtime completed recovery/cancellation and captured sixteen actual screens.
Windows CI adds the same packaged recovery check and two new dialog captures to the previous tour.

Fresh Tusky extraction: source gate 112/112 endpoints, 255/255 parameters, 112/112 response types,
83/83 models, 378/378 wire/property names/nullability, 377/378 Java field types, 17/18 enum sets.
Live HTTP: 109 routes, 111 probes, 110 independently valid examples, one expected 501. CI now
enforces the pinned APK/source dataset and these coverage/accuracy thresholds.

Actual installer signing is not activated: no publisher certificate/update private key was
available in the session. The manual release workflow builds/tests with a pinned public key,
signs/timestamps and verifies Authenticode identities, then signs hashes of final package bytes.
Required variable/secret names are in `docs/recovery-reliability.md`. It uploads candidates only.
The native verifier refuses unconfigured trust roots, invalid signatures, changed package bytes,
expired/future/replayed manifests and older/equal versions. There is no automatic update channel
or key rotation yet. The synthetic crypto fixture contains no private key and no real installer.

CodeRabbit CLI 0.9.0 was installed, but authentication returned `environment_unsupported` (no
browser callback). Its review did not run. An Agentic API key or user-controlled terminal login
is required; do not describe this as a successful CodeRabbit review.

These changes do not establish universally bug-free software, arbitrary APK source compilation,
original server business logic recovery, or automatic posting-status conditional dispatch. Earlier
handoff limitations and the original-type/enum score gap remain applicable.
