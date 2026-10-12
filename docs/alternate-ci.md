# Alternate hosted validation

[CircleCI's published Free plan](https://circleci.com/pricing/) includes **30,000 credits per
month**, covers private repositories and Windows builds, and says **no credit card is required**
(checked 12 October 2026). This supplies machines without using GitHub-hosted minutes. Credit
availability is determined by the connected CircleCI account; no paid upgrade is configured here.

The candidate contains a native CircleCI configuration at `.circleci/config.yml` for:

- Linux CI on an Ubuntu VM.
- Windows tests, native UI captures, packages and MSI lifecycle on the Windows Server 2022 GUI VM.
- Android original/candidate/mutant observation on the Android VM with hardware acceleration.

The adapter reads the existing GitHub workflow files and executes their validation run blocks;
there is no second copy of those assertions to keep synchronized. It checks the whole plan first,
rejects unsupported actions/conditions/tool changes and unresolved expressions, stops on failed
commands, retains logs and command/workflow hashes, and refuses to label Linux execution as
Windows validation. OS/tool bootstrap and artifact storage use native CircleCI steps. Android
startup requires KVM, forces acceleration and has boot deadlines and cleanup. Only the candidate
branch selects these jobs.

JDK, Python and Node bootstrap downloads are version/hash pinned. The build still installs and
verifies the engine's pinned toolchain. Downloaded tools and job reports are ignored by Git.
The Windows Python package is the official CPython NuGet distribution; Linux uses Astral's
CPython standalone distribution. These are CI tools, not additions to the default product.

## Activate from a browser

1. Open [CircleCI signup](https://circleci.com/signup/) and sign in with GitHub on the **Free** plan.
2. Connect the CircleCI GitHub App to **only `kiroaida007-max/Covington-`**, then create its CircleCI
   project. This authorizes CircleCI to check out the private code for these builds.
3. Select `.circleci/config.yml` as the CircleCI configuration, choose branch
   `codex/production-candidate`, and run the pipeline. Use the same branch for config and checkout
   when the setup form exposes separate fields. Future candidate pushes can trigger these jobs.

The current OpenAI GitHub connection cannot sign in to or create the CircleCI account. No CircleCI
connection was found in the available tools/plugin search. Do not paste API keys into chat; if
automated private-log/API access is needed later, configure a supported account connection or
managed credential. The owner authorization above is needed before any CircleCI job can execute.

Use the **native CircleCI configuration**. The
[GitHub-workflow compatibility preview](https://circleci.com/docs/guides/orchestrate/use-a-github-actions-workflow-file-as-your-pipeline-configuration/)
supports Linux only and warns that some accepted features do nothing. It cannot run the Windows
workflow. Relevant native executor documentation:
[Windows](https://circleci.com/docs/guides/execution-managed/using-windows/) and
[Android](https://circleci.com/docs/guides/execution-managed/android-machine-image/).

## Verification and current state

All nine Linux CI run blocks passed locally in an isolated checkout at `d2071f5`, including the
full Java suite, Python checks, schema/live HTTP checks, product hashes, toolchain doctor,
fixture benchmark, eight R8 workloads and pinned Tusky source comparisons. This is local evidence;
GitHub setup/upload actions did not execute. The run exposed and fixed the stale NumPy pin while
keeping strict model/card reproduction, frozen holdouts and the unpromoted ranker unchanged.

The CircleCI configuration passes both published JSON-schema checks and `circleci config validate`
with CLI v1.5.0; GitHub workflows pass actionlint. The clean Python environment passes all 65 checks.
Linux bootstrap downloads, extraction and tool checks execute locally. Windows bootstrap and
all eleven Windows run blocks pass PowerShell parsing. Provider safety tests check real failure
propagation and stop-on-failure, unsupported/drifting workflow rejection, Android configuration
limits and native-OS enforcement. **Parsing and preparation do not validate Windows or Android
execution.** Those jobs remain pending account activation and actual runs.

The GitHub self-hosted dispatch route also queued all three jobs successfully, using the expected
fixed labels. With no matching machine available, those diagnostic runs were cancelled. See
[self-hosted setup](self-hosted-validation.md). Hosted GitHub jobs still stop before execution with
the account billing/spending annotation. This alternative does not change repository visibility,
billing, required GitHub checks, or the draft PR's merge/release state.
