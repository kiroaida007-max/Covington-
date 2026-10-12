# Run validation on your own machines

The three candidate workflows accept a manual `runner=self-hosted` input. Their build, test,
recovery, screenshot, installer and Android comparison steps still run. Push and pull-request
events continue to use GitHub-hosted machines.

[GitHub documents self-hosted compute as free](https://docs.github.com/en/billing/concepts/product-billing/github-actions).
You supply and maintain the machines. This route skips GitHub artifact uploads and the Windows
Actions Gradle cache; results stay in the runner's checked-out workspace, avoiding those hosted
storage operations. Screenshot count, package hashes and other validation assertions still run.
Do not confuse a queued job with an executed or passing check.

## Machines and labels

| Workflow | Machine | Additional runner label |
| --- | --- | --- |
| `windows-desktop.yml` | Windows x64, logged-in desktop session | `covington-windows` |
| `ci.yml` | Linux x64, preferably Ubuntu 24.04 | `covington-linux` |
| `android-runtime.yml` | Linux x64 with working hardware virtualization and readable/writable `/dev/kvm` | `covington-android` |

The normal `self-hosted`, OS and `X64` labels must also be present. One suitable Linux machine
can carry both Linux labels and run the jobs sequentially. A Windows machine alone cannot run
the Linux Android workflow. Prefer dedicated, disposable machines with at least 8 GiB RAM and
50 GiB free disk; ordinary runner/tool prerequisites and outbound HTTPS access are required.
Windows packaging requires WiX 3, or Chocolatey with permission to install WiX 3. Native screenshots
require the Windows runner to run interactively using `run.cmd`, outside service session zero.
For Linux Android, grant the runner user KVM access before starting the runner; this route does
not change a self-hosted machine's udev rules.

## Register once, then run

The repository owner opens
[Settings → Actions → Runners](https://github.com/kiroaida007-max/Covington-/settings/actions/runners),
chooses **New self-hosted runner**, selects the OS/architecture, and follows GitHub's download and
configuration commands on that machine. Add the custom label from the table to the configuration
command (`--labels covington-windows`, or `--labels covington-linux,covington-android` on suitable
Linux). Keep the registration token on that machine; do not post it in chat, commits or logs.
Use `--ephemeral` for a runner that should unregister after one job. Do not install the Windows
runner as a service for the desktop job. Start it with `run.cmd` on Windows or `./run.sh` on Linux;
GitHub should show it online and waiting for jobs.

Dispatch the appropriate job against the candidate branch:

```sh
gh workflow run windows-desktop.yml --repo kiroaida007-max/Covington- \
  --ref codex/production-candidate -f runner=self-hosted
gh workflow run ci.yml --repo kiroaida007-max/Covington- \
  --ref codex/production-candidate -f runner=self-hosted
gh workflow run android-runtime.yml --repo kiroaida007-max/Covington- \
  --ref codex/production-candidate -f runner=self-hosted
```

Run only the workflows for which an appropriate machine is ready. An ephemeral runner handles
one job; start another registered runner for the next job. The same commands with
`-f runner=github-hosted` use the original hosted pools after billing is resolved. The Actions UI
may only list manual workflows present on the default branch; the CLI selects the candidate ref.

Self-hosted selection is restricted to manual dispatch, with fixed capability labels. Automatic
pull-request events never select these machines. Run only reviewed candidate code on them.

## Current access limit

The connected GitHub integration can dispatch workflows, but the repository runner-management
API returns `403 Resource not accessible by integration`. Repository admin status does not grant
the installation every API permission. Runner registration therefore needs the owner step above
or a correctly scoped administration credential configured through the supported connection.
This workspace has Linux compute, no Windows machine, no `/dev/kvm`, and no provisioned external
cloud identity. Hosted retries remain subject to GitHub's account billing restriction; routing
does not change billing or make arbitrary account restrictions disappear.
