#!/usr/bin/env python3
"""Run a recovered backend's mock server and check every answer against the recovered OpenAPI.

Starts ``server-skeleton/BackendSkeleton.java`` (SYNTHESIZED by apkproject), exercises every audited
route (legacy exports: GET only), and validates each JSON body against the operation's response
schema: types, enum values, nullability, list items, map values and model references.

usage: check_mock.py BACKEND_DIR [--java JAVA]   (requires PyYAML and openapi-spec-validator)
"""
import json
import hashlib
import queue
import re
import subprocess
import sys
import time
import threading
import urllib.request
import urllib.parse
from pathlib import Path

def check(value, schema, spec, path, errors, depth=0):
    if depth > 40:
        errors.append(f"{path}: validation depth budget exceeded")
        return
    if "$ref" in schema:
        name = schema["$ref"].rsplit("/", 1)[-1].replace("~1", "/").replace("~0", "~")
        return check(value, spec["components"]["schemas"][name], spec, path, errors, depth + 1)
    if "oneOf" in schema or "anyOf" in schema:
        mode = "oneOf" if "oneOf" in schema else "anyOf"
        trials = []
        for sub in schema[mode]:
            e = []
            check(value, sub, spec, path, e, depth + 1)
            trials.append(e)
        successes = sum(not e for e in trials)
        if successes == 0 or (mode == "oneOf" and successes != 1):
            errors.append(f"{path}: matches {successes} alternatives for {mode}")
        return
    if value is None:
        if not schema.get("nullable") or "enum" in schema and None not in schema["enum"]:
            errors.append(f"{path}: null is not allowed by this schema")
        return
    if "allOf" in schema:
        for sub in schema["allOf"]:
            check(value, sub, spec, path, errors, depth + 1)
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
    import yaml
    from openapi_spec_validator import validate_spec
    from openapi_schema_validator import OAS30Validator
    class UniqueLoader(yaml.SafeLoader):
        pass
    def mapping(loader, node, deep=False):
        result = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in result:
                raise ValueError(f"duplicate YAML key: {key}")
            result[key] = loader.construct_object(value_node, deep=deep)
        return result
    UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
    java = sys.argv[sys.argv.index("--java") + 1] if "--java" in sys.argv else "java"
    spec = yaml.load((backend / "openapi.yaml").read_text(), Loader=UniqueLoader)
    validate_spec(spec)
    response_validator = OAS30Validator(spec, format_checker=OAS30Validator.FORMAT_CHECKER)
    print("OpenAPI valid; no duplicate YAML keys")
    audit_file = backend / "contract-audit.json"
    audit = json.loads(audit_file.read_text()) if audit_file.exists() else None
    server = subprocess.Popen([java, str(backend / "server-skeleton" / "BackendSkeleton.java"), "0"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = queue.Queue()
    def read_lines():
        for line in server.stdout:
            lines.put(line)
        lines.put(None)
    threading.Thread(target=read_lines, daemon=True).start()
    try:
        # The JVM may print notices (JAVA_TOOL_OPTIONS) before the server's own startup line.
        deadline = time.monotonic() + 120
        seen = []
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=max(0.01, deadline - time.monotonic()))
            except queue.Empty:
                break
            if not line:
                break
            seen.append(line)
            if "Skeleton for" in line:
                break
        if not seen or "Skeleton for" not in seen[-1]:
            print("mock server did not start:", "".join(seen))
            return 1
        port = int(seen[-1].strip().rsplit(":", 1)[1])
        routes = answered = valid = withheld = 0
        failures = []
        defaults = audit["routes"] if audit else [{"method": "GET", "path": p, "fixedQuery": {}} for p, ops in spec["paths"].items() if "get" in ops]
        probes = []
        for probe in defaults:
            if probe.get("status") == "CHECKED_CLIENT_SELECTION":
                variants = probe["responseVariants"]
                field = variants[0]["clientSelection"]["jsonName"]
                # Include a missing property and a nested decoy. Only a top-level key selects.
                for state, body in [("null", {}), ("null", {field: None}), ("empty", {field: ""}),
                                    ("nonempty", {field: "__observed_client_probe__"}),
                                    ("null", {"nested": {field: "__decoy__"}})]:
                    matches = [v for v in variants if state in v["clientSelection"]["stringStates"]]
                    if len(matches) != 1:
                        raise ValueError("client selection is not a complete disjoint partition")
                    v = matches[0]
                    probes.append(dict(probe, status=v["status"], exampleSha256=v["exampleSha256"], expectedVariant=v["id"], requestBody=json.dumps(body)))
                for value in [True, 1, [], {}]:
                    probes.append(dict(probe, requestBody=json.dumps({field: value}), expectedStatus=400))
            else:
                probes.append(probe)
            for variant in probe.get("responseVariants", []):
                probes.append(dict(probe, status=variant["status"], exampleSha256=variant["exampleSha256"], selectedVariant=variant["id"]))
        shape = lambda p: re.sub(r"\{[^{}]+\}", "{}", p)
        for probe in probes:
            path, method = probe["path"], probe["method"]
            op = next((ops.get(method.lower()) for p, ops in spec["paths"].items() if shape(p) == shape(path)), None)
            if op is None:
                failures.append([f"{method} {path}: absent from OpenAPI"])
                continue
            routes += 1
            concrete = re.sub(r"\{[^{}]+\}", "__audit_probe_42__", path)
            query = urllib.parse.urlencode([(k, v) for k, values in probe["fixedQuery"].items() for v in values])
            url = f"http://127.0.0.1:{port}" + concrete + ("?" + query if query else "")
            request_headers = {"X-Apkproject-Variant": probe["selectedVariant"]} if "selectedVariant" in probe else {}
            request_body = probe.get("requestBody")
            if request_body is not None:
                request_headers["Content-Type"] = "application/json"
            try:
                with urllib.request.urlopen(urllib.request.Request(url, method=method, headers=request_headers,
                                          data=request_body.encode("utf-8") if request_body is not None else None), timeout=10) as r:
                    status, body, headers = r.status, r.read().decode(), r.headers
            except urllib.error.HTTPError as e:
                status, body, headers = e.code, e.read().decode(), e.headers
            if audit and headers.get("X-Apkproject-Evidence") != "SYNTHESIZED":
                failures.append([f"{method} {path}: missing synthesized evidence header"])
            if audit and headers.get("X-Apkproject-Contract") != audit["contractSha256"]:
                failures.append([f"{method} {path}: server belongs to a different contract than the audit"])
            expected_variant = probe.get("expectedVariant", probe.get("selectedVariant"))
            if expected_variant and headers.get("X-Apkproject-Variant") != expected_variant:
                failures.append([f"{method} {path}: answered by a different response variant"])
            if "expectedVariant" in probe and headers.get("X-Apkproject-Selection") != "OBSERVED_CLIENT_RULE":
                failures.append([f"{method} {path}: missing observed client selection header"])
            if "expectedStatus" in probe:
                if status != probe["expectedStatus"]:
                    failures.append([f"{method} {path}: invalid selection input answered HTTP {status}"])
                continue
            if status == 501:
                withheld += 1
                if audit and probe["status"] == "CHECKED_EXAMPLE":
                    failures.append([f"{method} {path}: checked example unexpectedly withheld"])
                continue
            if status not in (200, 204):
                failures.append([f"{method} {path}: unexpected HTTP {status}"])
                continue
            if audit and probe["status"] != "CHECKED_EXAMPLE":
                failures.append([f"{method} {path}: unchecked example served as success"])
            answered += 1
            if audit and headers.get("X-Apkproject-Route") != probe["id"]:
                failures.append([f"{method} {path}: answered by a different route than the audit"])
            if str(status) not in op["responses"]:
                failures.append([f"{method} {path}: status {status} missing from OpenAPI"])
                continue
            schema = op["responses"][str(status)].get("content", {}).get("application/json", {}).get("schema")
            if status == 204 or method == "HEAD" or schema is None:
                valid += 1
                continue
            errors = []
            try:
                value = json.loads(body)
                errors.extend(f"{path}: standard OpenAPI response validation: {e.message}" for e in response_validator.evolve(schema=schema).iter_errors(value))
                check(value, schema, spec, path, errors)
                if audit and hashlib.sha256(body.encode("utf-8")).hexdigest() != probe["exampleSha256"]:
                    errors.append(f"{path}: response differs from the audited example hash")
            except json.JSONDecodeError as e:
                errors.append(f"{path}: not JSON ({e})")
            if errors:
                failures.append(errors)
            else:
                valid += 1
        print(f"Routes {len(defaults)}, probes {routes}: {answered} examples served, {valid} valid against OpenAPI, {withheld} explicitly withheld (501)")
        if audit:
            print(f"Audit: {audit['checkedExamples']} default examples checked, {audit.get('checkedClientSelections', 0)} checked client selections, {audit['withheldExamples']} withheld by default, {audit.get('checkedResponseVariants', 0)} selectable checked variants, {len(audit['issues'])} issues; original server behavior is not verified")
        print("Responses checked with the standard OpenAPI 3.0 validator, the independent completeness checker and example hashes")
        for errors in failures:
            print("  " + "; ".join(errors[:3]) + (f" (+{len(errors) - 3} more)" if len(errors) > 3 else ""))
        return 0 if not failures else 1
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait(timeout=10)


if __name__ == "__main__":
    sys.exit(main())
