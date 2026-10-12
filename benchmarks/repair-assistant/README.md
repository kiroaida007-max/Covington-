# Controlled local coding-assistant experiment

The current engine adds bounded integer reconstruction ahead of model inference. The earlier
five-case model-only development run below is preserved; its unresolved cases are not deleted.

## Integer reconstruction and lazy inference

[`integer-results.json`](integer-results.json) records all forty trials across four arms: legacy
rules, integer engine, integer engine plus model without retrieval, and integer engine plus model
with retrieval. Keep the original five inputs and predeclare five additional faults before the
run: arithmetic/overflow order, char-versus-short narrowing, division branch order, a discarded
division whose exception must remain observable, and a branch join with parameter names `t0`/`t1`.

| Arm | Previous five faults repaired | Extended ten repaired | Native model calls on ten |
|---|---:|---:|---:|
| Legacy rules | 0/5 | 0/10 | 0 |
| Bounded integer engine | 4/5 | 9/10 | 0 |
| Integer engine + model, retrieval off | 5/5 | 10/10 | 1 |
| Integer engine + model, retrieval on | 5/5 | 10/10 | 1 |

The previous boundary and negation failures now pass through bytecode-checked integer
reconstruction. Each accepted case matches all three original methods. The remaining long
literal is outside V0's integer signature and uses actual Qwen inference. On the original five,
the model-call count falls from five to one per model arm, an **80% reduction**. This is an engine
improvement on controlled faults, not a model training gain, general APK accuracy or a marginal
RAG gain. The additional five cases are generated tests too, not independent projects.

Artifacts are copied and checked only when needed. Before the first inference, the benchmark's
shared snapshot contains no model/runtime copies; later unused providers report `UNPREPARED`
and zero preparation time while earlier snapshot bytes may remain in that shared directory.
No native model process runs for the integer-only repairs. A fresh packaged clean-input recovery
separately reports zero calls, zero preparation milliseconds, zero staged bytes and no model file.
Preparation now consumes the call deadline; timing includes any needed snapshot work. Do not
compare wall time directly with the earlier run's different timing scope/host contention.

[`integer-public-coverage.json`](integer-public-coverage.json) is a separate read-only probe of
predeclared pinned jsoup, Jackson, JDOM and Guava captures. Their source-archive hashes match the
existing lock; the original DEX and earlier baseline bytes are hash-bound. Of 5,263 captured
methods with code, 37 meet the bounded integer signature and seven lift into supported V0 IR.
The existing baselines contain 179 unresolved methods, none supported by this fallback. Another
3,142 captured methods had no baseline evaluation; do not treat those as correct or failed.
**No natural public-project repair gain is demonstrated.** The probe performs no model calls,
candidate compilation, acceptance, training or holdout reassignment.

Run the extended experiment with the existing command plus `--extended`. The coverage probe is:

```sh
java -Xmx512m -cp 'modules/benchmarks/build/install/benchmarks/lib/*' \
  dev.apkproject.benchmarks.IntegerRepairCoverage frozen-captures.json new-coverage.json
```

The local manifest names existing captured DEX/source/baseline paths and their immutable hashes,
family, coordinate, source URL/archive hash and license. Public output omits local paths and code.
The frozen input manifest used here is retained locally with the experiment; existing source
locks/capture instructions establish the source origins. These are library subsets, not an APK
population. Real compiler tests additionally restore a file with an unknown baseline only when
all original methods match; invalid/missing evidence still rejects proposals. Snapshot tests
cover lazy allocation, stale library removal, corrupt bytes, cancellation and deadlines.
Declaration regressions also show that identical constant-return bodies can hide changed static
or locking semantics; those results must be `DIFFERENT`, while equivalent synchronization flag
encodings continue to match. Body comparison alone no longer supplies those false equivalences.

## Earlier model-only development run

This experiment runs the actual official Qwen2.5-Coder-1.5B-Instruct Q4_K_M model through the
production CPU inference adapter and source-repair engine. It compares rules alone, the local
model without retrieval, and the same model with local retrieval. The input DEX and broken Java
are the same in each arm. No expected replacement Java is supplied to the model.

Three development faults informed protocol work: unsigned shift, a comparison boundary and an
oversized integer literal missing its long suffix. Two additional faults (XOR replaced by OR,
and negation dropped) were declared before the revised-protocol run. They remain in the denominator
even when the model fails. These five generated cases are not independent real APKs, broad
accuracy evidence, fine-tuning data or a repair-ranker promotion dataset.

| Fault | Rules alone | Local model, retrieval off | Local model, retrieval on |
|---|---|---|---|
| `>>>` changed to `>>` | Unresolved | Checked repair | Checked repair |
| `>= 8` changed to `> 8` | Unresolved | Unresolved | Unresolved |
| Long literal without `L` | Unresolved | Compilation restored | Compilation restored |
| XOR changed to OR | Unresolved | Checked repair | Checked repair |
| Unary negation dropped | Unresolved | Unresolved | Unresolved |

Both model arms repaired **3/5**; rules repaired **0/5**. The unresolved model trials returned
unchanged bodies and were discarded. Retrieval provides **no demonstrated marginal gain** here.
For accepted cases, all three original non-synthetic methods matched, including the untouched
constructor and protected method. For literal restoration, the unknown baseline was withheld
from pairwise learning labels. The two unaffected methods already matched the partial ECJ
baseline in that case; the failing method was marked not compiled.

[`controlled-results.json`](controlled-results.json) retains all fifteen trials, model/runtime/
knowledge/prompt identities, retrieved reference IDs, statuses, timings and native sampled RSS.
The tested runtime is llama.cpp b11565 on Linux x64, JDK 17.0.20+1, with two effective CPU threads,
4,096-token context and 384-token output cap. Maximum sampled native RSS was **1,952,563,200 bytes**
(about 1,862 MiB), within the configured 2,048 MiB limit. That excludes the engine and compiler
processes. Repair-phase timing excludes model snapshot preparation; some trials overlapped
developer builds, so these figures are not a dedicated-host speed comparison. The smaller 0.5B
model and native Windows inference have not been measured.

## Reproduce

Install the pinned developer toolchain and model pack, then run from the repository root:

```sh
python3 scripts/ml/setup_local_assistant.py --out models/local-coder --size 1.5b
./gradlew :benchmarks:installDist
java -Xmx1g -cp 'modules/benchmarks/build/install/benchmarks/lib/*' \
  dev.apkproject.benchmarks.RepairAssistantBenchmark \
  models/local-coder/assistant.json scratch/new-assistant-experiment
```

The output must be a new directory. This laboratory retains synthetic prompts/replies, generated
Java and a model snapshot locally for diagnosis. Production does not retain raw prompts/replies.
The benchmark writes retrieval-arm configurations beside the selected config to preserve relative
artifact paths; use a development pack, and do not run simultaneous benchmark processes against
that pack. It uses existing ECJ/javac/D8 round-trip checks; original Java is compiled only to make
the input DEX and is never used as a candidate fallback.

Separate regression tests inject compiler-invalid, incorrect and other-method-regressing
proposals into the real repair engine. Those proposals must leave source unchanged. They also
exercise paths containing spaces and full original-method matching when a file has no known
compiled baseline. They require the toolchain but no model download, keeping ordinary CI bounded.

Next evidence should come from predeclared independent Java/Kotlin/R8 projects with complete
project compilation, held-out successful cases and actual runtime observations where available.
Do not reinterpret this small experiment as a percentage of software that can be recovered.
