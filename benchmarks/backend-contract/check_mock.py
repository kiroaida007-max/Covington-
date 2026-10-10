#!/usr/bin/env python3
"""Run a recovered backend's mock server and check every answer against the recovered OpenAPI.

Starts ``server-skeleton/BackendSkeleton.java`` (SYNTHESIZED by apkproject), calls every GET route
with placeholder path values, and validates each JSON body against the operation's response
schema: types, enum values, nullability, list items, map values and model references.

usage: check_mock.py BACKEND_DIR [--java JAVA]   (BACKEND_DIR holds openapi.yaml and server-skeleton/)
"""
import json
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import yaml


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def check(value, schema, spec, path, errors, depth=0):
    if depth > 40:
        return
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1]
        return check(value, spec["components"]["schemas"][name], spec, path, errors, depth + 1)
    if value is None:
        if not schema.get("nullable"):
            errors.append(f"{path}: null for a non-null {schema.get('type') or 'value'}")
        return
    if "allOf" in schema:
        for sub in schema["allOf"]:
            check(value, sub, spec, path, errors, depth + 1)
        return
    if "oneOf" in schema:
        trials = []
        for sub in schema["oneOf"]:
            e = []
            check(value, sub, spec, path, e, depth + 1)
            trials.append(e)
        if all(trials):
            errors.append(f"{path}: matches none of oneOf ({trials[0][0]})")
        return
    t = schema.get("type")
    ok = {
        "object": isinstance(value, dict), "array": isinstance(value, list), "string": isinstance(value, str),
        "boolean": isinstance(value, bool), "integer": isinstance(value, int) and not isinstance(value, bool),
        "number": isinstance(value, (int, float)) and not isinstance(value, bool),
    }
    if t and not ok.get(t, True):
        errors.append(f"{path}: {type(value).__name__} where the schema says {t}")
        return
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: {value!r} is not one of {schema['enum']}")
    if t == "array":
        for k, item in enumerate(value):
            check(item, schema.get("items", {}), spec, f"{path}[{k}]", errors, depth + 1)
    if t == "object":
        props = schema.get("properties", {})
        for k, v in value.items():
            if k in props:
                check(v, props[k], spec, f"{path}.{k}", errors, depth + 1)
            elif isinstance(schema.get("additionalProperties"), dict):
                check(v, schema["additionalProperties"], spec, f"{path}.{k}", errors, depth + 1)
            elif props and schema.get("additionalProperties") is not True:
                errors.append(f"{path}: unexpected property {k!r}")
        for k in props:
            if k not in value:
                errors.append(f"{path}: property {k!r} missing")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    backend = Path(sys.argv[1])
    java = sys.argv[sys.argv.index("--java") + 1] if "--java" in sys.argv else "java"
    spec = yaml.safe_load((backend / "openapi.yaml").read_text())
    port = free_port()
    server = subprocess.Popen([java, str(backend / "server-skeleton" / "BackendSkeleton.java"), str(port)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        # The JVM may print notices (JAVA_TOOL_OPTIONS) before the server's own startup line.
        deadline = time.time() + 120
        seen = []
        while time.time() < deadline:
            line = server.stdout.readline()
            if not line:
                break
            seen.append(line)
            if "Skeleton for" in line:
                break
        if not seen or "Skeleton for" not in seen[-1]:
            print("mock server did not start:", "".join(seen))
            return 1
        routes = answered = valid = 0
        failures = []
        for path, ops in spec["paths"].items():
            op = ops.get("get")
            if not op:
                continue
            routes += 1
            url = f"http://127.0.0.1:{port}" + "/".join("x" if seg.startswith("{") else seg for seg in path.split("/"))
            try:
                with urllib.request.urlopen(url, timeout=10) as r:
                    status, body = r.status, r.read().decode()
            except urllib.error.HTTPError as e:
                status, body = e.code, e.read().decode()
            if status == 501:
                continue
            answered += 1
            schema = op["responses"]["200"].get("content", {}).get("application/json", {}).get("schema")
            if status == 204 or schema is None:
                valid += 1
                continue
            errors = []
            try:
                check(json.loads(body), schema, spec, path, errors)
            except json.JSONDecodeError as e:
                errors.append(f"{path}: not JSON ({e})")
            if errors:
                failures.append(errors)
            else:
                valid += 1
        print(f"GET routes {routes}: {answered} answered with example JSON, {valid} valid against the OpenAPI schema, {routes - answered} answer 501 (response shape unknown)")
        for errors in failures:
            print("  " + "; ".join(errors[:3]) + (f" (+{len(errors) - 3} more)" if len(errors) > 3 else ""))
        return 0 if not failures else 1
    finally:
        server.terminate()
        server.wait(timeout=10)


if __name__ == "__main__":
    sys.exit(main())
