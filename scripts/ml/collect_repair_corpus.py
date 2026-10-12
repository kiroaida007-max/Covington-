#!/usr/bin/env python3
"""Collect checked proposals from pinned source projects through javac -> D8 -> JADX.

Original source is compiled only to make the input DEX. The Kotlin laboratory receives
that DEX, decompiled source and pinned dependency binaries, never the original source.
Failed compilation/comparisons remain diagnostics and never become negative labels.
"""
import argparse
import hashlib
import json
import os
import re
import signal
import subprocess
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAX_ARTIFACT = 32 << 20
PREPARATION_SECONDS = 600


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def class_jar(path):
    if path.suffix != ".aar":
        return path
    target = path.with_suffix(".classes.jar")
    with zipfile.ZipFile(path) as archive:
        with archive.open("classes.jar") as stream:
            data = stream.read(MAX_ARTIFACT + 1)
    if len(data) > MAX_ARTIFACT:
        raise ValueError("AAR classes exceed artifact bounds")
    if target.exists() and target.read_bytes() != data:
        raise ValueError("extracted AAR classes changed")
    target.write_bytes(data)
    return target


def download(entry, target):
    if not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]):
        raise ValueError("artifact requires a pinned SHA-256")
    if not entry["url"].startswith(("https://repo.maven.apache.org/maven2/", "https://dl.google.com/dl/android/maven2/")):
        raise ValueError("corpus downloads require the canonical HTTPS Maven repository")
    if target.exists():
        if target.stat().st_size > MAX_ARTIFACT or digest(target) != entry["sha256"]:
            raise ValueError("cached artifact changed")
        return target
    with urllib.request.urlopen(entry["url"], timeout=60) as response:
        data = response.read(MAX_ARTIFACT + 1)
    if len(data) > MAX_ARTIFACT or hashlib.sha256(data).hexdigest() != entry["sha256"]:
        raise ValueError("download changed or exceeded bounds")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return target


