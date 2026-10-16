#!/usr/bin/env bash
# Runs `apkproject privacy` on every app of apps.tsv and scores the reports (evaluate.py).
# Each APK is downloaded from F-Droid (the repo, else its archive) into .tools/dl/apps and checked
# against its SHA-256. Reports go to OUT_DIR/<package>_<versionCode>; existing ones are kept, so a
# second run only scores (delete OUT_DIR to analyse again).
#
# Usage: benchmarks/privacy/apps/run.sh [OUT_DIR]      (default: runs/privacy-apps)
# Measure with JAVA_HOME at JDK 17, the test toolchain: JDK 21 analyses some apps slightly differently.
# JOBS=N analyses N apps at a time (default 2; each JVM takes up to 3 GB).
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../../.." && pwd)
out=${1:-$root/runs/privacy-apps}
apks=$root/.tools/dl/apps
cli=$root/modules/cli/build/install/apkproject/bin/apkproject
[ -x "$cli" ] || (cd "$root" && ./gradlew -q :cli:installDist)
mkdir -p "$apks" "$out"

grep -v -e '^#' -e '^$' "$here/apps.tsv" | while IFS=$'\t' read -r pkg ver sha _; do
  apk=$apks/${pkg}_${ver}.apk
  if [ ! -f "$apk" ]; then
    curl -fsSLo "$apk.part" "https://f-droid.org/repo/${pkg}_${ver}.apk" ||
      curl -fsSLo "$apk.part" "https://f-droid.org/archive/${pkg}_${ver}.apk"
    mv "$apk.part" "$apk"
  fi
  echo "$sha  $apk" | sha256sum -c --quiet - || { echo "SHA-256 mismatch: $apk" >&2; exit 1; }
  [ -f "$out/${pkg}_${ver}/privacy.json" ] || echo "${pkg}_${ver}"
done | xargs -r -P "${JOBS:-2}" -I{} sh -c 'JAVA_OPTS="${JAVA_OPTS:--Xmx3g}" "$1" privacy "$2/{}.apk" --out "$3/{}" > "$3/{}.log" 2>&1 || echo "failed: {} (see $3/{}.log)" >&2' _ "$cli" "$apks" "$out"

python3 -I "$here/evaluate.py" --runs "$out" --apks "$apks"
