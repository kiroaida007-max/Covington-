# ADR 0003: Whole-application recovery, front end to backend

- Status: accepted
- Date: 2026-10-09
- Context: "make this programme work from front end to backend … the source code from front end to
  back as result after giving it any APK", with REA (github.com/morluto/rea) offered as a tool.

## What an APK contains, and what it does not

| Layer | In the APK as | Best possible recovery | Evidence level we report |
|---|---|---|---|
| UI layouts, strings, styles, assets | compiled XML, raw files | exact (decoded) | DECODED / OBSERVED |
| Manifest | binary XML | exact (decoded) | DECODED |
| App logic (Java/Kotlin) | DEX bytecode | readable Java source | DECOMPILED, then PROVED / ROUND-TRIP EQUIVALENT per method |
| Third-party libraries | DEX bytecode | the real published artifact | VERIFIED IDENTITY |
| Web front ends (Cordova, Capacitor) | HTML/CSS/JS files | exact | SOURCE AS SHIPPED |
| React Native | JS bundle or Hermes bytecode | modules (plain JS); inventory only (Hermes) | DECOMPILED / BINARY ONLY |
| Native code (C/C++, Flutter, Unity IL2CPP) | ELF machine code | exports and JNI bindings, not source | OBSERVED |
| **Backend server** | **not present** | the client's contract with it | INFERRED / SYNTHESIZED |

The last row decides the honest shape of "front end to backend". A server's code runs on the server;
the APK only holds the code that calls it. We recover everything the client reveals about the server
(base URLs, every literal URL, Retrofit endpoints with parameters and types, JSON models with wire
names, GraphQL documents, cloud service configuration) as `backend/api-contract.json` and an
OpenAPI 3 draft. We also generate a compilable server skeleton with one 501 handler per inferred
operation. It is labelled SYNTHESIZED in its first line, so nobody mistakes it for the original.

## Decision: a hybrid of mature decompilers and our own verifier

Writing a full Java decompiler from scratch would take years to reach JADX's coverage. Our
advantage is different: we can *check* a decompiler's output against the bytecode it came from.
So `apkproject decompile`:

1. **Generates** with the best available tools. JADX 1.5.6 exports the Gradle project (code and
   resources). REA's `inspect-android-package` is an independent second reading of the manifest and
   package facts. REA with Wakaru recovers modules from JavaScript bundles.
2. **Removes toolchain artifacts.** D8 platform stubs and unreferenced D8 desugaring helpers were
   never source.
3. **Replaces libraries with the real thing.** A library is identified by:
   - META-INF version files, pom.properties and the kotlin-stdlib version constant;
   - POM dependencies of libraries already verified.

   It is fetched from Google Maven or Maven Central (SHA-1 checked) and accepted only if its classes
   are in the APK and ≥98% of their method signatures agree. Its decompiled copy is then deleted
   and an `implementation` dependency added. (Kotlin Multiplatform root artifacts are checked
   against their `-jvm`/`-android` platform artifact.)
4. **Verifies every remaining method**, at three strengths:
   - *Proved*: methods inside the V0 model are lifted from bytecode and checked with Z3 against the
     decompiled method, which is replaced by our validated reconstruction if it is refuted.
   - *Compile check*: ECJ with `-proceedOnError` gives per-file diagnostics.
   - *Round trip*: error-free files are recompiled with javac, dexed with D8 in release and debug
     mode, and every method is compared with the original by dataflow fingerprint:
     - EQUIVALENT_DATAFLOW: same operations and operand origins, register allocation ignored.
     - EQUIVALENT_MODULO_LAYOUT: additionally ignores block order, branch polarity, return-tail
       sharing, constant placement, array-literal encoding, null casts and Object-method
       dispatch type.
5. **Repairs what fails, but only verifiably.** For methods that do not round-trip, candidate
   edits for known decompiler mistakes are tried in batches:
   - a dropped widening before `/` or `*`;
   - `Outer.this.new Inner()`;
   - casts absent from the original.

   An edit is kept only if recompiling shows more equivalent methods and none worse. Every kept
   edit is listed with its before/after line in `recovery/repairs.json` and RECOVERY.md.

### Why the comparison ignores layout

Measured on ApiDemos, the first strict comparison reported 857 differing methods. Inspection showed
most were not decompiler errors:

- ECJ codegen differs from javac's (we switched to javac for the round trip);
- the original's D8 ran in release mode despite carrying debug info (we try both modes);
- anonymous classes were numbered differently (we match them by structure);
- synthetic accessors were named differently (we look through them).

What remains different after these is where real decompiler errors live. The clearest example is
JADX printing `float ratio = width / height;` (integer division) where the bytecode converts both
operands to float first.

### Obfuscated release builds (R8)

Most published apps are built with R8. R8 renames classes and members, reuses one short name for a
package, a class and a field, inlines and merges code, and strips metadata. Each of these broke an
assumption above, measured on Tusky 32.2 (F-Droid, R8 8.13 full mode):

| R8 effect | What broke | What we do |
|---|---|---|
| Retrofit's `@GET`, `@Path`, ... renamed | text-based endpoint extraction found 0 endpoints | endpoints are read from DEX annotations; each renamed annotation type is identified by aligning Retrofit's own parser (its fixed order of `instanceof` tests), anchored by branch-unique strings reached through control flow. Result: 112 endpoints, every verb and parameter kind |
| Moshi models, kotlin.Unit, Continuation renamed | no models, response types `Object` | keys from Moshi codegen adapters (`JsonReader.Options`), Unit recognised by its `toString`, Continuation by shape. Result: 83 models with Mastodon's field names and types |
| Several members with one name | JADX renames them (`f16765a`), every reference differs | JADX's `renamed from` comments become a rename map for the comparison |
| Package `k1` and class `b0.k1` | Java obscures the package (JLS 6.4.2): 2,000+ compile errors | a Tiny v2 mapping moves obscured packages (`k1` → `k1_`) and renames obscuring fields before JADX runs; the comparison maps both back |
| Trivial constructors inlined, field stores before `super()` | constructors never matched | the comparison looks through `super(sameArgs)`-only constructors and orders stores to `this` like javac |
| Libraries renamed and shrunk | no library matches its artifact | reported, not guessed: these stay decompiled source |

D8 and R8 stamp their version, mode and min-api into the DEX (`~~R8{...}`); RECOVERY.md reports it.
The remaining gap on R8 apps is mostly code that JADX cannot express as Java at all (unboxed enums,
merged classes); those files are reported as not compiling and their methods as not compared.

### Why not native assembly, again

Unchanged from ADR 0002: the time goes into JADX, javac, D8 and Z3. The engine's own parsing and
comparison take seconds.

## Consequences

- Every output file has an evidence level; RECOVERY.md lists per-layer status and what was not
  recoverable.
- `decompile` needs the full toolchain (`scripts/setup-toolchain.sh --full`) and, for library
  verification, network access to Maven repositories (results are cached and SHA-1 verified).
- Round-trip equivalence is strong evidence, not proof: two methods with the same dataflow
  fingerprint can still differ in behaviour that the fingerprint abstracts away. Only methods in the
  V0 model are proved.
