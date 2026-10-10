# Fixtures

Each fixture directory holds hidden ground-truth source (`hidden/`) and a `fixture.json`.
The benchmark harness (`modules/benchmarks`) compiles the hidden source with the pinned
toolchain profile into an APK, runs recovery on the APK alone, then compares the recovered
project with the hidden original by independent execution. Recovery modules never read
`hidden/`.

| Fixture | Purpose | Expected job state |
|---|---|---|
| `v0-pure` | Static, side-effect-free I32 methods (dossier "first fixture functions") | ACCEPTED |
| `v0-mixed` | In-scope methods beside loops, `long`, calls, fields, instance methods, switches | PARTIAL with gaps |
