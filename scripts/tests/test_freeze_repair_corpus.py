import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("freeze_repairs", Path(__file__).parents[1] / "ml/freeze_repair_corpus.py")
F = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(F)


class FreezeRepairCorpusTests(unittest.TestCase):
    def capture(self, root):
        lock = root / "lock.json"
        partitions = {"one": "train", "two": "train", "three": "train", "four": "validation", "five": "test"}
        lock.write_text(json.dumps({"projectPartitions": partitions}))
        run = root / "run"; run.mkdir()
        checked = run / "projects/one/checked"; checked.mkdir(parents=True)
        feedback = checked / "feedback.jsonl"
        row = {"querySha256": "a" * 64, "candidateSha256": "b" * 64, "queryCodeSha256": "c" * 64,
               "candidateCodeSha256": "d" * 64, "unitSha256": "e" * 64, "label": 1}
        feedback.write_text(json.dumps(row) + "\n")
        (checked / "summary.json").write_text(json.dumps({"positiveLabels": 1}))
        source = {"projectGroup": "one", "path": "projects/one/checked/feedback.jsonl", "sha256": F.digest(feedback)}
        manifest = {"schemaVersion": 1, "sourceLockSha256": F.digest(lock), "projectPartitions": partitions, "sources": [source]}
        (run / "manifest.json").write_text(json.dumps(manifest))
        (run / "collection-config.json").write_text(json.dumps({"sourceLockSha256": F.digest(lock), "sampling": "repair-diverse"}))
        (run / "collection-results.json").write_text(json.dumps([{"projectGroup": "one", "summary": {"positiveLabels": 1}},
                                                                 {"projectGroup": "two", "status": "FAILED", "detail": "compiler timeout"}]))
        return run, lock, row

    def test_freeze_preserves_locked_assignments_and_failed_attempts_without_inventing_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); run, lock, row = self.capture(root); out = root / "frozen"
            F.freeze([run], lock, out, root / "unused-jdk")
            manifest = json.loads((out / "manifest.json").read_text())
            self.assertEqual(json.loads(lock.read_text())["projectPartitions"], manifest["projectPartitions"])
            self.assertEqual(1, len(manifest["sources"]))
            self.assertEqual(row, json.loads((out / manifest["sources"][0]["path"]).read_text()))
            captures = json.loads((out / "collection-runs.json").read_text())
            self.assertEqual("FAILED", captures[0]["attempts"][1]["status"])

    def test_changed_lock_or_partition_assignment_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); run, lock, _ = self.capture(root)
            manifest = json.loads((run / "manifest.json").read_text())
            manifest["projectPartitions"]["four"] = "train"
            (run / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "partitions changed"):
                F.freeze([run], lock, root / "changed-partition", root)
            lock.write_text(lock.read_text() + "\n")
            with self.assertRaisesRegex(ValueError, "different source lock"):
                F.freeze([run], lock, root / "changed-lock", root)


if __name__ == "__main__":
    unittest.main()
