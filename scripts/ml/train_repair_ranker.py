#!/usr/bin/env python3
"""Train a local proposal ranker from complete, checked round-trip repair feedback.

Manifest entries pin each local JSONL file by SHA-256, project family, source and license.
No network calls, raw Java or runtime logs. Hold out entire project families, remove exact
candidate duplicates across families, select on validation only, and evaluate test once.
"""
import argparse
import hashlib
import json
import math
import os
from collections import defaultdict
from pathlib import Path

os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
import numpy as np

FEATURES = ["outer-instance", "drop-cast", "widen-divide", "widen-multiply", "to-float", "to-double", "to-long",
            "before-float", "before-double", "before-long", "before-return", "before-casts", "before-length", "length-change"]
SEED = 20261011
LIMIT = 100_000


def read_json(path, limit):
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"file exceeds {limit} bytes: {path}")
    return json.loads(data)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def valid_hash(value):
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def load(manifest_path):
    manifest = read_json(manifest_path, 1 << 20)
    entries = manifest.get("sources", [])
    if manifest.get("schemaVersion") != 1 or not 1 <= len(entries) <= 1000:
        raise ValueError("manifest needs schemaVersion 1 and 1–1000 pinned sources")
    rows, seen, licenses = [], {}, []
    total_bytes = 0
    processed = 0
    for source in entries:
        for key in ("projectGroup", "sourceUrl", "license", "path", "sha256", "kind"):
            if not isinstance(source.get(key), str) or not 1 <= len(source[key]) <= 2048:
                raise ValueError(f"source needs {key}")
        if source["kind"] not in ("source-project", "generated-fixture") or not valid_hash(source["sha256"]):
            raise ValueError("invalid source kind or hash")
        path = (manifest_path.parent / source["path"]).resolve()
        if path.stat().st_size > 16 << 20 or digest(path) != source["sha256"]:
            raise ValueError(f"training source exceeds bounds or changed: {path}")
        licenses.append({k: source[k] for k in ("projectGroup", "sourceUrl", "license", "sha256", "kind")})
        total = 0
        loaded_hash = hashlib.sha256()
        with path.open("rb") as stream:
            while True:
                line = stream.readline(4097)
                if not line:
                    break
                total += len(line)
                total_bytes += len(line)
                loaded_hash.update(line)
                if total_bytes > 64 << 20:
                    raise ValueError("corpus exceeds the 64 MiB read budget")
                if len(line) > 4096 or total > 16 << 20:
                    raise ValueError("feedback grew beyond the line/file limit")
                if not line.strip():
                    continue
                row = json.loads(line)
                processed += 1
                if processed > LIMIT:
                    raise ValueError("more than 100,000 rows including duplicate attempts")
                if (row.get("schemaVersion") != 1 or row.get("featureVersion") != 1 or
                        row.get("labelEvidence") != "COMPLETE_ROUNDTRIP_NO_REGRESSION" or type(row.get("label")) is not int or row.get("label") not in (0, 1) or
                        type(row.get("comparedMethods")) is not int or row["comparedMethods"] <= 0 or
                        type(row.get("improvedMethods")) is not int or not 0 <= row["improvedMethods"] <= row["comparedMethods"]):
                    raise ValueError("unverified, incomplete or unsupported feedback")
                for key in ("inputGroup", "querySha256", "candidateSha256", "profileHash"):
                    if not valid_hash(row.get(key)):
                        raise ValueError(f"invalid {key}")
                if row.get("unitSha256") is not None and not valid_hash(row["unitSha256"]):
                    raise ValueError("invalid unitSha256")
                for key in ("queryCodeSha256", "candidateCodeSha256"):
                    if row.get(key) is not None and not valid_hash(row[key]):
                        raise ValueError("invalid code fingerprint")
                x = row.get("features", [])
                if len(x) != len(FEATURES) or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or not -1 <= v <= 1 for v in x):
                    raise ValueError("invalid feature vector")
                if row["label"] == 1 and row["improvedMethods"] == 0:
                    raise ValueError("positive example did not improve any method")
                row = dict(row, projectGroup=source["projectGroup"], dataKind=source["kind"])
                key = (row["projectGroup"], row["inputGroup"], row.get("queryCodeSha256") or row["querySha256"], row.get("candidateCodeSha256") or row["candidateSha256"], row["profileHash"])
                if key in seen:
                    if seen[key]["label"] != row["label"] or seen[key]["features"] != row["features"]:
                        raise ValueError("conflicting duplicate feedback")
                    continue
                seen[key] = row
                rows.append(row)
                if len(rows) > LIMIT:
                    raise ValueError("more than 100,000 examples; split the corpus explicitly")
        if loaded_hash.hexdigest() != source["sha256"]:
            raise ValueError("feedback changed while it was being loaded")
    # The same candidate source in two projects must never leak into different partitions.
    owners = defaultdict(set)
    for row in rows:
        owners[row.get("candidateCodeSha256") or row["candidateSha256"]].add(row["projectGroup"])
    duplicates = {key for key, groups in owners.items() if len(groups) > 1}
    rows = [r for r in rows if (r.get("candidateCodeSha256") or r["candidateSha256"]) not in duplicates]
    source_lock = manifest.get("sourceLockSha256")
    if source_lock is not None and not valid_hash(source_lock):
        raise ValueError("invalid source lock hash")
    corpus_hash = hashlib.sha256(json.dumps({"sources": licenses, "rows": rows, "sourceLockSha256": source_lock}, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return rows, licenses, corpus_hash, len(duplicates)


def split(rows):
    groups = sorted({r["projectGroup"] for r in rows}, key=lambda g: hashlib.sha256(f"{SEED}:{g}".encode()).hexdigest())
    if len(groups) < 5:
        raise ValueError("at least five independent project families are required after deduplication")
    n = max(1, len(groups) // 5)
    validation = set(groups[:n]); test = set(groups[n:2*n]); train = set(groups[2*n:])
    return [[r for r in rows if r["projectGroup"] in names] for names in (train, validation, test)]


def ranking(rows, scores):
    queries = defaultdict(list)
    for order, (row, score) in enumerate(zip(rows, scores)):
        queries[(row["projectGroup"], row["inputGroup"], row.get("queryCodeSha256") or row["querySha256"], row["profileHash"])].append((float(score), order, row["label"]))
    mrr, hits, units = [], [], set()
    unit_by_query = {(r["projectGroup"], r["inputGroup"], r.get("queryCodeSha256") or r["querySha256"], r["profileHash"]): (r["projectGroup"], r.get("unitSha256") or r.get("queryCodeSha256") or r["querySha256"]) for r in rows}
    for key, items in queries.items():
        if not any(x[2] == 1 for x in items) or not any(x[2] == 0 for x in items):
            continue
        ordered = sorted(items, key=lambda x: (-x[0], x[1]))
        first = next(i + 1 for i, x in enumerate(ordered) if x[2] == 1)
        mrr.append(1 / first); hits.append(first <= 5)
        units.add(unit_by_query[key])
    return {"queries": len(mrr), "units": len(units), "mrr": float(np.mean(mrr)) if mrr else 0.0, "hit5": float(np.mean(hits)) if hits else 0.0}


def fit(train, validation):
    x = np.asarray([r["features"] for r in train], dtype=np.float64)
    y = np.asarray([r["label"] for r in train], dtype=np.float64)
    if set(y) != {0, 1}:
        raise ValueError("training needs both complete successful and unsuccessful proposals")
    xv = np.asarray([r["features"] for r in validation], dtype=np.float64)
    class_weights = np.where(y == 1, len(y) / (2 * y.sum()), len(y) / (2 * (len(y) - y.sum())))
    w = np.zeros(len(FEATURES)); bias = 0.0; best = None
    rng = np.random.default_rng(SEED)
    for epoch in range(120):
        indices = rng.permutation(len(y))
        for start in range(0, len(y), 4096):
            at = indices[start:start+4096]; xb = x[at]
            p = 1 / (1 + np.exp(-np.clip(xb @ w + bias, -40, 40)))
            error = (p - y[at]) * class_weights[at]
            rate = 0.5 / math.sqrt(1 + epoch / 10)
            w -= rate * (xb.T @ error / len(at) + 0.001 * w)
            bias -= rate * float(error.mean())
        if epoch % 10 == 9:
            measured = ranking(validation, xv @ w + bias)
            choice = (measured["mrr"], measured["hit5"], -float(np.dot(w, w)))
            if best is None or choice > best[0]:
                best = (choice, w.copy(), bias, epoch + 1)
    return best[1:]


def train(manifest_path, out):
    rows, licenses, corpus_hash, excluded = load(manifest_path)
    training, validation, test = split(rows)
    w, bias, epochs = fit(training, validation)
    # No tuning on test, no refit including validation/test rows.
    xt = np.asarray([r["features"] for r in test], dtype=np.float64)
    baseline = ranking(test, np.zeros(len(test)))
    measured = ranking(test, xt @ w + bias)
    validation_score = ranking(validation, np.asarray([r["features"] for r in validation]) @ w + bias)
    production = all(r["dataKind"] == "source-project" for r in rows)
    eligible = production and measured["queries"] >= 10 and validation_score["queries"] >= 5 and measured["units"] >= 10 and validation_score["units"] >= 5 and measured["mrr"] > baseline["mrr"] + 0.01 and measured["hit5"] >= baseline["hit5"]
    evaluation = {"trainingProjects": len({r["projectGroup"] for r in training}), "validationProjects": len({r["projectGroup"] for r in validation}),
                  "testProjects": len({r["projectGroup"] for r in test}), "testQueries": measured["queries"], "baselineMrr": baseline["mrr"],
                  "modelMrr": measured["mrr"], "baselineHit5": baseline["hit5"], "modelHit5": measured["hit5"],
                  "validationQueries": validation_score["queries"], "validationUnits": validation_score["units"], "testUnits": measured["units"]}
    model = {"schemaVersion": 1, "featureVersion": 1, "features": FEATURES, "weights": w.tolist(), "bias": bias,
             "datasetSha256": corpus_hash, "eligible": eligible, "evaluation": evaluation}
    card = {"purpose": "Rank verified-repair proposals; does not generate Java, establish historical source or bypass verification.",
            "examples": len(rows), "sources": licenses, "excludedCrossProjectCandidateHashes": excluded, "selectedEpochs": epochs,
            "splits": {name: sorted({r["projectGroup"] for r in data}) for name, data in zip(("train", "validation", "test"), (training, validation, test))},
            "validation": validation_score, "test": measured, "baseline": baseline, "eligible": eligible,
            "labelCounts": {"positive": sum(r["label"] == 1 for r in rows), "negative": sum(r["label"] == 0 for r in rows)},
            "promotionReasons": ([] if eligible else ["Promotion requires real-project data, at least five independent mixed-outcome validation units, ten test units, ranking improvement and no hit-at-five regression."]),
            "limits": "Only attempted proposals with complete comparison are labelled. Selection bias and untried repairs remain; measure end-to-end success, regressions and time on a further frozen corpus before release. Scores are rankings, not calibrated correctness probabilities."}
    out.mkdir(parents=True, exist_ok=True)
    target = out / "repair-priority-model.json"
    target.write_text(json.dumps(model, indent=2) + "\n")
    card["modelSha256"] = digest(target)
    (out / "repair-priority-model-card.json").write_text(json.dumps(card, indent=2) + "\n")
    return model, card


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    model, _ = train(args.manifest, args.out)
    print(json.dumps({"eligible": model["eligible"], "evaluation": model["evaluation"]}, indent=2))


if __name__ == "__main__":
    main()
