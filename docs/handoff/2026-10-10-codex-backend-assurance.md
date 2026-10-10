# Handoff: contract assurance

The user asked for greater distinction, stronger backend work and more accuracy. The PDF remains
background. This continues the native desktop handoff, incorporating Claude's `8aa0222` backend
extraction improvements; it does not replace or claim to solve arbitrary APK source compilation.

Branch: `codex/backend-contract-assurance`. This work is stacked on `codex/windows-desktop-gui`
and incorporates the newer `ccr-b00e80ab-1evg7q` commit. Keep the Windows LAF/layout and earlier
recovery fixes. No main/base merge has been requested.

## Behavior and assurance

- `ContractAuditor` produces `backend/contract-audit.json` from both `contract` and `decompile`.
  Every route has evidence, a stable ID, structural check status and optional example/hash.
  The audit hashes the canonical serialized contract; the server embeds that hash. Desktop counts
  are shown only when the loaded audit belongs to the loaded contract.
- `ContractExampleValidator` parses JSON independently of YAML rendering and checks recovered
  model identities, properties, nullability, enums, arrays/maps, numbers and dates. Seeded wrong
  candidates must fail. Exhausting a validation budget fails, never silently passes.
- Only `CHECKED_EXAMPLE` responses can be generated as successful example responses. Unknown,
  conflicting, recursively invalid and ambiguous examples answer 501 with explicit diagnostics.
  Examples remain SYNTHESIZED. Checks are relative to the client contract; they do not establish
  original server behavior, completeness, authentication, persistence or service state.
- Template in `modules/app-recovery/src/main/resources/backend/BackendSkeleton.java.template`:
  loopback binding, OS-assigned port support, bounded worker count/queue, shutdown cleanup,
  deterministic literal-route specificity, conservative ambiguity handling, fixed query matching
  including repeated values, 405/Allow, correct HEAD/204 and closed exchanges. No external libraries.
  `X-Apkproject-Evidence`, `-Contract` and `-Route` identify synthesized responses and their artifacts.
  The descriptive operation header is sanitized; Java/JSON/YAML controls are escaped correctly.
- API extraction keeps overloads by full signature instead of collapsing by operation name.
  DEX extractor failures are recorded in notes/audit instead of becoming silent empty lists.
  Fully qualified model names never resolve to a different package's class with the same simple name.
- OpenAPI preserves declaration variants, skips dynamic @Url routes without inventing `/`, normalizes
  absolute and equivalent placeholder paths, records fixed queries and check statuses, uses anyOf
  for alternative nonexclusive shapes, and labels successful status codes as synthesized choices.
  Parameters shared by declarations keep alternative types; header names are compared without case.
  Void examples use 204; withholding uses 501.
- Example generation has node/size limits. It can withhold cyclic/large models without generating an
  exponentially growing object. This is a local contract mock, not a production service.

## Measured validation

Local JVM suite: 196 tests, 195 passed, one opt-in benchmark skipped, zero failures/errors.
Python checks: 13 passed. `ContractAssuranceTest` has eight tests, including compiling the generated
Java and testing live HTTP, 20 concurrent requests, overlap ambiguity and deliberately bad examples.

The JVM test exports a fixture to `modules/app-recovery/build/contract-assurance/`. The independent
Python check validates OpenAPI (including duplicate key rejection) and exercises 14 routes:
11 schema-valid examples, 3 intentional 501 responses. Linux CI runs this and uploads the fixture.
Windows CI requires both new contract suites to run without skipped tests and still exercises
the packaged UI and all fourteen native captures. Consult the Actions run for the current commit;
previous Windows green runs do not validate this branch.

Tusky APK downloaded from the locked F-Droid URL and SHA-256 verified:
`3e8fcc49a80d4c30ab6f6037e51402c77e2694d27ec19ae5b8a93cd08b6caffa`.
Ground truth source checked out at `ff0c9f58874fafc72a53789f079ce0dc1a7e9207`.
Independent source scores unchanged: 112/112 endpoints, 255/255 parameter wire names, 112/112
response types, 83/83 models, 378/378 wire/property names and nullabilities, 377/378 field types,
17/18 enum sets. The remaining sealed custom adapter is not recovered.

HTTP check on the bytecode-only export: 109 distinct routes from 112 declarations, 107 served,
107 schema-valid, 2 withheld. GET notifications has an invalid non-null custom type candidate;
POST statuses has conflicting ScheduledStatusReply/Status responses. Unlike the older GET-only
benchmark, this checks all audited HTTP methods and detects a checked route returning 501 or
an unchecked route returning success. This is improved consistency, not a fabricated 100% score.

The full Tusky `decompile` path was also run with source compile/proof/repair checks disabled to
isolate backend extraction and source/DEX evidence merging. Its export gives the same source scores
and 107/109 checked responses, and its actual desktop project passed the capture/lifecycle checks
under Linux. This is not a Tusky compilation or native Windows rendering result. The independent
HTTP checker also matches each successful body against its recorded example SHA-256.

The separate NotesDemo full recovery still compiles 2/2 Java files, has 9/10 methods round-trip
equivalent, one V0 proof and one verified division repair. This does not show full Tusky rebuilding.

## Reproduce

```sh
./gradlew test :cli:installDist :desktop:installDist
python3 -m unittest discover -s scripts/tests
python3 -m pip install PyYAML==6.0.2 openapi-spec-validator==0.7.1
python3 benchmarks/backend-contract/check_mock.py modules/app-recovery/build/contract-assurance
modules/cli/build/install/apkproject/bin/apkproject contract tusky-142.apk --out api
python3 benchmarks/backend-contract/evaluate.py tusky-source api/api-contract.json --apk tusky-142.apk
python3 benchmarks/backend-contract/check_mock.py api
```

Cloud-local reproduction artifacts live under `/workspace/scratch/backend-ground-truth/`.
Do not commit the APK or source checkout. Follow AGENTS' backend benchmark rule when changing
extraction or rendering. Future useful work: recover the custom adapter from actual bytecode facts,
support user-supplied response traces with provenance, and add a separate evidence level for those
traces. Do not guess business semantics from a route name.
