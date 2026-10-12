import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('provider', Path(__file__).parents[1]/'ci/run-provider-workflow.py')
P = importlib.util.module_from_spec(spec)
spec.loader.exec_module(P)

class ProviderSafetyTest(unittest.TestCase):
    def test_unknown_actions_conditions_or_tool_configuration_cannot_be_silently_dropped(self):
        cases = [
            {'uses': 'unrecognized/action@v1'},
            {'run': 'echo unsafe', 'if': '${{ false }}'},
            {'uses': 'actions/setup-java@v4', 'with': {'distribution': 'temurin', 'java-version': '21'}},
            {'uses': 'actions/checkout@v4', 'with': {'ref': 'other-commit'}},
            {'run': 'echo ${{ secrets.TOKEN }}'},
        ]
        for case in cases:
            with self.subTest(case=case):
                with self.assertRaises(ValueError):
                    P.plan({'steps': [{'run': 'echo must-not-start'}, case]}, 'linux')
        with self.assertRaises(ValueError):
            P.plan({'defaults': {'run': {'working-directory': 'elsewhere'}}, 'steps': []}, 'linux')

    @unittest.skipIf(os.name == 'nt', 'POSIX process execution; Windows commands are checked on their native runner')
    def test_a_failed_process_stops_later_commands_and_is_recorded_as_failure(self):
        with tempfile.TemporaryDirectory(prefix='CI path with spaces ') as tmp:
            root = Path(tmp); report = {'scope': 'synthetic subprocess rejection check'}
            code = P.execute([
                ('broken', 'printf "failure detail\\n"; exit 7', 'shell'),
                ('must-not-execute', 'touch should-not-exist', 'shell'),
            ], 'linux', root, root/'reports', os.environ.copy(), 30, report)
            self.assertEqual(7, code)
            self.assertFalse((root/'should-not-exist').exists())
            saved = json.loads((root/'reports/result.json').read_text())
            self.assertEqual('FAILED', saved['status'])
            self.assertEqual([7], [s['exitCode'] for s in saved['steps']])
            self.assertIn('failure detail', (root/'reports/step-00.log').read_text())

    def test_android_api_architecture_and_session_checks_are_not_approximated(self):
        base = {'api-level': '30', 'arch': 'x86_64', 'target': 'default', 'disable-animations': 'true', 'script': 'exit 0'}
        for changed in [{'api-level': '35'}, {'arch': 'arm64-v8a'}, {'script': 'echo ${{ secrets.TOKEN }}'}, {'unrecognized-option': 'true'}]:
            with self.subTest(changed=changed):
                with self.assertRaises(ValueError):
                    P.plan({'steps': [{'uses': 'reactivecircus/android-emulator-runner@v2', 'with': base | changed}]}, 'android')
        steps = P.plan({'steps': [{'uses': 'reactivecircus/android-emulator-runner@v2', 'with': base}]}, 'android')
        self.assertEqual('android-session', steps[0][2])

    @unittest.skipIf(os.name == 'nt', 'The rejection concerns a non-Windows host')
    def test_windows_validation_cannot_be_reported_by_a_linux_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaisesRegex(ValueError, 'native operating system'):
                P.execute([('fake Windows', 'exit 0', 'shell')], 'windows', root, root/'reports', os.environ.copy(), 30, {})
            self.assertFalse((root/'reports').exists())

if __name__ == '__main__': unittest.main()
