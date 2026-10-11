#!/usr/bin/env python3
"""Independently check source type attributions without inflating APK recovery scores."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess

from evaluate import moshi_models, declared_types, scoped_field_type


def check(source, contract, raw_contract, manifest, apk):
    with Path(apk).open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if manifest["apkSha256"] != digest:
        raise ValueError("reference manifest APK hash differs")
    head = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if head != manifest["revision"]:
        raise ValueError("reference source revision differs")
    truth, names = moshi_models(source), declared_types(source)
    bindings = {(b["model"], b["property"]): b for b in manifest["bindings"]}
    if len(bindings) != len(manifest["bindings"]):
        raise ValueError("duplicate reference binding")
    found = set()
    for model in contract["models"]:
        for field in model["fields"]:
            ref = field.get("referenceType")
            if not ref:
                continue
            key = model["className"], field["javaName"]
            if key not in bindings or key in found:
                raise ValueError("extra or duplicate reference attribution")
            b = bindings[key]
            original = truth[model["className"]][field["jsonName"]]
            if scoped_field_type(original[3], names) != ref["name"] or ref["name"] != b["referenceType"]:
                raise ValueError("reference name differs from independent scoped source declaration")
            if field["javaType"] != b["bytecodeType"]:
                raise ValueError("APK type was replaced by a source name")
            expected = dict(repository=manifest["repository"], revision=head, sourcePath=b["sourcePath"],
                            sourceSha256=b["sourceSha256"], apkSha256=digest,
                            sourceProperty=".".join(key))
            if any(ref.get(k) != v for k, v in expected.items()):
                raise ValueError("reference provenance differs")
            committed = subprocess.check_output(["git", "-C", str(source), "show", f"{head}:{b['sourcePath']}"])
            if hashlib.sha256(committed).hexdigest() != b["sourceSha256"] or (source / b["sourcePath"]).read_bytes() != committed:
                raise ValueError("reference source is not the exact committed bytes")
            if not ref["scope"].startswith("REFERENCE_SOURCE:") or "not bytecode identity verification" not in ref["scope"]:
                raise ValueError("reference name lacks its separate evidence scope")
            found.add(key)
    if found != set(bindings):
        raise ValueError("missing reference attribution")
    note = f"{len(found)} type names attributed to supplied reference source. APK types and extraction scores remain separate; source hashes do not prove original build or server identity."
    if contract["notes"] != list(dict.fromkeys(raw_contract["notes"] + [note])):
        raise ValueError("reference attribution changed extraction notes")
    before, after = copy.deepcopy(raw_contract), copy.deepcopy(contract)
    for c in (before, after):
        for m in c["models"]:
            for f in m["fields"]:
                f.pop("referenceType", None)
        c.pop("notes", None)
    if before != after:
        raise ValueError("source attribution changed extracted contract facts")
    return len(found)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", type=Path)
    p.add_argument("contract", type=Path)
    p.add_argument("--raw-contract", type=Path, required=True)
    p.add_argument("--manifest", type=Path, required=True)
    p.add_argument("--apk", type=Path, required=True)
    a = p.parse_args()
    count = check(a.source, json.loads(a.contract.read_text()), json.loads(a.raw_contract.read_text()),
                  json.loads(a.manifest.read_text()), a.apk)
    print(f"Reference source: {count} checked type attributions; original APK contract facts unchanged")


if __name__ == "__main__":
    main()
