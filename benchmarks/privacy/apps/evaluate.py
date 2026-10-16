#!/usr/bin/env python3
"""Scores the privacy map's reports on the benchmark apps (apps.tsv) against what is known about each app.

Known without reading code: an app that does not request android.permission.INTERNET cannot open a
network connection, so every NETWORK flow reported for it is false (handing data to another app is IPC).
Known from reading code (flows.tsv): flows checked against the app's decompiled code, real ones the map
must find and false ones it must not report. Both are checks on what is reported, not a full ground
truth: an app's unverified flows are counted, not scored.

Usage: python3 -I benchmarks/privacy/apps/evaluate.py --runs DIR [--apks DIR] [--strict]
  DIR/<package>_<versionCode>/privacy.json, as `apkproject privacy APK --out DIR/<package>_<versionCode>` writes it.
  --strict: exit 1 when a known-false or impossible flow is reported or a known-real one is missing.
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import axml  # noqa: E402

INTERNET = "android.permission.INTERNET"


def read_tsv(path):
    """Rows of a tab-separated file, without `#` comments and blank lines."""
    with open(path, encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f if line.strip() and not line.startswith("#")]


def read_apps(path):
    """apps.tsv: package, versionCode, SHA-256, what it does -> [(app id, sha, what)] with id `package_versionCode`."""
    return [("%s_%s" % (r[0], r[1]), r[2], r[3] if len(r) > 3 else "") for r in read_tsv(path)]


def read_verdicts(path):
    """flows.tsv: app, verdict (real | false), data, sink, sink method, source (API prefix or *), why."""
    out = {}
    for r in read_tsv(path):
        if len(r) < 7 or r[1] not in ("real", "false"):
            raise ValueError("flows.tsv: bad row %r" % (r,))
        out.setdefault(r[0], []).append({"real": r[1] == "real", "data": r[2], "sink": r[3], "sink_in": r[4], "source": r[5], "why": r[6]})
    return out


def matches(flow, v):
    """Whether a reported flow is the one a verdict describes (its data, sink, sink method and source)."""
    return (flow["data"] == v["data"] and flow["sink"] == v["sink"] and flow["sinkIn"] == v["sink_in"]
            and (v["source"] == "*" or flow["sourceApi"].startswith(v["source"])))


def score(report, permissions, verdicts):
    """One app's report against its permissions and verdicts."""
    flows = report["flows"]
    stats = report.get("stats", {})
    impossible = [f for f in flows if f["sink"] == "NETWORK" and permissions is not None and INTERNET not in permissions]
    real = [v for v in verdicts if v["real"]]
    false = [v for v in verdicts if not v["real"]]
    return {
        "flows": len(flows),
        "network": sum(1 for f in flows if f["sink"] == "NETWORK"),
        "internet": None if permissions is None else INTERNET in permissions,
        "complete": not stats.get("truncated", False),
        "analyses": stats.get("analyses"),
        "impossible": impossible,
        "real_found": [v for v in real if any(matches(f, v) for f in flows)],
        "real_missing": [v for v in real if not any(matches(f, v) for f in flows)],
        "false_reported": [v for v in false if any(matches(f, v) for f in flows)],
        "real_known": len(real),
        "false_known": len(false),
    }


def describe(v):
    return "%s -> %s in %s (%s): %s" % (v["data"], v["sink"], v["sink_in"], v["source"], v["why"])


def main():
    ap = argparse.ArgumentParser()
    root = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
    ap.add_argument("--runs", required=True)
    ap.add_argument("--apks", default=os.path.join(root, ".tools", "dl", "apps"))
    ap.add_argument("--apps", default=os.path.join(HERE, "apps.tsv"))
    ap.add_argument("--flows", default=os.path.join(HERE, "flows.tsv"))
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()
    verdicts = read_verdicts(args.flows)
    unknown = set(verdicts) - {a for a, _, _ in read_apps(args.apps)}
    if unknown:
        raise SystemExit("flows.tsv names apps not in apps.tsv: %s" % ", ".join(sorted(unknown)))

    rows, problems = [], []
    totals = {"flows": 0, "impossible": 0, "real_found": 0, "real_known": 0, "false_reported": 0, "false_known": 0, "incomplete": 0, "missing": 0}
    print("| App | Flows | Network | INTERNET | Complete | Analyses | Impossible | Known real found | Known false reported |")
    print("|---|---:|---:|---|---|---:|---:|---:|---:|")
    for app, _, _ in read_apps(args.apps):
        path = os.path.join(args.runs, app, "privacy.json")
        if not os.path.exists(path):
            print("| %s | no report |  |  |  |  |  |  |  |" % app)
            totals["missing"] += 1
            continue
        with open(path, encoding="utf-8") as f:
            report = json.load(f)
        apk = os.path.join(args.apks, app + ".apk")
        permissions = axml.permissions(apk) if os.path.exists(apk) else None
        s = score(report, permissions, verdicts.get(app, []))
        totals["flows"] += s["flows"]
        totals["impossible"] += len(s["impossible"])
        totals["real_found"] += len(s["real_found"])
        totals["real_known"] += s["real_known"]
        totals["false_reported"] += len(s["false_reported"])
        totals["false_known"] += s["false_known"]
        totals["incomplete"] += 0 if s["complete"] else 1
        print("| %s | %d | %d | %s | %s | %s | %d | %d / %d | %d / %d |" % (
            app, s["flows"], s["network"], "?" if s["internet"] is None else ("yes" if s["internet"] else "no"),
            "yes" if s["complete"] else "**no**", s["analyses"], len(s["impossible"]),
            len(s["real_found"]), s["real_known"], len(s["false_reported"]), s["false_known"]))
        problems += ["%s: missing real flow %s" % (app, describe(v)) for v in s["real_missing"]]
        problems += ["%s: reports false flow %s" % (app, describe(v)) for v in s["false_reported"]]
        problems += ["%s: NETWORK flow without the INTERNET permission: %s in %s from %s" % (app, f["data"], f["sinkIn"], f["sourceApi"]) for f in s["impossible"]]
    print()
    print("Flows %(flows)d; impossible (NETWORK without INTERNET) %(impossible)d; known real found %(real_found)d of %(real_known)d; "
          "known false reported %(false_reported)d of %(false_known)d; incomplete reports %(incomplete)d; missing reports %(missing)d" % totals)
    for p in problems:
        print("  " + p)
    if args.strict and (totals["impossible"] or totals["false_reported"] or totals["real_found"] < totals["real_known"]):
        sys.exit(1)


if __name__ == "__main__":
    main()
