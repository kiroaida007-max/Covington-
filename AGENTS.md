# Notes for coding agents

Start with the latest handoff in `docs/handoff/` (currently
`2026-10-10-claude-to-codex.md`): branch state, measurements, open work.

- Build and test: `scripts/setup-toolchain.sh --full`, then `./gradlew test` (JDK 17 toolchain).
  Toolchain-dependent tests skip themselves when tools are missing; set `APKPROJECT_TOOLS` if
  `.tools` lives elsewhere. Python checks: `python3 -m unittest discover -s scripts/tests`.
- R8 ground truth for library names: `benchmarks/library-matching/run.sh --suite`.
  For recovered names on a real app: `benchmarks/name-recovery/README.md`.
- Every recovered name or line of code carries an evidence level (README). Do not present
  inferred names, synthesized code or original bytecode as recovered or verified.
- Renaming through the JADX mapping: never move packages, and keep every renamed simple name
  unique among the classes the DEX refers to (JADX 1.5.6 import collisions, see the handoff).
