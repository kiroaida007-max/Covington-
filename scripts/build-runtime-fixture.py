#!/usr/bin/env python3
"""Build only the Android runtime benchmark: original, strictly recompiled export, seeded mutant."""
import argparse
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--sdk", type=Path, required=True); p.add_argument("--java-home", type=Path, required=True)
    p.add_argument("--tools", type=Path, required=True); p.add_argument("--out", type=Path, required=True)
    p.add_argument("--recovered-source", type=Path); p.add_argument("--original", type=Path)
    p.add_argument("--mutant", action="store_true")
    args = p.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=True)
    build = out / "build"; build.mkdir(exist_ok=True)
    sdk = args.sdk.resolve(); jdk = args.java_home.resolve() / "bin"
    android = sdk / "platforms/android-30/android.jar"
    tools = sdk / "build-tools/35.0.0"
    def run(*argv): subprocess.run([str(x) for x in argv], check=True, timeout=120)
    if args.recovered_source:
        if not args.original: p.error("--original is required with --recovered-source")
        # No fallback to ground truth, Android stubs or edited exported code.
        sources = sorted(args.recovered_source.resolve().rglob("*.java"))
    else:
        if args.mutant: p.error("mutants must originate from recovered source")
        sources = [ROOT / "benchmarks/android-runtime/hidden/MainActivity.java"]
    if not sources or len(sources) > 32: raise ValueError("fixture source count outside bounds")
    if args.mutant:
        changed = False; modified = []
        for i, source in enumerate(sources):
            text = source.read_text()
            if "Result: " in text: text = text.replace("Result: ", "Broken: "); changed = True
            target = build / f"mutant-{i}" / source.name; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(text)
            modified.append(target)
        if not changed: raise ValueError("seeded mutation did not change recovered code")
        sources = modified
    classes = build / "classes"; classes.mkdir(exist_ok=True)
    if any(classes.rglob("*.class")): raise ValueError("use a fresh fixture build directory")
    run(jdk / "javac", "-encoding", "UTF-8", "--release", "8", "-cp", android, "-d", classes, *sources)
    classes_jar = build / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w") as z:
        for f in sorted(classes.rglob("*.class")): z.write(f, f.relative_to(classes).as_posix())
    profile = json.loads((ROOT / "profiles/android-full.json").read_text())
    d8 = Path(profile["d8"]["jar"].replace("${APKPROJECT_TOOLS}", str(args.tools.resolve())))
    import hashlib
    with d8.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != profile["d8"]["sha256"]: raise ValueError("D8 pin mismatch")
    dex = build / "dex"; dex.mkdir(exist_ok=True)
    run(jdk / "java", "-cp", d8, "com.android.tools.r8.D8", "--release", "--min-api", "23", "--lib", android, "--output", dex, classes_jar)
    base = build / "base.apk"
    if args.original:
        with zipfile.ZipFile(args.original) as source, zipfile.ZipFile(base, "w") as target:
            for name in source.namelist():
                if not name.startswith("META-INF/") and not (name.startswith("classes") and name.endswith(".dex")):
                    target.writestr(name, source.read(name))
    else:
        run(tools / "aapt2", "link", "-I", android, "--manifest", ROOT / "benchmarks/android-runtime/AndroidManifest.xml", "-o", base)
    with zipfile.ZipFile(base, "a") as z:
        for f in sorted(dex.glob("*.dex")): z.write(f, f.name)
    key = build / "debug.p12"
    run(jdk / "keytool", "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "7", "-storetype", "PKCS12", "-keystore", key,
        "-storepass", "android", "-keypass", "android", "-dname", "CN=Disposable Runtime Fixture")
    apk = out / "app.apk"
    run(tools / "apksigner", "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk, base)
    run(tools / "apksigner", "verify", apk)
    print(apk)


if __name__ == "__main__": main()
