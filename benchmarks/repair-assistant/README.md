# Controlled local coding-assistant experiment

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
