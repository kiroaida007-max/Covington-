import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("repair_ranker", Path(__file__).parents[1] / "ml/train_repair_ranker.py")
try:
    R = importlib.util.module_from_spec(SPEC)
    SPEC.loader.exec_module(R)
except ImportError:
    R = None


@unittest.skipIf(R is None, "training requires numpy")
class RepairRankerTests(unittest.TestCase):
    def corpus(self, root, kind="generated-fixture"):
        sources = []
        def sha(text): return hashlib.sha256(text.encode()).hexdigest()
        for group in range(8):
            rows = []
            for query in range(20):
                for choice in range(3):
                    x = [0.0] * len(R.FEATURES)
                    x[choice] = 1.0
                    rows.append({"schemaVersion": 1, "featureVersion": 1, "inputGroup": sha(f"input-{group}"),
                                 "querySha256": sha(f"query-{group}-{query}"), "candidateSha256": sha(f"candidate-{group}-{query}-{choice}"),
                                 "profileHash": sha("profile"), "rule": "fixture", "features": x, "label": int(choice == 2),
                                 "labelEvidence": "COMPLETE_ROUNDTRIP_NO_REGRESSION", "improvedMethods": int(choice == 2), "comparedMethods": 1})
            path = root / f"project-{group}.jsonl"
            path.write_text("".join(json.dumps(row) + "\n" for row in rows))
            sources.append({"projectGroup": f"project-{group}", "sourceUrl": f"urn:test:project-{group}", "license": "MIT",
                            "kind": kind, "path": path.name, "sha256": R.digest(path)})
        manifest = root / "manifest.json"
        manifest.write_text(json.dumps({"schemaVersion": 1, "sources": sources}))
        return manifest

    def test_project_partitions_disjoint_and_ranker_learns_without_promoting_fixtures(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); manifest = self.corpus(root)
            model, card = R.train(manifest, root / "out")
            a, b, c = [set(card["splits"][k]) for k in ("train", "validation", "test")]
            self.assertFalse(a & b or a & c or b & c)
            self.assertEqual(1.0, model["evaluation"]["modelMrr"])
            self.assertAlmostEqual(1 / 3, model["evaluation"]["baselineMrr"])
            self.assertFalse(model["eligible"], "generated data is not independent product validation")
            self.assertEqual(R.digest(root / "out/repair-priority-model.json"), card["modelSha256"])

    def test_cross_project_exact_candidates_removed_before_splitting(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); manifest = self.corpus(root)
            data = json.loads(manifest.read_text())
            duplicate = None
            for source in data["sources"][:2]:
                path = root / source["path"]; rows = [json.loads(line) for line in path.read_text().splitlines()]
                if duplicate is None: duplicate = rows[0]["candidateSha256"]
                rows[0]["candidateSha256"] = duplicate
                path.write_text("".join(json.dumps(r) + "\n" for r in rows)); source["sha256"] = R.digest(path)
            manifest.write_text(json.dumps(data))
            rows, _, _, excluded = R.load(manifest)
            self.assertEqual(1, excluded)
            self.assertFalse(any(r["candidateSha256"] == duplicate for r in rows))
            train, validation, test = R.split(rows)
            hashes = [{r["candidateSha256"] for r in part} for part in (train, validation, test)]
            self.assertFalse(hashes[0] & hashes[1] or hashes[0] & hashes[2] or hashes[1] & hashes[2])

    def test_comment_only_candidate_duplicates_across_projects_are_removed_by_token_fingerprint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); manifest = self.corpus(root)
            data = json.loads(manifest.read_text())
            for source in data["sources"][:2]:
                path = root / source["path"]; rows = [json.loads(line) for line in path.read_text().splitlines()]
                rows[0]["candidateCodeSha256"] = "f" * 64
                path.write_text("".join(json.dumps(r) + "\n" for r in rows)); source["sha256"] = R.digest(path)
            manifest.write_text(json.dumps(data))
            rows, _, _, excluded = R.load(manifest)
            self.assertEqual(1, excluded)
            self.assertFalse(any(r.get("candidateCodeSha256") == "f" * 64 for r in rows))

    def test_changed_or_unverified_feedback_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); manifest = self.corpus(root)
            data = json.loads(manifest.read_text()); source = data["sources"][0]; path = root / source["path"]
            with path.open("a") as stream: stream.write(" ")
            with self.assertRaisesRegex(ValueError, "changed"): R.load(manifest)
            rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
            rows[0]["labelEvidence"] = "MODEL_GUESS"
            path.write_text("".join(json.dumps(r) + "\n" for r in rows)); source["sha256"] = R.digest(path)
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "unverified"): R.load(manifest)

    def test_identical_source_against_different_input_dex_keeps_separate_labels_and_queries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); manifest = self.corpus(root)
            data = json.loads(manifest.read_text()); source = data["sources"][0]; path = root / source["path"]
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            additional = []
            for row in rows[:3]:
                changed = dict(row, inputGroup="e" * 64, label=1-row["label"], improvedMethods=1-row["label"])
                additional.append(changed)
            path.write_text("".join(json.dumps(row) + "\n" for row in rows + additional)); source["sha256"] = R.digest(path)
            manifest.write_text(json.dumps(data))
            loaded, _, _, _ = R.load(manifest)
            selected = [r for r in loaded if r["projectGroup"] == source["projectGroup"]]
            self.assertEqual(len(rows) + 3, len(selected))
            self.assertEqual(21, R.ranking(selected, [0] * len(selected))["queries"])

    def test_compiler_variants_do_not_inflate_independent_validation_unit_support(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); manifest = self.corpus(root)
            rows, _, _, _ = R.load(manifest)
            selected = [dict(r, unitSha256="f" * 64) for r in rows if r["projectGroup"] == "project-0"]
            measured = R.ranking(selected, [r["label"] for r in selected])
            self.assertEqual(20, measured["queries"])
            self.assertEqual(1, measured["units"])

    def test_committed_real_corpus_is_pinned_and_sparse_model_remains_unpromoted(self):
        root = Path(__file__).parents[2] / "benchmarks/repair-learning"
        rows, _, _, _ = R.load(root / "manifest.json")
        self.assertEqual(945, len(rows))
        self.assertEqual(12, len({r["projectGroup"] for r in rows}))
        self.assertEqual(8, sum(r["label"] for r in rows))
        model = json.loads((root / "model/repair-priority-model.json").read_text())
        self.assertFalse(model["eligible"])
        self.assertEqual(0, model["evaluation"]["testUnits"])
        self.assertEqual(0, model["evaluation"]["validationUnits"])


if __name__ == "__main__":
    unittest.main()
