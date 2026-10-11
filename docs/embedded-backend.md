# Embedded backend recovery

An APK may ship server handlers and business rules, for example an offline service or local
HTTP server. `decompile` now exports supported handlers to `backend/embedded/` alongside the
ordinary recovered app. Remote server implementations still require their own input.

```
apkproject contract app.apk --out api
# api/embedded/report.json: quick declaration scan, no execution or source verification
apkproject decompile app.apk --out recovered --profile profiles/android-full.json
# recovered/backend/embedded/: implementation/dependencies and recorded checks
```

The desktop Backend panel has an **Embedded Logic** button opening the result and launch
instructions. The existing mock server remains a separate contract-testing tool.

Detection requires concrete bodies and retained framework evidence:

| Evidence | What is detected |
| --- | --- |
| javax/jakarta JAX-RS | Class `Path` plus method HTTP verb annotations; class/method path components |
| Spring MVC | Controller marker and direct method mappings; path arrays/class prefixes |
| NanoHTTPD | Public instance session `serve` override in a subclass; routing remains unknown |
| JDK HttpHandler | Public instance `handle(HttpExchange)` implementation; routing remains unknown |

Declarations establish candidate handlers, not proof that an endpoint was mounted. Spring
conditions, filters, injection and all original runtime routing require the original framework
setup. Client Retrofit/Feign declarations and standalone URL strings are not server evidence.
Stripped or obfuscated markers, other custom routers, native and JavaScript servers may be missed.

The export follows whole-class DEX dependencies across all input parts: parent/interfaces,
fields, method signatures, annotation types and referenced methods/fields/types. Entire source
files, including sibling nested classes, remain intact. Files are copied from the recovered
project with SHA-256s, not regenerated business rules. Existing per-method bytecode comparisons
are attached. Missing sources, duplicate classes, runtime dispatch, reflection, native code and
analysis limits are reported. Reachability is conservative, not a complete dynamic call graph.

Limits: 128 handlers, 512 queued classes, 2 million decoded code units, 4,096 external types,
2 MiB per source file and 32 MiB total source. Diagnostic growth is capped. Cancellation is
checked during scanning and dependency traversal; compiler workers retain recovery timeouts.

## Runnable local adapter

The initial portable subset is unique literal GET routes with public static int/long methods,
0–8 int/long inputs with distinct explicit `QueryParam` names, and int/long results. The whole
exported class slice must have matching full-recovery round trips and no unresolved analysis
issues. A strict standalone javac build without Android or library stubs must then succeed.
That exact build is re-dexed and compared again, including constructors/static initialization
and helper methods. Only then are `run.sh` and `run.cmd` written.

From `backend/embedded/`, with a Java 17 JDK available:

```
sh run.sh 8080      # POSIX
run.cmd 8080       # Windows
```

Port `0` selects an available port; the adapter prints its actual loopback URL. For a recovered
`GET /pricing/quote` handler with integer `amount`, request
`http://127.0.0.1:8080/pricing/quote?amount=250`. The handler and its recovered helper calculate
the answer from this request. There are no fixed example responses in this adapter.

The adapter is **SYNTHESIZED transport**: it binds to 127.0.0.1, parses bounded query input,
returns decimal text, and uses a bounded two-thread executor. Missing/invalid/duplicate integer
parameters produce 400, unknown routes 404, other HTTP methods 405 and handler exceptions 500.
These are adapter policies, not reconstructed framework/server acceptance rules. The business
code is **DECOMPILED**, with separately recorded **ROUND-TRIP** results; fingerprint matches do
not prove all original service or deployment behavior. Recovery does not start the embedded
server; its existing pure-method checks may test eligible calculations separately.

Handlers with Android/native dependencies or framework construction requirements remain
available as recovered source with checks and dependency notes; their launchers are withheld.
All handlers currently share one conservative slice: a runtime issue in any selected handler
may withhold the portable adapter for that whole slice. Original databases, secrets, server
authentication, deployment configuration and remote logic are never invented. Editing source
invalidates the recorded hashes/checks; re-run verification before trusting modified logic.
