#!/usr/bin/env bash
# Measures LibraryMatcher against ground truth: a small app that uses Retrofit, OkHttp, Moshi and
# Okio is built with R8 (full renaming and shrinking), and R8's own mapping.txt says which renamed
# class is which library class. Prints precision and recall to benchmarks/library-matching/eval.txt.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
tools="${APKPROJECT_TOOLS:-$root/.tools}"
work="$(mktemp -d)"
if [[ "${APKPROJECT_KEEP_WORK:-0}" == 1 ]]; then
  echo "library matching fixture: $work" >&2
else
  trap 'rm -rf "$work"' EXIT
fi
mirror="https://maven-central.storage-download.googleapis.com/maven2"
mkdir -p "$work/libs" "$work/classes" "$work/out" "$work/libdex"
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
java -Xmx4g -cp "$tools/r8-8.7.18.jar" com.android.tools.r8.R8 --release --min-api 24 --lib "$tools/android-35/android.jar" \
  --output "$work/out" --pg-map-output "$work/mapping.txt" --pg-conf "$here/rules.pro" "$work/app.jar" "$work"/libs/*.jar >/dev/null 2>&1
java -Xmx4g -cp "$tools/r8-8.7.18.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$tools/android-35/android.jar" \
  --output "$work/libdex" "$work"/libs/*.jar >/dev/null 2>&1
(cd "$root" && APKPROJECT_LIBMATCH="$work" ./gradlew -q :app-recovery:test --tests '*LibraryMatchingBenchmark*' --rerun >/dev/null)
grep -v MISSED "$work/eval.txt" | tee "$here/eval.txt"
