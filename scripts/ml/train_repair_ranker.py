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
PAIR_LIMIT = 100_000


def query_key(row):
    return (row["projectGroup"], row["inputGroup"], row.get("queryCodeSha256") or row["querySha256"], row["profileHash"])


def unit_key(row):
    return (row["projectGroup"], row.get("unitSha256") or row.get("queryCodeSha256") or row["querySha256"])


def project_partitions(manifest):
    """Explicit assignments are fixed before collection, including not-yet-collected families."""
    partitions = manifest.get("projectPartitions")
    if partitions is None:
        return None
    if (not isinstance(partitions, dict) or not 5 <= len(partitions) <= 1000 or
            any(not isinstance(k, str) or not 1 <= len(k) <= 128 or v not in ("train", "validation", "test") for k, v in partitions.items())):
        raise ValueError("invalid frozen project partitions")
    groups = {source["projectGroup"] for source in manifest["sources"]}
    if not groups <= partitions.keys():
        raise ValueError("frozen partitions omit a source family")
    return partitions


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
    partitions = project_partitions(manifest)
    locked_sources = None
    if "sourceLockPath" in manifest:
        location = manifest["sourceLockPath"]
        if not isinstance(location, str) or not 1 <= len(location) <= 2048:
            raise ValueError("invalid source lock path")
        lock_path = (manifest_path.parent / location).resolve()
        with lock_path.open("rb") as stream:
            raw_lock = stream.read((1 << 20) + 1)
        if len(raw_lock) > 1 << 20 or hashlib.sha256(raw_lock).hexdigest() != manifest.get("sourceLockSha256"):
            raise ValueError("source lock changed or exceeded bounds")
        locked = json.loads(raw_lock)
        if locked.get("schemaVersion") != 1 or locked.get("projectPartitions") != partitions:
            raise ValueError("source lock partitions differ from the manifest")
        locked_sources = {(p["projectGroup"], p["artifacts"]["sources"]["url"]): p["license"] for p in locked["projects"]}
    rows, seen, licenses = [], {}, []
    total_bytes = 0
    processed = 0
    for source in entries:
        for key in ("projectGroup", "sourceUrl", "license", "path", "sha256", "kind"):
            if not isinstance(source.get(key), str) or not 1 <= len(source[key]) <= 2048:
                raise ValueError(f"source needs {key}")
        if source["kind"] not in ("source-project", "generated-fixture") or not valid_hash(source["sha256"]):
            raise ValueError("invalid source kind or hash")
        if (locked_sources is not None and source["kind"] == "source-project" and
                locked_sources.get((source["projectGroup"], source["sourceUrl"])) != source["license"]):
            raise ValueError("source origin/license differs from the pinned lock")
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
                if partitions is not None:
                    row["partition"] = partitions[source["projectGroup"]]
                key = (row["projectGroup"], row["inputGroup"], row.get("queryCodeSha256") or row["querySha256"], row.get("candidateCodeSha256") or row["candidateSha256"], row["profileHash"])
                if key in seen:
                    if (seen[key]["label"] != row["label"] or seen[key]["features"] != row["features"] or
                            seen[key].get("unitSha256") != row.get("unitSha256") or seen[key]["dataKind"] != row["dataKind"]):
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
    identity = {"sources": licenses, "rows": rows, "sourceLockSha256": source_lock}
    if partitions is not None:
        identity["projectPartitions"] = partitions
    corpus_hash = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return rows, licenses, corpus_hash, len(duplicates)


