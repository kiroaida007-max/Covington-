# Alternate CI activation and reproducibility fix

Continue on `codex/production-candidate`, draft PR #11; no merge or release. Core engine details
remain in `2026-10-12-integer-repair-and-lazy-inference.md`. Follow-up parents: `701fc01` adds manual
self-hosted routing; `d2071f5` fixes the NumPy pin. The user currently has no runner machine or
available billing remedy.

## Verified routes and exact limits

- Hosted retry `38277980680` at `0ffac89` and all hosted jobs at `701fc01` stop before execution
  with the failed-payment/spending annotation. Runner administration API access returns HTTP 403;
  dispatch remains available. The managed Linux host has no Windows/KVM or external cloud identity.
- Self-hosted dispatches `38279261422` (Windows), `38279265660` (Linux), `38279270017` (Android)
  queued on the intended fixed capability labels instead of failing on billing. None acquired a
  runner or ran steps. Cancelled only those diagnostic runs after the user confirmed no machine.
  Hosted push/PR behavior stays; native self-hosted runs keep results locally and skip Actions
  artifact/cache storage. `docs/self-hosted-validation.md` has owner registration instructions.
- CircleCI publishes 30,000 monthly Free credits for private/Windows builds without a credit card.
  Native `.circleci/config.yml` supplies Linux, Windows Server 2022 GUI and Android VM jobs for
  the candidate branch. Account authorization/remaining credit availability has not been verified.
  No CircleCI connection/plugin is available here. Owner signup/selected-repository authorization
  is required; see `docs/alternate-ci.md`. Do not claim these jobs have executed.
- Its GitHub Actions compatibility preview is Linux-only and accepts some features without
  enforcing them. Use the prepared native configuration, not that preview, for the three jobs.

## Adapter and tool preparation

`scripts/ci/run-provider-workflow.py` consumes the original validation run blocks rather than
duplicating the assertions. It validates the whole plan first, rejects unsupported action/tool/
condition/default changes and unresolved expressions, stops on child failure, bounds execution,
streams logs, retains command/source hashes and requires the native OS. Android startup uses KVM,
fixed API-30 x64 defaults, deadlines, disabled animations and emulator cleanup. Unknown/mutant
outcomes still fail through the original checks. GitHub setup actions are replaced by explicit
pinned provider bootstrap; CircleCI retains artifacts separately.

JDK 17.0.20.1, Node 22.22.0 and Python bootstrap archives are SHA-256 pinned at their official
Adoptium/Node/CPython NuGet or Astral origins. Linux Python is 3.12.15; Windows NuGet is 3.12.10.
No new default product dependencies/model weights are packaged. CI tools/results are ignored by Git.

## Completed local checks

Executing all nine Linux CI run blocks at `d2071f5` in an isolated checkout passed: full Gradle
reports **317 cases, zero failures/errors, one optional external library skip**; **61 Python
checks**; backend schemas/live HTTP; BOM schema/exact **21 JAR hashes**; both toolchain doctors;
fixture benchmark; eight R8 workloads; raw/reference Tusky truth and live mocks. The initial
clean dependency installation revealed NumPy 2.2.6 versus the frozen model card's 2.3.5; fixed
the pin, kept strict byte comparisons/holdouts/promotion unchanged, and reran the run blocks.
Do not erase the original failed `local-ci` report.

New adapter tests bring Python to **65 passing checks**, including execution failure propagation,
unsupported/drifting semantics, Android limits and preventing fake Windows results from Linux.
They also pass in the freshly bootstrapped standalone Python environment with the corrected pin.
Native CircleCI config passes its published JSON schema and official CLI v1.5.0 validation;
GitHub workflows pass actionlint, both
Bash helpers parse, and the Windows bootstrap plus eleven Windows blocks parse through PowerShell.
Linux bootstrap downloads/hash checks/extraction/venv/version checks execute successfully here.
PowerShell parsing does not validate Windows bootstrap or GUI/MSI behavior. Android boot/session
execution remains untested on CircleCI until the account is connected.

Local records are under `/workspace/scratch/runner-routing/`: original `local-ci`, corrected
`local-ci-after-pin`, `linux-validation`, clean Python logs, bootstrap log, schema and PowerShell
checks. Existing corpus/model artifacts are unchanged; no ranker promotion, fine-tuning or new
recovery-accuracy claim. Keep the PR draft while actual native checks/account activation are pending.
