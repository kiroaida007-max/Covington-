# Runtime behavior analysis

`apkproject runtime` and the desktop **Runtime Behavior** page execute an APK and report
observations separately from static privacy/code inference. This is a bounded, scenario-driven
observer. It is not a universal tracer or proof of whole-app equivalence.

## Run a session

Install Android SDK platform-tools/build-tools and start a fresh rooted API 30+ emulator. The
desktop detects tool paths from `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or accepts explicit paths.
The emulator is selected by its exact serial; physical devices, secondary users, an existing
target installation, and shared app UIDs are refused.
Boot must be complete. The engine wakes the display and asks Android to dismiss a normal
keyguard; it does not bypass credentials or dismiss crash/ANR dialogs. A system error overlay
blocks the scenario and is reported as a failed observation.

```sh
apkproject runtime input.apk --out runs/session-001 \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2" \
  --serial emulator-5554 --scenario scenario.json
```

The output must be a new directory. With no scenario the program uses the APK's launcher,
waits one second and takes an observation. APKs without a launcher need an explicit scenario.
Sessions accept ordinary, installable APKs, not a split bundle or AAB by itself. Signed APKs
are installed as supplied; the engine does not repack or resign arbitrary inputs.

```json
{
  "schemaVersion": 1,
  "package": "dev.example.app",
  "activity": ".MainActivity",
  "actions": [
    {"kind": "wait", "milliseconds": 1000},
    {"kind": "snapshot", "contains": "Ready"},
    {"kind": "tap_text", "text": "Calculate"},
    {"kind": "snapshot", "contains": "Result"}
  ]
}
```

Supported actions: snapshot (optional text expectation), unambiguous app-UI text tap (case-insensitive,
including Android's all-caps button rendering),
Android key code, bounded wait, and ASCII text entry. Unknown actions, shell metacharacters in
text entry, invalid component identities, excessive actions and oversized scenarios are refused.
All remote shell data arguments are explicitly quoted; the scenario cannot supply shell commands.

## Evidence collected

| Collector | Actual observation | Limits |
|---|---|---|
| UI | Visible app nodes and text at declared snapshots | System UI/geometry excluded; 500 nodes, 256 characters per field; capture bounds disclosed |
| App logs | UID-filtered logcat since the session's emulator-clock boundary; crash log excerpts | 64 Ki characters retained; missing/truncated logs disclosed; other-UID native/system crashes may be missed |
| Processes/memory | Dedicated UID and app-name process IDs; total PSS | Samples, not peak/continuous profiling |
| Storage | Private credential/device-encrypted file metadata before launch and after actions; sampled changes | Depth 8, 128 entries, 512-character paths; first 16 prefixes hashed up to 1 MiB; external/transient storage excluded |
| Network | UID-attributed kernel socket metadata; IPv4/IPv6 denied-packet counters | Short-lived sockets may be missed; no requests, TLS plaintext, method hooks or instruction traces |
| Permissions | Grant state after the scenario | A grant is not evidence of permission use |

`report.json` contains APK/scenario hashes, emulator build/API/ABI, tool versions, per-collector
coverage, issues, status and cleanup result. `README.md` summarizes the same scope. Empty results
from an unavailable collector are never described as absence of behavior. Incomplete inventories
withhold storage deltas rather than claim that omitted files were deleted. Prefix hashes are
explicitly identified; files changing during a sample are not assigned a stable hash.

Reports remain local. UI text and logs can contain personal data. No runtime data or APK content
is sent to model training or an external service automatically.

## Execution and cleanup boundary

The input is copied with a 1 GiB byte limit and hashed during staging, so metadata and installation
use the same bytes. A local per-emulator file lock serializes sessions. Commands have timeouts,
bounded output and cancellation; the session defaults to 180 seconds (10–600 configurable).
Each session owns a temporary foreground ADB server on a loopback port and stops it at cleanup.
It does not kill an existing SDK server or leave a new daemon behind. Host-server cleanup has
its own five-second bound after the guest cleanup budget.

After installation, dedicated-UID IPv4 and IPv6 denial rules are inserted before launching the
app. Each command that could have committed remotely is journaled in memory before sending it.
Cancellation/command failure still runs bounded final observation and cleanup (45 seconds).
Cleanup confirms the app is gone before removing only the session's unique firewall chains. If
uninstall cannot be confirmed, denial rules remain and the report says cleanup failed. A timed-out
installation is still checked and cleaned. The staged APK is removed after the session.

Installation is outside this per-UID launch boundary. A rooted emulator, its ADB server and host
tools are not an isolation boundary for hostile APKs. Run untrusted inputs in a disposable host VM
with its own network controls. Root changes and shared emulator state are not restored to a VM
snapshot by this engine; a fresh emulator is required, and other operators must not use it during
the session. Abrupt host termination cannot guarantee cleanup; discard that emulator.

## Compare two APKs

Add `--candidate recovered.apk`. Both run independently under the saved original scenario;
`comparison.json` reports `SCENARIO_MATCHED`, `DIFFERENT`, or `INCONCLUSIVE`. It compares declared
canonical UI observations only. Failed cleanup, incomplete UI capture, different scenarios, or
unfinished execution cannot become a match. An expectation can fail execution; omit expectations
when intentionally comparing a seeded mutation, and assert the original independently.

The Android workflow validates the strict rebuild and an intentionally changed candidate. Its
product-engine gate also requires logs, persistent preferences, memory, permission grants and an
observed denied network attempt. These are fixture checks, not a held-out real-app corpus.

## Further work

Method/API hooks, continuous native crash/ANR capture, request-body tracing, controlled network
replay, sensor/environment fixtures, coverage-guided exploration, process-independent DEX/native
load tracking, and deterministic full-state comparison remain explicit separate capabilities.
Runtime observation does not retrieve business logic that exists only on a remote server.
