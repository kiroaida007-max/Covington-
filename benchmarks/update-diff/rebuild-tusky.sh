#!/bin/bash
# Rebuild an upstream Tusky release (blue flavour, unsigned) for its R8 mapping, the ground truth
# evaluate.py scores against. Same build as scripts/restore-tusky.py, for any commit.
#
#   benchmarks/update-diff/rebuild-tusky.sh COMMIT DEST
#
# Needs a full JDK 21 (JAVA_HOME) and an Android SDK with platform 36 and build-tools 35.0.0
# (ANDROID_HOME). Afterwards compare classes.dex with the F-Droid APK: identical bytes mean the
# mapping is the true name map for that APK.
set -euo pipefail
commit="$1"; dest="$2"
root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${JAVA_HOME:?set JAVA_HOME to a full JDK 21}"
: "${ANDROID_HOME:?set ANDROID_HOME to an SDK with platform 36 and build-tools 35.0.0}"
if [ ! -d "$dest/.git" ]; then
  git init --quiet "$dest"
  git -C "$dest" remote add origin https://codeberg.org/tusky/Tusky.git
  git -C "$dest" fetch --depth 1 origin "$commit"
  git -C "$dest" checkout --quiet --detach FETCH_HEAD
fi
cd "$dest"
ANDROID_SDK_ROOT="$ANDROID_HOME" bash gradlew --no-daemon --no-configuration-cache --no-scan --max-workers=2 \
  -Pkotlin.compiler.execution.strategy=in-process \
  --init-script "$root/benchmarks/tusky-32.2/unsigned.init.gradle" :app:assembleBlueRelease
echo "mapping: $dest/app/build/outputs/mapping/blueRelease/mapping.txt"