def source_units(source_jar, binary_jar, count, sampling="numeric-hash"):
    """Select production classes before outcomes are known; never rewrite original source."""
    if sampling not in ("numeric-hash", "repair-diverse"):
        raise ValueError("unknown source sampling policy")
    units = []
    seen_paths = {}
    with zipfile.ZipFile(binary_jar) as binary, zipfile.ZipFile(source_jar) as source:
        names = set(binary.namelist())
        if len(source.infolist()) > 100_000:
            raise ValueError("source archive has too many entries")
        scanned_bytes = 0
        for info in source.infolist():
            name = info.filename
            if not name.endswith(".java") or info.file_size > 200_000 or name.endswith(("module-info.java", "package-info.java")):
                continue
            with source.open(info) as stream:
                data = stream.read(200_001)
            scanned_bytes += len(data)
            if len(data) > 200_000 or scanned_bytes > 64 << 20:
                raise ValueError("source scan exceeded its byte budget")
            text = data.decode("utf-8")
            package = re.search(r"(?m)^\s*package\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*)\s*;", text)
            if not package:
                continue
            simple = name.rsplit("/", 1)[-1][:-5]
            if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", simple):
                continue
            path = package[1].replace(".", "/") + "/" + simple
            if path + ".class" not in names or not re.search(r"\b(?:class|enum)\s+" + re.escape(simple) + r"\b", text):
                continue
            if path in seen_paths:
                if seen_paths[path] != hashlib.sha256(data).hexdigest():
                    raise ValueError("conflicting source entries for one production class")
                continue
            seen_paths[path] = hashlib.sha256(data).hexdigest()
            code = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
            score = code.count(" / ") + code.count(" * ") + 4 * code.count("(float)") + 4 * code.count("(double)")
            casts = len(re.findall(r"\(\s*[A-Za-z_$][\w.$]*(?:\[\])?\s*\)\s*[\w(]", code))
            units.append({"path": path + ".java", "archivePath": name, "bytes": data,
                          "sha256": hashlib.sha256(data).hexdigest(), "score": score, "castScore": casts})
    rich = max(1, count // (3 if sampling == "repair-diverse" else 2))
    ordered = sorted(units, key=lambda x: (-x["score"], x["path"]))[:rich]
    if sampling == "repair-diverse":
        picked = {u["path"] for u in ordered}
        ordered += sorted((u for u in units if u["path"] not in picked), key=lambda x: (-x["castScore"], x["path"]))[:min(rich, count - len(ordered))]
    selected = {u["path"] for u in ordered}
    remainder = sorted((u for u in units if u["path"] not in selected), key=lambda x: hashlib.sha256(x["path"].encode()).hexdigest())
    return ordered + remainder[:count - len(ordered)]


def run(command, cwd, log, timeout=180, env=None):
    with log.open("w") as output:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
            stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return process.wait(timeout=timeout)
        except BaseException:
            # The unreaped session leader prevents its process-group ID from being reused.
            # Kill the entire owned group, including a compiler ignoring termination.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
            raise


def usable_jadx_output(exit_code, sources):
    # Pinned JADX 1.5.6: 3 means saving finished with method errors, 1 is a
    # process/argument error and 2 a load failure. Partial source still needs
    # strict candidate compilation and complete comparison before any label.
    return exit_code in (0, 3) and sources.is_dir() and any(sources.rglob("*.java"))


def prepare(project, lock, args, root):
    cached = root / "downloads"
    identity = project.get("id", project["projectGroup"])
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", identity):
        raise ValueError("invalid project identity")
    artifacts = {kind: download(entry, cached / (identity + "-" + kind + Path(entry["url"]).suffix))
                 for kind, entry in project["artifacts"].items()}
    dependencies = [class_jar(download(d, cached / Path(d["url"]).name)) for d in lock["dependencies"]]
    binary = class_jar(artifacts["binary"])
    libraries = [binary] + dependencies
    deadline = time.monotonic() + PREPARATION_SECONDS
    def budget(maximum):
        remaining_seconds = deadline - time.monotonic()
        if remaining_seconds <= 0:
            raise TimeoutError("source preparation exceeded its ten-minute budget")
        return min(maximum, remaining_seconds)
    work = root / "projects" / identity
    work.mkdir(parents=True, exist_ok=False)
    units = source_units(artifacts["sources"], binary, args.units, args.sampling)
    source = work / "original-source"
    for u in units:
        path = source / u["path"]; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(u["bytes"])
    (work / "selected-sources.json").write_text(json.dumps([{k: v for k, v in u.items() if k != "bytes"} for u in units], indent=2) + "\n")
    remaining = units
    for attempt in range(args.compiler_passes):
        classes = work / ("original-classes-" + str(attempt)); classes.mkdir()
        listing = work / ("original-javac-" + str(attempt) + ".txt")
        listing.write_text("".join('"' + str((source / u["path"]).absolute()).replace("\\", "\\\\").replace('"', '\\"') + '"\n' for u in remaining))
        if not remaining:
            raise ValueError("no original source units compiled")
        command = [str(args.jdk / "bin/javac"), "-J-Xmx1g", "-source", "8", "-target", "8", "-bootclasspath", str(args.tools / "android-35/android.jar"),
                   "-cp", os.pathsep.join(map(str, libraries)), "-g" if args.debug_info == "full" else "-g:none", "-proc:none", "-nowarn", "-encoding", "UTF-8", "-Xmaxerrs", "10000", "-d", str(classes), "@" + str(listing)]
        log = work / ("original-javac-" + str(attempt) + ".log")
        if run(command, work, log, budget(180)) == 0:
            break
        bad = {str(Path(p).relative_to(source)) for p in re.findall(r"(?m)^(.+?\.java):[0-9]+: error:", log.read_text())}
        if not bad:
            raise ValueError("original compilation failed; see diagnostics")
        kept = [u for u in remaining if u["path"] not in bad]
        if len(kept) == len(remaining):
            raise ValueError("original compilation made no diagnostic progress")
        remaining = kept
    else:
        raise ValueError("original compilation did not converge")
    class_files = list(classes.rglob("*.class"))
    original = work / "original.jar"
    with zipfile.ZipFile(original, "w", zipfile.ZIP_STORED) as jar:
        for p in sorted(class_files):
            jar.write(p, p.relative_to(classes).as_posix())
    dex = work / "dex"; dex.mkdir()
    command = [str(args.jdk / "bin/java"), "-Xmx1g", "-cp", str(args.tools / "r8-8.7.18.jar"), "com.android.tools.r8.D8", "--" + args.d8_mode, "--min-api", "26",
               "--lib", str(args.tools / "android-35/android.jar"), "--output", str(dex)]
    for library in libraries:
        command += ["--classpath", str(library)]
    if run(command + [str(original)], work, work / "d8.log", budget(180)) != 0:
        raise ValueError("original D8 compilation failed")
    if len(list(dex.glob("*.dex"))) != 1:
        raise ValueError("collector expects one bounded DEX")
    decompiled = work / "decompiled"
    command = [str(args.jdk / "bin/java"), "-Xmx1g", "-cp", str(args.tools / "jadx-1.5.6/lib/jadx-1.5.6-all.jar"), "jadx.cli.JadxCLI", "--no-res",
               "--show-bad-code", "--respect-bytecode-access-modifiers", "-j", "2", "-d", str(decompiled), str(dex / "classes.dex")]
    code = run(command, work, work / "jadx.log", budget(300))
    if not usable_jadx_output(code, decompiled / "sources"):
        raise ValueError("decompilation failed")
    q = {"schemaVersion": 1, "profile": str(ROOT / "profiles/android-full.json"), "dex": str(dex / "classes.dex"),
         "sources": str(decompiled / "sources"), "libraries": list(map(str, libraries)), "out": str(work / "checked"), "maxTrialsPerFile": args.trials,
         "maxCompilerPasses": args.compiler_passes,
         "sourceUnits": {u["path"]: hashlib.sha256((project["projectGroup"] + ":" + u["path"]).encode()).hexdigest() for u in remaining}}
    if args.model:
        q["model"] = str(args.model.absolute())
    request = work / "request.json"; request.write_text(json.dumps(q, indent=2) + "\n")
    return work, request, len(remaining)


def collect(args):
    if os.name != "posix":
        raise ValueError("source collection requires a POSIX host for owned process-group cleanup; training and inference are portable")
    if not 1 <= args.units <= 64 or not 1 <= args.trials <= 48 or not 1 <= args.compiler_passes <= 16:
        raise ValueError("units/trials outside collection limits")
    raw = args.lock.read_bytes()
    if len(raw) > 1 << 20:
        raise ValueError("source lock too large")
    lock = json.loads(raw)
    if lock.get("schemaVersion") != 1 or not 5 <= len(lock["projects"]) <= 100 or len(lock["dependencies"]) > 31:
        raise ValueError("invalid source lock")
    experiment = None
    if args.experiment:
        experiment = next((e for e in lock.get("experiments", []) if e["id"] == args.experiment), None)
        if experiment is None:
            raise ValueError("unknown locked experiment")
        args.debug_info = experiment["debugInfo"]; args.d8_mode = experiment["d8Mode"]
        if any(key in experiment and value != experiment[key] for key, value in
               (("units", args.units), ("trialsPerFile", args.trials), ("sampling", args.sampling))):
            raise ValueError("collection arguments differ from the locked experiment")
    for tool, expected in lock["toolchain"].items():
        if digest(args.tools / tool) != expected:
            raise ValueError("pinned toolchain changed: " + tool)
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / "collection-config.json").write_text(json.dumps({"schemaVersion": 1, "units": args.units, "trials": args.trials, "debugInfo": args.debug_info,
        "d8Mode": args.d8_mode, "sampling": args.sampling, "compilerPasses": args.compiler_passes, "sourceLockSha256": hashlib.sha256(raw).hexdigest(), "javacSha256": digest(args.jdk / "bin/javac"),
        "collectorScriptSha256": digest(Path(__file__)),
        "preparationTimeoutSeconds": PREPARATION_SECONDS,
        "collectorArtifacts": {p.name: digest(p) for p in sorted((ROOT / "modules/benchmarks/build/install/benchmarks/lib").glob("*.jar"))}}, indent=2) + "\n")
    sources = []; results = []
    projects = [p for p in lock["projects"] if not args.project or p.get("id", p["projectGroup"]) == args.project]
    if experiment:
        projects = [p for p in projects if p.get("id", p["projectGroup"]) in experiment["projects"]]
    if not projects:
        raise ValueError("unknown requested project")
    env = dict(os.environ, JAVA_HOME=str(args.jdk), APKPROJECT_TOOLS=str(args.tools))
    for project in projects:
        started = time.monotonic()
        print("Collecting " + project["projectGroup"], flush=True)
        try:
            work, request, compiled = prepare(project, lock, args, args.out)
            command = [str(args.jdk / "bin/java"), "-Xmx2g", "-cp", str(ROOT / "modules/benchmarks/build/install/benchmarks/lib/*"), "dev.apkproject.benchmarks.RepairCorpus", str(request)]
            result = run(command, ROOT, work / "collector.log", 1800, env=env)
            if result != 0:
                raise ValueError("checked collector failed; see collector.log")
            feedback = work / "checked/feedback.jsonl"
            summary = json.loads((work / "checked/summary.json").read_text())
            sources.append({"projectGroup": project["projectGroup"], "sourceUrl": project["artifacts"]["sources"]["url"], "license": project["license"], "kind": "source-project",
                            "path": str(feedback.relative_to(args.out)), "sha256": digest(feedback)})
            results.append({"projectGroup": project["projectGroup"], "originalCompiledFiles": compiled, "summary": summary})
            print(json.dumps(results[-1]), flush=True)
        except (ValueError, OSError, subprocess.TimeoutExpired) as e:
            results.append({"projectGroup": project["projectGroup"], "status": "FAILED", "detail": str(e), "elapsedSeconds": time.monotonic() - started})
            print(json.dumps(results[-1]), flush=True)
        manifest = {"schemaVersion": 1, "sourceLockSha256": hashlib.sha256(raw).hexdigest(), "sources": sources}
        if "projectPartitions" in lock:
            manifest["projectPartitions"] = lock["projectPartitions"]
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (args.out / "collection-results.json").write_text(json.dumps(results, indent=2) + "\n")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--lock", type=Path, required=True); p.add_argument("--out", type=Path, required=True)
    p.add_argument("--jdk", type=Path, required=True); p.add_argument("--tools", type=Path, required=True)
    p.add_argument("--units", type=int, default=32); p.add_argument("--trials", type=int, default=12)
    p.add_argument("--debug-info", choices=("none", "full"), default="none")
    p.add_argument("--d8-mode", choices=("release", "debug"), default="release")
    p.add_argument("--sampling", choices=("numeric-hash", "repair-diverse"), default="numeric-hash")
    p.add_argument("--compiler-passes", type=int, default=8, help="bounded passes to isolate diagnostic-failing files (1–16)")
    p.add_argument("--project"); p.add_argument("--experiment"); p.add_argument("--model", type=Path)
    args = p.parse_args()
    args.lock = args.lock.absolute(); args.out = args.out.absolute(); args.jdk = args.jdk.absolute(); args.tools = args.tools.absolute()
    collect(args)


if __name__ == "__main__":
    main()
