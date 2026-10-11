import copy
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

BENCH = Path(__file__).resolve().parents[2] / "benchmarks/backend-contract"
sys.path.insert(0, str(BENCH))
from check_reference_types import check


class ReferenceAttributionCheckTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        text = """package sample
import sample.Notification.Type
@JsonClass(generateAdapter = true)
data class Notification(val type: Type) { sealed class Type(val name: String) }
class Other { enum class Type { A, B } }
"""
        (self.source / "Notification.kt").write_text(text)
        subprocess.run(["git", "-C", str(self.source), "init", "--quiet", "--initial-branch=main"], check=True)
        subprocess.run(["git", "-C", str(self.source), "add", "Notification.kt"], check=True)
        subprocess.run(["git", "-C", str(self.source), "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture"], check=True)
        revision = subprocess.check_output(["git", "-C", str(self.source), "rev-parse", "HEAD"], text=True).strip()
        self.apk = self.root / "input.apk"
        self.apk.write_bytes(b"APK hash fixture")
        digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        source_hash = hashlib.sha256((self.source / "Notification.kt").read_bytes()).hexdigest()
        self.manifest = dict(apkSha256=digest, repository="https://example.org/project.git", revision=revision,
                             bindings=[dict(model="sample.Notification", property="type", bytecodeType="a.b",
                                            referenceType="sample.Notification.Type", sourcePath="Notification.kt", sourceSha256=source_hash)])
        self.raw = dict(models=[dict(className="sample.Notification", fields=[dict(jsonName="type", javaName="type", javaType="a.b", referenceType=None)])],
                        endpoints=[], notes=["extraction note"])
        self.contract = copy.deepcopy(self.raw)
        self.contract["models"][0]["fields"][0]["referenceType"] = dict(name="sample.Notification.Type", repository=self.manifest["repository"],
            revision=revision, sourcePath="Notification.kt", sourceSha256=source_hash, apkSha256=digest,
            sourceProperty="sample.Notification.type", scope="REFERENCE_SOURCE: declaration in supplied source; not bytecode identity verification")
        self.contract["notes"].append("1 type names attributed to supplied reference source. APK types and extraction scores remain separate; source hashes do not prove original build or server identity.")

    def test_real_scoped_source_name_is_separate_from_unchanged_APK_facts(self):
        self.assertEqual(1, check(self.source, self.contract, self.raw, self.manifest, self.apk))

    def test_fabricated_names_changed_facts_missing_notes_and_wrong_provenance_fail(self):
        for key, value in (("name", "sample.Other.Type"), ("revision", "b" * 40), ("scope", "BYTECODE_VERIFIED")):
            bad = copy.deepcopy(self.contract)
            bad["models"][0]["fields"][0]["referenceType"][key] = value
            with self.assertRaises(ValueError):
                check(self.source, bad, self.raw, self.manifest, self.apk)
        for mutation in (lambda c: c["models"][0]["fields"][0].update(javaType="sample.Notification.Type"),
                         lambda c: c["endpoints"].append({"invented": True}),
                         lambda c: c["notes"].remove("extraction note")):
            bad = copy.deepcopy(self.contract)
            mutation(bad)
            with self.assertRaises(ValueError):
                check(self.source, bad, self.raw, self.manifest, self.apk)
        (self.source / "Notification.kt").write_text("changed")
        with self.assertRaises((ValueError, KeyError)):
            check(self.source, self.contract, self.raw, self.manifest, self.apk)
