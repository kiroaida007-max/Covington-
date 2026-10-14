# Notes for coding agents

Start with the latest handoff in `docs/handoff/` (currently
`2026-10-14-analysis-workbench.md`): branch state, measurements, open work.
The native GUI and packaging notes remain in `2026-10-10-codex-windows-desktop.md`.

- Build and test: `scripts/setup-toolchain.sh --full`, then `./gradlew test` (JDK 17 toolchain).
  Toolchain-dependent tests skip themselves when tools are missing; set `APKPROJECT_TOOLS` if
  `.tools` lives elsewhere. Python checks: `python3 -m unittest discover -s scripts/tests`.
- Desktop app (`modules/desktop`): `./gradlew :desktop:run`. On a machine without a display, run it
  under `xvfb-run`. Model tests run with Gradle; the UI check is
  `apkproject-studio --capture PROJECT_DIR IMAGE_DIR [INPUT_FILE]` (see the handoff). Native
  Windows screenshots and installer checks run in `.github/workflows/windows-desktop.yml`.
- Backend contract accuracy (endpoints, parameters, models, nullability, enums, OpenAPI validity,
  mock answers) against Tusky source: `benchmarks/backend-contract/README.md`. Rerun after
  touching `BytecodeApi` or `ContractRenderer`.
- Front end + security audit (`FrontendAudit`): UI toolkit/language, screens, static UI-quality and
  security findings with a risk score. It is a defensive static review (no app execution, no
  exploitation): every finding carries an evidence level (OBSERVED/INFERRED) and an OWASP MASVS or
  CWE reference. Written to `audit/`, surfaced on the desktop "UI & Risk" screen, standalone via
  `apkproject audit`. It also reports hardening/resilience (`HardeningAudit`): obfuscation level,
  packers/protectors, and anti-analysis defenses, so it works on "solid" (packed/obfuscated/RASP)
  apps. Detection tokens must be high-precision (qualified/boundary-matched) to avoid false
  positives (e.g. "xposed" in "ExposedDropdownMenu"). Tests: `FrontendAuditTest`, `HardeningAuditTest`.
- Update diff (`apkproject diff`, `AppDiff`/`CodeMatcher`): pairs classes of two builds across R8
  renames and ranks every change. Accuracy against two real R8 mappings: `benchmarks/update-diff/`;
  rerun `evaluate.py` after touching `CodeMatcher`. Tests: `AppDiffTest`.
- Screen map (`apkproject screens`, `ScreenMap`/`AppGraph`): per screen, the endpoints, hosts, SQL
  tables and SharedPreferences its code reaches, with call paths (static, INFERRED). Merged R8
  lambda classes and Hilt providers are split per case; check a change on Tusky 142 (LicenseActivity
  should reach no endpoint of its own, ComposeActivity `POST api/v1/statuses`). Tests: `ScreenMapTest`.
- R8 ground truth for library names: `benchmarks/library-matching/run.sh --suite`.
  For recovered names on a real app: `benchmarks/name-recovery/README.md`.
- Every recovered name or line of code carries an evidence level (README). Do not present
  inferred names, synthesized code or original bytecode as recovered or verified.
- Renaming through the JADX mapping: never move packages, and keep every renamed simple name
  unique among the classes the DEX refers to (JADX 1.5.6 import collisions, see the handoff).
