import importlib.util
import unittest
from unittest.mock import patch
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("runtime_check", Path(__file__).parents[1] / "android-runtime-check.py")
RUNTIME = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(RUNTIME)


class AndroidRuntimeTests(unittest.TestCase):
    def scenario(self):
        return {"schemaVersion": 1, "package": "dev.test.app", "activity": ".Main", "actions": [{"kind": "snapshot", "contains": "42"}]}

    def test_empty_observations_and_command_injection_are_refused(self):
        for mutate in (lambda s: s.update(actions=[]), lambda s: s.update(package="dev.test;rm x"),
                       lambda s: s.update(activity=".Main;touch x"),
                       lambda s: s.update(actions=[{"kind": "text", "text": "$(id)"}])):
            scenario = self.scenario(); mutate(scenario)
            with self.assertRaises(ValueError): RUNTIME.validate(scenario)

    def test_ui_comparison_retains_behavior_and_ignores_geometry(self):
        xml = '<hierarchy><node package="dev.test.app" class="TextView" text="42" bounds="[0,0][10,10]"/><node package="android" text="12:00"/></hierarchy>'
        _, before = RUNTIME.parse_ui(xml, "dev.test.app")
        _, resized = RUNTIME.parse_ui(xml.replace('[0,0][10,10]', '[20,30][100,110]'), "dev.test.app")
        self.assertEqual("SCENARIO_MATCHED", RUNTIME.compare([before], [resized])["status"])
        _, wrong = RUNTIME.parse_ui(xml.replace('text="42"', 'text="43"'), "dev.test.app")
        self.assertEqual("DIFFERENT", RUNTIME.compare([before], [wrong])["status"])

    def test_missing_target_and_entity_xml_cannot_pass(self):
        for xml in ('<hierarchy><node package="android"/></hierarchy>', '<!DOCTYPE x><hierarchy/>'):
            with self.assertRaises((RuntimeError, ValueError)): RUNTIME.parse_ui(xml, "dev.test.app")

    def test_different_observation_counts_cannot_match(self):
        self.assertEqual("DIFFERENT", RUNTIME.compare([[]], [[], []])["status"])

    def test_physical_devices_are_refused_before_any_android_command(self):
        with self.assertRaises(ValueError):
            RUNTIME.check("not-adb", "not-aapt", "physical-device", Path("x"), Path("y"), self.scenario())

    def test_partial_network_denial_uninstalls_before_removing_applied_rule(self):
        commands = []
        def command(argv, timeout=30):
            commands.append(argv)
            if argv[0] == "aapt": return "package: name='dev.test.app'"
            if "getprop" in argv: return "1"
            if "packages" in argv: return "package:dev.test.app uid:10001\n" if "-U" in argv else ""
            if "id" in argv: return "0"
            if "ip6tables" in argv: raise RuntimeError("IPv6 control unavailable")
            if "install" in argv or "uninstall" in argv: return "Success"
            return ""
        with patch.object(RUNTIME, "run", side_effect=command), self.assertRaisesRegex(RuntimeError, "IPv6 control unavailable"):
            RUNTIME.check("adb", "aapt", "emulator-5554", Path("x"), Path("y"), self.scenario())
        uninstall = next(i for i, c in enumerate(commands) if "uninstall" in c)
        removal = next(i for i, c in enumerate(commands) if "-D" in c)
        self.assertLess(uninstall, removal)
        self.assertFalse(any("start" in c for c in commands))
        rules = [c for c in commands if "iptables" in c or "ip6tables" in c]
        self.assertTrue(all("-w" in c for c in rules))
        self.assertEqual(1, sum("-D" in c for c in commands))
