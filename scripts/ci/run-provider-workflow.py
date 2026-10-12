#!/usr/bin/env python3
"""Execute reviewed run blocks without maintaining a second copy of their validation logic."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import yaml

JOBS = {'linux': ('ci.yml', 'build'), 'windows': ('windows-desktop.yml', 'desktop'), 'android': ('android-runtime.yml', 'differential')}
SETUP = {
    'actions/checkout@v4': {},
    'actions/setup-java@v4': {'distribution': 'temurin', 'java-version': '17'},
    'actions/setup-python@v5': {'python-version': '3.12'},
    'actions/setup-node@v4': {'node-version': '22'},
    'android-actions/setup-android@v3': {'packages': 'platform-tools'},
}
SELF_CONDITION = "${{ github.event_name == 'workflow_dispatch' && inputs.runner == 'self-hosted' }}"
HOSTED_CONDITION = "${{ github.event_name != 'workflow_dispatch' || inputs.runner != 'self-hosted' }}"
ARTIFACT_CONDITION = "${{ always() && (github.event_name != 'workflow_dispatch' || inputs.runner != 'self-hosted') }}"

def plan(job, kind):
    if set(job) - {'runs-on', 'timeout-minutes', 'env', 'defaults', 'steps'}:
        raise ValueError('Unsupported job semantics; update the adapter instead of skipping gates')
    if job.get('defaults', {}) != ({'run': {'shell': 'pwsh'}} if kind == 'windows' else {}):
        raise ValueError('Unsupported shell or working-directory defaults')
    result = []
    for step in job['steps']:
        if set(step) - {'name', 'uses', 'with', 'run', 'if'}:
            raise ValueError('Unsupported step semantics')
        action = step.get('uses')
        if action in SETUP:
            options = step.get('with', {})
            allowed = set(SETUP[action]) | ({'cache'} if action == 'actions/setup-java@v4' else set())
            if 'if' in step or set(options) - allowed or any(str(options.get(k)) != v for k, v in SETUP[action].items()):
                raise ValueError('Bootstrap tool configuration drift')
            continue
        if action == 'actions/upload-artifact@v4':
            if step.get('if') != ARTIFACT_CONDITION:
                raise ValueError('Artifact condition drift')
            continue  # Native CircleCI store_artifacts steps retain the same output directories.
        if action == 'reactivecircus/android-emulator-runner@v2':
            options = step.get('with', {})
            expected = {'api-level': '30', 'arch': 'x86_64', 'target': 'default', 'disable-animations': 'true'}
            if kind != 'android' or 'if' in step or set(options) != set(expected) | {'script'} or any(str(options[k]) != v for k, v in expected.items()):
                raise ValueError('Unsupported Android execution configuration')
            body = options['script']
            mode = 'android-session'
        elif action:
            raise ValueError('Unsupported action: ' + action)
        else:
            if 'if' in step:
                if step['if'] == SELF_CONDITION and step.get('name') in {'Check self-hosted desktop session', 'Check self-hosted emulator acceleration'}:
                    continue
                if step['if'] != HOSTED_CONDITION or step.get('name') != 'Enable hardware acceleration':
                    raise ValueError('Unsupported step condition; do not silently omit validation')
            body = step['run']
            mode = 'shell'
        if not isinstance(body, str) or '${{' in body:
            raise ValueError('Unresolved GitHub expression in command')
        result.append((step.get('name', action or 'run'), body, mode))
    return result

def execute(steps, kind, root, output, env, budget_seconds, report):
    if (kind == 'windows' and os.name != 'nt') or (kind != 'windows' and sys.platform != 'linux'):
        raise ValueError('The validation kind needs its native operating system')
    output.mkdir(parents=True, exist_ok=True)
    deadline = time.monotonic() + budget_seconds
    report.update(status='RUNNING', steps=[])
    def save(): (output/'result.json').write_text(json.dumps(report, indent=2)+'\n')
    save()
    for index, (name, body, mode) in enumerate(steps):
        path = output/f'step-{index:02}.{"ps1" if kind == "windows" else "sh"}'
        path.write_text(("$ErrorActionPreference = 'Stop'\n" if kind == 'windows' else '') + body+'\n', encoding='utf-8')
        if kind == 'windows':
            argv = [shutil.which('pwsh') or 'powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(path)]
        elif mode == 'android-session':
            argv = ['bash', str(root/'scripts/ci/circleci-android-session.sh'), str(path)]
        else:
            argv = ['bash', '--noprofile', '--norc', '-e', '-o', 'pipefail', str(path)]
        started = time.monotonic()
        print('START', name, flush=True)
        with (output/f'step-{index:02}.log').open('wb') as log:
            process = subprocess.Popen(argv, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=(kind != 'windows'))
            try:
                code = process.wait(timeout=max(1, deadline-time.monotonic()))
            except BaseException:
                if kind == 'windows':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], check=False, stdout=log, stderr=subprocess.STDOUT)
                else:
                    try: os.killpg(process.pid, signal.SIGTERM)
                    except ProcessLookupError: pass
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    if kind != 'windows':
                        try: os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError: pass
                    process.kill(); process.wait()
                report['status'] = 'INTERRUPTED'; save()
                raise
        report['steps'].append({'name': name, 'commandSha256': hashlib.sha256(body.encode()).hexdigest(), 'exitCode': code, 'elapsedSeconds': round(time.monotonic()-started, 3)})
        with (output/f'step-{index:02}.log').open(errors='replace') as stream:
            while chunk := stream.read(65_536): sys.stdout.write(chunk)
        sys.stdout.flush()
        if code:
            report['status'] = 'FAILED'; save(); return code
        save()
    report['status'] = 'PASSED_RUN_BLOCKS'; save(); return 0

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kind', choices=JOBS)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    if os.environ.get('CIRCLE_SHA1') not in (None, commit): raise ValueError('Checkout does not match the requested CircleCI commit')
    name, job_name = JOBS[args.kind]
    workflow = root/'.github/workflows'/name
    job = yaml.load(workflow.read_text(), Loader=yaml.BaseLoader)['jobs'][job_name]
    steps = plan(job, args.kind)  # Validate the complete plan before executing any command.
    env = os.environ.copy()
    for key, value in job.get('env', {}).items():
        value = value.replace('${{ github.workspace }}', str(root))
        if '${{' in value: raise ValueError('Unresolved job environment expression')
        env[key] = value
    env['GITHUB_WORKSPACE'] = str(root)  # Path compatibility for the existing Windows capture commands.
    if args.kind == 'android':
        subprocess.run(['sdkmanager', 'platform-tools'], env=env, check=True, timeout=300)
    report = {'schemaVersion': 1, 'provider': 'CircleCI' if os.environ.get('CIRCLECI') == 'true' else 'LOCAL_PROVIDER_ADAPTER', 'kind': args.kind, 'commit': commit, 'sourceWorkflowSha256': hashlib.sha256(workflow.read_bytes()).hexdigest(),
              'scope': 'Run blocks from the source workflow; bootstrap and artifact operations are provided by native CircleCI configuration. Passing commands are not universal recovery or formal equivalence evidence.'}
    return execute(steps, args.kind, root, root/'.ci-results'/args.kind, env, int(job['timeout-minutes'])*60, report)

if __name__ == '__main__': sys.exit(main())
