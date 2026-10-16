#!/usr/bin/env python3
"""The privacy API model on the APIs real apps call (app_api_corpus.py), against labels made from them.

Two measurements:
  - what the privacy map would take from the model in these apps: every method it may ask about (a
    platform android.* or java.net.* method no table names, returning data) that the model calls a
    personal-data source at or above the map's threshold. Each such method adds sources, and flows,
    to every app calling it, so these are the predictions to check first;
  - accuracy against labels (app-api-labels.tsv): per class, and for sources at the map's threshold.

The labels are independent of the model's training data when the model was trained without them
(as the shipped one was); after retraining with them, only cross-validation on held-out owners is.

Usage: python3 -I scripts/ml/evaluate_api_model.py --corpus corpus.tsv [--labels labels.tsv]
       [--model privacy-api-model.bin] [--out predictions.tsv]
"""
import argparse
import os
import re
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import train_api_classifier as T  # noqa: E402  (features and data readers are shared)

# The privacy map's use of the model (PrivacyMap.kt: MODEL_SOURCE_KINDS, MODEL_SOURCE_OWNERS, MODEL_THRESHOLD).
MAP_KINDS = T.MAP_KINDS
MAP_THRESHOLD = T.MAP_THRESHOLD


def load_model(path):
    with open(path, "rb") as f:
        b = f.read()
    if b[:4] != b"APSM":
        raise ValueError("not an APSM model")
    _, dims, k = struct.unpack_from("<HIH", b, 4)
    off = 12
    labels = []
    for _ in range(k):
        n = b[off]
        labels.append(b[off + 1:off + 1 + n].decode("utf-8"))
        off += 1 + n
    scale = np.frombuffer(b, "<f4", k, off).astype(np.float64)
    off += 4 * k
    bias = np.frombuffer(b, "<f4", k, off).astype(np.float64)
    off += 4 * k
    Q = np.frombuffer(b, np.int8, dims * k, off).reshape(dims, k)
    return labels, dims, Q, scale, bias


def predict(model, sig):
    labels, dims, Q, scale, bias = model
    idx = sorted({T.fnv1a(t) % dims for t in T.features(*sig)})
    z = Q[idx].astype(np.int64).sum(axis=0).astype(np.float64) * scale + bias
    z -= z.max()
    p = np.exp(z)
    p /= p.sum()
    c = int(p.argmax())
    return labels[c], float(p[c])


def read_corpus(path):
    out = []
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        f = line.rstrip("\n").split("\t")
        sig = (f[0], f[1], f[2], tuple(p for p in f[3].split(",") if p))
        out.append((sig, int(f[4]), f[5].split(",") if len(f) > 5 else []))
    return out


def read_labels(path):
    """class, return, name, parameters, label, evidence (one per line, `#` comments)."""
    out = {}
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        f = line.rstrip("\n").split("\t")
        out[(f[0], f[1], f[2], tuple(p for p in f[3].split(",") if p))] = f[4]
    return out


def engine_names(root):
    """`(class, name)` pairs the privacy map's own source tables name (the model is not asked about them)."""
    text = open(os.path.join(root, "modules", "app-recovery", "src", "main", "kotlin", "dev", "apkproject", "app", "PrivacyMap.kt"), encoding="utf-8").read()
    names = set()
    for m in re.finditer(r'add\("([a-z-]+)", "(L[^"]+;)", ((?:"[^"]+",?\s*)+)\)', text):
        cls = m.group(2).strip("L;").replace("/", ".").replace("\\$", "$")
        for n in re.findall(r'"([^"]+)"', m.group(3)):
            names.add((cls, n))
    return names


def main():
    ap = argparse.ArgumentParser()
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--labels")
    ap.add_argument("--model", default=os.path.join(root, "modules", "app-recovery", "src", "main", "resources", "dev", "apkproject", "app", "privacy-api-model.bin"))
    ap.add_argument("--out")
    args = ap.parse_args()
    model = load_model(args.model)
    corpus = read_corpus(args.corpus)
    tables = engine_names(root)
    labels = read_labels(args.labels) if args.labels else {}

    rows = []
    for sig, apps, pkgs in corpus:
        label, p = predict(model, sig)
        in_tables = (sig[0], sig[2]) in tables
        taken = T.map_eligible(sig, tables) and label in MAP_KINDS and p >= MAP_THRESHOLD
        rows.append((sig, apps, pkgs, label, p, in_tables, taken))
    if args.out:
        with open(args.out, "w") as f:
            f.write("# class\treturn\tname\tparameters\tapps\tmodel label\tprobability\tin tables\ttaken by the map\tlabel\n")
            for sig, apps, _, label, p, in_tables, taken in sorted(rows, key=lambda r: (not r[6], -r[4])):
                f.write("\t".join([sig[0], sig[1], sig[2], ",".join(sig[3]), str(apps), label, "%.3f" % p, str(in_tables).lower(), str(taken).lower(), labels.get(sig, "")]) + "\n")

    taken = [r for r in rows if r[6]]
    print("corpus: %d methods; the map would take %d as model sources (calls in %d app-method pairs)" % (len(rows), len(taken), sum(r[1] for r in taken)))
    by_class = {}
    for r in rows:
        by_class[r[3]] = by_class.get(r[3], 0) + 1
    print("model labels over the corpus:", ", ".join("%s %d" % kv for kv in sorted(by_class.items(), key=lambda kv: -kv[1])))
    if labels:
        known = [r for r in rows if r[0] in labels]
        print("labelled corpus methods: %d" % len(known))
        y = [labels[r[0]] for r in known]
        pred = [r[3] for r in known]
        def group(l):
            return "none" if l == "none" else l.split(":")[0]
        for g in ("source", "sink"):
            tp = sum(1 for a, b in zip(y, pred) if group(a) == g and group(b) == g)
            fp = sum(1 for a, b in zip(y, pred) if group(a) != g and group(b) == g)
            fn = sum(1 for a, b in zip(y, pred) if group(a) == g and group(b) != g)
            prec = tp / (tp + fp) if tp + fp else 0.0
            rec = tp / (tp + fn) if tp + fn else 0.0
            print("  %s: precision %.3f recall %.3f (support %d)" % (g, prec, rec, tp + fn))
        exact = sum(1 for a, b in zip(y, pred) if a == b)
        print("  exact class: %.3f" % (exact / len(y) if y else 0.0))
        # What the map takes: precision of its model sources, by the labels.
        judged = [r for r in taken if r[0] in labels]
        right = [r for r in judged if labels[r[0]] == r[3]]
        some_source = [r for r in judged if labels[r[0]].startswith("source:")]
        print("  map-taken model sources labelled: %d; right kind %d, a source of another kind %d, not a source %d" % (
            len(judged), len(right), len(some_source) - len(right), len(judged) - len(some_source)))
        # Recall of the map's model use: labelled eligible sources outside the tables it would take.
        wanted = [r for r in known if T.map_eligible(r[0], tables) and labels[r[0]] in MAP_KINDS]
        got = [r for r in wanted if r[6] and r[3] == labels[r[0]]]
        print("  labelled map-kind sources outside the tables: %d; taken with the right kind: %d" % (len(wanted), len(got)))


if __name__ == "__main__":
    main()
