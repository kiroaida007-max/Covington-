# Independent Android runtime fixture

`android-runtime.yml` builds the known original from `hidden/MainActivity.java`, recovers it,
strictly recompiles only exported Java against Android 30, and reuses original resource bytes.
The two APKs run independently on one disposable rooted API 30 emulator. The original must
satisfy the expectations in `scenario.json`; canonical visible app UI must match at each
observation. A separately compiled mutated candidate must report `DIFFERENT`, not an execution
failure. Reports identify APK and scenario SHA-256 values.

The harness refuses physical devices, an existing target installation and shared app UIDs.
App-UID IPv4 and IPv6 traffic is blocked before launch; cleanup stops and uninstalls the package
before removing its rules. APK installation itself is outside that per-UID launch boundary.
Use only disposable emulator instances for these benchmark fixtures. This is not an isolation
boundary for arbitrary hostile APKs; the emulator and host tools need their own sandbox.

Scope is the declared visible UI scenario. Geometry and system UI are excluded; hidden state,
timing, network contracts, persistence and unvisited paths are not certified. The fixture is
regression data and must not be labeled a held-out real-app corpus. Adding real app scenarios
requires pinned original inputs, independently authored expectations and supported strict builds.
