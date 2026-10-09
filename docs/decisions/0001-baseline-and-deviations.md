# ADR 0001: Toolchain baseline and deviations from the dossier

- Status: accepted
- Date: 2026-10-09

## Baseline as implemented

| Component | Dossier (section 04) | Implemented | Note |
|---|---|---|---|
| Engine language | Kotlin/JVM | Kotlin 2.0.21 | as specified |
| Engine JDK | 17 | Gradle toolchain `jvmToolchain(17)` | builds and runs on JDK 17; a newer host JDK may launch Gradle |
| Build | Gradle 8.9 | Gradle wrapper 8.9, distribution SHA-256 pinned in `gradle-wrapper.properties` | as specified |
| Candidate compiler | javac (JDK 17) | javac 17, `--release 8` | as specified |
| Forward DEX compiler | D8 from build-tools | D8/R8 8.7.18 jar from Google Maven, SHA-256 pinned | same D8 line as AGP 8.7; no SDK install needed for V0 |
| Platform library for D8 | android-35 `android.jar` | JDK 17 home (`--lib`) | V0 methods call no framework API; the V1 profile switches to android-35 |
| SMT solver | Z3 executable | Z3 4.13.3, binary extracted from the PyPI wheel, SHA-256 pinned | isolated process, SMT-LIB 2 over a file |
| Serialization | kotlinx.serialization | 1.7.3 | as specified |
| Persistence | SQLite JDBC (V1) | content-addressed files + JSON | SQLite is listed for V1; V0 job state fits in files |
| Tests | JUnit + property-based tests | JUnit 5 with seeded random property loops | no extra property-testing library; seeds are fixed for reproducibility |

## Deviations and why

1. **D8 library path.** The dossier's illustrative command uses `--lib android.jar`. V0 fixtures
   only use `java.lang.Object` and integer arithmetic, so the JDK is a sufficient and smaller
   library. Recorded in `profiles/v0-jvm-pure.json`; every compile records the exact argv.
2. **No SQLite yet.** Section 05 marks SQLite as "V1 persistence". V0 keeps immutable bytes in a
   content-addressed store and job state in an atomically written `state.json` plus an NDJSON
   journal, which already gives resumable, inspectable jobs.
3. **Supported DEX versions.** The profile lists 035, 037, 038 and 039. Version 036 was never a
   released format and is rejected as malformed; 040 and 041 are recognised and reported as
   unsupported (exit code 4), never parsed by guesswork.
4. **Implicit default constructor.** A V0 class always contains javac's synthesized
   `public <init>()V`. Rather than count every class as incomplete, the rule
   `class.implicit-default-constructor.v1` accepts that exact pattern and requires the recovered
   class to recompile to the same constructor shape.
5. **Runtime reference for arbitrary APKs.** Independent execution of an original DEX needs an
   Android runtime (V1: emulator or device over ADB). In V0 the runtime check compares the
   candidate, executed on a real JVM, with the original's lifted semantics, and the benchmark adds
   a fully independent check by compiling and executing the hidden original source. Reports say
   which reference was used.

## Reproducing the toolchain

```
scripts/setup-toolchain.sh      # D8 jar + Z3 binary into .tools/, hashes verified
./gradlew test                  # unit and integration tests
./gradlew :cli:installDist
modules/cli/build/install/apkproject/bin/apkproject doctor
```
