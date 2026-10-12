# Checked corpus and CI diagnosis

Continue on `codex/production-candidate`, draft PR #11. Parent before this work was `1b60ffa`.
Keep the original Claude/backend/UI worktrees intact and review the combined candidate against
`main`. The previous runtime handoff contains the real emulator evidence and runtime boundaries.

## CI root cause

The connected GitHub CLI became available and exposed the check annotations that the narrower
connector could not read. Windows check `114725957696`, Linux `114725956304` and Android
`114725955914` all report that recent account payments failed or the spending limit must be
increased. Runs: Windows `38223715509`, Linux `38223715466`, Android `38223715396`.
These jobs did not receive a runner and executed zero steps. This is account billing, not a
test result. GitHub Settings → Billing & plans needs owner action. Do not change repository
visibility, spending limits, signing identities or required checks to conceal it.

Repository actions-permission administration returned HTTP 403 through the connected integration;
the repo connection cannot fix billing. The Windows candidate push gate already exists. Re-run
the exact candidate after billing is resolved; native screenshots, Job Objects and MSI lifecycle
remain unvalidated on this candidate. Keep the PR draft and publish no release.
CodeRabbit CLI is still signed out; no CodeRabbit review ran. No key or signing identity was invented.

## Corpus and training

- Add a source-lock-driven POSIX collector and Kotlin benchmark laboratory. Source archives,
  published dependency binaries, POM/license evidence and toolchain hashes are pinned. Compile
  unchanged original source to DEX, decompile it, then independently test bounded proposals.
  Candidate compilation gets decompiled source and dependency binaries, never original source
  or fabricated error stubs. Failed compilation/incomplete comparison produces no training label.
- Collect 13 artifacts from 12 project families in 23 configurations. The corpus contains 438
  decompiled files, 8,377 baseline method comparisons and 1,274 attempted proposals. Training
  has 945 complete trial rows: eight positive and 937 negative. Withhold 302 compiler failures
  and 27 incomplete comparisons. Positives occur in four source units from jsoup and GPUImage;
  variants are correlated. This is a bounded library subset, not broad Android app accuracy.
- Freeze feature-only feedback and train a reproducible 14-feature ranker. It is unpromoted:
  the two validation and two test families contain no mixed-success ranking queries. A ranking
  or end-to-end recovery gain is unmeasured. Production keeps deterministic ordering.
  See `benchmarks/repair-learning/README.md` and the model card. Do not present zero-support
  metric placeholders as zero-percent accuracy or enable this model by changing eligibility.
- Make learning labels require complete, known comparisons of the same methods on both sides.
  Unknown/missing/duplicate baseline methods cannot produce positive feedback. Source-edit
  acceptance rules are unchanged. Engine-content checkpoint identity invalidates older code.
- Keep different original input DEX groups separate during deduplication/ranking, so identical
  candidate text against different inputs does not conflict. Add token fingerprints that ignore
  comments/spacing while preserving literals and operator boundaries; actual source hashes
  remain intact. This removes local-path generator comments from deduplication identities.
- Promotion now needs independent source-unit support, not merely repeated compiler variants:
  at least five mixed-outcome validation units and ten test units plus ranking improvement.
  Missing support fields in older models cause safe deterministic fallback.
- Bound archives/source scanning/feedback and add whole owned process-group cleanup on collector
  timeout. Collecting sources is POSIX-only; training and packaged Kotlin inference are portable.

Local captures are under `/workspace/scratch/repair-corpus`. The first collection probe mistakenly
invoked JADX's GUI jar entry point; its failed outputs were preserved and contain no training
labels. The collector now calls `jadx.cli.JadxCLI`. The final GPUImage rerun uses the corrected
collector, same input DEX and checked outcome counts. Retraining the pinned corpus reproduced
model/model-card bytes; the frozen dataset contains no application source or APK bytes.

## Validation and remaining work

Final full suite: 300 Kotlin cases, zero failures/errors, one optional external library-benchmark
skip; the desktop performance case was enabled. Python: 45 checks passed. Product BOM: schema
and exact hashes for 21 packaged JARs passed. The final GPUImage run reproduced all 30 canonical
trial records in a different output directory, including input DEX, token fingerprints, features,
labels and unit identities. Pinned-corpus retraining reproduced model and model-card bytes.
The PR records publication state and exact-commit CI results. Relevant regression
coverage includes incomplete learning evidence, token fingerprints/literal/operator preservation,
different input DEX targets, comment-only duplicates, compiler-variant unit support, immutable
source pins/AAR extraction and killing a compiler descendant on timeout. Real full-recovery
integration still produces successful checked learning feedback.

The collector observes only the existing repair families and compiling source subsets. Expand
real successful repairs and compile-error recovery before expecting a strong model. Keep a further
frozen end-to-end test set, measure accepted regressions and resource costs, and preserve family
grouping/attribution. No universal compilation or measured accuracy uplift is claimed. Method/API
hooks and continuous native tracing remain separate runtime capabilities; billing cannot be solved
by repository code. Private APKs, Java source and runtime logs are not uploaded to training services.
