#!/usr/bin/env python3
"""Train the privacy API classifier: which Android/Java platform methods return personal data
(sources) or send data out (sinks), by category, from their signature alone.

The privacy map's own tables name the APIs it knows. This model covers the rest of the platform API,
so an app reading personal data through a method no table lists is still noticed; such flows are
labelled as model-based with the model's confidence (never presented as table facts).

Data (downloaded, not committed; LGPL-2.1, see the model card):
  - SuSi (Rasthofer, Arzt, Bodden: "A Machine-learning Approach for Classifying and Categorizing
    Android Sources and Sinks", NDSS 2014): its hand-labelled training set (permissionMethodWithLabel.pscout)
    and its categorised source and sink lists for Android 4.2 (predictions of SuSi's own SVM: weak labels).
  - FlowDroid's SourcesAndSinks.txt (the android.* entries, hand-curated).
  - The privacy map's own source and sink tables (PrivacyMap.kt), matched to android.jar signatures.
  - Negatives: public methods of android.jar (the SDK in .tools) that no list names (weak).

Model: multinomial logistic regression over hashed signature tokens (FNV-1a, 2^15 buckets), trained
with AdaGrad and L2, class-balanced. Weights are quantized to int8 with one scale per class; the
Kotlin inference (PrivacyApiModel) reads the same file and reproduces the same probabilities, which a
golden test checks. Accuracy is reported by 5-fold cross-validation on the hand-labelled data only
(weak labels are used for training, never for scoring).

Usage: python3 -I scripts/ml/train_api_classifier.py [--data DIR] [--android-jar PATH] [--out DIR]
"""
import argparse
import hashlib
import io
import json
import os
import re
import struct
import sys
import urllib.request
import zipfile

import numpy as np

DIMS = 1 << 15
SEED = 20261011
SOURCES_URLS = {
    "permissionMethodWithLabel.pscout": "https://raw.githubusercontent.com/secure-software-engineering/SuSi/develop/permissionMethodWithLabel.pscout",
    "Ouput_CatSources_v0_9.txt": "https://raw.githubusercontent.com/secure-software-engineering/SuSi/develop/SourceSinkLists/Android%204.2/SourcesSinks/Ouput_CatSources_v0_9.txt",
    "Ouput_CatSinks_v0_9.txt": "https://raw.githubusercontent.com/secure-software-engineering/SuSi/develop/SourceSinkLists/Android%204.2/SourcesSinks/Ouput_CatSinks_v0_9.txt",
    "SourcesAndSinks.txt": "https://raw.githubusercontent.com/secure-software-engineering/FlowDroid/develop/soot-infoflow-android/SourcesAndSinks.txt",
}

# Model classes: personal-data sources by the privacy map's data kinds, sinks by its sink kinds.
CLASSES = [
    "none",
    "source:location", "source:device-id", "source:phone-number", "source:accounts", "source:contacts",
    "source:calendar", "source:sms", "source:wifi", "source:other",
    "sink:network", "sink:log", "sink:file", "sink:sms", "sink:ipc", "sink:other",
]
SUSI_SOURCE = {
    "LOCATION_INFORMATION": "source:location", "UNIQUE_IDENTIFIER": "source:device-id", "ACCOUNT_INFORMATION": "source:accounts",
    "CONTACT_INFORMATION": "source:contacts", "CALENDAR_INFORMATION": "source:calendar", "SMS_MMS": "source:sms",
    # SuSi's network information is any network state (connectivity type, URIs): not the Wi-Fi networks around.
    "NETWORK_INFORMATION": "source:other", "BLUETOOTH_INFORMATION": "source:other",
}
SUSI_SINK = {
    "NETWORK": "sink:network", "LOG": "sink:log", "FILE": "sink:file", "SMS_MMS": "sink:sms",
}
ENGINE_SOURCE = {
    "location": "source:location", "device-id": "source:device-id", "phone-number": "source:phone-number",
    "accounts": "source:accounts", "contacts": "source:contacts", "calendar": "source:calendar", "sms": "source:sms",
    "wifi": "source:wifi", "advertising-id": "source:device-id", "installed-apps": "source:other", "clipboard": "source:other",
    "audio": "source:other", "call-log": "source:other",
}
ENGINE_SINK = {"NETWORK": "sink:network", "LOG": "sink:log", "STORAGE": "sink:file", "SMS": "sink:sms", "IPC": "sink:ipc"}

