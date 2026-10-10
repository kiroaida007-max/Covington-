#!/usr/bin/env python3
"""Build the pinned Tusky source; record provenance separately from APK decompilation."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "benchmarks/tusky-32.2"


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require_input(apk, lock):
    if sha256(apk) != lock["apkSha256"]:
        raise ValueError("APK does not match the pinned Tusky 32.2 input; refusing to attribute this source to another binary")


def entry_hashes(apk, select=lambda name: True):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK has duplicate ZIP entries; payload identity is ambiguous")
        selected = [n for n in names if select(n)]
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in sorted(selected)}


def payloads(apk):
    return entry_hashes(apk, lambda n: re.fullmatch(r"classes(?:[0-9]+)?\.dex", n) or
                        (n.startswith("lib/") and n.endswith(".so")))


def compare_archives(original, rebuilt):
    before, after = entry_hashes(original), entry_hashes(rebuilt)
    common = before.keys() & after.keys()
    return {
        "originalEntries": len(before),
        "rebuiltEntries": len(after),
        "identicalEntries": sum(before[n] == after[n] for n in common),
        "changedEntries": sorted(n for n in common if before[n] != after[n]),
        "missingEntries": sorted(before.keys() - after.keys()),
        "additionalEntries": sorted(after.keys() - before.keys()),
        "scope": "Uncompressed ZIP-entry bytes; APK signing blocks, ZIP headers/order and alignment are not compared.",
    }


def compare_payloads(original, rebuilt):
    before, after = payloads(original), payloads(rebuilt)
    dex = {n for n in before if n.endswith(".dex")}
    rebuilt_dex = {n for n in after if n.endswith(".dex")}
    matched = sorted(n for n in before if before[n] == after.get(n))
    return {
        "dexPayloadsIdentical": bool(dex) and dex == rebuilt_dex and dex.issubset(matched),
        "original": before,
        "rebuilt": after,
        "identicalEntries": matched,
        "changedOrMissingEntries": sorted(n for n in before if before[n] != after.get(n)),
        "additionalEntries": sorted(after.keys() - before.keys()),
        "scope": "DEX and native-library bytes only; this does not establish identical resources, signing or runtime behavior.",
    }


def capture(argv, cwd=None):
    return subprocess.check_output(argv, cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()


def checkout(destination, lock):
    if destination.exists() and not (destination / ".git").is_dir():
        raise ValueError("Output exists without a Git checkout; choose an empty destination")
    if not destination.exists():
        destination.mkdir(parents=True)
        subprocess.run(["git", "init", "--quiet", str(destination)], check=True)
        subprocess.run(["git", "remote", "add", "origin", lock["sourceRepository"]], cwd=destination, check=True)
        subprocess.run(["git", "fetch", "--depth", "1", "origin", lock["sourceCommit"]], cwd=destination, check=True)
        subprocess.run(["git", "checkout", "--detach", "FETCH_HEAD"], cwd=destination, check=True)
    if capture(["git", "remote", "get-url", "origin"], destination) != lock["sourceRepository"]:
        raise ValueError("Existing checkout has a different origin; it will not be changed")
    if capture(["git", "rev-parse", "HEAD"], destination) != lock["sourceCommit"]:
        raise ValueError("Existing checkout is at a different commit; it will not be reset")
    if capture(["git", "status", "--porcelain", "--untracked-files=no"], destination):
        raise ValueError("Tracked upstream files have local changes; they will not be overwritten or attributed to the pinned commit")


def write_report(path, report):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n")
    temporary.replace(path)


@contextmanager
def temporary_test_patch(destination, patch):
    if patch is None:
        yield
        return
    capture(["git", "apply", "--check", str(patch)], destination)
    capture(["git", "apply", str(patch)], destination)
    try:
        yield
    finally:
        # Refuse to overwrite edits made while the build was running.
        capture(["git", "apply", "--reverse", "--check", str(patch)], destination)
        capture(["git", "apply", "--reverse", str(patch)], destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="exact F-Droid Tusky 32.2 APK (versionCode 142)")
    parser.add_argument("--out", required=True, type=Path, help="new directory or an unchanged checkout at the pinned commit")
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--variant", choices=("debug", "release", "both"), default="both")
    parser.add_argument("--test", action="store_true", help="also run upstream blueDebug unit tests")
    parser.add_argument("--fix-test-harness", action="store_true",
                        help="temporarily apply the recorded timeline test scheduler/lifecycle fix; requires --test")
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()
    if args.fix_test_harness and not args.test:
        parser.error("--fix-test-harness requires --test")
    lock = json.loads((FIXTURE / "source-lock.json").read_text())
    require_input(args.apk, lock)
    jdk = Path(os.environ.get("JAVA_HOME", ""))
    javac = jdk / "bin/javac"
    if not javac.is_file() or not capture([str(javac), "-version"]).startswith("javac 21."):
        raise ValueError("Set JAVA_HOME to a full JDK 21 installation with javac, not a Java runtime")
    if args.sdk is None:
        raise ValueError("Supply --sdk or ANDROID_HOME with Android platform 36 and build-tools 35.0.0 installed")
    sdk = Path(args.sdk).resolve()
    platform = sdk / "platforms/android-36/android.jar"
    aapt = sdk / "build-tools/35.0.0/aapt2"
    if not platform.is_file() or not aapt.is_file():
        raise ValueError("SDK must contain platforms;android-36 and build-tools;35.0.0")
    if args.workers < 1:
        raise ValueError("--workers must be positive")
    destination = args.out.resolve()
    checkout(destination, lock)
    evidence = destination / ".restoration"
    evidence.mkdir(exist_ok=True)
    report_path = evidence / "build.json"
    report = {
        "schemaVersion": 1,
        "status": "BUILDING",
        "source": lock,
        "sourceKind": "PUBLISHED_UPSTREAM_SOURCE",
        "apkOnlyDecompilation": False,
        "jdk": capture([str(javac), "-version"]),
        "androidJarSha256": sha256(platform),
        "buildOverrideSha256": sha256(FIXTURE / "unsigned.init.gradle"),
        "releaseSigning": "unsigned; distributor keys are not used",
        "artifacts": [],
    }
    test_patch = FIXTURE / "test-dispatcher.patch" if args.fix_test_harness else None
    if args.test:
        report["testHarness"] = {
            "modified": test_patch is not None,
            "patchSha256": sha256(test_patch) if test_patch else None,
            "scope": "Timeline test dispatcher and ViewModel cleanup only; production source and assertions are unchanged.",
        }
    variants = ["debug", "release"] if args.variant == "both" else [args.variant]
    tasks = [":app:assembleBlue" + variant.title() for variant in variants]
    if args.test:
        tasks.append(":app:testBlueDebugUnitTest")
    argv = ["bash", "gradlew", "--no-daemon", "--no-configuration-cache", "--no-scan",
            "--max-workers=" + str(args.workers), "-Pkotlin.compiler.execution.strategy=in-process",
            "--init-script", str(FIXTURE / "unsigned.init.gradle")] + tasks
    report["tasks"] = tasks
    env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
    write_report(report_path, report)
    started = time.monotonic()
    try:
        print("Building exact upstream commit", lock["sourceCommit"], flush=True)
        with temporary_test_patch(destination, test_patch), (evidence / "gradle.log").open("w") as log:
            process = subprocess.Popen(argv, cwd=destination, env=env, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, start_new_session=True)
            try:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    print(line, end="", flush=True)
                code = process.wait()
            except BaseException:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
                raise
        report["gradleExitCode"] = code
        if args.test:
            suites = [ET.parse(path).getroot() for path in
                      (destination / "app/build/test-results/testBlueDebugUnitTest").glob("TEST-*.xml")]
            counts = {key: sum(int(suite.get(key, "0")) for suite in suites)
                      for key in ("tests", "failures", "errors", "skipped")}
            report["unitTests"] = counts
        if code:
            raise RuntimeError("Gradle failed; see .restoration/gradle.log")
        if capture(["git", "status", "--porcelain", "--untracked-files=no"], destination):
            raise RuntimeError("Build modified tracked source; inspect it before claiming a pinned-source build")
        if args.test:
            if not counts["tests"] or counts["failures"] or counts["errors"]:
                raise RuntimeError("Upstream unit-test reports are missing or unsuccessful")
        for variant in variants:
            apks = sorted((destination / "app/build/outputs/apk/blue" / variant).glob("*.apk"))
            if len(apks) != 1:
                raise RuntimeError(f"Expected one {variant} APK, found {len(apks)}")
            apk = apks[0]
            metadata = capture([str(aapt), "dump", "badging", str(apk)])
            package = next(line for line in metadata.splitlines() if line.startswith("package:"))
            for field, value in [("name", lock["applicationId"]), ("versionCode", str(lock["versionCode"])),
                                 ("versionName", lock["versionName"])]:
                if f"{field}='{value}'" not in package:
                    raise RuntimeError("Output APK metadata differs from the requested Tusky version")
            report["artifacts"].append({"variant": variant, "path": str(apk.relative_to(destination)),
                                        "sha256": sha256(apk), "package": package,
                                        "payloadComparison": compare_payloads(args.apk, apk),
                                        "archiveComparison": compare_archives(args.apk, apk)})
        mapping = destination / "app/build/outputs/mapping/blueRelease/mapping.txt"
        if "release" in variants and mapping.is_file():
            report["releaseMappingSha256"] = sha256(mapping)
            report["mappingValidForInputApk"] = any(a["variant"] == "release" and
                a["payloadComparison"]["dexPayloadsIdentical"] for a in report["artifacts"])
        report["status"] = "PASS"
    except (Exception, KeyboardInterrupt) as error:
        report["status"] = "FAIL"
        report["error"] = str(error)
        raise
    finally:
        report["elapsedSeconds"] = round(time.monotonic() - started, 2)
        write_report(report_path, report)
    print("Build PASS; provenance and payload comparisons:", report_path)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
