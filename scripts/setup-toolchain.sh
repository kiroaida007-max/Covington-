#!/usr/bin/env bash
# Installs the pinned external tools used by the v0-jvm-pure profile into
# $APKPROJECT_TOOLS (default: <repo>/.tools) and verifies their SHA-256.
#
#   D8/R8 8.7.18  (forward DEX compiler, Google Maven)       -> r8-8.7.18.jar
#   Z3 4.13.3     (SMT solver, extracted from the PyPI wheel) -> z3/bin/z3
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
    exit 1
  fi
}

r8="$TOOLS/r8-$R8_VERSION.jar"
if [[ ! -f "$r8" ]]; then
  echo "downloading D8/R8 $R8_VERSION"
  curl -fsSL -o "$r8.part" \
    "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
  mv "$r8.part" "$r8"
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

echo "tools ready in $TOOLS"
"$z3" --version
