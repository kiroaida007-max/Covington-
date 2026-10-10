# Handoff: custom scalar wire recovery and response alternatives

User requested fixes for the notification custom adapter and both response shapes for posting a
status. This follows `2026-10-10-codex-backend-assurance.md` on the same
`codex/backend-contract-assurance` branch and draft PR #4, stacked on draft #3. Do not merge bases.

## Implemented

- `CustomScalarIndex` follows Moshi adapter registration, the constructor's integer discriminator,
  and specialized serializer branches. It discovers the writer's string-value operation through
  the String serializer, distinguishes it from writing a property name, and tracks the custom
  model's String field through moves and nullable branches. Analysis budgets fail closed. Supported
  serializers have no exception handlers. No Tusky class name or wire value is hardcoded.
- `ModelField.wire` keeps supported scalar JSON shape, known example values and bytecode locations,
  separately from original/obfuscated Java identity. Known values remain open, not a closed enum.
  Rendering, generation and independent example checking consume the same recovered wire facts.
- Tusky `Notification.type`: registered R8-merged adapter `gg.d`, branch discriminator 1, writer
  `th.r.y(String)`, model field `dg.j.a`, and fourteen singleton values. Source ground truth confirms
  the names and its Unknown fallback. The Java name `dg.j` remains obfuscated; do not change the
  legacy Java-type score to claim this name was recovered.
- Multi-response routes retain separately checked `responseVariants` with IDs, type, operations,
  evidence, example and SHA-256. `X-Apkproject-Variant: <id>` selects a mock example. Invalid variants
  still return 501, unknown or repeated selectors return 400, and no selector remains 501 for a
  genuinely ambiguous route. Equivalent/overlapping routes cannot bypass withholding via a variant.
  The selector is SYNTHESIZED, not an original API header or a recovered request-dependent rule.
- Tusky immediate/scheduled status requests share one route and one request model. Both Status and
  ScheduledStatusReply are supported as separately checked examples. The exact upstream client uses
  `scheduledAt.isNullOrEmpty()` to choose a declaration. The optimized call-site condition is not
  automatically recovered into a server dispatch rule. Do not claim original backend reconstruction.
- CLI, full-recovery report and desktop audit footer distinguish checked default examples,
  withholding by default, and selectable checked variants. OpenAPI exports variant metadata.
  Independent Python HTTP checks probe every default and every variant and verify selection headers.

## Validation and reproduction

Local full JVM suite: 198 tests, 197 passed, one intentional opt-in benchmark skip. Python: 13 passed.

The locked Tusky APK/source hashes are unchanged from the preceding handoff. Source agreement is
still 112/112 endpoints, 255/255 parameters, 112/112 response types, 83/83 models, 378/378 wire/property
names and nullabilities, 377/378 Java field types, and 17/18 enum-value sets. The custom open string
is separate from the legacy enum/name measurement. Its fourteen known values match the exact source.

Tusky HTTP: 109 routes, 111 probes, 110 responses pass standard OpenAPI 3.0 validation, independent
completeness checks and recorded example hashes. These are 108 default responses (notifications now
included) plus the two explicitly selected posting examples. One unselected posting request still
answers 501. Regression fixture: 15 routes, 19 probes, 15 checked responses and four expected 501s
(three default withholding cases and one invalid selected alternative).

New tests cover actual compiled DEX with obfuscated/merged adapters, correct and incorrect constructor
selectors, unregistered branches, property-name versus value writes, open future strings, invalid
JSON, independent alternative checking, stable variant IDs, unknown/repeated selectors and refusing
invalid variants over live HTTP. Existing portability/concurrency/native desktop checks remain.

```sh
./gradlew test :cli:installDist :desktop:installDist
python3 -m unittest discover -s scripts/tests
python3 benchmarks/backend-contract/check_mock.py modules/app-recovery/build/contract-assurance
apkproject contract tusky-142.apk --out api
python3 benchmarks/backend-contract/evaluate.py tusky-source api/api-contract.json --apk tusky-142.apk
python3 benchmarks/backend-contract/check_mock.py api
```

Local outputs: `/workspace/scratch/backend-ground-truth/custom-api/`; logs prefixed
`/workspace/scratch/backend-custom-`. Consult current-head Actions for final Windows/Linux CI results.
Never use a previous green run as evidence for this head. APK/source artifacts remain uncommitted.