SIG = re.compile(r"<([\w.$]+):\s+([\w.$\[\]]+)\s+([\w$<>]+)\(([^)]*)\)>")


# ---- features (mirrored exactly by PrivacyApiModel.kt) ---------------------------------------------

def fnv1a(s):
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def camel(s):
    """Lower-case words of an identifier: getLastKnownLocation -> get, last, known, location; SSID -> ssid."""
    return [w.lower() for w in re.findall(r"[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+", s)]


def simple(t):
    return t.rsplit(".", 1)[-1].split("$")[-1].replace("[]", "")


def features(cls, ret, name, params):
    """Tokens of a method signature (dotted Java names: android.location.LocationManager)."""
    out = set()
    pkg = cls.rsplit(".", 1)[0] if "." in cls else ""
    cs = simple(cls).lower()
    for p in pkg.split(".")[:4]:
        out.add("p:" + p)
    out.add("P:" + pkg)
    out.add("C:" + cs)
    for w in camel(simple(cls)):
        out.add("c:" + w)
    words = camel(name) or [name.lower()]
    verb = words[0]
    out.add("M:" + name.lower())
    out.add("v:" + verb)
    for w in words:
        out.add("m:" + w)
    rs = simple(ret).lower()
    out.add("r:" + rs)
    for w in camel(simple(ret)):
        out.add("rt:" + w)
    kind = "void" if ret == "void" else "array" if ret.endswith("[]") else "prim" if ret in ("boolean", "byte", "char", "short", "int", "long", "float", "double") else "string" if ret == "java.lang.String" else "object"
    out.add("R:" + kind)
    for a in params:
        out.add("a:" + simple(a).lower())
    out.add("n:" + str(min(len(params), 4)))
    out.add("vC:" + verb + "|" + cs)
    out.add("vr:" + verb + "|" + rs)
    out.add("Cm:" + cs + "|" + name.lower())
    out.add("vk:" + verb + "|" + kind)
    return sorted(out)


def hashed(tokens):
    return sorted({fnv1a(t) % DIMS for t in tokens})


# ---- data -----------------------------------------------------------------------------------------

def fetch(data_dir):
    os.makedirs(data_dir, exist_ok=True)
    for name, url in SOURCES_URLS.items():
        path = os.path.join(data_dir, name)
        if not os.path.exists(path):
            print("downloading", url, file=sys.stderr)
            with urllib.request.urlopen(url, timeout=120) as r, open(path, "wb") as f:
                f.write(r.read())
    return {name: os.path.join(data_dir, name) for name in SOURCES_URLS}


def parse_sig(line):
    m = SIG.search(line)
    if not m:
        return None
    cls, ret, name, params = m.group(1), m.group(2), m.group(3), m.group(4)
    return cls, ret, name, tuple(p.strip() for p in params.split(",") if p.strip())


MANAGER = re.compile(r"(Manager|Service|Client|Adapter|Helper|Provider|Factory|Builder)$")


def personal(sig, label):
    """A source label only where the method returns data: a boolean or void "source" is a status or an
    action (isProviderEnabled, requestLocationUpdates), a manager or factory object (AccountManager.get,
    SmsManager.getDefault) is a handle, and a list of provider names is not a location."""
    if not label.startswith("source:"):
        return label
    cls, ret, name, params = sig
    rs = simple(ret)
    if ret in ("boolean", "void") or MANAGER.search(rs) or rs == simple(cls):
        return "none"
    if label == "source:location" and re.search(r"(?i)provider|criteria|listener|status$", name):
        return "source:other"
    return label


def public_api(cls):
    """Classes an app can call: the public SDK, not Android's internal and server classes."""
    return (cls.startswith("android.") or cls.startswith("java.") or cls.startswith("javax.")) and ".internal." not in cls


def read_susi_labelled(path):
    """SuSi's hand-labelled training set: `<sig> (n) -> _SOURCE_|_CATEGORY_` lines; _IGNORE_ is skipped."""
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        if "->" not in line:
            continue
        sig = parse_sig(line)
        if sig is None:
            continue
        label = line.rsplit("->", 1)[1].strip()
        parts = [p for p in label.split("|") if p]
        kind = parts[0]
        cat = parts[1].strip("_") if len(parts) > 1 else None
        if kind == "_NONE_":
            out[sig] = "none"
        elif kind == "_SOURCE_":
            out[sig] = SUSI_SOURCE.get(cat, "source:other")
        elif kind == "_SINK_":
            out[sig] = SUSI_SINK.get(cat, "sink:other")
    return out


