# apkproject

A self-experimenting inverse compiler: APK in, editable and independently rebuildable project out.
This repository implements the first vertical slice of the *APK to Project: Architecture &
Implementation Plan* (v1.0, 08 October 2026), the **V0 method laboratory**: static,
side-effect-free 32-bit integer methods, recovered as Java source and accepted only after formal
and runtime validation.

It is not a general APK decompiler yet. Anything outside the V0 model is reported as a capability
gap with its byte offset. Nothing is stubbed, and the original DEX is never shipped as a stand-in
for missing source.

## What a recovery looks like

Input bytecode, as the engine's Dalvik disassembler renders it (`recovery/disassembly/classes.dasm`):

```
.method public static clamp(III)I
    .registers 3
    0000: if-ge v0, v1, :L0003    ; @0x2b0
    0002: return v1               ; @0x2b4
  :L0003
    0003: if-le v0, v2, :L0006    ; @0x2b6
    0005: return v2               ; @0x2ba
  :L0006
    0006: return v0               ; @0x2bc
.end method
```

Recovered source (`src/main/java/com/example/fixtures/Pure.java`):

```java
public static int clamp(int p0, int p1, int p2) {
    if (p0 < p1) {
        return p1;
    }
    if (p0 > p2) {
        return p2;
    }
    return p0;
}
```

The evidence recorded for it in `recovery/report.json`: `OBSERVED` (from bytecode), `PROVED` (Z3
found no 32-bit input on which the outcomes differ), `TESTED` (the recompiled candidate agreed on
every generated input in a separate JVM). Its recompiled bytecode is `IDENTICAL` to the original.
Parameter names are `p0..p2` because this APK has no debug names. The engine does not guess
names and present them as recovered.

## Measured results

Fixture benchmark (`./gradlew :benchmarks:run`). These numbers cover the two development fixtures
in `fixtures/` only, and say nothing about arbitrary APKs.

| Fixture | Job state | Accepted / in scope | Identical bytecode on recompile | Independent runs (mismatches) | Seeded wrong candidates rejected |
|---|---|---|---|---|---|
| v0-pure | ACCEPTED (exit 0) | 22 / 22 | 22 | 6,759 (0) | 19 / 19 |
| v0-mixed | PARTIAL (exit 4) | 3 / 9 | 3 | 462 (0) | 2 / 2 |

"Independent runs" execute the hidden original source and the recovered project, each compiled
separately, on the same inputs. They share no code with the lifter. `v0-mixed` stops at PARTIAL
by design: its loop, `long` method, call, field read, instance method and packed switch are
reported as gaps, not approximated. Full per-method tables are in `benchmarks/results/`.

## Quick start

Requires JDK 17 and Python 3 (to fetch the pinned Z3 wheel).

```
scripts/setup-toolchain.sh                 # pinned D8 8.7.18 + Z3 4.13.3 into .tools/, SHA-256 verified
./gradlew test                             # 79 unit and integration tests
./gradlew :cli:installDist
alias apkproject=modules/cli/build/install/apkproject/bin/apkproject

apkproject doctor                          # binaries, versions, pinned hashes, smoke compile and solve
apkproject inspect app.apk --out runs/app  # inventory, disassembly, per-method capability gaps
apkproject recover app.apk --out runs/app  # candidate loop with checkpoints and budgets
apkproject export runs/app --out recovered # atomic Gradle project + report + source map
apkproject validate runs/app --suite extra-inputs.json
apkproject resume runs/app
apkproject cancel runs/app
apkproject disasm app.apk                  # Dalvik assembly listing
apkproject asm prog.dasm --out prog.dex    # Dalvik assembler (test kit)
```

Exit codes follow section 07: 0 policy satisfied, 2 invalid configuration, 3 malformed input,
4 unsupported or partial, 5 build or verification failure, 6 worker failure, 130 cancelled.

## How it works

```
APK ─► :archive ─► :formats-dex ─► :lift-dex ─► :reconstruct ─► :compiler-runner ─► :validate ─► :export
        bounded     DEX reader,     CFG, SSA,    Java AST via     javac + D8 in       Z3 BV32 proof,
        ZIP reader  decoder,        V0 opcode    post-dominator   bounded             runtime agreement,
                    disassembler    manifest     structuring      subprocesses        re-lift consistency
                                                       ▲                                  │
                                                       └──── :experiments (probes) ◄──────┘
                                                             counterexamples, refinement
```

- **Acceptance is separate from generation.** `:reconstruct` proposes source and `:validate`
  decides. A candidate is compiled from source alone, its D8 output is lifted again, and Z3
  checks that no 32-bit input separates it from the original, exceptions included. It is then
  executed in a separate JVM on boundary, constant-derived and random inputs.
- **Three independent semantic models** (IR evaluator, SMT encoding, real JVM) are cross-checked
  in tests and on every validation run.
- **Local compiler experiments.** If an accepted candidate does not recompile to the original
  bytecode, the engine probes semantically equal variants (branch polarity, operand order,
  ternary form), compiles them in one batch, and keeps the closest match. Probes count as
  fidelity evidence only.
- **Whole-bundle gate.** Before a job is marked ACCEPTED, the accepted methods are compiled again
  as the real classes, and every one is validated again from that build.

Module ownership follows dossier sections 08–09. See `settings.gradle.kts` for the list and
`docs/semantics/v0-opcodes.md` for the modelled semantics.

## Assembly language

Short answer to "would assembly make it better?": Dalvik assembly, yes; native assembly, no.

- The engine renders all input bytecode as **Dalvik assembly**. That listing is the observed
  evidence layer, and recovered lines map back to it by byte offset. A matching **Dalvik
  assembler** (`:dex-asm`) builds exact and deliberately malformed DEX fixtures without the
  Android SDK, and lets D8's output round-trip through disassembly and reassembly as a decoder
  test.
- **Native x86/ARM assembly** would speed up the part that is already negligible (engine-side
  parsing and lifting of the fixture takes 0.75 s, including JVM start-up, out of a 19.6 s
  recovery). It would also add memory-unsafe parsing of untrusted input and per-platform builds.

Details, measurements and the conditions for revisiting: `docs/decisions/0002-assembly-language.md`.

## Repository map

| Path | Contents |
|---|---|
| `modules/` | 16 Gradle modules (contracts, store, archive, formats-dex, dex-asm, ir, lift-dex, source-model, reconstruct, compiler-runner, validate, experiments, export, orchestrator, cli, benchmarks) |
| `profiles/` | Versioned toolchain profile with pinned tool hashes |
| `rules/` | Reconstruction rule catalogue (ids recorded per method and per source line) |
| `fixtures/` | Hidden-source ground truth; only `:benchmarks` reads `hidden/` |
| `benchmarks/results/` | Measured results |
| `docs/decisions/` | Architecture decisions and deviations from the dossier |
| `docs/roadmap.md` | Milestone gates M0–M7 and their status |
| `schemas/` | Record contracts and encoding rules |
| `scripts/` | Toolchain setup, fixture regeneration, opcode-table extraction |

## Limits that matter

- V0 scope only: static `(I...)I` methods with no loops, heap, calls, exception handlers,
  `long`, floating point or switches. Everything else is reported, method by method.
- Without an Android runtime, the runtime check for an arbitrary APK compares the candidate with
  the original's *lifted* semantics. Fully independent execution of the original currently exists
  only in the fixture benchmark (hidden source). An ADB/emulator worker is planned for V1.
- The proposed 98% project-level target (section 27) has not been measured. No held-out corpus
  exists yet.
- Recovered source can be correct and still differ from the historical source. Comments, local
  names and erased code are not in the APK and are never invented.
