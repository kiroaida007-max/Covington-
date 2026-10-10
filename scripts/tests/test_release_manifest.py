import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("release_manifest", Path(__file__).resolve().parents[1] / "release-manifest.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class SignedReleaseTest(unittest.TestCase):
    def test_manifest_hashes_final_asset_bytes_and_rejects_invalid_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            asset = Path(directory) / "studio.msi"
            asset.write_bytes(b"final signed installer")
            before = release.build_manifest("0.4.0", 3, [asset], 1700000000000)
            asset.write_bytes(b"changed signed installer")
            after = release.build_manifest("0.4.0", 3, [asset], 1700000000000)
            self.assertNotEqual(before["assets"][0]["sha256"], after["assets"][0]["sha256"])
            for version, count, days in [("../x", 3, 30), ("1.2", 3, 30), ("1.2.3", 0, 30), ("1.2.3", 3, 91)]:
                with self.assertRaises(ValueError):
                    release.build_manifest(version, count, [asset], 1700000000000, days)

    def test_signature_uses_the_key_pinned_in_the_build(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        key = Ed25519PrivateKey.generate()
        pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
        public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        data, signature = release.sign({"releaseId": 3}, pem, public)
        key.public_key().verify(signature, data)
        wrong = Ed25519PrivateKey.generate().public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        with self.assertRaises(ValueError):
            release.sign({"releaseId": 3}, pem, wrong)
