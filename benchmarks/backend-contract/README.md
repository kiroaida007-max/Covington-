# Backend contract against the app's own source

`apkproject contract app.apk --out api/` writes what the app reveals about its server:
- `api-contract.json`, with endpoints, parameters, models and evidence;
- `openapi.yaml`;
- `contract-audit.json`: per-route evidence, checked/withheld examples and contract/example hashes;
- a runnable mock server (`server-skeleton/BackendSkeleton.java`), which is SYNTHESIZED.

These two scripts measure how much of that is right, against the source the APK was built from.

| Script | Checks |
|---|---|
| `evaluate.py SOURCE api-contract.json --apk APP.apk [--details]` | Every Retrofit function in the source: method, path, each parameter's location and wire name, response type. Every Moshi model: JSON names, field types, enum values, Kotlin property names, nullability. `--apk` sets aside what R8 deleted (never-called functions, unused models), since nothing can recover those. |
| `check_mock.py BACKEND_DIR` | Rejects duplicate YAML keys, validates OpenAPI, starts the server on an OS-assigned port and exercises each audited route across HTTP methods. Checks response types, enums, nullability, list/map items and references, expected withholding, contract hash and route identity. Without an audit it checks legacy GET routes. |

`openapi.yaml` is also checked with `openapi-spec-validator` 0.7.1 and with a YAML loader that
rejects duplicate keys.

## Tusky 32.2 (R8 full mode)

Setup: the APK and the restored source tree from
`scripts/restore-tusky.py` (see `benchmarks/name-recovery/README.md`).

```
python3 -m pip install PyYAML==6.0.2 openapi-spec-validator==0.7.1
apkproject contract tusky-142.apk --out api
python3 benchmarks/backend-contract/evaluate.py tusky-restored api/api-contract.json --apk tusky-142.apk
python3 benchmarks/backend-contract/check_mock.py api
```

| Measure | Before (`53e77ec`) | Now |
|---|---|---|
| Endpoints (method + path) | 112 / 112 | 112 / 112 |
| Parameters, location and wire name (5 unnamed multipart parts included) | 250 / 255 | **255 / 255** |
| Response types | 112 / 112 | 112 / 112 |
| Models found, JSON field names | 83 / 83, 378 / 378 | 83 / 83, 378 / 378 |
| Field types | 348 / 378 | **377 / 378** |
| Enum fields: JSON values | 0 / 18 | **17 / 18** |
| Kotlin property names | 234 / 378 | **378 / 378** |
| Nullability | not recovered | **378 / 378** |
| `openapi.yaml` | invalid: duplicate keys, a repeated operationId | **valid**, 0 errors |
| Mock server, GET routes with schema-valid JSON (`8aa0222`) | not measured | **46 / 47** |

## Contract assurance (Codex continuation)

Rechecked the APK SHA-256 against `benchmarks/tusky-32.2/source-lock.json` and checked out its exact
recorded source commit `ff0c9f58874fafc72a53789f079ce0dc1a7e9207`. Extraction scores above remain
unchanged: 112 endpoints, 255 parameters, 83 models, 377/378 field types. These are measured
against upstream source, not inferred from the generated server's success.

The new independent model checker rejects invalid candidates before server generation. The HTTP
benchmark now exercises **109 distinct method/path/query routes** (112 declarations share some
routes): **107 responses served, 107 valid against OpenAPI, 2 explicitly withheld**. GET notification
examples have an unresolved non-null custom type; POST statuses has two conflicting response shapes.
The previous invalid notification response is now withheld. This improves honesty and consistency;
it does not recover the missing adapter or select the correct posting behavior.
The full decompile path, with compile/proof checks disabled for this backend evaluation, gives
the same scores after combining DEX and decompiled-source evidence. Successful HTTP bodies are
also checked against their audit's example SHA-256 and the standard OpenAPI 3.0 response validator.
Nullable references use an explicit null-only anyOf branch, because nullable/allOf does not allow
null in the referenced schema. Nullable enums also include null in their permitted values.

