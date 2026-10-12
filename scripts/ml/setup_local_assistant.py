#!/usr/bin/env python3
"""Install pinned official Q4_K_M coding weights and a CPU-only llama.cpp runtime.

Downloads public artifacts only. No APK/source/log upload and no model/server execution.
The resulting local JSON configuration is selected explicitly by CLI/desktop recovery.
"""
import argparse
import hashlib
import json
import os
import platform
import shutil
import tarfile
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

CATALOG = Path(__file__).with_name("local-assistant-catalog.json")
MAX_RUNTIME = 128 << 20


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(entry, target):
    if target.exists():
        if target.stat().st_size != entry["bytes"] or digest(target) != entry["sha256"]:
            raise ValueError("cached artifact changed: " + target.name)
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".download-", dir=target.parent); os.close(fd)
    temp = Path(name)
    started = time.monotonic(); last_report = started
    try:
        hasher = hashlib.sha256(); count = 0
        with urllib.request.urlopen(entry["url"], timeout=60) as response, temp.open("wb") as output:
            while chunk := response.read(1 << 20):
                count += len(chunk)
                if count > entry["bytes"] or time.monotonic() - started > 1800:
                    raise ValueError("artifact exceeds size/time bounds")
                output.write(chunk); hasher.update(chunk)
                if time.monotonic() - last_report >= 15:
                    print(f"Downloading {target.name}: {count * 100 // entry['bytes']}%", flush=True)
                    last_report = time.monotonic()
        if count != entry["bytes"] or hasher.hexdigest() != entry["sha256"]:
            raise ValueError("artifact size/hash does not match its pin")
        temp.replace(target)
        return target
    finally:
        temp.unlink(missing_ok=True)


def runtime_name(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise ValueError("runtime archive path escaped")
    base = path.name.lower()
    return base.endswith(".dll") or ".so" in base or base in {
        "llama-completion", "llama-completion.exe", "llama-tokenize", "llama-tokenize.exe", "llama-quantize", "llama-quantize.exe"}


def extract_runtime(archive, out):
    """Flatten only native runtime files; never create archive symlinks or special files."""
    out.mkdir(parents=True, exist_ok=False)
    total = 0; names = set()
    def write(name, data):
        nonlocal total
        if not runtime_name(name):
            return
        base = PurePosixPath(name).name
        if base in names or not base or len(names) >= 64 or len(data) > 32 << 20:
            raise ValueError("runtime file collision/count/size limit")
        names.add(base); total += len(data)
        if total > MAX_RUNTIME:
            raise ValueError("runtime exceeds 128 MiB")
        path = out / base; path.write_bytes(data)
        if base.startswith("llama-") and not base.endswith(".dll"):
            path.chmod(0o755)
    if archive.suffix == ".zip":
        with zipfile.ZipFile(archive) as z:
            if len(z.infolist()) > 1000:
                raise ValueError("runtime archive has too many members")
            for info in z.infolist():
                if runtime_name(info.filename) and not info.is_dir():
                    if info.file_size > 32 << 20 or (info.external_attr >> 16) & 0o170000 == 0o120000:
                        raise ValueError("invalid runtime zip member")
                    with z.open(info) as stream:
                        write(info.filename, stream.read((32 << 20) + 1))
    else:
        with tarfile.open(archive) as tar:
            members = tar.getmembers()
            if len(members) > 1000:
                raise ValueError("runtime archive has too many members")
            table = {m.name: m for m in members}
            for info in members:
                if not runtime_name(info.name):
                    continue
                resolved = info; followed = set()
                while resolved.issym():
                    if resolved.name in followed or len(followed) > 8:
                        raise ValueError("runtime symlink cycle")
                    followed.add(resolved.name)
                    target = str(PurePosixPath(resolved.name).parent / resolved.linkname)
                    runtime_name(target)  # reject absolute/traversal paths, including unselected targets
                    resolved = table[target]
                if not resolved.isfile() or resolved.size > 32 << 20:
                    raise ValueError("invalid runtime tar member")
                with tar.extractfile(resolved) as stream:
                    write(info.name, stream.read((32 << 20) + 1))
    if not any(name in names for name in ("llama-completion", "llama-completion.exe")):
        raise ValueError("runtime archive lacks the completion executable")


def install(args):
    catalog = json.loads(CATALOG.read_text())
    machine = platform.machine().lower()
    system = platform.system()
    if machine not in ("x86_64", "amd64") or system not in ("Linux", "Windows"):
        raise ValueError("pinned native runtime currently supports Linux/Windows x64")
    platform_id = "windows-x64" if system == "Windows" else "linux-x64"
    model_id = f"qwen2.5-coder-{args.size}-instruct-q4_k_m"
    model = catalog["models"][model_id]
    runtime = catalog["runtime"]["platforms"][platform_id]
    if args.out.exists():
        raise ValueError("assistant output must be a new directory")
    args.cache.mkdir(parents=True, exist_ok=True)
    weight = download(model, args.cache / (model_id + ".gguf"))
    archive = download(runtime, args.cache / runtime["url"].rsplit("/", 1)[-1])
    licenses = {name: download(entry, args.cache / (name + ".txt")) for name, entry in catalog["licenses"].items()}
    with tempfile.TemporaryDirectory(prefix="assistant-", dir=args.out.parent) as tmp:
        staged = Path(tmp) / "pack"; staged.mkdir()
        # A copied pack is portable; mutable shared links are not installed.
        shutil.copyfile(weight, staged / "model.gguf")
        extract_runtime(archive, staged / "runtime")
        for name, path in licenses.items():
            shutil.copyfile(path, staged / (name + ".txt"))
        files = [{"path": "runtime/" + p.name, "sha256": digest(p)} for p in sorted((staged / "runtime").iterdir())]
        exe = "runtime/llama-completion" + (".exe" if system == "Windows" else "")
        config = {"schemaVersion": 1, "modelId": model_id, "modelFile": "model.gguf", "runtimeExecutable": exe,
                  "runtimeFiles": files, "runtimeRelease": catalog["runtime"]["release"], "threads": 4,
                  "memoryMb": 2048 if args.size == "1.5b" else 1536, "maxCalls": 6, "callTimeoutMs": 45000,
                  "totalBudgetMs": 120000, "retrieval": True}
        if args.knowledge:
            if args.knowledge.stat().st_size > 512 << 10:
                raise ValueError("local knowledge exceeds 512 KiB")
            shutil.copyfile(args.knowledge, staged / "knowledge.json")
            config["knowledgeFile"] = {"path": "knowledge.json", "sha256": digest(staged / "knowledge.json")}
        (staged / "assistant.json").write_text(json.dumps(config, indent=2) + "\n")
        (staged / "artifact-provenance.json").write_text(json.dumps({"model": model, "runtime": dict(runtime, release=catalog["runtime"]["release"],
            revision=catalog["runtime"]["revision"]), "platform": platform_id, "licenses": catalog["licenses"]}, indent=2) + "\n")
        staged.rename(args.out)
    print(f"Installed {model_id} ({model['bytes']} bytes), CPU runtime {catalog['runtime']['release']}")
    print("Local configuration: " + str(args.out / "assistant.json"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".cache/apkproject/local-assistant")
    parser.add_argument("--size", choices=("1.5b", "0.5b"), default="1.5b")
    parser.add_argument("--knowledge", type=Path, help="optional checked local repair memory; copied locally, never uploaded")
    args = parser.parse_args(); args.out = args.out.absolute(); args.cache = args.cache.absolute()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    install(args)


if __name__ == "__main__":
    main()
