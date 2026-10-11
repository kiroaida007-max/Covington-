"""Validates a CycloneDX 1.5 JSON BOM against the official schema (CycloneDX/specification, tag 1.5).

Usage: python3 -I validate.py SCHEMA_DIR BOM.json
Exit 0 when valid, 1 with the first errors printed otherwise, 3 when jsonschema is not installed.
"""
import json
import pathlib
import sys

try:
    from jsonschema import Draft7Validator
    from referencing import Registry, Resource
except ImportError:
    print("jsonschema with referencing is not installed")
    sys.exit(3)

schemas = pathlib.Path(sys.argv[1])


def resource(name):
    """Load a sibling schema file from the schema directory as a referencing Resource."""
    return Resource.from_contents(json.loads((schemas / name).read_text(encoding="utf-8")))


# bom-1.5 refers to its sibling schemas by relative name; resolve both forms offline.
pairs = []
for name in ("spdx.schema.json", "jsf-0.82.schema.json"):
    pairs.append((name, resource(name)))
    pairs.append(("http://cyclonedx.org/schema/" + name, resource(name)))
registry = Registry().with_resources(pairs)
schema = json.loads((schemas / "bom-1.5.schema.json").read_text(encoding="utf-8"))
validator = Draft7Validator(schema, registry=registry, format_checker=Draft7Validator.FORMAT_CHECKER)
bom = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
errors = sorted(validator.iter_errors(bom), key=lambda e: list(e.path))
for e in errors[:20]:
    print(list(e.path), e.message[:300])
print("valid" if not errors else "%d errors" % len(errors))
sys.exit(1 if errors else 0)
