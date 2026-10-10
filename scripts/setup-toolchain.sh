#!/usr/bin/env bash
# Installs the pinned external tools used by the profiles in profiles/ into
# $APKPROJECT_TOOLS (default: <repo>/.tools) and verifies their SHA-256.
#
#   D8/R8 8.7.18  (forward DEX compiler, Google Maven)       -> r8-8.7.18.jar
#   Z3 4.13.3     (SMT solver, extracted from the PyPI wheel) -> z3/bin/z3
#
# With --full (profile android-full, used by `apkproject decompile`):
#   JADX 1.5.6            (whole-app decompiler, GitHub release)      -> jadx-1.5.6/
#   Android platform 35   (android.jar for compile checks)            -> android-35/android.jar
#   jadx-headless-mcp 0.7.1 (REA's audited Android engine)            -> jadx-headless-mcp-0.7.1-all.jar
#   REA 6.1.0             (npm rea-agents; needs Node.js >= 22.19)   -> rea/
#   Wakaru 1.13.0         (JavaScript decompiler REA drives)          -> rea/node_modules/@wakaru/
#   ECJ 3.45.0            (Eclipse compiler, Maven Central)           -> ecj-3.45.0.jar
#
# JDK 17 is expected to be installed separately; the profile finds it through
# $JDK17_HOME or the usual /usr/lib/jvm locations. Run `apkproject doctor`
# afterwards to check the whole profile.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="${APKPROJECT_TOOLS:-$ROOT/.tools}"
mkdir -p "$TOOLS"

R8_VERSION=8.7.18
R8_SHA256=58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903
Z3_VERSION=4.13.3.0
Z3_WHEEL_SHA256=794843e4946ade1561e40a75ffc1163b45d36b493fd6cc269ad1d6a65bddb8e5
Z3_BINARY_SHA256=1cc0766515f49af2b79d2243685f496814b0feb9ceab28744cfecdbf7c5f5d9c

verify() { # file expected-sha256
  local actual
  actual="$(sha256sum "$1" | awk '{print $1}')"
  if [[ "$actual" != "$2" ]]; then
    echo "checksum mismatch for $1: expected $2, got $actual" >&2
    return 1
  fi
}

download_verified() { # destination url expected-sha256
  local destination="$1" url="$2" expected="$3" partial="$1.part"
  # Retry transport failures as well as transient HTTP errors, but bound both
  # each transfer and the retry window. Never publish unchecked partial bytes.
  if ! curl -fsSL --retry 4 --retry-all-errors --retry-delay 2 \
      --retry-max-time 180 --connect-timeout 20 --max-time 180 \
      -o "$partial" "$url"; then
    rm -f "$partial"
    return 1
  fi
  if ! verify "$partial" "$expected"; then
    rm -f "$partial"
    return 1
  fi
  if ! mv "$partial" "$destination"; then
    rm -f "$partial"
    return 1
  fi
}

r8="$TOOLS/r8-$R8_VERSION.jar"
if [[ ! -f "$r8" ]]; then
  echo "downloading D8/R8 $R8_VERSION"
  download_verified "$r8" \
    "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar" "$R8_SHA256"
fi
verify "$r8" "$R8_SHA256"

z3="$TOOLS/z3/bin/z3"
if [[ ! -x "$z3" ]]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) ;;
    *)
      echo "The pinned Z3 wheel is linux-x86_64 only. Install Z3 $Z3_VERSION from" >&2
      echo "https://github.com/Z3Prover/z3/releases and place the binary at $z3," >&2
      echo "then record its checksum in profiles/v0-jvm-pure.json." >&2
      exit 1 ;;
  esac
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  echo "downloading Z3 $Z3_VERSION wheel"
  python3 -m pip download --quiet --no-deps --only-binary=:all: \
    --platform manylinux2014_x86_64 --python-version 3.12 \
    -d "$tmp" "z3-solver==$Z3_VERSION"
  wheel="$(ls "$tmp"/z3_solver-*.whl)"
  verify "$wheel" "$Z3_WHEEL_SHA256"
  mkdir -p "$tmp/x" "$TOOLS/z3/bin"
  python3 -I -m zipfile -e "$wheel" "$tmp/x" >/dev/null
  install -m 0755 "$tmp/x/z3_solver-$Z3_VERSION.data/data/bin/z3" "$z3"
