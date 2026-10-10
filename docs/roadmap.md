# Milestones and status

Status of the dossier's delivery gates (section 28) as of 2026-10-09. "Done" means the exit gate
is exercised by a test or by the fixture benchmark in this repository, not that the capability
generalises beyond the V0 scope.

| Milestone | Exit gate (dossier) | Status | Where it is checked |
|---|---|---|---|
| M0 contracts and fixture rig | One reproducible original APK and complete provenance record | Done | `:contracts`, `:store`, `fixtures/`; fixture APKs rebuild byte-identically; journal + content-addressed store |
| M1 decoder and semantic slice | Positive and malformed-input tests; no unknown operation treated as modelled | Done | `D8GroundTruthTest`, `MalformedDexTest` (3,000-case corruption fuzz), `DecoderNegativeTest`, `OpcodeTableTest`, `DexLifterTest` |
| M2 first inverse loop | Independently execute recovered pure methods; seeded wrong candidates fail | Done | `ValidateTest`, `RecoveryJobTest`, benchmark (independent execution of hidden originals; 21/21 seeded mutants rejected) |
| M3 local experiments | A predeclared ambiguous pattern is resolved or accurately left ambiguous | Done for V0 features (branch polarity, operand order, ternary form); probe results are not yet cached across jobs | `LocalExperimentsTest`, `reports/experiments.json` |
| M4 complete Java/XML fixture | Clean build, installation, scenarios and one real source edit | Not started | needs objects, fields, calls, binary XML/ARSC readers, AAPT2, Android export, ADB runtime |
| M5 held-out benchmark | Reported acceptance and unresolved cases, no silent stubs | Not started | current fixtures are development fixtures, not held out |
| M6 desktop and packaging | Same core gates through CLI and desktop | Not started | CLI only |
| M7+ more frontends | Independent capability gates per family | Not started | |

## The first ten working days (section 29)

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

1. Persist probe and compile results under full cache keys (source, flags, classpath, compiler
   binary hashes) so resumed and repeated jobs skip identical work.
2. Extend the semantic model in the dossier's order: loops with bounded checks, then `long`,
   then switches (payloads are already decoded), then fields and static calls with explicit
   contracts.
3. Binary XML and ARSC readers (`:formats-resources`), then the Android project exporter and an
   ADB runtime worker for independent execution of the original APK (M4).
4. A held-out fixture corpus split by project lineage before any accuracy number is quoted (M5).
