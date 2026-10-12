#!/usr/bin/env python3
"""Freeze local collection runs as feature-only pinned feedback; never copy source or binaries."""
import argparse
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def json_file(path, limit=1 << 20):
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("collection metadata exceeds bounds")
    return json.loads(data)


def fingerprints(paths, jdk):
    result = {}
    with tempfile.TemporaryDirectory() as tmp:
        request = Path(tmp) / "paths.json"
        for start in range(0, len(paths), 64):
            batch = paths[start:start + 64]
            request.write_text(json.dumps(batch))
            data = subprocess.check_output([str(jdk / "bin/java"), "-Xmx512m", "-cp",
                str(ROOT / "modules/benchmarks/build/install/benchmarks/lib/*"),
                "dev.apkproject.benchmarks.RepairCorpus", "--fingerprint-files", str(request)], timeout=60)
            if len(data) > 65_536:
                raise ValueError("fingerprint output exceeds bounds")
            result.update(json.loads(data))
    return result


def freeze(runs, lock, out, jdk):
    if out.exists():
        raise ValueError("frozen output must be new")
    out.mkdir(parents=True)
    corpus = out / "corpus"; corpus.mkdir()
    sources = []; results = []; copied = set(); total_rows = 0
    for run in runs:
        manifest = json_file(run / "manifest.json")
        for source in manifest["sources"]:
            path = (run / source["path"]).resolve()
            if not path.is_relative_to(run.resolve()) or path.stat().st_size > 16 << 20 or digest(path) != source["sha256"]:
                raise ValueError("collected feedback changed or escaped the run")
            work = path.parent.parent
            with path.open("rb") as stream:
                rows = []
                captured_hash = hashlib.sha256(); captured_bytes = 0
                while True:
                    line = stream.readline(4097)
                    if not line:
                        break
                    captured_hash.update(line); captured_bytes += len(line)
                    if len(line) > 4096 or captured_bytes > 16 << 20:
                        raise ValueError("feedback line exceeds bounds")
                    rows.append(json.loads(line)); total_rows += 1
                    if total_rows > 100_000:
                        raise ValueError("corpus exceeds row bounds")
                if captured_hash.hexdigest() != source["sha256"]:
                    raise ValueError("feedback changed while being frozen")
            # Older captured runs retain their actual source hashes. Add only token fingerprints
            # and opaque unit identity; never regenerate their labels or feature vectors.
            needed = {r["querySha256"] for r in rows if not r.get("queryCodeSha256")}
            needed |= {r["candidateSha256"] for r in rows if not r.get("candidateCodeSha256")}
            file_by_hash = {}; unit_by_query = {}
            for p in (work / "decompiled/sources").rglob("*.java"):
                value = digest(p); file_by_hash.setdefault(value, str(p))
                unit_by_query[value] = hashlib.sha256((source["projectGroup"] + ":" + p.relative_to(work / "decompiled/sources").as_posix()).encode()).hexdigest()
            if needed:
                for p in path.parent.rglob("*.java"):
                    value = digest(p)
                    if value in needed:
                        file_by_hash.setdefault(value, str(p))
                if not needed <= file_by_hash.keys():
                    raise ValueError("captured source for a feedback hash is unavailable")
                hashes = fingerprints(sorted({file_by_hash[k] for k in needed}), jdk)
                for row in rows:
                    if not row.get("queryCodeSha256"):
                        row["queryCodeSha256"] = hashes[file_by_hash[row["querySha256"]]]
                    if not row.get("candidateCodeSha256"):
                        row["candidateCodeSha256"] = hashes[file_by_hash[row["candidateSha256"]]]
            for row in rows:
                if not row.get("unitSha256"):
                    row["unitSha256"] = unit_by_query[row["querySha256"]]
            name = run.name + "-" + work.name + ".jsonl"
            if name in copied:
                raise ValueError("duplicate frozen output identity")
            copied.add(name)
            target = corpus / name
            target.write_text("".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows))
            sources.append(dict(source, path="corpus/" + name, sha256=digest(target)))
            results.append({"run": run.name, "projectGroup": source["projectGroup"], "artifactId": work.name,
                "summary": json_file(path.parent / "summary.json"), "feedbackSha256": digest(target)})
    (out / "manifest.json").write_text(json.dumps({"schemaVersion": 1, "sourceLockSha256": digest(lock), "sources": sources}, indent=2) + "\n")
    (out / "collection-results.json").write_text(json.dumps(results, indent=2) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--runs", type=Path, nargs="+", required=True); p.add_argument("--lock", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True); p.add_argument("--jdk", type=Path, required=True)
    args = p.parse_args()
    freeze(args.runs, args.lock, args.out, args.jdk)


if __name__ == "__main__":
    main()
