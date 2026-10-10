#!/usr/bin/env python3
"""Sign exact release bytes with the protected Ed25519 publisher key; never emit private keys."""
import argparse
import hashlib
import json
import os
import re
import time
from pathlib import Path


def build_manifest(version, release_id, assets, now, expires_days=30):
    if not re.fullmatch(r"(?:0|[1-9][0-9]{0,3})\.(?:0|[1-9][0-9]{0,3})\.(?:0|[1-9][0-9]{0,3})", version):
        raise ValueError("invalid release version")
    if release_id <= 0 or not 1 <= expires_days <= 90 or not 1 <= len(assets) <= 32:
        raise ValueError("invalid release ID, expiry or asset count")
    records = []
    for file in assets:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", file.name) or file.is_symlink():
            raise ValueError("invalid release asset name")
        size = file.stat().st_size
        if not 1 <= size <= 2 * 1024 * 1024 * 1024:
            raise ValueError("invalid release asset size")
        with file.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        records.append(dict(name=file.name, sha256=digest, bytes=size, platform="windows-x64", kind=file.suffix.lstrip(".")))
    if len({r["name"] for r in records}) != len(records):
        raise ValueError("duplicate release asset names")
    return dict(schemaVersion=1, releaseId=release_id, version=version, createdAt=now,
                expiresAt=now + expires_days * 86400000, assets=records)


def sign(manifest, private_pem, public_der):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    key = serialization.load_pem_private_key(private_pem, password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError("update key must be Ed25519")
    actual = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if actual != public_der:
        raise ValueError("private update key does not match the public key pinned in this build")
    encoded = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode()
    signature = key.sign(encoded)
    key.public_key().verify(signature, encoded)
    return encoded, signature


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--release-id", type=int, required=True)
    parser.add_argument("--expires-days", type=int, default=30)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("assets", type=Path, nargs="+")
    args = parser.parse_args()
    pem = os.environ.get("APKPROJECT_UPDATE_PRIVATE_KEY")
    if not pem:
        parser.error("APKPROJECT_UPDATE_PRIVATE_KEY is not configured")
    manifest = build_manifest(args.version, args.release_id, args.assets, int(time.time() * 1000), args.expires_days)
    data, signature = sign(manifest, pem.encode(), args.public_key.read_bytes())
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "release.json").write_bytes(data)
    (args.out / "release.sig").write_bytes(signature)
    print(f"Signed manifest for {len(args.assets)} assets, version {args.version}")


if __name__ == "__main__":
    main()
