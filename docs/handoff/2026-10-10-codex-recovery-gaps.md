# Concrete recovery gaps

Branch `codex/close-recovery-gaps`, stacked on `codex/recovery-reliability` at
`6c13077bef2c1b21538c1618031733381e10d1e6` (draft PR #5). Do not merge the base without instruction.

The user requested that remaining gaps be fixed. This follow-up closes the benchmark namespace
collision, conservative automatic status-posting client selection, and unchecked ZIP metadata.
The PDF remains background ideas, not a request to replace the working product architecture.

## Backend behavior and precision

- `RequestDispatch.kt` uses equal-value CFG joins, final-field provenance, direct constructor
  assignments, and bounded String-domain execution. The exact Retrofit method descriptor is
  part of annotation evidence, avoiding overload ambiguity. All actual call sites must be
  covered and agree. Other declared API requests terminate a path outside the selected route.
- The recovered Tusky rule links `kg/h.n` to `NewStatus.h` via the constructor, then the Moshi
  writer to `scheduled_at`. The optimized null/length/boolean tests select Status for null/empty
  and ScheduledStatusReply for nonempty. No Tusky names or field-name heuristics are in the engine.
- Model-field evidence requires a direct read from the serializer input in a straight-line
  key/write region. Disagreement, mutation, escaping requests, mutable provenance, unsupported
  length comparisons, exception regions, switches, loops and budgets abstain. Candidate groups
  and raw method-reference index prefilters avoid decoding unrelated methods unnecessarily.
- `CHECKED_CLIENT_SELECTION` requires checked alternatives and an exact disjoint partition of
  null/empty/nonempty for one nullable String property. Explicit mock variant selection remains.
  Generated JDK-only mocks parse bounded JSON (64 KiB, depth 64, 10,000 nodes), reject duplicates,
  malformed UTF-8, invalid primitives and wrong selection types, and mark automatic selection
  `OBSERVED_CLIENT_RULE`. Missing keys become null by mock convention, not recovered acceptance.
- Native Windows capture exposed a clipped backend assurance footer. Its counts now use three
  short lines so the checked, withheld, selectable and issue totals remain readable.
- A damaged caller regression exposed a coverage hole: decoder failures were silently skipped.
  Candidate methods that cannot decode now withhold selection, including conservative raw-index
  prefilter hits, because complete caller coverage cannot be established. The fixture fails before
  the fix and checks that intact independent routes retain their proven partition.
- `evaluate.py` previously erased scopes before looking up enums: sealed `Notification.Type`
  inherited unrelated nested `Type` enum candidates. Imports/aliases and nested ownership now
  resolve exact enums. Literal URLs/braces no longer corrupt comment or delimiter parsing.
  The corrected enum denominator is 17, not 18; original Java type accuracy stays 377/378.
- `tusky-behavior-truth.json` is independently labeled from the pinned upstream source, with
  hashes of its five supporting files. The gate checks the open scalar, 14 known values, no
  invented known values, and the client dispatch partition with no extra route predictions.
- `dg.j` has no class/nesting annotations in the pinned APK. Original `Notification.Type`
  identity cannot be justified from this bytecode. The open wire shape is already recovered;
  no invented name is labeled recovered. No original server business logic is reconstructed.

## Archive correctness

Inventory now checks local flags, CRC and sizes against the central record. Descriptor-mode
local zero placeholders are allowed; contradictory values are rejected. Signed and unsigned
32-bit data descriptors must match all three central fields. Their bytes participate in entry
overlap checks. Local variable headers cannot cross the central directory, and extraction uses
subtraction bounds to avoid overflow. ZIP64 remains explicitly unsupported; limits are unchanged.

## Validation and remaining work

The source gate measured 112/112 endpoints, 255/255 parameters, 112/112 responses, 83/83 models,
378/378 wire/property names/nullability, 377/378 Java field types, 17/17 true enum fields,
1/1 custom scalar, 14/14 known values and 1/1 client selection, with no extra predictions.
Live HTTP: 109 routes, 119 probes, 115 successful independent schema/hash checks, four intended
400 responses, no withheld Tusky examples. See the PR for final exact-head local/CI evidence.
The full local Gradle suite passed with 220 successes, one intentional opt-in benchmark skip,
and no failures/errors. All 20 Python tests passed. The independent generated regression mock
also passed (16 routes, 30 probes, 22 valid examples, four expected 501s, four expected 400s).

Regression fixtures include namespace collisions/import aliases, invented scalar values,
wrong and extra client selections, overloaded API names, disagreeing/uncovered callers, mutable
inputs, threshold predicates, request escape and exceptions. Real HTTP tests cover parser
abuse, body/nesting bounds, UTF-8 errors, nested decoys and explicit-selector override. ZIP tests
cover local metadata mismatches, signed/unsigned/missing/corrupt descriptors and valid extraction.

Remaining product extensions: arbitrary custom serializers and reflection-only model inference,
ZIP64, wholly lazy cross-DEX recovery, per-method/cross-project caches, OS-native resource quotas,
automatic update distribution and key rotation. These are not claimed complete. Publisher signing
still needs external certificate/private-key configuration. CodeRabbit authentication was blocked
in the prior handoff and no external authenticated CodeRabbit review is claimed here.