def read_susi_categorised(path, sink):
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        sig = parse_sig(line)
        if sig is None:
            continue
        cat = line.rsplit("(", 1)[1].strip().rstrip(")") if "(" in line.rsplit(">", 1)[-1] else None
        out[sig] = (SUSI_SINK.get(cat, "sink:other") if sink else SUSI_SOURCE.get(cat, "source:other"))
    return out


def read_flowdroid(path):
    out = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        if not line.startswith("<android.") or "->" not in line:
            continue
        sig = parse_sig(line)
        if sig is None:
            continue
        kind = line.rsplit("->", 1)[1].strip()
        if kind.startswith("_SOURCE_"):
            out[sig] = "source:other"
        elif kind.startswith("_SINK_"):
            out[sig] = "sink:other"
    return out


def descriptor_to_java(d):
    """`[Ljava/lang/String;` -> java.lang.String[]."""
    dims = 0
    while d.startswith("["):
        dims += 1
        d = d[1:]
    prim = {"Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int", "J": "long", "F": "float", "D": "double", "V": "void"}
    t = prim.get(d) or d[1:-1].replace("/", ".")
    return t + "[]" * dims


def split_params(desc):
    out, i = [], 0
    while i < len(desc):
        j = i
        while desc[j] == "[":
            j += 1
        if desc[j] == "L":
            j = desc.index(";", j)
        out.append(desc[i:j + 1])
        i = j + 1
    return out


def read_android_jar(path):
    """Public methods of public classes in android.jar: (cls, ret, name, params)."""
    sigs = []
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if not n.endswith(".class") or "$" in n.rsplit("/", 1)[-1] and n.rsplit("$", 1)[-1][0].isdigit():
                continue
            b = z.read(n)
            try:
                sigs.extend(parse_class(b))
            except Exception:
                continue
    return sigs