Additional regression cases cover wrong types, nulls, enum values, integer ranges, map/list items,
ambiguous and missing fully qualified models, recursive generation budgets, overloaded API methods,
control characters, absolute URLs, fixed/repeated query values, overlapping routes, HEAD/204,
405/Allow, encoded slashes and concurrent HTTP requests. The same Java regression fixture is
exported to `modules/app-recovery/build/contract-assurance/` for independent Python validation in CI.

`CHECKED_EXAMPLE` means a synthesized JSON example passed structural checks relative to the
recovered client model. It is not a recovered response, a measured server trace or a proof of
business behavior. Contract and example hashes identify the checked artifacts. SHA-256 is an
integrity identifier, not authentication of the original service. Unknown response shapes,
conflicting declarations and invalid examples answer 501. Dynamic URLs stay in the diagnostics.
No authentication, persistence or service state is guessed.

R8 removed one endpoint and three models: `GET /api/v1/notifications/{id}` is never called, and
`Instance.Usage`, `.Thumbnail` and `.Contact` are commented out of `Instance`.

### What was wrong, and why

- **Field types came from the model's constructor, assumed to be in JSON key order.** R8's proto
  normalization reorders constructor parameters. `Attachment.Size(width: Int, height: Int,
  aspect: Float, duration: Float)` became `(float, float, int, int)`, so width and height read as
  float. Types now come from the generated adapter itself:
  - its constructor calls `moshi.adapter(type, annotations, "property")`;
  - its `toJson` writes `name("key")` and then the value through exactly one of those adapters.
  
  R8 outlines parts of `toJson` into static helpers (write a value, then the next name), and
  hoists adapter fields into registers; both are followed.
- **Lists lost their element type.** `Types.newParameterizedType(List.class, Status.class)` is
  now rebuilt, including when R8 narrows its first parameter from `Type` to `Class`.
- **Enums were opaque class names.** Moshi's `@Json` is renamed by R8 (`th.h` in Tusky). It is now
  recognised by its members (`name(): String`, `ignore(): boolean`), and each constant's JSON
  value is read in declaration order from `<clinit>`.
- **Kotlin property names** come from the `get<Property>$annotations` methods Kotlin keeps for
  annotated properties, then from the reader's `unexpectedNull("property", "key", reader)` calls.
- **Nullability**: Moshi's reader calls `unexpectedNull` for exactly the non-null properties.
  Readers load every key once before their loop, so a may-dataflow over the control-flow graph
  follows the strings into each branch.
- **Unnamed `@Part`s** (`MultipartBody.Part` parameters) are named where the request is built:
  `createFormData("file", ...)`, renamed or not, followed through register moves and the `null`
  arm of `x?.let { part(...) }` by a may-dataflow. These names are marked `nameSource:
  call-site` (INFERRED); a part stays unnamed when call sites disagree.
- **OpenAPI**:
  - models are referenced by full name: `entity.Translation` and
    `Instance.Configuration.Translation` collided on their simple name;
  - unnamed parts produced duplicate keys;
  - `/api/v1/instance` and `/api/v2/instance` produced the same operationId;
  - multipart bodies were declared form-urlencoded;
  - map parameters and `Map<String, X>` values were dropped;
      - two declarations of one route now share an operation with alternative responses (`anyOf`
        in the assurance continuation, since shapes are not necessarily mutually exclusive).
- **Mock server**: past three levels of nesting it answered `null` even for non-null fields,
  which the app's own reader rejects. Non-null fields are now always filled, and nullable nested
  models are cut off after one level. Enum fields answer a real wire value.

### Known gaps

- `Notification.type` is a Kotlin sealed class with a hand-written adapter, not an enum. Its type
  stays the renamed class (`dg.j`). The audit catches the invalid null candidate and the mock
  withholds the route with 501; the missing adapter is still unresolved.
- Only Moshi codegen adapters and fields with wire-name annotations (Gson, Moshi, Jackson,
  kotlinx) are read. Gson models built purely by reflection, without annotations, only have
  field names.
