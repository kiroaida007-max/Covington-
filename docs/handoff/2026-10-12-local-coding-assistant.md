# Local quantized coding repairs

Continue on `codex/production-candidate`, draft PR #11; parent `0357b51`. No merge or release.
The pairwise-learning, runtime and Windows handoffs remain relevant to unchanged components.

## Implementation

- Add opt-in `--repair-assistant FILE` and desktop configuration selection. Provision official
  Apache-2.0 Qwen2.5-Coder 1.5B or 0.5B Q4_K_M GGUF with pinned MIT llama.cpp b11565 CPU binaries
  for Linux/Windows x64. Installer verifies public downloads, safely flattens native archives,
  includes licenses/provenance and installs a portable copied pack. No weights in Git/default app.
- Local bounded BM25 retrieval: nine checked training-partition edits from GPUImage/jsoup/Jackson
  plus three marked reference summaries, with license notices. Holdout families remain excluded.
  Custom knowledge is untrusted data, capped to 512 KiB/256 entries. Checked accepted snippets
  can enter bounded private project-local memory; weights are not fine-tuned automatically.
- Prompt one existing method, original DEX, bounded diagnostics/opcode guidance, at most two
  examples and at most ten supported pure-integer IR interpreter hints (1,024 steps each).
  These are modeled behavior, not Android runtime observations. Strict JSON/body/signature checks
  constrain edits to a body. Output/source/prompt/runtime/knowledge hashes identify proposals.
- Deterministic proposals run first. Model suggestions cannot approve themselves. Ordinary
  acceptance now explicitly requires the complete positive comparison label; compiler failure,
  unknown/missing comparisons, regressions, timeout/truncation reject the edit. Compilation-error
  restoration requires every original non-synthetic method with code to match; unknown baselines
  are withheld from pairwise labels. Refresh whole-source ECJ compilation and quality afterwards.
- Bound calls, context/output, deadlines and CPU; sampled native RSS on Linux/Windows with
  optional OS containment. Delete temporary prompts, purge stale files on resume and preserve
  cancellation even if cleanup fails. Private job files can remain after a crash/cleanup failure.
  Accepted edits retain INFERRED_GENERATED provenance. This is not formal equivalence.
- Honor exact Tiny method mappings for proposal location. Format source lists for the actual
  launcher: raw paths for RelaxedJavac's JavaFileManager, escaped quoted paths for standard javac.
  Apply the same correction to JavacOverlay; paths containing spaces now have real compiler tests.
- Make the enlarged native recovery dialog scrollable/resizable within usable screen height.
  Add report/layer/cache integration, private checked memory and feature-only feedback.

## Evidence

`benchmarks/repair-assistant/controlled-results.json` retains all 15 real-inference trials with
input hashes, model/runtime/knowledge/prompt identities, statuses, references, timing and sampled
native memory. Five generated faults: three development cases plus two declared before the
revised-protocol run. Rules fix 0/5; both model arms fix 3/5 (unsigned shift, long suffix, XOR).
Boundary and dropped negation remain unresolved and in the denominator. Retrieval adds no
measured marginal gain. Each accepted case matches all three original methods; unknown-baseline
restoration does not invent a pairwise label. This is not independent APK accuracy evidence.

Tested 1.5B model SHA `cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046`;
runtime release b11565, revision `f9a95aeb2096d0c0218e0cd6e6d901cd37be798a`;
knowledge SHA `6b0a0fc44baaf7d73c5d34c1bd805d7eecac1b26871c7d599f4daf7c3722beee`.
Two effective CPU threads, 2,048 MiB native limit; largest sampled RSS 1,952,563,200 bytes
(~1,862 MiB), excluding engine/compiler memory. Timings exclude snapshot preparation and some
trials overlapped developer builds. 0.5B and Windows inference were not measured.

Full Gradle suite with desktop performance enabled: **307 cases, zero failures/errors, one
optional external library-benchmark skip**. All **61 Python checks** pass. Product BOM schema
and **21 packaged JAR hashes** pass. Real-compiler integration rejects invalid, incorrect and
other-method-regressing proposals without source changes; accepts complete original restoration
without fabricated feedback. Existing small-app repair/checkpoint regression passes after the
source-list format correction. Checked-exception overlay tests include paths with spaces.

The packaged Linux desktop capture passed **17 screens/dialogs**, source content/filtering,
navigation, mock lifecycle, worker isolation, cancellation/publication and job-history checks.
The recovery dialog was inspected at 1280x720. Packaged CLI recovery with the assistant config
forwarded through the isolated worker compiles 1/1 files and matches 3/3 methods on a clean
generated input; it needs zero inference calls. `resume-full` returns that completed result.

Local artifacts: `/workspace/scratch/local-repair-assistant` (download cache/pack, experiment-004,
full-regression-final.log, final-python.log, screens-final, full-job/full-recovered). Synthetic
laboratory prompts/replies stay there; production retains hashes/statuses and accepted private
snippets, not raw prompts/replies. Public repository contains compact metrics/knowledge/notices.

## Remaining constraints

The separate 14-feature pairwise ranker remains unpromoted; corpus/holdout assignments and
promotion criteria are unchanged. The privacy classifier remains opt-in and separate. Broader
frozen successful Java/Kotlin/R8 and end-to-end real-project compilation evidence is still needed.
Do not claim training/quantization performed here, general RAG uplift, universal APK compilation,
zero bugs, original remote server recovery or best-in-class accuracy. Runtime method/API hooks
and continuous native tracing remain separate unfinished work.

REA is already pinned/integrated for Android inspection and JavaScript recovery. Current upstream
documentation was inspected; no unverified version bump or claim that its tool catalogue proves
recovery accuracy. Native Windows screenshots, memory/Job Object enforcement and MSI lifecycle
remain unvalidated while GitHub jobs are blocked before execution by account billing/spending
limits. Owner action in Billing & plans is still required; do not alter billing or required checks.
Keep PR draft. No external CodeRabbit review is claimed.

See `docs/local-repair-assistant.md` for setup, artifacts, privacy, resources and acceptance scope.
