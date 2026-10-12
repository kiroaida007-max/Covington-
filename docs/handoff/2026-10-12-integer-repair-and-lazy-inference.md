# Bounded integer repairs and lazy model snapshots

Continue on `codex/production-candidate`, draft PR #11. Parent `1019615`. No merge or release.
The local-coding-assistant, pairwise-learning, runtime and Windows handoffs remain relevant.

## Changes

- Add a bounded V0 integer reconstruction proposal tier after known edits and before the local
  model. Pure static `(I...)I` only: at most eight parameters, 192 DEX code units, 32 blocks,
  128 generated statements and 8,192 body characters. Unsupported loops, heap accesses, calls, implicit synchronization,
  types and exception handlers are skipped, not approximated. Preserve the existing declaration
  and source parameter names; fix generated locals that could shadow parameters named `t0`/`t1`.
- Keep the complete compiler/bytecode acceptance gate. Unknown/missing/regressing comparisons
  reject an edit. Original-complete compilation restoration also works for integer proposals,
  without inventing training labels. Generation is `INFERRED_RECONSTRUCTED` with original DEX,
  body hash and rule provenance; no solver proof is claimed. The separate solver pass is unchanged.
- Bound eligible method/source attempts (256), per-file unresolved targets (128) and files (128),
  within existing repair round/time limits. Cheap signature screening avoids reading unsupported
  source files; method lookup uses the per-file index. Keep exact Tiny/JADX name handling.
- Defer model/native snapshot copying and verification until a usable request requires inference.
  Cancellation and the call deadline apply during bounded copying and cached-target hashing.
  Failed preparation cannot execute native inference and disables further assistant attempts.
  Purge stale native libraries before first use. Report artifact state, preparation time and
  staged bytes. `UNPREPARED` records expected pins, not verification. Prior shared/resumed snapshots
  may remain on disk; fresh unused jobs allocate no model/native files. No new model/runtime version.
- Refresh compile/quality reports after completely checked integer compilation restoration, add
  a distinct recovery layer and retain backward-compatible optional record/report fields.
  Model correctness gates, independent ranker promotion and held-out assignments are unchanged.
- Fix a reproduced false-equivalence bug in round-trip comparison: constant-return methods could
  match despite static/instance or implicit synchronization changes. Filter incompatible candidate
  declarations before selecting the best D8 mode, normalize both synchronization flag encodings,
  and report a `DIFFERENT` result with a `declarationMismatch` reason. Three dedicated metadata
  regression tests include both change directions and valid encoding equivalence. This does not
  turn body matching into a proof of every API/class/concurrency property.

## Exact measurements

`benchmarks/repair-assistant/integer-results.json` preserves all **40 trials**. The five prior
input DEX hashes are unchanged; five further generated faults were declared before execution:
overflow/order, char narrowing, division branch order, discarded division exception and join-local
collision. Legacy rules fix 0/10, integer engine fixes **9/10**, and either hybrid model arm fixes
**10/10** with **one actual native model call per arm**. The integer engine now fixes the earlier
boundary and negation failures. Long-literal restoration remains outside V0 and uses Qwen.
Every accepted case matches all three original methods. On the original five cases, native calls
fall from five to one per model arm (**80% fewer**). This is controlled coverage and a call-count
reduction, not independent APK accuracy, model training or a marginal RAG gain. New timings
include needed snapshot work and are not directly comparable to earlier contention/timing scope.

`integer-public-coverage.json` records a predeclared read-only scan of existing hash-bound jsoup,
Jackson, JDOM and Guava captures. Source-archive hashes match the existing lock. **5,263** methods
have code, **37** meet the bounded integer signature and **seven** lift into V0. Existing baselines
contain **179** unresolved methods and **3,142** unexamined captured methods. None of the evaluated
unresolved methods is supported by this fallback; no natural public-project repair gain is
demonstrated. The probe does not compile/accept candidates, call models, produce training labels
or change holdouts. Final compiled probe reproduces its four capture results exactly.

Packaged clean-input recovery with the optional assistant selected compiles 1/1 files and matches
3/3 methods. It reports `UNPREPARED`, zero calls/preparation milliseconds/staged bytes, and its
job contains no model snapshot. `resume-full` returns the completed result. Actual model use in
the long-literal arms reports verified snapshots and passes the original complete method gate.

## Validation

Full Gradle suite with desktop performance enabled: **317 cases, zero failures/errors, one
optional external library-benchmark skip**. All **61 Python checks** pass. Product BOM schema
and **21 packaged JAR hashes** pass. New tests cover unsupported/ambiguous reconstruction,
declaration/parameter preservation and provenance, join-local collisions, original-complete
unknown-baseline restoration including division exception effects, lazy allocation, stale native
library cleanup, corrupted bytes, cancellation and preparation deadlines. Existing model rejection
tests use the declared integer-tier ablation so they still exercise incorrect model suggestions.

Native Linux capture passed all **17 screens/dialogs**, including the new assistant report fields,
navigation/filtering, worker isolation, mock lifecycle, cancellation/publication and job history
at 1280x720. Artifacts are in `integer-screens`; Windows validation remains blocked by
GitHub account billing/spending limits until exact-head jobs can execute. No external CodeRabbit
review, merge or release is claimed.

Local artifacts: `/workspace/scratch/local-repair-assistant/experiment-005`,
`integer-source-probe.json`, `integer-coverage-after-declarations.json`, `integer-and-declaration-regression.log`,
`integer-python.log`, `lazy-job`, `lazy-recovered`, and `integer-screens`. Public files retain
compact metrics/hashes, not model weights, private Java or runtime logs.

## Next useful scope

This fallback is narrow: public-library coverage demonstrates why loops, instance/heap behavior,
calls, wider types, Kotlin/R8 and end-to-end successful real projects still need work. Do not
extrapolate 10/10 controlled cases to arbitrary APKs. Multiple compile errors in the same file
can require a bounded multi-method transaction; never relax the original-complete gate to accept
partial unknown restorations. The ranker remains unpromoted; no automatic fine-tuning occurs.
Runtime method/API hooks and continuous native tracing remain separate unfinished capabilities.
REA Android inspection and JavaScript recovery adapters already exist. Upstream Android/ADB
documentation also describes Frida instrumentation, which this update does not integrate. Do not
treat shared JADX observations as independent proof or imply the broader tool catalogue is installed.