def parse_class(b):
    r = io.BytesIO(b)
    def u1(): return r.read(1)[0]
    def u2(): return struct.unpack(">H", r.read(2))[0]
    def u4(): return struct.unpack(">I", r.read(4))[0]
    if u4() != 0xCAFEBABE:
        return []
    u2(); u2()
    n = u2()
    pool = [None] * n
    i = 1
    while i < n:
        tag = u1()
        if tag == 1:
            pool[i] = r.read(u2()).decode("utf-8", "replace")
        elif tag in (3, 4):
            r.read(4)
        elif tag in (5, 6):
            r.read(8); i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = ("ref", u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            r.read(4)
        elif tag == 15:
            r.read(3)
        else:
            raise ValueError("tag %d" % tag)
        i += 1
    access = u2()
    this = pool[pool[u2()][1]].replace("/", ".")
    u2()
    for _ in range(u2()):
        u2()
    if not access & 0x0001:
        return []
    def skip_attrs():
        for _ in range(u2()):
            u2(); r.read(u4())
    for _ in range(u2()):
        u2(); u2(); u2(); skip_attrs()
    out = []
    for _ in range(u2()):
        acc = u2(); name = pool[u2()]; desc = pool[u2()]; skip_attrs()
        if not acc & 0x0001 or name.startswith("<") or acc & 0x1000:
            continue
        params = tuple(descriptor_to_java(p) for p in split_params(desc[1:desc.index(")")]))
        out.append((this.replace("$", "$"), descriptor_to_java(desc[desc.index(")") + 1:]), name, params))
    return out


def read_engine_tables(path, jar):
    """The privacy map's own tables: owner->name entries, joined with android.jar for full signatures."""
    text = open(path, encoding="utf-8").read()
    by_owner_name = {}
    for s in jar:
        by_owner_name.setdefault((s[0], s[2]), []).append(s)
    def owner(o):
        return o.strip("L;").replace("/", ".").replace("\\$", "$")
    labels = {}
    for m in re.finditer(r'add\("([a-z-]+)", "(L[^"]+;)", ((?:"[^"]+",?\s*)+)\)', text):
        data, cls = m.group(1), owner(m.group(2))
        for name in re.findall(r'"([^"]+)"', m.group(3)):
            for s in by_owner_name.get((cls, name), []):
                if data in ENGINE_SOURCE:
                    labels[s] = ENGINE_SOURCE[data]
    for m in re.finditer(r'(net|log)\("(L[^"]+;)", setOf\(([^)]*)\)', text):
        kind, cls = m.group(1), owner(m.group(2))
        for name in re.findall(r'"([^"]+)"', m.group(3)):
            for s in by_owner_name.get((cls, name), []):
                labels[s] = "sink:network" if kind == "net" else "sink:log"
    for m in re.finditer(r'SinkSpec\("([A-Z]+)", "(L[^"]+;)", setOf\(([^)]*)\)', text):
        kind, cls = m.group(1), owner(m.group(2))
        for name in re.findall(r'"([^"]+)"', m.group(3)):
            for s in by_owner_name.get((cls, name), []):
                if kind in ENGINE_SINK:
                    labels[s] = ENGINE_SINK[kind]
    return labels


# ---- model ----------------------------------------------------------------------------------------

def train(X, y, w, epochs=40, lr=0.5, l2=1e-6, seed=SEED):
    """Multinomial logistic regression on sparse binary features (index lists), AdaGrad."""
    rng = np.random.default_rng(seed)
    k = len(CLASSES)
    W = np.zeros((DIMS, k))
    b = np.zeros(k)
    gW = np.full((DIMS, k), 1e-8)
    gb = np.full(k, 1e-8)
    order = np.arange(len(X))
    for _ in range(epochs):
        rng.shuffle(order)
        for i in order:
            idx = X[i]
            z = W[idx].sum(axis=0) + b
            z -= z.max()
            p = np.exp(z); p /= p.sum()
            g = p.copy(); g[y[i]] -= 1.0
            g *= w[i]
            grad = g[None, :] + l2 * W[idx]
            gW[idx] += grad * grad
            W[idx] -= lr * grad / np.sqrt(gW[idx])
            gb += g * g
            b -= lr * g / np.sqrt(gb)
    return W, b


def quantize(W):
    scale = np.abs(W).max(axis=0) / 127.0
    scale[scale == 0] = 1.0
    Q = np.clip(np.round(W / scale), -127, 127).astype(np.int8)
    return Q, scale.astype(np.float32)


def predict(X, W, b):
    out = np.zeros((len(X), len(CLASSES)))
    for i, idx in enumerate(X):
        z = W[idx].sum(axis=0) + b
        z = z - z.max()
        p = np.exp(z)
        out[i] = p / p.sum()
    return out


def predict_q(X, Q, scale, b):
    """Exactly what the Kotlin inference computes: int8 sums times the class scale, plus the bias (float64)."""
    out = np.zeros((len(X), len(CLASSES)))
    for i, idx in enumerate(X):
        z = Q[idx].astype(np.int64).sum(axis=0).astype(np.float64) * scale.astype(np.float64) + b.astype(np.float32).astype(np.float64)
        z = z - z.max()
        p = np.exp(z)
        out[i] = p / p.sum()
    return out


def metrics(y, p):
    pred = p.argmax(axis=1)
    def group(c):
        return "none" if c == 0 else CLASSES[c].split(":")[0]
    res = {"accuracy": float((pred == y).mean()), "classes": {}}
    for g in ("source", "sink"):
        t = np.array([group(c) == g for c in y]); q = np.array([group(c) == g for c in pred])
        tp = int((t & q).sum()); fp = int((~t & q).sum()); fn = int((t & ~q).sum())
        prec = tp / (tp + fp) if tp + fp else 0.0; rec = tp / (tp + fn) if tp + fn else 0.0
        res[g] = {"precision": round(prec, 3), "recall": round(rec, 3), "f1": round(2 * prec * rec / (prec + rec), 3) if prec + rec else 0.0, "support": int(t.sum())}
    for c in range(len(CLASSES)):
        t = y == c; q = pred == c
        tp = int((t & q).sum()); fp = int((~t & q).sum()); fn = int((t & ~q).sum())
        if t.sum() == 0:
            continue
        prec = tp / (tp + fp) if tp + fp else 0.0; rec = tp / (tp + fn) if tp + fn else 0.0
        res["classes"][CLASSES[c]] = {"precision": round(prec, 3), "recall": round(rec, 3), "support": int(t.sum())}
    return res


def write_model(path, Q, scale, b, meta):
    """APSM v1: magic, dims, classes, labels, scales, biases, then dims x classes int8 (feature-major)."""
    with open(path, "wb") as f:
        f.write(b"APSM")
        f.write(struct.pack("<HIH", 1, DIMS, len(CLASSES)))
        for c in CLASSES:
            e = c.encode("utf-8")
            f.write(struct.pack("<B", len(e))); f.write(e)
        f.write(scale.astype("<f4").tobytes())
        f.write(b.astype("<f4").tobytes())
        f.write(Q.astype(np.int8).tobytes())
    meta["sha256"] = hashlib.sha256(open(path, "rb").read()).hexdigest()
    meta["bytes"] = os.path.getsize(path)


def grouped_folds(hand_items, rng, count=5):
    """Hold out entire API owners, rather than overloads from the same class."""
    owners = sorted({s[0] for s, _ in hand_items})
    rng.shuffle(owners)
    assigned = {owner: i % count for i, owner in enumerate(owners)}
    return np.array([assigned[s[0]] for s, _ in hand_items])


def without_hand_labels(weak, hand):
    """An exact held-out hand signature must never remain as a weak training label."""
    return {s: l for s, l in weak.items() if s not in hand}


def main():
    ap = argparse.ArgumentParser()
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--data", default=os.path.join(root, ".tools", "dl", "susi"))
    ap.add_argument("--android-jar", default=os.path.join(root, ".tools", "android-35", "android.jar"))
    ap.add_argument("--out", default=os.path.join(root, "modules", "app-recovery", "src", "main", "resources", "dev", "apkproject", "app"))
    ap.add_argument("--golden", default=os.path.join(root, "modules", "app-recovery", "src", "test", "resources", "privacy-api-model-golden.tsv"))
    ap.add_argument("--epochs", type=int, default=12)
    args = ap.parse_args()

    files = fetch(args.data)
    jar = read_android_jar(args.android_jar)
    jar_public = [s for s in jar if public_api(s[0])]
    engine = read_engine_tables(os.path.join(root, "modules", "app-recovery", "src", "main", "kotlin", "dev", "apkproject", "app", "PrivacyMap.kt"), jar)
    susi = {s: l for s, l in read_susi_labelled(files["permissionMethodWithLabel.pscout"]).items() if public_api(s[0])}
    flowdroid = read_flowdroid(files["SourcesAndSinks.txt"])
    weak_src = {s: l for s, l in read_susi_categorised(files["Ouput_CatSources_v0_9.txt"], sink=False).items() if public_api(s[0])}
    weak_snk = {s: l for s, l in read_susi_categorised(files["Ouput_CatSinks_v0_9.txt"], sink=True).items() if public_api(s[0])}

    # Hand labels, most specific first: the engine's tables, then SuSi's, then FlowDroid's (kind only).
    hand = {}
    for d in (flowdroid, susi):
        hand.update({s: personal(s, l) for s, l in d.items()})
    hand.update(engine)
    weak = {}
    weak.update(weak_snk)
    weak.update({s: personal(s, l) for s, l in weak_src.items()})
    overlapping = len(set(weak) & set(hand))
    weak = without_hand_labels(weak, hand)
    labelled = set(hand) | set(weak)
    rng = np.random.default_rng(SEED)
    negatives = [s for s in jar_public if s not in labelled]
    rng.shuffle(negatives)
    negatives = negatives[:40000]

    def encode(items):
        X = [np.array(hashed(features(*s)), dtype=np.int64) for s, _ in items]
        y = np.array([CLASSES.index(l) for _, l in items])
        return X, y

    hand_items = sorted(hand.items())
    weak_items = sorted(weak.items()) + [(s, "none") for s in negatives]
    Xh, yh = encode(hand_items)
    Xw, yw = encode(weak_items)
    counts = np.bincount(np.concatenate([yh, yw]), minlength=len(CLASSES)).astype(float)
    cw = 1.0 / np.sqrt(np.maximum(counts, 1.0)); cw *= len(cw) / cw.sum()

    # 5-fold owner-grouped cross-validation. Neither exact strong labels nor any
    # weak/negative method from a held-out owner can remain in training.
    folds = grouped_folds(hand_items, rng)
    cv_float, cv_q = [], []
    ys = []
    for f in range(5):
        tr = [i for i in range(len(Xh)) if folds[i] != f]
        te = [i for i in range(len(Xh)) if folds[i] == f]
        held_owners = {hand_items[i][0][0] for i in te}
        wi = [i for i, (sig, _) in enumerate(weak_items) if sig[0] not in held_owners]
        X = [Xh[i] for i in tr] + [Xw[i] for i in wi]
        y = np.concatenate([yh[tr], yw[wi]])
        fold_counts = np.bincount(y, minlength=len(CLASSES)).astype(float)
        fold_cw = 1.0 / np.sqrt(np.maximum(fold_counts, 1.0)); fold_cw *= len(fold_cw) / fold_cw.sum()
        w = np.concatenate([fold_cw[yh[tr]] * 3.0, fold_cw[yw[wi]]])
        W, b = train(X, y, w, epochs=args.epochs)
        Q, scale = quantize(W)
        cv_float.append(predict([Xh[i] for i in te], W, b))
        cv_q.append(predict_q([Xh[i] for i in te], Q, scale, b))
        ys.append(yh[te])
        print("fold", f, "done", file=sys.stderr)
    y_all = np.concatenate(ys)
    m_float = metrics(y_all, np.concatenate(cv_float))
    m_q = metrics(y_all, np.concatenate(cv_q))

    W, b = train(Xh + Xw, np.concatenate([yh, yw]), np.concatenate([cw[yh] * 3.0, cw[yw]]), epochs=args.epochs)
    Q, scale = quantize(W)
    meta = {
        "model": "privacy API classifier", "version": 1, "dims": DIMS, "classes": CLASSES,
        "training": {
            "hand_labelled": len(hand_items), "engine_tables": len(engine), "susi_labelled": len(susi), "flowdroid": len(flowdroid),
            "weak_susi_categorised": len(weak), "negatives_android_jar": len(negatives), "epochs": args.epochs, "seed": SEED,
            "excluded_hand_weak_overlaps": overlapping,
        },
        "evaluation_split": "owner-grouped; held-out owners excluded from every strong/weak/negative training set",
        "sources_sha256": {name: hashlib.sha256(open(path, "rb").read()).hexdigest() for name, path in files.items()},
        "cross_validation_hand_labelled": {"float": m_float, "int8": m_q},
        "data_licenses": "SuSi and FlowDroid lists: LGPL-2.1 (secure-software-engineering). The weights are derived from them.",
    }
    os.makedirs(args.out, exist_ok=True)
    write_model(os.path.join(args.out, "privacy-api-model.bin"), Q, scale, b, meta)
    with open(os.path.join(args.out, "privacy-api-model.json"), "w") as f:
        json.dump(meta, f, indent=2)

    # Golden cases for the Kotlin inference: signatures with the quantized model's probabilities.
    golden_sigs = [
        ("android.location.LocationManager", "android.location.Location", "getLastKnownLocation", ("java.lang.String",)),
        ("android.telephony.TelephonyManager", "java.lang.String", "getDeviceId", ()),
        ("android.telephony.TelephonyManager", "java.lang.String", "getLine1Number", ()),
        ("android.accounts.AccountManager", "android.accounts.Account[]", "getAccounts", ()),
        ("android.util.Log", "int", "d", ("java.lang.String", "java.lang.String")),
        ("android.telephony.SmsManager", "void", "sendTextMessage", ("java.lang.String", "java.lang.String", "java.lang.String", "android.app.PendingIntent", "android.app.PendingIntent")),
        ("java.net.URL", "java.net.URLConnection", "openConnection", ()),
        ("android.view.View", "void", "setVisibility", ("int",)),
        ("android.graphics.Paint", "void", "setColor", ("int",)),
        ("android.net.wifi.WifiInfo", "java.lang.String", "getSSID", ()),
        ("android.location.Location", "double", "getLatitude", ()),
        ("java.io.FileOutputStream", "void", "write", ("byte[]",)),
    ]
    Xg = [np.array(hashed(features(*s)), dtype=np.int64) for s in golden_sigs]
    pg = predict_q(Xg, Q, scale, b)
    os.makedirs(os.path.dirname(args.golden), exist_ok=True)
    with open(args.golden, "w") as f:
        f.write("# class\treturn\tname\tparams\tlabel\tprobability (from scripts/ml/train_api_classifier.py)\n")
        for s, p in zip(golden_sigs, pg):
            c = int(p.argmax())
            f.write("\t".join([s[0], s[1], s[2], ",".join(s[3]), CLASSES[c], "%.6f" % p[c]]) + "\n")
    print(json.dumps(meta["cross_validation_hand_labelled"], indent=1))
    for s, p in zip(golden_sigs, pg):
        print(s[0].rsplit(".", 1)[-1] + "." + s[2], CLASSES[int(p.argmax())], round(float(p.max()), 3))


if __name__ == "__main__":
    main()
