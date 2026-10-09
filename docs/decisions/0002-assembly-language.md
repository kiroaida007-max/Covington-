# ADR 0002: Where assembly language helps, and where it does not

- Status: accepted
- Date: 2026-10-09
- Context: the request was "if assembly would make it better, use it."

## Question

Two different things can be called "assembly" in this project:

1. **Native assembly** (x86-64 or ARM64) used to implement parts of the engine for speed.
2. **Dalvik assembly**, the human-readable form of the DEX bytecode that the engine reads.

We evaluated both against the dossier's constraints: a single JVM codebase (section 02), bounded
parsing of untrusted input with typed errors (section 08), ordinary laptops on Windows, macOS and
Linux (section 26), and no early native binding dependency (section 04).

## Decision

**Dalvik assembly: yes.** It is now part of the engine in two places.

- `:formats-dex` renders every DEX file as Dalvik assembly (`.dasm`), with code-unit addresses
  and, optionally, file byte offsets. This listing is the OBSERVED evidence layer: it is what the
  bytecode says before any inference. `apkproject disasm` prints it, `apkproject recover` stores
  it, and every exported project ships it under `recovery/disassembly/`, where
  `recovery/source-map.json` links each recovered Java line back to the same byte ranges.
- `:dex-asm` assembles the same grammar back into valid DEX (sorted id pools, type lists,
  try/catch handlers, debug positions, map list, Adler-32 and SHA-1). It is a test kit, never
  part of the recovery path. It lets us:
  - build exact bytecode fixtures without the Android SDK, including shapes javac and D8 never
    emit (registers above v255, every instruction format, unusual branch layouts);
  - build single-defect malformed inputs (a branch into the middle of an instruction, an
    unassigned opcode, a register outside the frame, a misdirected switch) to test each decoder
    gate in isolation;
  - check the decoder against itself in a way that catches real bugs: D8's output is
    disassembled, reassembled and disassembled again, and the two listings must match.

  Assembler and disassembler share one opcode table, so a wrong table entry could round-trip
  silently. Two independent checks guard against that: the table is compared with a
  transcription of the official AOSP opcode table (`OpcodeTableTest`), and D8, Google's own
  DEX reader, must accept our reassembly of D8-built code (`AssemblerTest`).

**Native assembly: no.** We measured where time goes on the `v0-pure` fixture (22 methods):
`apkproject inspect`, which runs every engine-side step (ZIP inventory, DEX parsing, decoding,
disassembly, lifting) and no subprocess, took 0.75 s wall time including JVM start-up, while
`apkproject recover` on the same APK took 19.6 s. The difference is the external forward
compilers (javac and D8), the JVM execution harness and Z3. Hand-written native code would:

- speed up the part that is already negligible, and leave the javac, D8, JVM and Z3 subprocesses
  unchanged;
- put memory-unsafe parsing on the path that handles untrusted APKs, the opposite of the
  bounded, typed-error readers the dossier requires;
- need a separate build per CPU and OS and a JNI bridge, the early native binding dependency
  section 04 asks us to avoid;
- make the semantic model harder to audit, when correctness of that model is the product.

## Consequences

- Native code is reconsidered only if profiling of real, larger inputs shows engine-side CPU
  (not subprocesses) dominating, and then as JVM-level optimisation first.
- Native *frontends* (ELF/JNI, Flutter AOT, IL2CPP) are a different matter: there the input is
  machine code, and reading ARM64 or x86-64 assembly is the job itself. Section 25 keeps those as
  research frontends with their own capability contracts.
