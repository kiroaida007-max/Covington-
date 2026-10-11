#!/usr/bin/env python3
"""Score a recovered backend contract against the app's own Retrofit and Moshi source.

Ground truth comes from the source the APK was built from:
  * Retrofit interfaces (``@GET``/``@POST``/... functions): method, path, every parameter with
    its location (path, query, field, part, header, body, ...) and wire name, and the response
    type with wrappers (``Response``, ``NetworkResult``, ``Call``, ...) removed;
  * Moshi models (``@JsonClass(generateAdapter = true)`` classes): the JSON name of every
    property (``@Json(name = ...)`` or the property name).

Ground truth that R8 removed from the APK (never-called endpoints, unused models) cannot be
recovered; with ``--apk APP.apk`` it is counted separately instead of as missing.

usage: evaluate.py SOURCE_ROOT api-contract.json [--apk APP.apk] [--details]
"""
import json
import argparse
import hashlib
import subprocess
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path

HTTP = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}
LOCATIONS = {
    "Path": "path", "Query": "query", "QueryMap": "querymap", "QueryName": "queryname",
    "Field": "field", "FieldMap": "fieldmap", "Part": "part", "PartMap": "partmap",
    "Header": "header", "HeaderMap": "headermap", "Body": "body", "Url": "url", "Tag": "tag",
}
WRAPPERS = {"Response", "NetworkResult", "Call", "Single", "Observable", "Maybe", "Flowable", "Deferred", "Flow", "Result"}


LEXEMES = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*', re.S)


def strip_comments(text):
    # Preserve literal URLs and braces. Removing // inside a string corrupts Kotlin source.
    return LEXEMES.sub(lambda m: re.sub(r"[^\n]", " ", m[0]) if m[0].startswith(("//", "/*")) else m[0], text)


def syntax_mask(text):
    return LEXEMES.sub(lambda m: re.sub(r"[^\n]", " ", m[0]), text)


def split_top(s, sep=","):
    parts, depth, cur, quote = [], 0, [], False
    for ch in s:
        if ch == '"':
            quote = not quote
        elif not quote and ch in "(<[":
            depth += 1
        elif not quote and ch in ")>]":
            depth -= 1
        if ch == sep and depth == 0 and not quote:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]


def matching(text, start, open_ch="(", close_ch=")"):
    text = syntax_mask(text)
    depth = 0
    for i in range(start, len(text)):
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
    raise ValueError("unbalanced")


def annotation_value(args, consts, key=None):
    """First string argument (or named argument ``key``), with ``const val`` names resolved."""
    if args is None:
        return None
    for part in split_top(args):
        name, _, value = part.partition("=")
        if value and key and name.strip() != key:
            continue
        value = (value or name).strip()
        if value.startswith('"'):
            return value.strip('"')
        if value in consts:
            return consts[value]
        if not key:
            return value
    return None


def simple_type(t):
    """`NetworkResult<List<com.x.Emoji>>?` -> `List<Emoji>`; wrappers removed, packages dropped."""
    t = t.replace("?", "").replace(" ", "")
    t = re.sub(r"\b(?:[a-z_][\w]*\.)+([A-Z])", r"\1", t)
    while True:
        m = re.fullmatch(r"(\w+)<(.*)>", t)
        if m and m.group(1) in WRAPPERS:
            t = m.group(2)
        else:
            break
    t = t.replace("MutableList", "List").replace("ArrayList", "List").replace("MutableMap", "Map").replace("HashMap", "Map").replace("LinkedHashMap", "Map")
    t = {"Unit": "void", "Void": "void"}.get(t, t)
    # Kotlin and Java spell the same JSON type differently.
    for kotlin, java in (("Boolean", "boolean"), ("Int", "int"), ("Integer", "int"), ("Long", "long"), ("Float", "float"), ("Double", "double"), ("Short", "short"), ("Byte", "byte"), ("Char", "char"), ("Character", "char")):
        t = re.sub(rf"\b{kotlin}\b", java, t)
    # Nested classes: compare the innermost name (Attachment.Size and Size are the same class).
    t = re.sub(r"\b(?:[A-Z]\w*\.)+([A-Z]\w*)", r"\1", t)
    return t


