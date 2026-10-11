#!/usr/bin/env python3
"""Validate the product BOM schema and hashes against the actual packaged JVM distribution."""
import argparse
import hashlib
import json
from pathlib import Path
from jsonschema import Draft7Validator, RefResolver

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def check(bom_file, libraries):
    schema_root = ROOT / "modules/app-recovery/src/test/resources/cyclonedx-1.5"
    schemas = [json.loads(p.read_text()) for p in schema_root.glob("*.json")]
    schema = json.loads((schema_root / "bom-1.5.schema.json").read_text())
    bom = json.loads(bom_file.read_text())
    Draft7Validator(schema, resolver=RefResolver.from_schema(schema, store={s["$id"]: s for s in schemas})).validate(bom)
    jars = {p.name: p for p in libraries.glob("*.jar")}
    application = [p for name, p in jars.items() if name.startswith("desktop-")]
    if len(application) != 1:
        raise ValueError("expected one packaged desktop application JAR")
    def matches(component, path):
        hashes = [h["content"] for h in component.get("hashes", []) if h["alg"] == "SHA-256"]
        if hashes != [digest(path)]:
            raise ValueError("BOM hash does not match packaged artifact: " + path.name)
    matches(bom["metadata"]["component"], application[0])
    inventory = {application[0].name}
    for component in bom["components"]:
        filenames = [p["value"] for p in component.get("properties", []) if p["name"] == "apkproject:artifact-file"]
        if len(filenames) != 1 or filenames[0] not in jars or filenames[0] in inventory:
            raise ValueError("missing or duplicate packaged component")
        inventory.add(filenames[0])
        matches(component, jars[filenames[0]])
    if inventory != set(jars):
        raise ValueError("packaged JVM artifacts missing from inventory")
    print(f"Product BOM schema and {len(inventory)} packaged JAR hashes valid")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bom", type=Path)
    parser.add_argument("libraries", type=Path)
    args = parser.parse_args()
    check(args.bom, args.libraries)
