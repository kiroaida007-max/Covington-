# Local quantized coding assistant

Covington can optionally ask a small local coding model for method-body repairs after the
deterministic rules run out. The model proposes; the compiler and original-bytecode comparison
decide whether an edit is kept. This is experimental repair support, not universal recovery.

## Install and select

Python 3.11 or later installs a portable pack on Linux or Windows x64:

```sh
python3 scripts/ml/setup_local_assistant.py --out models/local-coder --size 1.5b
apkproject decompile app.apk --out recovered --profile profiles/android-full.json \
  --repair-assistant models/local-coder/assistant.json
```

On Windows, use `profiles/android-windows.json`. In the desktop Recover APK dialog, browse to
`assistant.json` under **Local coding assistant**. Verification and repair must remain enabled.
Leaving the field blank retains ordinary recovery. A missing, changed, oversized or unsupported
configuration disables this assistant and logs the reason; ordinary repair still works.

The default is the official Apache-2.0 **Qwen2.5-Coder-1.5B-Instruct Q4_K_M GGUF**, 1,117,320,768
bytes, with the MIT **llama.cpp b11565** CPU runtime. The smaller `--size 0.5b` choice downloads
491,400,064 bytes of official quantized weights; it has not been measured in this experiment.
These are upstream quantized weights, not newly trained or newly quantized weights. Q4_K_M uses
mixed quantization rather than making every tensor exactly four bits.

[`local-assistant-catalog.json`](../scripts/ml/local-assistant-catalog.json) pins repositories,
revisions, download sizes, SHA-256 values and license files. Downloads are checked before the
pack is installed. Native artifacts and weights are copied into a job snapshot and checked again
before inference. No model weights enter this Git repository or the default desktop installer.
Budget disk space for the download cache, installed pack and a separate job snapshot; the 1.5B
model alone occupies about 1.04 GiB in each. Select configuration files from a trusted local
installation: hashes detect changes, but locally supplied runtime hashes are not publisher signatures.

## Retrieval and engine context

Inference uses local BM25 retrieval, with no embedding service, vector database or HTTP server.
The built-in knowledge contains nine checked edits from the training partitions of GPUImage,
jsoup and Jackson, plus three clearly marked reference guides. It excludes validation/test
families. Source/license notices ship next to the knowledge resource. Every checked entry carries
source-unit and before/after fingerprints; reference advice carries no invented checked hashes.
Custom knowledge is bounded to 512 KiB and 256 total entries and remains untrusted prompt data.

Each prompt supplies one existing Java method, original DEX disassembly, bounded compiler
diagnostics, mechanical opcode guidance and at most two retrieved entries. For supported pure
static integer methods with at most two arguments, the existing V0 IR interpreter supplies at
most ten input/output hints, each limited to 1,024 interpreter steps. Those hints are labelled
`V0_IR_MODELED_EXECUTION`: they are modeled behavior, not observations from an Android device,
and do not prove untested inputs. Unsupported methods receive no interpreter approximation.

The model may return one body or abstain. Strict JSON parsing, balanced-body checks and an exact
declaration fingerprint constrain the edit to one existing body. Named class declarations and
escapes into sibling methods, imports or file headers are rejected; expressions inside the body
still require the compiler and complete-method comparison.
Chat control tokens are neutralized in prompt data; ordinary Java operators remain readable.
Instructions embedded in source or knowledge cannot authorize actions. No model-generated shell
command is executed. Candidate Java is compiled with annotation processing disabled.

## Acceptance and learning

Ordinary edits require a complete comparison of the same non-synthetic method set before and
after: at least one method must improve and none may regress. Compiler failure, truncated tool
output, missing comparisons and unknown evidence reject the edit and leave the source unchanged.
Compilation-error repairs have a separate conservative gate: every original non-synthetic method
with DEX code in the file must match after recompilation. Abstract/native declarations have no
DEX body to compare. An unknown baseline is never manufactured into a
positive pairwise training label. Full source compilation is refreshed after such a restoration.

Accepted code remains **INFERRED_GENERATED**. The accompanying round-trip evidence records which
methods matched. Bytecode matching is not a formal proof of all execution paths, external services,
reflection, native code or environment behavior. Runtime emulator observations remain a separate
engine capability; this assistant does not add method hooks or continuous native tracing.

Accepted bounded snippets can enter project-local retrieval memory for subsequent suggestions.
They do not modify the model weights or automatically promote the separate repair priority model.
That ranker's independent holdout requirements are unchanged, and its current research candidate
remains unpromoted. The privacy API classifier also remains separate and opt-in.

## Resources, privacy and records

Defaults are six inference calls, 45 seconds per call and 120 seconds total inference time,
within the recovery repair budget. The model uses a 4,096-token context, at most 384 output tokens,
CPU-only execution and at most four configured threads, further clamped by the resource governor.
The default sampled native resident-memory limit is 2,048 MiB for 1.5B and 1,536 MiB for 0.5B.
The model requires its weight size plus at least 512 MiB of native budget; smaller available
budgets disable it. Use the existing worker resource options to reserve capacity for heavy jobs.

The process supervisor applies cancellation/deadlines/output bounds and optional OS containment.
Linux `/proc` and Windows working-set sampling stop an inference process that exceeds the
configured limit or loses memory monitoring. Sampling is not a hard OS allocation guarantee.
The Windows adapter still needs native Windows validation. Inference is sequential under the
tool lease; the model is loaded separately for each call, trading throughput for bounded lifetime.

The setup helper downloads only public artifacts. Inference is local and uses offline mode;
APK/source/prompts are not uploaded. Other selected recovery options, such as Maven downloads
or OSV lookups, retain their documented network behavior. Temporary prompts and grammars are
deleted when the call ends and stale files are purged on resume. A crashed job can retain its
private work directory until resume or cleanup, so protect that directory like the APK itself.

- `recovery/local-assistant.json`: identities, references, statuses, timing, containment and sampled
  memory; no raw model prompts/replies.
- `recovery/repairs.json`: accepted edits and model/prompt provenance. Like recovered source, this
  contains private application code.
- `recovery/local-assistant-memory.json`: at most 32 checked local snippets; private project data.
- `recovery/learning-repairs.jsonl`: existing feature-only complete-comparison feedback; compilation
  restorations with unknown baselines are withheld.

To reuse checked memory deliberately, install a new pack with
`--knowledge recovered/recovery/local-assistant-memory.json`. No automatic cross-project upload,
sharing or fine-tuning occurs. Do not put private memory into a public repository.
For an interrupted whole-app job, use `apkproject resume-full JOB_DIR` or the desktop job history;
the `resume` command belongs to the separate method laboratory.

## Measurement

[`benchmarks/repair-assistant`](../benchmarks/repair-assistant/README.md) records the real local-model
experiment, rules-only and retrieval-disabled baselines, failures and resource measurements.
Controlled generated faults establish that this integration can propose and verify repairs;
they do not establish production APK accuracy or a general RAG improvement. Broader frozen,
independent Java/Kotlin/R8 projects and end-to-end compilation evidence remain necessary.

The screenshot's [REA project](https://github.com/morluto/rea) is already integrated through
`ReaEvidence` for Android package inspection and JavaScript module recovery. Its current Android
device tools describe observations through ADB; a wider tool catalogue alone does not supply a
trained repair model or correctness proof. This change keeps the pinned REA version and uses the
existing recovery engine's original DEX and interpreter evidence for repair proposals.