def retrofit_endpoints(root):
    endpoints = []
    for file in sorted(root.rglob("*.kt")) + sorted(root.rglob("*.java")):
        if "/test/" in str(file) or "/androidTest/" in str(file):
            continue
        raw = file.read_text(encoding="utf-8", errors="replace")
        if not re.search(r"@(GET|POST|PUT|PATCH|DELETE|HTTP)\(", raw):
            continue
        text = strip_comments(raw)
        consts = dict(re.findall(r'const\s+val\s+(\w+)\s*=\s*"([^"]*)"', text))
        consts.update(re.findall(r'static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"', text))
        for m in re.finditer(r"((?:@\w+(?:\((?:[^()]|\([^()]*\))*\))?\s*)+)(?:suspend\s+)?fun\s+(\w+)\s*\(", text):
            annotations = re.findall(r"@(\w+)(?:\(((?:[^()]|\([^()]*\))*)\))?", m.group(1))
            method = path = None
            for name, args in annotations:
                if name in HTTP:
                    method, path = name, annotation_value(args, consts) or ""
                elif name == "HTTP":
                    method, path = annotation_value(args, consts, "method"), annotation_value(args, consts, "path") or ""
            if method is None:
                continue
            close = matching(text, m.end() - 1)
            params = []
            for p in split_top(text[m.end():close]):
                pann = re.findall(r"@(\w+)(?:\(((?:[^()]|\([^()]*\))*)\))?", p)
                decl = re.sub(r"@\w+(?:\((?:[^()]|\([^()]*\))*\))?", "", p).strip()
                pname, _, ptype = decl.partition(":")
                ptype = ptype.split("=")[0].strip()
                for an, args in pann:
                    if an in LOCATIONS:
                        loc = LOCATIONS[an]
                        wire = annotation_value(args, consts, "value") if args else None
                        params.append({"location": loc, "name": wire or pname.strip(), "named": wire is not None, "type": simple_type(ptype),
                                       "callsite": None if wire else pname.strip()})
            rest = text[close + 1:close + 300]
            rm = re.match(r"\s*:\s*([\w.<>?, ]+?)\s*(?:\n|$|\{|=)", rest)
            response = simple_type(rm.group(1)) if rm else "void"
            endpoints.append({
                "method": method.upper(), "path": "/" + path.lstrip("/"), "function": m.group(2),
                "file": file.name, "params": params, "response": response,
            })
    return endpoints


def class_bodies(text):
    """(name, start, body_start, body_end) of every class in the file, for nesting."""
    text = syntax_mask(text)
    out = []
    for m in re.finditer(r"\b(?:class|object|interface)\s+(\w+)", text):
        i = m.end()
        while i < len(text) and text[i] in " \t<":
            if text[i] == "<":
                i = matching(text, i, "<", ">")
            i += 1
        if i < len(text) and text[i] == "(":
            i = matching(text, i) + 1
        stop = re.search(r"\{|\n\s*\n|\n\s*(?:@|class |data |enum |sealed |object |interface |fun |val |var |\})", text[i:])
        if stop and text[i + stop.start()] == "{":
            start = i + stop.start()
            out.append((m.group(1), m.start(), start, matching(text, start, "{", "}")))
    return out


