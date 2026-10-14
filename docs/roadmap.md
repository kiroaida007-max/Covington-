# Milestones and status

Status of the dossier's delivery gates (section 28) as of 2026-10-14. "Done" means the exit gate
is exercised by a test or by the fixture benchmark in this repository, not that the capability
generalises beyond the V0 scope.

[Software architecture](software-architecture.md) describes current module ownership, tool roles,
analyst workflow and trust boundaries. The original PDF remains background. CI activation is
parked until an execution route is available; workflow definitions are not passed native gates.

| Milestone | Exit gate (dossier) | Status | Where it is checked |
|---|---|---|---|
| M0 contracts and fixture rig | One reproducible original APK and complete provenance record | Done | `:contracts`, `:store`, `fixtures/`; fixture APKs rebuild byte-identically; journal + content-addressed store |
| M1 decoder and semantic slice | Positive and malformed-input tests; no unknown operation treated as modelled | Done | `D8GroundTruthTest`, `MalformedDexTest` (3,000-case corruption fuzz), `DecoderNegativeTest`, `OpcodeTableTest`, `DexLifterTest` |
| M2 first inverse loop | Independently execute recovered pure methods; seeded wrong candidates fail | Done | `ValidateTest`, `RecoveryJobTest`, benchmark (independent execution of hidden originals; 21/21 seeded mutants rejected) |
| M3 local experiments | A predeclared ambiguous pattern is resolved or accurately left ambiguous | Done for V0 features; compiler/round-trip-gated repairs and optional local proposals implemented; complete cross-job caches unfinished | `LocalExperimentsTest`, `reports/experiments.json`, repair and assistant regression suites |
| M4 complete Java/XML fixture | Clean build, installation, scenarios and one real source edit | Export/runtime implemented with a strict rebuilt Android fixture and seeded behavior mutation gate; broader rebuild corpus needed | JADX export, binary manifest inventory, `benchmarks/android-runtime/`, runtime scenarios |
| M5 held-out benchmark | Reported acceptance and unresolved cases, no silent stubs | Narrow source/mapping truth and frozen repair holdouts implemented; broad app-lineage benchmark and natural repair gains pending; ranker unpromoted | Tusky, R8 matching/name/diff benchmarks and `benchmarks/repair-assistant/` |
| M6 desktop and packaging | Same core gates through CLI and desktop | Native Swing, worker jobs/resume, bounded readers, source paging, audit/privacy/runtime/backend/status screens and packaging implemented; current-head native validation/signing pending | `modules/desktop`, Windows installer/capture workflow, JVM BOM |
| M7+ more frontends | Independent capability gates per family | JavaScript recovery and native boundary inventory implemented; general native/Flutter/Unity source recovery pending | REA/Wakaru integration and JNI mapping |

## Initial V0 delivery record (section 29)

| Day | Deliverable | Status |
|---|---|---|
| 1 | Repository boots; tools identified and hashes recorded | Done: `scripts/setup-toolchain.sh`, `profiles/v0-jvm-pure.json`, `apkproject doctor` |
| 2 | Original source hidden from the recovery input path | Done: `fixtures/*/hidden`, read only by `:benchmarks` |
| 3 | Malformed and traversal samples fail cleanly | Done: `ZipInventoryTest` |
| 4 | Stable class/method identities and range checks | Done: `DexFile` |
| 5 | Exact opcode capability manifest and decode fixtures | Done: `Opcodes`, `V0Semantics`, `docs/semantics/v0-opcodes.md` |
| 6 | Provenance and unsupported-operation output | Done: IR provenance, `CapabilityGap`, `apkproject inspect` |
| 7 | Ordinary source compiled from the candidate alone | Done: probe classes compiled without the original DEX |
| 8 | Forward compilation and diagnostic records | Done: `ForwardCompiler`, argv manifests, environment hashes |
| 9 | Correct expression passes the modelled check; wrong one fails | Done: `SmtEncoder`, section 19 negative cases |
| 10 | One end-to-end measured recovery report | Done: `benchmarks/results/v0-results.md` |

## Next engineering steps, in order

1. Preserve app-test evidence while stabilizing analyst workflows and hostile-project boundaries.
   Finish native validation and prolonged resource/cancellation/failure soak when runners are available.
2. Extract engine subsystems behind tested request/result interfaces; persist per-method checkpoints
   and probe/compile results under complete keys (source, flags, classpath, compiler hashes).
3. Extend runtime method/native collectors and semantic reconstruction one supported family at a
   time, with independent originals, seeded wrong candidates and explicit unsupported outcomes.
4. Expand the held-out corpus by app lineage and measure natural repair gains before quoting broad
   accuracy improvement or promoting a model. Larger training data alone does not establish accuracy.

Candidate publication is separate from merging and releasing. No gate promises zero bugs, universal
compilation or recovery of remote server code absent from the supplied artifacts.
