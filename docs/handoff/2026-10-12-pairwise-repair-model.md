# Pairwise repair learning and expanded evidence

Continue on `codex/production-candidate`, draft PR #11. Parent was `213e3e9`.
The previous corpus/CI handoff and runtime handoff remain relevant. No release or merge.

## Changes

- Pairwise logistic proposal ordering replaces the default pointwise training objective.
  Prefer successful edits over checked unsuccessful alternatives for the same query; unrelated
  all-negative/all-positive queries do not teach ordering. Equal family/unit/query weight keeps
  compiler sweeps from dominating. Materialize at most 100,000 deterministic sampled pairs.
  Linear 14-feature Kotlin inference is unchanged; source-edit verification is mandatory.
- Preserve existing train/validation/test assignments and predeclare eight new family roles in
  a source lock. The frozen manifest references the actual hash-verified primary lock; origins,
  licenses and assignments must agree. Freeze rejects mismatched capture locks/partitions and
  keeps failed attempts/configurations separately instead of inventing negative labels.
- Evaluate independent source units and families, hit-at-one/three/five and ranks among checked
  candidates. Compare deterministic ordering, a training-only rule prior and the old pointwise
  learner. Production loading additionally requires two informative families per holdout and
  query/unit/family MRR improvement above 0.01 over every baseline, with no hit-at-five regression.
  Missing old fields and impossible support counts fail closed. Metrics are locally asserted
  metadata, not authenticated attestations; scores are not correctness probabilities.
- Fix collection of partial JADX 1.5.6 output: exit 3 is save-finished-with-errors, not process
  failure. Compile/isolate bounded usable subsets, allow up to 16 progress-making passes, retain
  preparation and process-group deadlines. Explicitly exclude/report >1 MiB generated files
  within the existing 256-file/64 MiB corpus limit. No original-source fallback or error stubs.
- Add pinned DOM4J, JDOM, Kryo, JavaEWAH, Jackson, ASM, SnakeYAML and Fastjson data, plus expanded
  training-only jsoup/GPUImage/Guava subsets. Public source/build scripts are not executed and
  no library dependency enters the shipping app. Custom license notices accompany metadata.

## Exact evidence

`benchmarks/repair-learning/expansion/` combines the unchanged archive with 189 additional complete
labels: 1,134 total, 19 positive and 1,115 negative. Eighteen new capture attempts, twelve completed,
six failed; withhold 437 compile failures and 24 incomplete comparisons. One oversized Fastjson
generated file was excluded in the bounded rerun. Twenty families were declared; nineteen supplied
labels. Training has 27 preferences from three units in two families. The five training ranking
queries improve from MRR 0.80 to 1.00, which is fitted-data performance only. The one independent
JDOM validation success remains third (MRR 1/3); no successful test units exist. The candidate is
unpromoted, and no independent ranking or recovery gain is claimed. Do not enable it by changing
eligibility or choose new test assignments based on these outcomes.

Model SHA-256: `92b94be3831b7353f46b0575af51fc721fefa70a5a81be8e584c684b5fed400a`.
Pinned corpus retraining reproduces model and card bytes. Local captures/diagnostics are in
`/workspace/scratch/repair-enhancement`; published files contain features/hashes/public metadata,
not APKs, Java or runtime logs. Commands and detailed denominators are in the expansion README.

## Validation and remaining limits

Full Gradle suite, with desktop performance enabled: 300 cases, zero failures/errors and one
optional library-benchmark skip. All 57 Python checks pass, covering fixed holdouts/primary-lock integrity,
repeated compiler-unit weighting, bounded deterministic pair sampling, failed-capture retention,
source conflicts, partial JADX output and byte-for-byte corpus/model reproduction. Product BOM
schema and 21 packaged JAR hashes pass. The PR records final validation and exact publication.

GitHub runner jobs were previously blocked before any steps by failed account payments/spending
limits. Owner action in Billing & plans remains necessary if unchanged; do not modify visibility,
spending or required checks. Native Windows screenshots/Job Objects/MSI remain unvalidated until
those jobs run. CodeRabbit is signed out; no external review is claimed.

Next useful work is broader independent **successful** repair coverage and compile-error repair
capability, then Kotlin/R8 and whole-project/end-to-end regression/resource checks on further
frozen projects. Current ordering features cannot make unsupported repair types possible.
Runtime method/API hooks and continuous native tracing are separate unfinished capabilities.
Do not claim original server implementation recovery, universal APK compilation, zero bugs,
calibrated correctness or a measured ML accuracy uplift.