def moshi_models(root):
    models = {}
    for file in sorted(root.rglob("*.kt")):
        if "/test/" in str(file):
            continue
        raw = file.read_text(encoding="utf-8", errors="replace")
        if "JsonClass" not in raw:
            continue
        text = strip_comments(raw)
        package = (re.search(r"^\s*package\s+([\w.]+)", text, re.M) or [None, ""])[1]
        bodies = class_bodies(text)
        imports = dict((alias or name.rsplit(".", 1)[-1], name) for name, alias in
                       re.findall(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", text, re.M))
        for m in re.finditer(r"@JsonClass\(\s*generateAdapter\s*=\s*true[^)]*\)\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:data|internal|public|private)\s+)*class\s+(\w+)[^(]*\(", text):
            close = matching(text, m.end() - 1)
            outer = [name for name, start, b0, b1 in sorted(bodies, key=lambda b: b[2]) if b0 < m.start() < b1]
            scope = ".".join([package] + outer + [m.group(1)])
            fields = {}
            for p in split_top(text[m.end():close]):
                if not re.search(r"\b(val|var)\b", p):
                    continue
                if re.search(r"@(?:field:)?Json\s*\(\s*ignore\s*=\s*true", p) or "@Transient" in p:
                    continue
                prop = re.search(r"\b(?:val|var)\s+(\w+)\s*:\s*([^=]+)", p)
                jn = re.search(r'@(?:field:)?Json\s*\(\s*name\s*=\s*"([^"]+)"', p)
                if prop:
                    fields[jn.group(1) if jn else prop.group(1)] = (simple_type(prop.group(2)), prop.group(1), prop.group(2).strip().endswith("?"),
                                                               (prop.group(2).strip(), scope, package, imports))
            fqn = ".".join([package] + outer + [m.group(1)])
            models[fqn] = fields
    return models


def enums(root):
    """Fully qualified enum name -> JSON values. A nested Type is never a global Type."""
    out = {}
    for file in sorted(root.rglob("*.kt")):
        if "/test/" in str(file):
            continue
        text = strip_comments(file.read_text(encoding="utf-8", errors="replace"))
        package = (re.search(r"^\s*package\s+([\w.]+)", text, re.M) or [None, ""])[1]
        bodies = class_bodies(text)
        for m in re.finditer(r"enum\s+class\s+(\w+)[^{]*\{", syntax_mask(text)):
            body = text[m.end():matching(text, m.end() - 1, "{", "}")]
            head = body.split(";")[0]
            values = []
            for c in split_top(head):
                c = c.strip()
                jn = re.search(r'@(?:field:)?Json\s*\(\s*name\s*=\s*"([^"]+)"', c)
                name = re.sub(r"@\w+(?:\([^)]*\))?", "", c).strip()
                name = re.match(r"(\w+)", name)
                if name:
                    values.append(jn.group(1) if jn else name.group(1))
            outer = [name for name, _, b0, b1 in sorted(bodies, key=lambda b: b[2]) if b0 < m.start() < b1]
            out[".".join([package] + outer + [m.group(1)])] = values
    return out


def declared_types(root):
    """Include non-enums: a local sealed Type shadows an imported or unrelated enum Type."""
    names = set()
    for file in sorted(root.rglob("*.kt")):
        if "/test/" in str(file) or "/androidTest/" in str(file):
            continue
        text = strip_comments(file.read_text(encoding="utf-8", errors="replace"))
        package = (re.search(r"^\s*package\s+([\w.]+)", text, re.M) or [None, ""])[1]
        bodies = class_bodies(text)
        for m in re.finditer(r"\b(?:class|object|interface|typealias)\s+(\w+)", syntax_mask(text)):
            outer = [name for name, _, b0, b1 in sorted(bodies, key=lambda b: b[2]) if b0 < m.start() < b1]
            names.add(".".join([package] + outer + [m.group(1)]))
    return names


def scoped_field_type(context, names):
    raw, scope, package, imports = context
    inner = raw.replace("?", "").replace(" ", "")
    inner = re.sub(r"^(?:List|Set|MutableList)<(.+)>$", r"\1", inner)
    # Do not guess arbitrary generic arguments, aliases or wildcard imports.
    if not re.fullmatch(r"[\w.]+", inner):
        return None
    if inner in names:
        return inner
    prefix = scope
    while prefix.startswith(package + "."):
        candidate = prefix + "." + inner
        if candidate in names:
            return candidate
        prefix = prefix.rsplit(".", 1)[0]
    first, _, rest = inner.partition(".")
    if first in imports:
        candidate = imports[first] + ("." + rest if rest else "")
        return candidate if candidate in names else None
    candidate = package + "." + inner
    return candidate if candidate in names else None


def recovered(contract):
    eps = []
    for e in contract["endpoints"]:
        eps.append({
            "method": e["method"].upper(), "path": "/" + e["path"].lstrip("/"), "operation": e.get("operation"),
            "params": [{"location": p["location"], "name": p["name"], "type": simple_type(p.get("javaType") or ""),
                        "named": p.get("nameSource", "annotation") == "annotation",
                        "callsite": p["name"] if p.get("nameSource") == "call-site" else None} for p in e["params"]],
            "response": simple_type(e.get("responseType") or "void"), "body": e.get("bodyType"),
        })
    models = {m["className"]: {f["jsonName"]: (simple_type(f.get("javaType") or ""), f.get("javaName"), f.get("enumValues") or [], f.get("nullable")) for f in m["fields"]} for m in contract.get("models", [])}
    return eps, models


def dex_bytes(apk):
    with zipfile.ZipFile(apk) as z:
        return b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))


