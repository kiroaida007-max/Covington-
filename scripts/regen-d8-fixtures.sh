#!/usr/bin/env bash
# Regenerates the checked-in D8 ground-truth DEX files used by :formats-dex tests.
# Requires JDK 17 and the pinned D8 from scripts/setup-toolchain.sh.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="${APKPROJECT_TOOLS:-$ROOT/.tools}"
JDK17="${JDK17_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
RES="$ROOT/modules/formats-dex/src/test/resources/d8"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

build() { # name javac-debug-flag d8-mode
  mkdir -p "$tmp/$1/cls" "$tmp/$1/out"
  "$JDK17/bin/javac" --release 8 -Xlint:-options -proc:none "$2" -d "$tmp/$1/cls" "$RES/Broad.java"
  "$JDK17/bin/java" -cp "$TOOLS/r8-8.7.18.jar" com.android.tools.r8.D8 "$3" --min-api 28 \
    --lib "$JDK17" --output "$tmp/$1/out" "$tmp/$1/cls"/*.class
  cp "$tmp/$1/out/classes.dex" "$RES/$1.dex"
}
build broad -g:none --release
build broad-debug -g --debug
sha256sum "$RES"/*.dex
