import hashlib
import importlib.util
import os
import subprocess
import sys
import time
import tempfile
import unittest
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("collect_corpus", Path(__file__).parents[1] / "ml/collect_repair_corpus.py")
C = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(C)


class CorpusCollectionTests(unittest.TestCase):
    def test_selects_unchanged_production_classes_without_test_or_path_injection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / "source.jar"; binary = root / "binary.jar"
            original = b"package real.math; public class Division { float f(int a,int b) { return (float) a / b; } }"
            with zipfile.ZipFile(source, "w") as z:
                z.writestr("prefix/src/main/java/real/math/Division.java", original)
                z.writestr("src/test/java/real/math/OnlyTest.java", "package real.math; public class OnlyTest {}")
                z.writestr("../../Outside.java", "package ...escape; public class Outside {}")
            with zipfile.ZipFile(binary, "w") as z:
                z.writestr("real/math/Division.class", "compiled")
                z.writestr("///escape/Outside.class", "invalid")
            units = C.source_units(source, binary, 4)
            self.assertEqual(["real/math/Division.java"], [u["path"] for u in units])
            self.assertEqual(original, units[0]["bytes"])
            self.assertEqual(hashlib.sha256(original).hexdigest(), units[0]["sha256"])

    def test_hash_sampling_is_stable_and_diverse_without_source_rewrites(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / "source.jar"; binary = root / "binary.jar"
            with zipfile.ZipFile(source, "w") as s, zipfile.ZipFile(binary, "w") as b:
                for i in range(20):
                    text = f"package p; public class C{i} {{ int f(int a,int b) {{ return a {' / ' if i < 4 else ' + '} b; }} }}"
                    s.writestr(f"p/C{i}.java", text); b.writestr(f"p/C{i}.class", "compiled")
            a = C.source_units(source, binary, 6); b = C.source_units(source, binary, 6)
            self.assertEqual([u["sha256"] for u in a], [u["sha256"] for u in b])
            self.assertEqual(6, len(a)); self.assertEqual(6, len({u["path"] for u in a}))
            self.assertTrue(all(u["score"] > 0 for u in a[:3]))

    def test_partial_jadx_output_is_collected_but_process_and_load_failures_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertFalse(C.usable_jadx_output(0, root))
            (root / "partial.java").write_text("class Partial {}")
            self.assertTrue(C.usable_jadx_output(0, root)); self.assertTrue(C.usable_jadx_output(3, root))
            for code in (1, 2, -9, 137):
                self.assertFalse(C.usable_jadx_output(code, root))

    def test_diverse_sampling_covers_numeric_cast_and_hash_choices_without_duplication(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / "source.jar"; binary = root / "binary.jar"
            with zipfile.ZipFile(source, "w") as s, zipfile.ZipFile(binary, "w") as b:
                for i in range(20):
                    expression = "a / b" if i < 4 else "(Integer) a" if i < 8 else "a"
                    text = f"package p; public class C{i} {{ Object f(Object a,Object b) {{ return {expression}; }} }}"
                    s.writestr(f"p/C{i}.java", text); b.writestr(f"p/C{i}.class", "compiled")
            units = C.source_units(source, binary, 6, "repair-diverse")
            self.assertEqual(6, len({u["path"] for u in units}))
            self.assertTrue(all(u["score"] > 0 for u in units[:2]))
            self.assertTrue(all(u["castScore"] > 0 for u in units[2:4]))

    def test_conflicting_archive_entries_for_the_same_class_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); source = root / "source.jar"; binary = root / "binary.jar"
            with zipfile.ZipFile(source, "w") as s, zipfile.ZipFile(binary, "w") as b:
                s.writestr("main/p/C.java", "package p; public class C { int f() { return 1; } }")
                s.writestr("alternate/p/C.java", "package p; public class C { int f() { return 2; } }")
                b.writestr("p/C.class", "compiled")
            with self.assertRaisesRegex(ValueError, "conflicting source entries"):
                C.source_units(source, binary, 6)

    def test_changed_cached_artifacts_are_rejected_without_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "artifact.jar"; p.write_bytes(b"changed")
            entry = {"url": "https://repo.maven.apache.org/maven2/fixture.jar", "sha256": "0" * 64}
            with self.assertRaisesRegex(ValueError, "changed"):
                C.download(entry, p)

    def test_aar_extracts_only_classes_and_refuses_changed_extraction(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); p = root / "library.aar"
            with zipfile.ZipFile(p, "w") as z:
                z.writestr("classes.jar", b"pinned classes")
                z.writestr("../../outside", b"not extracted")
            target = C.class_jar(p)
            self.assertEqual(b"pinned classes", target.read_bytes())
            self.assertFalse((root.parent / "outside").exists())
            target.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "changed"):
                C.class_jar(p)

    @unittest.skipUnless(os.name == "posix", "process-group cleanup requires POSIX")
    def test_timeout_terminates_a_compiler_descendant(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            child = "import time; time.sleep(60)"
            parent = "import subprocess,sys,time,pathlib; p=subprocess.Popen([sys.executable,'-c'," + repr(child) + "]); pathlib.Path('child.pid').write_text(str(p.pid)); time.sleep(60)"
            with self.assertRaises(subprocess.TimeoutExpired):
                C.run([sys.executable, "-c", parent], root, root / "run.log", timeout=1)
            pid = int((root / "child.pid").read_text())
            for _ in range(20):
                stat = Path(f"/proc/{pid}/stat")
                if not stat.exists() or stat.read_text().split()[2] == "Z":
                    break
                time.sleep(0.05)
            else:
                self.fail("compiler descendant survived timeout")


if __name__ == "__main__":
    unittest.main()