fi
verify "$z3" "$Z3_BINARY_SHA256"

if [[ "${1:-}" == "--full" ]]; then
  JADX_VERSION=1.5.6
  JADX_ZIP_SHA256=545ea2be9c242511bc145755cf4bda2485ade42966e096f8b4d3da2a230e8974
  JADX_JAR_SHA256=fe3e12c45acf75f92369685fd02d1d7a7323385dc725680a9b98a0dac0ea554b
  PLATFORM_ZIP_SHA256=0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0
  ANDROID_JAR_SHA256=4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b
  REA_ENGINE_SHA256=6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75
  REA_VERSION=6.1.0

  jadx_jar="$TOOLS/jadx-$JADX_VERSION/lib/jadx-$JADX_VERSION-all.jar"
  if [[ ! -f "$jadx_jar" ]]; then
    echo "downloading JADX $JADX_VERSION"
    tmp="$(mktemp -d)"
    download_verified "$tmp/jadx.zip" "https://github.com/skylot/jadx/releases/download/v$JADX_VERSION/jadx-$JADX_VERSION.zip" "$JADX_ZIP_SHA256"
    mkdir -p "$TOOLS/jadx-$JADX_VERSION"
    python3 -I -m zipfile -e "$tmp/jadx.zip" "$TOOLS/jadx-$JADX_VERSION" >/dev/null
    rm -rf "$tmp"
  fi
  verify "$jadx_jar" "$JADX_JAR_SHA256"

  android_jar="$TOOLS/android-35/android.jar"
  if [[ ! -f "$android_jar" ]]; then
    echo "downloading Android platform 35"
    tmp="$(mktemp -d)"
    download_verified "$tmp/platform.zip" "https://dl.google.com/android/repository/platform-35_r02.zip" "$PLATFORM_ZIP_SHA256"
    mkdir -p "$TOOLS/android-35"
    python3 -I -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1]); open(sys.argv[2],'wb').write(z.read('android-35/android.jar'))" "$tmp/platform.zip" "$android_jar"
    rm -rf "$tmp"
  fi
  verify "$android_jar" "$ANDROID_JAR_SHA256"

  ecj="$TOOLS/ecj-3.45.0.jar"
  if [[ ! -f "$ecj" ]]; then
    echo "downloading ECJ 3.45.0"
    for repo in https://repo.maven.apache.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
      download_verified "$ecj" "$repo/org/eclipse/jdt/ecj/3.45.0/ecj-3.45.0.jar" \
        c8f5e66787ea6e4e0394ba3051c86a4edc50683ce9850f71739646529a99a2d7 && break
    done
    [[ -f "$ecj" ]] || { echo "ECJ download failed from both mirrors" >&2; exit 1; }
  fi
  verify "$ecj" c8f5e66787ea6e4e0394ba3051c86a4edc50683ce9850f71739646529a99a2d7

  engine="$TOOLS/jadx-headless-mcp-0.7.1-all.jar"
  if [[ ! -f "$engine" ]]; then
    echo "downloading REA's Android engine (jadx-headless-mcp 0.7.1)"
    download_verified "$engine" "https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar" "$REA_ENGINE_SHA256"
  fi
  verify "$engine" "$REA_ENGINE_SHA256"

  if command -v npm >/dev/null 2>&1; then
    if [[ ! -x "$TOOLS/rea/node_modules/.bin/rea" ]]; then
      echo "installing REA $REA_VERSION from npm"
      mkdir -p "$TOOLS/rea"
      (cd "$TOOLS/rea" && npm install --no-audit --no-fund --loglevel=error "rea-agents@$REA_VERSION" "@wakaru/cli@1.13.0")
    fi
    "$TOOLS/rea/node_modules/.bin/rea" --version
  else
    echo "npm not found: REA evidence will be skipped (decompilation still works)" >&2
  fi
fi

echo "tools ready in $TOOLS"
"$z3" --version