def split(rows):
    groups = sorted({r["projectGroup"] for r in rows}, key=lambda g: hashlib.sha256(f"{SEED}:{g}".encode()).hexdigest())
    if len(groups) < 5:
        raise ValueError("at least five independent project families are required after deduplication")
    if any("partition" in r for r in rows):
        if any(r.get("partition") not in ("train", "validation", "test") for r in rows):
            raise ValueError("incomplete frozen partitions")
        assignments = defaultdict(set)
        for row in rows:
            assignments[row["projectGroup"]].add(row["partition"])
        if any(len(parts) != 1 for parts in assignments.values()):
            raise ValueError("one project family crosses frozen partitions")
        parts = [[r for r in rows if r["partition"] == name] for name in ("train", "validation", "test")]
        if any(not part for part in parts):
            raise ValueError("each frozen partition needs collected feedback")
        return parts
    n = max(1, len(groups) // 5)
    validation = set(groups[:n]); test = set(groups[n:2*n]); train = set(groups[2*n:])
    return [[r for r in rows if r["projectGroup"] in names] for names in (train, validation, test)]


def ranked_units(rows, scores):
    if len(rows) != len(scores) or any(not math.isfinite(float(s)) for s in scores):
        raise ValueError("ranking needs one finite score per row")
    queries = defaultdict(list)
    for order, (row, score) in enumerate(zip(rows, scores)):
        queries[query_key(row)].append((float(score), order, row["label"]))
    unit_by_query = {}
    for row in rows:
        key = query_key(row)
        if key in unit_by_query and unit_by_query[key] != unit_key(row):
            raise ValueError("one query has conflicting source-unit identities")
        unit_by_query[key] = unit_key(row)
    units = defaultdict(list)
    for key, items in queries.items():
        if not any(x[2] == 1 for x in items) or not any(x[2] == 0 for x in items):
            continue
        ordered = sorted(items, key=lambda x: (-x[0], x[1]))
        first = next(i + 1 for i, x in enumerate(ordered) if x[2] == 1)
        units[unit_by_query[key]].append(first)
    return units


def ranking(rows, scores):
    units = ranked_units(rows, scores)
    ranks = [rank for values in units.values() for rank in values]
    by_family = defaultdict(list)
    for (family, _), values in units.items():
        by_family[family].append(values)
    mean = lambda values: float(np.mean(values)) if values else 0.0
    return {"supported": bool(ranks), "queries": len(ranks), "units": len(units), "projects": len(by_family),
            "mrr": mean([1/r for r in ranks]), "unitMrr": mean([mean([1/r for r in v]) for v in units.values()]),
            "familyMrr": mean([mean([mean([1/r for r in v]) for v in values]) for values in by_family.values()]),
            "hit1": mean([r <= 1 for r in ranks]), "hit3": mean([r <= 3 for r in ranks]), "hit5": mean([r <= 5 for r in ranks]),
            "unitHit5": mean([mean([r <= 5 for r in v]) for v in units.values()]),
            "familyHit5": mean([mean([mean([r <= 5 for r in v]) for v in values]) for values in by_family.values()]),
            "meanFirstSuccessRankAmongChecked": mean(ranks)}


def coverage(rows):
    queries = defaultdict(set)
    for row in rows:
        queries[query_key(row)].add(row["label"])
    return {"checkedRows": len(rows), "positiveRows": sum(r["label"] for r in rows),
            "positiveUnits": len({unit_key(r) for r in rows if r["label"] == 1}),
            "allPositiveQueries": sum(v == {1} for v in queries.values()),
            "allNegativeQueries": sum(v == {0} for v in queries.values()),
            "mixedQueries": sum(v == {0, 1} for v in queries.values())}


def fit_pointwise(train, validation):
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


def preference_pairs(rows):
    """Bounded positive-minus-negative pairs; equal family, unit and query influence.

    Single-outcome queries cannot teach ordering. Compiler variants of the same unit
    share its weight instead of masquerading as independent training examples.
    """
    queries = defaultdict(list)
    for row in rows:
        queries[query_key(row)].append(row)
    mixed = {key: values for key, values in queries.items() if {r["label"] for r in values} == {0, 1}}
    if not mixed:
        raise ValueError("pairwise training needs a query with both successful and unsuccessful checked proposals")
    families = defaultdict(set); unit_queries = defaultdict(int)
    for values in mixed.values():
        unit = unit_key(values[0])
        if any(unit_key(r) != unit for r in values):
            raise ValueError("one query has conflicting source-unit identities")
        families[unit[0]].add(unit); unit_queries[unit] += 1
    cap = min(128, max(1, PAIR_LIMIT // len(mixed)))
    differences, weights = [], []
    indistinguishable = 0
    for key, values in sorted(mixed.items()):
        order = lambda r: r.get("candidateCodeSha256") or r["candidateSha256"]
        positive = sorted((r for r in values if r["label"] == 1), key=order)
        negative = sorted((r for r in values if r["label"] == 0), key=order)
        total = len(positive) * len(negative); count = min(cap, total)
        if count == total:
            selected = range(total)
        else:
            # Do not materialize a potentially quadratic Cartesian product.
            seed = int.from_bytes(hashlib.sha256((str(SEED) + repr(key)).encode()).digest()[:8], "big")
            rng = np.random.default_rng(seed); picked = set()
            for _ in range(4 * count):
                picked.add(int(rng.integers(total)))
                if len(picked) == count:
                    break
            for at in range(count):
                if len(picked) == count:
                    break
                picked.add(at)
            selected = sorted(picked)
        unit = unit_key(values[0])
        weight = 1 / (len(families) * len(families[unit[0]]) * unit_queries[unit] * count)
        for at in selected:
            a, b = positive[at // len(negative)], negative[at % len(negative)]
            difference = np.asarray(a["features"]) - np.asarray(b["features"])
            indistinguishable += int(not np.any(difference))
            differences.append(difference); weights.append(weight)
    return np.asarray(differences, dtype=np.float64), np.asarray(weights, dtype=np.float64), {
        "queries": len(mixed), "units": len(unit_queries), "projects": len(families), "pairs": len(differences),
        "indistinguishablePairs": indistinguishable, "excludedSingleOutcomeQueries": len(queries) - len(mixed)}


def fit(train, validation):
    x, weights, _ = preference_pairs(train)
    xv = np.asarray([r["features"] for r in validation], dtype=np.float64)
    w = np.zeros(len(FEATURES)); best = None
    has_validation = ranking(validation, np.zeros(len(validation)))["queries"] > 0
    for epoch in range(120):
        error = -weights / (1 + np.exp(np.clip(x @ w, -40, 40)))
        rate = 0.5 / math.sqrt(1 + epoch / 10)
        w -= rate * (x.T @ error + 0.001 * w)
        if has_validation and epoch % 10 == 9:
            measured = ranking(validation, xv @ w)
            choice = (measured["familyMrr"], measured["unitMrr"], measured["familyHit5"], -float(np.dot(w, w)))
            if best is None or choice > best[0]:
                best = (choice, w.copy(), epoch + 1)
    # A constant bias cancels within a query. With no informative validation, use the
    # predefined final epoch and leave promotion disabled; do not select on test.
    return (best[1], 0.0, best[2]) if best is not None else (w, 0.0, 120)


def rule_prior(train, rows):
    """Simple training-only rule success prior, a baseline rather than a tuned model."""
    counts = defaultdict(lambda: [1, 2])  # fixed Laplace smoothing
    for row in train:
        rule = tuple(row["features"][:4])
        counts[rule][0] += row["label"]; counts[rule][1] += 1
    return np.asarray([counts[tuple(r["features"][:4])][0] / counts[tuple(r["features"][:4])][1] for r in rows])


def train(manifest_path, out, objective="pairwise"):
    if objective not in ("pairwise", "pointwise"):
        raise ValueError("unknown learning objective")
    rows, licenses, corpus_hash, excluded = load(manifest_path)
    training, validation, test = split(rows)
    w, bias, epochs = (fit if objective == "pairwise" else fit_pointwise)(training, validation)
    # No tuning on test, no refit including validation/test rows.
    xt = np.asarray([r["features"] for r in test], dtype=np.float64)
    baseline = ranking(test, np.zeros(len(test)))
    measured = ranking(test, xt @ w + bias)
    validation_score = ranking(validation, np.asarray([r["features"] for r in validation]) @ w + bias)
    prior = ranking(test, rule_prior(training, test))
    pw, pb, pe = fit_pointwise(training, validation)
    pointwise = ranking(test, xt @ pw + pb)
    _, _, support = preference_pairs(training)
    production = all(s["kind"] == "source-project" for s in licenses) and all(valid_hash(r.get("unitSha256")) for r in rows)
    baselines = {"deterministic": baseline, "trainingRulePrior": prior, "pointwiseLogistic": pointwise}
    # Predeclared gates compare all baselines; never choose the winning objective on test.
    eligible = (production and len({r["projectGroup"] for r in training}) >= 3 and measured["queries"] >= 10 and validation_score["queries"] >= 5 and
                measured["units"] >= 10 and validation_score["units"] >= 5 and measured["projects"] >= 2 and validation_score["projects"] >= 2 and
                all(measured["mrr"] > b["mrr"] + 0.01 and measured["hit5"] >= b["hit5"] and
                    measured["unitMrr"] > b["unitMrr"] + 0.01 and measured["familyMrr"] > b["familyMrr"] + 0.01 for b in baselines.values()))
    evaluation = {"trainingProjects": len({r["projectGroup"] for r in training}), "validationProjects": len({r["projectGroup"] for r in validation}),
                  "testProjects": len({r["projectGroup"] for r in test}), "testQueries": measured["queries"], "baselineMrr": baseline["mrr"],
                  "modelMrr": measured["mrr"], "baselineHit5": baseline["hit5"], "modelHit5": measured["hit5"],
                  "validationQueries": validation_score["queries"], "validationUnits": validation_score["units"], "testUnits": measured["units"],
                  "validationRankingProjects": validation_score["projects"], "testRankingProjects": measured["projects"],
                  "baselineUnitMrr": baseline["unitMrr"], "modelUnitMrr": measured["unitMrr"],
                  "baselineFamilyMrr": baseline["familyMrr"], "modelFamilyMrr": measured["familyMrr"],
                  "strongestBaselineMrr": max(b["mrr"] for b in baselines.values()), "strongestBaselineHit5": max(b["hit5"] for b in baselines.values()),
                  "strongestBaselineUnitMrr": max(b["unitMrr"] for b in baselines.values()), "strongestBaselineFamilyMrr": max(b["familyMrr"] for b in baselines.values())}
    model = {"schemaVersion": 1, "featureVersion": 1, "features": FEATURES, "weights": w.tolist(), "bias": bias,
             "datasetSha256": corpus_hash, "eligible": eligible, "evaluation": evaluation}
    card = {"purpose": "Rank verified-repair proposals; does not generate Java, establish historical source or bypass verification.",
            "examples": len(rows), "sources": licenses, "excludedCrossProjectCandidateHashes": excluded, "selectedEpochs": epochs,
            "splits": {name: sorted({r["projectGroup"] for r in data}) for name, data in zip(("train", "validation", "test"), (training, validation, test))},
            "objective": objective, "trainingPreferences": support, "validationUsedForSelection": validation_score["queries"] > 0,
            "trainerSha256": digest(Path(__file__)), "numpyVersion": np.__version__,
            "partitionPolicy": "frozen manifest" if "partition" in rows[0] else "legacy seeded family split",
            "partitionCoverage": {name: coverage(data) for name, data in zip(("train", "validation", "test"), (training, validation, test))},
            "trainingDiagnostics": {"model": ranking(training, np.asarray([r["features"] for r in training]) @ w + bias),
                                    "deterministic": ranking(training, np.zeros(len(training)))},
            "validation": validation_score, "test": measured, "baseline": baseline, "baselines": baselines, "pointwiseBaselineSelectedEpochs": pe, "eligible": eligible,
            "labelCounts": {"positive": sum(r["label"] == 1 for r in rows), "negative": sum(r["label"] == 0 for r in rows)},
            "promotionReasons": ([] if eligible else ["Promotion requires real-project data with opaque unit identity, five independent mixed-outcome validation units and ten test units across at least two informative families in each holdout; query/unit/family MRR gains above 0.01 over deterministic, training-only rule-prior and pointwise baselines without hit-at-five regression."]),
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
    parser.add_argument("--objective", choices=("pairwise", "pointwise"), default="pairwise")
    args = parser.parse_args()
    model, _ = train(args.manifest, args.out, args.objective)
    print(json.dumps({"eligible": model["eligible"], "evaluation": model["evaluation"]}, indent=2))


if __name__ == "__main__":
    main()
