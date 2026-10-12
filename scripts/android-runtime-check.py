#!/usr/bin/env python3
"""Compare declared UI scenarios on a disposable, rooted Android emulator (no physical devices)."""
import argparse
import hashlib
import json
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_OUTPUT = 2 * 1024 * 1024


def run(argv, timeout=30):
    process = subprocess.Popen([str(x) for x in argv], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    buffers = [bytearray(), bytearray()]
    overflow = threading.Event()
    def drain(stream, target):
        while True:
            block = stream.read(8192)
            if not block: return
            remaining = MAX_OUTPUT - len(target)
            target.extend(block[:remaining])
            if len(block) > remaining: overflow.set(); process.kill()
    readers = [threading.Thread(target=drain, args=(stream, target), daemon=True)
               for stream, target in zip((process.stdout, process.stderr), buffers)]
    for reader in readers: reader.start()
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill(); process.wait(timeout=5)
        raise RuntimeError("Android command timed out")
    finally:
        for reader in readers: reader.join(timeout=5)
        process.stdout.close(); process.stderr.close()
    if overflow.is_set(): raise RuntimeError("Android output exceeded the capture limit")
    if process.returncode:
        detail = buffers[1].decode("utf-8", errors="replace")[-2000:]
        raise RuntimeError(f"Android command failed ({process.returncode}): {detail}")
    return buffers[0].decode("utf-8", errors="strict")


def validate(scenario):
    if scenario.get("schemaVersion") != 1: raise ValueError("unsupported scenario schema")
    package = scenario.get("package", "")
    activity = scenario.get("activity", "")
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+", package): raise ValueError("invalid package")
    if not re.fullmatch(r"\.?[a-zA-Z][a-zA-Z0-9_.$]*", activity): raise ValueError("invalid activity")
    actions = scenario.get("actions")
    if not isinstance(actions, list) or not 1 <= len(actions) <= 32: raise ValueError("scenario requires 1..32 actions")
    for action in actions:
        kind = action.get("kind")
        if kind == "snapshot":
            if not isinstance(action.get("contains"), str) or not 1 <= len(action["contains"]) <= 256: raise ValueError("snapshot requires expected baseline text")
        elif kind == "tap_text":
            if not isinstance(action.get("text"), str) or not 1 <= len(action["text"]) <= 128: raise ValueError("invalid tap text")
        elif kind == "key":
            if action.get("code") not in (4, 19, 20, 21, 22, 23, 66): raise ValueError("unsupported key")
        elif kind == "text":
            if not re.fullmatch(r"[A-Za-z0-9_ .-]{1,80}", action.get("text", "")): raise ValueError("unsupported input text")
        else: raise ValueError("unsupported scenario action")
    if not any(a["kind"] == "snapshot" for a in actions): raise ValueError("at least one observation is required")
    return package, activity, actions


def parse_ui(text, package):
    if len(text.encode("utf-8")) > MAX_OUTPUT or "<!DOCTYPE" in text or "<!ENTITY" in text: raise ValueError("unsafe UI XML")
    root = ET.fromstring(text)
    nodes = list(root.iter())
    if len(nodes) > 5000: raise ValueError("UI node limit")
    app = [n for n in nodes if n.get("package") == package]
    if not app: raise RuntimeError("target activity has no visible UI")
    # Geometry, focus and Android system UI are outside this comparison's scope.
    keys = ("class", "resource-id", "text", "content-desc", "checked", "enabled", "clickable")
    return app, [{k: n.get(k, "") for k in keys} for n in app]


def compare(original, candidate):
    if len(original) != len(candidate): return {"status": "DIFFERENT", "reason": "observation count changed"}
    differences = [i for i, (a, b) in enumerate(zip(original, candidate)) if a != b]
    return {"status": "DIFFERENT" if differences else "SCENARIO_MATCHED", "differentObservations": differences}


def check(adb, aapt2, serial, original, candidate, scenario):
    package, activity, actions = validate(scenario)
    if not re.fullmatch(r"emulator-\d{4,5}", serial): raise ValueError("a disposable emulator serial is required")
    def command(*args, timeout=30): return run([adb, "-s", serial, *args], timeout)
    if command("shell", "getprop", "ro.kernel.qemu").strip() != "1": raise RuntimeError("physical devices are refused")
    packages = command("shell", "cmd", "package", "list", "packages", package).splitlines()
    if "package:" + package in packages: raise RuntimeError("target package already exists; use a fresh emulator")
    for apk in (original, candidate):
        badging = run([aapt2, "dump", "badging", apk])
        match = re.search(r"^package: name='([^']+)'", badging, re.M)
        if not match or match[1] != package: raise ValueError("APK package does not match the scenario")
    command("root"); command("wait-for-device", timeout=60)
    if command("shell", "id", "-u").strip() != "0": raise RuntimeError("rooted emulator is needed to enforce app network denial")
    observations = []
    for apk in (original, candidate):
        installed = False; uid = None; blocked = []; failure = None
        try:
            result = command("install", "--no-streaming", "-t", str(apk), timeout=60)
            if "Success" not in result: raise RuntimeError("APK installation failed")
            installed = True
            listing = command("shell", "cmd", "package", "list", "packages", "-U")
            match = re.search(r"^package:" + re.escape(package) + r" uid:(\d+)\s*$", listing, re.M)
            if not match or int(match[1]) < 10000: raise RuntimeError("dedicated app UID unavailable")
            uid = match[1]
            if len(re.findall(r" uid:" + uid + r"\s*$", listing, re.M)) != 1: raise RuntimeError("shared UIDs are refused")
            # IPv4 and IPv6 are both denied before the APK is launched.
            for tool in ("iptables", "ip6tables"):
                command("shell", tool, "-w", "10", "-A", "OUTPUT", "-m", "owner", "--uid-owner", uid, "-j", "REJECT")
                blocked.append(tool)
            command("shell", "settings", "put", "system", "font_scale", "1.0")
            launch = command("shell", "am", "start", "-W", "-n", package + "/" + activity)
            if "Error:" in launch or "Exception" in launch: raise RuntimeError("activity launch failed")
            def snapshot():
                command("shell", "uiautomator", "dump", "/data/local/tmp/apkproject-ui.xml")
                return parse_ui(command("shell", "cat", "/data/local/tmp/apkproject-ui.xml"), package)
            samples = []
            for action in actions:
                kind = action["kind"]
                if kind == "snapshot":
                    _, canonical = snapshot()
                    if apk == original and not any(action["contains"] in n["text"] for n in canonical):
                        raise RuntimeError("original APK did not satisfy the predeclared scenario")
                    samples.append(canonical)
                elif kind == "tap_text":
                    nodes, _ = snapshot()
                    matches = [n for n in nodes if n.get("text", "").casefold() == action["text"].casefold()]
                    if len(matches) != 1: raise RuntimeError("tap target must have exactly one app-UI match")
                    node = matches[0]
                    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", "") if node is not None else "")
                    if not bounds: raise RuntimeError("tap target unavailable")
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    command("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                elif kind == "key": command("shell", "input", "keyevent", str(action["code"]))
                elif kind == "text": command("shell", "input", "text", action["text"].replace(" ", "%s"))
            observations.append(samples)
        except Exception as error:
            failure = error
            raise
        finally:
            errors = []
            removed = not installed
            if installed:
                try: command("shell", "am", "force-stop", package)
                except Exception as error: errors.append(str(error))
                try:
                    if "Success" not in command("uninstall", package): raise RuntimeError("uninstall failed")
                    removed = True
                except Exception as error: errors.append(str(error))
            if removed:
                for tool in blocked:
                    try: command("shell", tool, "-w", "10", "-D", "OUTPUT", "-m", "owner", "--uid-owner", uid, "-j", "REJECT")
                    except Exception as error: errors.append(str(error))
            if errors: raise RuntimeError(("scenario failed: " + str(failure) + "; " if failure else "") + "emulator cleanup failed: " + "; ".join(errors))
    return compare(*observations)


def sha256(path):
    with path.open("rb") as stream: return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("original", "candidate", "scenario", "report"): p.add_argument("--"+name, type=Path, required=True)
    p.add_argument("--serial", required=True); p.add_argument("--adb", default="adb"); p.add_argument("--aapt2", required=True)
    args = p.parse_args()
    report = {"schemaVersion": 1, "scope": "Declared visible UI scenarios on one Android emulator with app-UID network denied; not whole-app equivalence",
              "originalSha256": sha256(args.original),
              "candidateSha256": sha256(args.candidate),
              "scenarioSha256": hashlib.sha256(args.scenario.read_bytes()).hexdigest()}
    try: report.update(check(args.adb, args.aapt2, args.serial, args.original, args.candidate, json.loads(args.scenario.read_text())))
    except Exception as error: report.update(status="FAILED", reason=str(error))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2)+"\n")
    print(report["status"])
    return 0 if report["status"] == "SCENARIO_MATCHED" else 2


if __name__ == "__main__": raise SystemExit(main())
