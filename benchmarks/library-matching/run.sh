#!/usr/bin/env bash
# Measures LibraryMatcher against ground truth: a small app that uses Retrofit, OkHttp, Moshi and
# Okio is built with R8 (full renaming and shrinking), and R8's own mapping.txt says which renamed
# class is which library class. --suite exercises optimization, repackaging and shrinking separately.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
tools="${APKPROJECT_TOOLS:-$root/.tools}"
mode="${1:-single}"
if [[ "$mode" != single && "$mode" != --suite ]]; then
  echo "usage: $0 [--suite]" >&2
  exit 2
fi
work="$(mktemp -d)"
if [[ "${APKPROJECT_KEEP_WORK:-0}" == 1 ]]; then
  echo "library matching fixture: $work" >&2
else
  trap 'rm -rf "$work"' EXIT
fi
mirror="https://maven-central.storage-download.googleapis.com/maven2"
mkdir -p "$work/libs" "$work/classes" "$work/libdex"
for c in com/squareup/retrofit2/retrofit/2.11.0/retrofit-2.11.0 com/squareup/retrofit2/converter-moshi/2.11.0/converter-moshi-2.11.0 \
         com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0 com/squareup/okio/okio-jvm/3.9.0/okio-jvm-3.9.0 \
         com/squareup/moshi/moshi/1.15.1/moshi-1.15.1 org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21; do
  f="$work/libs/$(basename "$c").jar"
  curl -sSf --max-time 120 -o "$f" "$mirror/$c.jar"
  echo "$(curl -sSf --max-time 30 "$mirror/$c.jar.sha1" | cut -c1-40)  $f" | sha1sum -c --quiet
done
cp="$(ls "$work"/libs/*.jar | tr '\n' ':')$tools/android-35/android.jar"
javac -nowarn -source 8 -target 8 -bootclasspath "$tools/android-35/android.jar" -cp "$cp" -d "$work/classes" "$here/src/com/example/app/Main.java" 2>/dev/null
(cd "$work/classes" && jar cf ../app.jar .)
java -Xmx4g -cp "$tools/r8-8.7.18.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$tools/android-35/android.jar" \
  --output "$work/libdex" "$work"/libs/*.jar >/dev/null 2>&1
variants=(optimized)
if [[ "$mode" == --suite ]]; then variants+=(shrink-only repackaged unshrunk); fi
for variant in "${variants[@]}"; do
  fixture="$work"
  if [[ "$mode" == --suite ]]; then
    fixture="$work/$variant"
    mkdir -p "$fixture"
    ln -s "$work/libdex" "$fixture/libdex"
  fi
  mkdir -p "$fixture/out"
  cp "$here/rules.pro" "$fixture/rules.pro"
  case "$variant" in
    shrink-only) echo '-dontoptimize' >> "$fixture/rules.pro" ;;
    repackaged) echo "-repackageclasses ''" >> "$fixture/rules.pro" ;;
    unshrunk) echo '-dontoptimize' >> "$fixture/rules.pro"; echo '-dontshrink' >> "$fixture/rules.pro" ;;
  esac
  if ! java -Xmx4g -cp "$tools/r8-8.7.18.jar" com.android.tools.r8.R8 --release --min-api 24 --lib "$tools/android-35/android.jar" \
    --output "$fixture/out" --pg-map-output "$fixture/mapping.txt" --pg-conf "$fixture/rules.pro" "$work/app.jar" "$work"/libs/*.jar > "$fixture/build.log" 2>&1; then
    cat "$fixture/build.log" >&2
    exit 1
  fi
done
benchmark_status=0
(cd "$root" && APKPROJECT_LIBMATCH="$work" APKPROJECT_LIBMATCH_ENFORCE=1 ./gradlew -q :app-recovery:libraryMatchingBenchmark) || benchmark_status=$?
if [[ "$mode" == --suite ]]; then
  mkdir -p "$here/reports"
  for variant in "${variants[@]}"; do
    echo "$variant"
    if [[ -f "$work/$variant/eval.txt" ]]; then
      head -n 2 "$work/$variant/eval.txt"
      grep '^method names:' "$work/$variant/eval.txt"
      cp "$work/$variant/eval.txt" "$here/reports/$variant.txt"
    else
      echo 'evaluation report unavailable'
    fi
  done
  python3 - "$work" "$here/suite.json" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
reports = {p.parent.name: json.loads(p.read_text()) for p in sorted(root.glob('*/eval.json'))}
pathlib.Path(sys.argv[2]).write_text(json.dumps(reports, indent=2) + '\n')
PY
  if [[ -f "$work/optimized/eval.txt" ]]; then cp "$work/optimized/eval.txt" "$here/eval.txt"; fi
else
  if [[ -f "$work/eval.txt" ]]; then grep -v MISSED "$work/eval.txt" | tee "$here/eval.txt"; fi
fi
exit "$benchmark_status"
