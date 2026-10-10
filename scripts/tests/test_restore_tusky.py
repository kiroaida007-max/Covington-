import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

SPEC = importlib.util.spec_from_file_location("restore_tusky", Path(__file__).resolve().parents[1] / "restore-tusky.py")
restore = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(restore)


class RestorationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def apk(self, name, entries, compression=zipfile.ZIP_STORED):
        path = self.root / name
        with zipfile.ZipFile(path, "w", compression=compression) as archive:
            for entry, data in entries:
                archive.writestr(entry, data)
        return path

    def test_input_identity_is_required_before_source_attribution(self):
        apk = self.apk("input.apk", [("classes.dex", b"original")])
        restore.require_input(apk, {"apkSha256": restore.sha256(apk)})
        with self.assertRaisesRegex(ValueError, "does not match"):
            restore.require_input(apk, {"apkSha256": "0" * 64})

    def test_dex_identity_ignores_zip_layout_but_reports_native_differences(self):
        original = self.apk("original.apk", [("classes.dex", b"code"), ("lib/arm64-v8a/libx.so", b"native")])
        rebuilt = self.apk("rebuilt.apk", [("lib/arm64-v8a/libx.so", b"changed"), ("classes.dex", b"code")], zipfile.ZIP_DEFLATED)
        result = restore.compare_payloads(original, rebuilt)
        self.assertTrue(result["dexPayloadsIdentical"])
        self.assertEqual(["lib/arm64-v8a/libx.so"], result["changedOrMissingEntries"])

    def test_missing_changed_extra_or_absent_dex_cannot_validate_an_r8_map(self):
        original = self.apk("original.apk", [("classes.dex", b"one"), ("classes2.dex", b"two")])
        for entries in [[], [("classes.dex", b"one")], [("classes.dex", b"wrong"), ("classes2.dex", b"two")],
                        [("classes.dex", b"one"), ("classes2.dex", b"two"), ("classes3.dex", b"extra")]]:
            rebuilt = self.apk("rebuilt.apk", entries)
            self.assertFalse(restore.compare_payloads(original, rebuilt)["dexPayloadsIdentical"])
        empty = self.apk("empty.apk", [("AndroidManifest.xml", b"manifest")])
        self.assertFalse(restore.compare_payloads(empty, empty)["dexPayloadsIdentical"])

    def test_duplicate_zip_names_are_ambiguous(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            apk = self.apk("duplicate.apk", [("classes.dex", b"one"), ("classes.dex", b"two")])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            restore.payloads(apk)

    def test_archive_comparison_includes_resources_and_signature_entries(self):
        original = self.apk("original.apk", [("classes.dex", b"code"), ("resources.arsc", b"original"),
                                               ("META-INF/CERT.RSA", b"signature")])
        rebuilt = self.apk("rebuilt.apk", [("classes.dex", b"code"), ("resources.arsc", b"changed"),
                                             ("assets/added.txt", b"new")])
        self.assertTrue(restore.compare_payloads(original, rebuilt)["dexPayloadsIdentical"])
        result = restore.compare_archives(original, rebuilt)
        self.assertEqual(1, result["identicalEntries"])
        self.assertEqual(["resources.arsc"], result["changedEntries"])
        self.assertEqual(["META-INF/CERT.RSA"], result["missingEntries"])
        self.assertEqual(["assets/added.txt"], result["additionalEntries"])

    def test_existing_checkout_at_another_commit_is_never_reset(self):
        checkout = self.root / "source"
        (checkout / ".git").mkdir(parents=True)
        lock = {"sourceRepository": "https://example.com/source.git", "sourceCommit": "wanted"}
        with patch.object(restore, "capture", side_effect=[lock["sourceRepository"], "other"]), \
             patch.object(restore.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "different commit"):
                restore.checkout(checkout, lock)
            run.assert_not_called()

    def test_local_changes_and_different_origins_are_preserved(self):
        checkout = self.root / "source"
        (checkout / ".git").mkdir(parents=True)
        lock = {"sourceRepository": "https://example.com/source.git", "sourceCommit": "wanted"}
        cases = [(["https://example.com/another.git"], "different origin"),
                 ([lock["sourceRepository"], "wanted", " M app/build.gradle"], "local changes")]
        for responses, message in cases:
            with self.subTest(message=message), \
                 patch.object(restore, "capture", side_effect=responses), \
                 patch.object(restore.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, message):
                    restore.checkout(checkout, lock)
                run.assert_not_called()

    def test_temporary_patch_is_reversed_even_when_build_fails(self):
        source = self.root / "source.txt"
        source.write_text("original\n")
        patch_file = self.root / "change.patch"
        patch_file.write_text("diff --git a/source.txt b/source.txt\n--- a/source.txt\n+++ b/source.txt\n"
                              "@@ -1 +1 @@\n-original\n+patched\n")
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True)
        for fail in (False, True):
            with self.subTest(failed_build=fail):
                try:
                    with restore.temporary_test_patch(self.root, patch_file):
                        self.assertEqual("patched\n", source.read_text())
                        if fail:
                            raise RuntimeError("simulated build failure")
                except RuntimeError as error:
                    self.assertEqual("simulated build failure", str(error))
                self.assertEqual("original\n", source.read_text())

    def test_patch_cleanup_does_not_overwrite_concurrent_user_edits(self):
        source = self.root / "source.txt"
        source.write_text("original\n")
        patch_file = self.root / "change.patch"
        patch_file.write_text("diff --git a/source.txt b/source.txt\n--- a/source.txt\n+++ b/source.txt\n"
                              "@@ -1 +1 @@\n-original\n+patched\n")
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True)
        with self.assertRaises(subprocess.CalledProcessError):
            with restore.temporary_test_patch(self.root, patch_file):
                source.write_text("user edit during build\n")
        self.assertEqual("user edit during build\n", source.read_text())


if __name__ == "__main__":
    unittest.main()
