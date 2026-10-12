import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("assistant_setup", Path(__file__).parents[1] / "ml/setup_local_assistant.py")
S = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(S)


class AssistantSetupTests(unittest.TestCase):
    def test_changed_cache_is_rejected_without_using_network(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "model.gguf"; p.write_bytes(b"wrong")
            with self.assertRaisesRegex(ValueError, "changed"):
                S.download({"bytes": 5, "sha256": "0" * 64}, p)

    def test_native_extraction_resolves_safe_links_without_writing_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); archive = root / "runtime.tar.gz"
            with tarfile.open(archive, "w:gz") as tar:
                for name in ["pack/llama-completion", "pack/libllama.so.0"]:
                    info = tarfile.TarInfo(name); info.size = 4; tar.addfile(info, io.BytesIO(b"data"))
                alias = tarfile.TarInfo("pack/libllama.so"); alias.type = tarfile.SYMTYPE; alias.linkname = "libllama.so.0"; tar.addfile(alias)
            S.extract_runtime(archive, root / "runtime")
            self.assertFalse((root / "runtime/libllama.so").is_symlink())
            self.assertEqual(b"data", (root / "runtime/libllama.so").read_bytes())

    def test_archive_traversal_and_duplicate_flat_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for names, label in [(["../../escape.dll"], "escape"), (["a/llama-completion.exe", "b/llama-completion.exe"], "collision")]:
                archive = root / (label + ".zip")
                with zipfile.ZipFile(archive, "w") as z:
                    for name in names: z.writestr(name, b"native")
                with self.assertRaises(ValueError): S.extract_runtime(archive, root / label)

    def test_catalog_only_pins_registered_open_models_and_cpu_runtimes(self):
        data = json.loads(S.CATALOG.read_text())
        self.assertEqual(2, len(data["models"]))
        for model in data["models"].values():
            self.assertEqual("Apache-2.0", model["license"])
            self.assertEqual("Q4_K_M", model["quantization"])
            self.assertRegex(model["sha256"], "^[0-9a-f]{64}$")
            self.assertIn(model["revision"], model["url"])
        self.assertEqual({"linux-x64", "windows-x64"}, set(data["runtime"]["platforms"]))


if __name__ == "__main__": unittest.main()