def regression_errors(metrics, baseline):
    errors = []
    for key, expected in baseline.items():
        actual = metrics.get(key)
        if actual is None:
            errors.append(f"missing metric {key}")
        elif key.endswith("_matched"):
            if actual < expected:
                errors.append(f"{key}: {actual} below {expected}")
        elif key.endswith(("_extra", "_missing")):
            if actual > expected:
                errors.append(f"{key}: {actual} above {expected}")
        elif actual != expected:
            errors.append(f"{key}: dataset coverage changed ({actual}, expected {expected})")
    return errors


def behavior_metrics(contract, truth):
    metrics = dict(custom_scalars_total=0, custom_scalars_matched=0, custom_values_total=0, custom_values_matched=0, custom_values_extra=0,
                   client_selections_total=0, client_selections_matched=0, client_selections_extra=0)
    for scalar in truth["customScalars"]:
        metrics["custom_scalars_total"] += 1
        metrics["custom_values_total"] += len(scalar["knownValues"])
        fields = [f for m in contract["models"] if m["className"] == scalar["model"] for f in m["fields"] if f["jsonName"] == scalar["field"]]
        wire = fields[0].get("wire") if len(fields) == 1 else None
        if wire and wire["type"] == scalar["type"] and wire.get("evidence") and not fields[0].get("enumValues"):
            metrics["custom_scalars_matched"] += 1
            metrics["custom_values_matched"] += len(set(wire["knownValues"]) & set(scalar["knownValues"]))
            metrics["custom_values_extra"] += len(set(wire["knownValues"]) - set(scalar["knownValues"]))
    def key(e):
        return (e["method"], e["path"].lstrip("/"), e["bodyType"])
    expected = {key(rule) for rule in truth["clientSelections"]}
    predicted = {key(e) for e in contract["endpoints"] if e.get("clientSelection")}
    metrics["client_selections_extra"] = len(predicted - expected)
    for rule in truth["clientSelections"]:
        metrics["client_selections_total"] += 1
        endpoints = [e for e in contract["endpoints"] if key(e) == key(rule)]
        got = {e["responseType"]: e.get("clientSelection") for e in endpoints}
        if len(endpoints) == len(rule["variants"]) and set(got) == set(rule["variants"]) and all(
            got[t] and got[t]["jsonName"] == rule["jsonName"] and sorted(got[t]["stringStates"]) == sorted(states)
            and got[t].get("evidence") for t, states in rule["variants"].items()):
            metrics["client_selections_matched"] += 1
    return metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("contract", type=Path)
    parser.add_argument("--apk")
    parser.add_argument("--details", action="store_true")
    parser.add_argument("--metrics-out", type=Path)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    root, contract_path, details, apk = args.source, args.contract, args.details, args.apk
    baseline = json.loads(args.baseline.read_text()) if args.baseline else None
    behavior_truth = json.loads((args.baseline.parent / baseline["behaviorTruth"]).read_text()) if baseline and baseline.get("behaviorTruth") else None
    if behavior_truth:
        for file, expected_hash in behavior_truth["sourceFiles"].items():
            if hashlib.sha256((root / file).read_bytes()).hexdigest() != expected_hash:
                parser.error(f"behavior ground truth source changed: {file}")
    if baseline and baseline.get("apkSha256"):
        if not apk:
            parser.error("this baseline requires --apk")
        with open(apk, "rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != baseline["apkSha256"]:
                parser.error("APK does not match the baseline's pinned input")
    if baseline and baseline.get("sourceCommit"):
        head = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True, timeout=10)
        clean = subprocess.run(["git", "-C", str(root), "diff", "--quiet", "HEAD", "--", "*.kt"], timeout=10)
        if head.returncode or head.stdout.strip() != baseline["sourceCommit"] or clean.returncode:
            parser.error("source does not match the baseline's clean pinned Kotlin source")
    truth = retrofit_endpoints(root)
    contract = json.loads(contract_path.read_text())
    got, got_models = recovered(contract)
    absent_endpoints, absent_models = [], []
    if apk:
        # A path string or adapter class name missing from every DEX file was removed by R8.
        dex = dex_bytes(apk)
        absent_endpoints = [e for e in truth if e["path"].lstrip("/").encode() not in dex]
        truth = [e for e in truth if e not in absent_endpoints]

    def key(e):
        return (e["method"], e["path"])

    truth_keys = Counter(key(e) for e in truth)
    got_keys = Counter(key(e) for e in got)
    tp = sum((truth_keys & got_keys).values())
    missing = truth_keys - got_keys
    extra = got_keys - truth_keys
    print(f"endpoints: truth {len(truth)}, recovered {len(got)}, matched {tp}, missing {sum(missing.values())}, extra {sum(extra.values())}")
    for k in sorted(missing):
        print(f"  MISSING  {k[0]} {k[1]}")
    for k in sorted(extra):
        print(f"  EXTRA    {k[0]} {k[1]}")

    # Pair endpoints with the same method and path; several functions may share one (with and
    # without auth headers): pair each truth function with the recovered one whose parameters
    # agree best.
    by_key = {}
    for e in got:
        by_key.setdefault(key(e), []).append(e)
    param_stats = Counter()
    response_ok = response_total = 0
    problems = []
    for t in truth:
        cands = by_key.get(key(t), [])
        if not cands:
            continue

        def wire(p):
            # Maps, bodies and unnamed parts carry no wire name in the declaration: compare their
            # location (and, for bodies, type) only.
            if p["location"] in ("querymap", "fieldmap", "partmap", "headermap", "url", "tag"):
                return (p["location"], "*")
            if p["location"] == "body":
                return ("body", p["type"])
            # An unnamed @Part gets its name where the part is built; the Kotlin parameter is named after it.
            if p["location"] == "part" and not p.get("named", True):
                return ("part", p.get("callsite") or "*")
            return (p["location"], p["name"])

        def pset(e):
            items = [wire(p) for p in e["params"]]
            if e.get("body"):
                items.append(("body", simple_type(e["body"])))
            return Counter(items)

        tset = Counter(wire(p) for p in t["params"])
        best = max(cands, key=lambda c: (sum((pset(c) & tset).values()), -sum((pset(c) - tset).values()), c["response"] == t["response"]))
        cands.remove(best)
        gset = pset(best)
        ok = tset & gset
        param_stats["truth"] += sum(tset.values())
        param_stats["recovered"] += sum(gset.values())
        param_stats["matched"] += sum(ok.values())
        if tset != gset:
            problems.append(f"  PARAMS   {t['method']} {t['path']} ({t['function']}): missing {sorted((tset - gset).elements())} extra {sorted((gset - tset).elements())}")
        response_total += 1
        if t["response"] == best["response"]:
            response_ok += 1
        else:
            problems.append(f"  RESPONSE {t['method']} {t['path']} ({t['function']}): truth {t['response']} recovered {best['response']}")
    print(f"parameters (location + wire name): truth {param_stats['truth']}, recovered {param_stats['recovered']}, matched {param_stats['matched']}")
    print(f"response types: {response_ok} of {response_total} matched endpoints")

    truth_models = moshi_models(root)
    if apk:
        for name in list(truth_models):
            parts = name.split(".")
            first = next(k for k, part in enumerate(parts) if part[:1].isupper())
            # Moshi names the adapter of Outer.Inner `Outer_InnerJsonAdapter`, in the model's package.
            adapter = ("L" + "/".join(parts[:first]) + "/" + "_".join(parts[first:]) + "JsonAdapter;").encode()
            if adapter not in dex:
                absent_models.append(name)
                del truth_models[name]
        print(f"removed by R8 (not in the APK, not counted): {len(absent_endpoints)} endpoints {[e['method'] + ' ' + e['path'] for e in absent_endpoints]}, {len(absent_models)} models {absent_models}")
    common = sorted(set(truth_models) & set(got_models))
    field_tp = field_truth = field_got = 0
    model_problems = []
    type_ok = type_total = enum_ok = enum_total = prop_ok = prop_total = null_ok = null_total = 0
    type_problems = Counter()
    enum_truth = enums(root)
    source_types = declared_types(root)
    for name in common:
        t, g = set(truth_models[name]), set(got_models[name])
        field_tp += len(t & g)
        field_truth += len(t)
        field_got += len(g)
        if t != g:
            model_problems.append(f"  FIELDS   {name}: missing {sorted(t - g)} extra {sorted(g - t)}")
        for f in t & g:
            type_total += 1
            ttype, tprop, tnull, context = truth_models[name][f]
            gtype, gprop, gvalues, gnull = got_models[name][f]
            if gnull is not None:
                null_total += 1
                null_ok += tnull == gnull
                if tnull != gnull:
                    model_problems.append(f"  NULLABLE {name}.{f}: truth {tnull} recovered {gnull}")
            prop_total += 1
            prop_ok += tprop == gprop
            if tprop != gprop:
                model_problems.append(f"  PROPERTY {name}.{f}: truth {tprop} recovered {gprop}")
            # An enum (or a list of one) is right when its JSON values are; R8 renamed its class.
            inner = scoped_field_type(context, source_types)
            if inner in enum_truth:
                enum_total += 1
                # Compared as sets: the order of an enum's values carries no meaning on the wire.
                if sorted(gvalues) == sorted(enum_truth[inner]):
                    enum_ok += 1
                    type_ok += 1
                    continue
                model_problems.append(f"  ENUM     {name}.{f}: truth {enum_truth[inner]} recovered {gvalues}")
            if ttype == gtype:
                type_ok += 1
            else:
                type_problems[(ttype, gtype)] += 1
                model_problems.append(f"  TYPE     {name}.{f}: truth {ttype} recovered {gtype}")
    print(f"models: truth {len(truth_models)}, recovered {len(got_models)}, same class {len(common)}; JSON fields truth {field_truth}, recovered {field_got}, matched {field_tp}")
    print(f"field types {type_ok} of {type_total} (enums by JSON values: {enum_ok} of {enum_total}); Kotlin property names {prop_ok} of {prop_total}; nullability {null_ok} of {null_total}")
    if type_problems:
        print("  most common type differences (truth -> recovered): " + "; ".join(f"{a} -> {b} x{n}" for (a, b), n in type_problems.most_common(8)))
    unmatched_got = sorted(set(got_models) - set(truth_models))
    unmatched_truth = sorted(set(truth_models) - set(got_models))
    if details:
        for line in problems + model_problems:
            print(line)
        for n in unmatched_truth:
            print(f"  MODEL MISSING {n}")
        for n in unmatched_got:
            print(f"  MODEL EXTRA   {n}")
    else:
        print(f"({len(problems)} endpoint and {len(model_problems)} model differences, {len(unmatched_truth)} models missing, {len(unmatched_got)} extra; --details lists them)")
    metrics = dict(endpoints_truth=len(truth), endpoints_recovered=len(got), endpoints_matched=tp,
                   endpoints_missing=sum(missing.values()), endpoints_extra=sum(extra.values()),
                   parameters_truth=param_stats["truth"], parameters_recovered=param_stats["recovered"], parameters_matched=param_stats["matched"],
                   response_total=response_total, response_matched=response_ok,
                   models_truth=len(truth_models), models_recovered=len(got_models), models_matched=len(common),
                   fields_truth=field_truth, fields_recovered=field_got, fields_matched=field_tp,
                   types_total=type_total, types_matched=type_ok, enums_total=enum_total, enums_matched=enum_ok,
                   properties_total=prop_total, properties_matched=prop_ok, nullability_total=null_total, nullability_matched=null_ok)
    if behavior_truth:
        metrics.update(behavior_metrics(contract, behavior_truth))
        print(f"custom scalar shapes: {metrics['custom_scalars_matched']}/{metrics['custom_scalars_total']}; known values: {metrics['custom_values_matched']}/{metrics['custom_values_total']}; client selections: {metrics['client_selections_matched']}/{metrics['client_selections_total']}, extra {metrics['client_selections_extra']}")
    if args.metrics_out:
        args.metrics_out.parent.mkdir(parents=True, exist_ok=True)
        args.metrics_out.write_text(json.dumps({"schemaVersion": 1, "contractSha256": hashlib.sha256(contract_path.read_bytes()).hexdigest(), "metrics": metrics}, indent=2) + "\n")
    errors = regression_errors(metrics, baseline["metrics"]) if baseline else []
    for error in errors:
        print(f"REGRESSION: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
