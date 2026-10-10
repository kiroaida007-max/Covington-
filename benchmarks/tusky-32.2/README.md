# Tusky 32.2 source restoration

Tusky's optimized APK does not retain enough source information for the current decompiler to
produce a fully compilable Java project. Its published source provides a separate, reproducible
build path. This fixture restores **published upstream source**, not Java reconstructed solely
from the APK. No failed method is replaced by a stub to obtain a successful build.

`source-lock.json` pins the F-Droid APK hash and the exact upstream Git commit identified by
F-Droid's version-142 build entry. The production source, license, resources, Kotlin/Compose setup and
dependency versions remain unchanged. `unsigned.init.gradle` removes the need for the original
distributor's signing key for release builds. Debug builds use a development signature.

## Build

Use Python 3.11+, Git, a **full JDK 21** (including `javac`), and an Android SDK containing
`platforms;android-36`, `build-tools;35.0.0` and `platform-tools`. Android Studio's SDK Manager or
`sdkmanager` can install these packages. Gradle 9.0.0 is downloaded by Tusky's pinned wrapper.

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
python3 scripts/restore-tusky.py /path/to/com.keylesspalace.tusky_142.apk \
  --out runs/tusky-32.2-restored --test --fix-test-harness
```

The default builds blueDebug and an unsigned blueRelease APK. `--variant debug` or
`--variant release` restricts the build. `--test` runs the upstream blueDebug unit tests.
`--fix-test-harness` explicitly enables the recorded test-only patch described below.
The script passes the environment through to Gradle; behind a proxy, configure the usual
Gradle proxy properties through `GRADLE_OPTS` or your Gradle user properties. Build scans are
disabled. No release keys or publishing credentials are needed.

The **Tusky source restoration** GitHub Actions workflow runs the same build when this workflow
changes in a pull request, and supports manual runs after it is available on the default branch.
Its `tusky-32.2-build-and-evidence` artifact contains the APKs, corresponding upstream source,
R8 mapping, build provenance, logs and test reports. Build/test failures remain failed workflow
runs even though diagnostic artifacts are uploaded.

An existing output is accepted only if it is an unchanged checkout at the pinned commit and
origin. The script does not reset another revision or overwrite modified tracked source files.
A mismatched input APK is rejected before source checkout or build execution.

Outputs:

- `app/build/outputs/apk/blue/debug/`: development APK.
- `app/build/outputs/apk/blue/release/`: unsigned release APK.
- `app/build/outputs/mapping/blueRelease/mapping.txt`: the release build's R8 mapping.
- `.restoration/build.json`: status, provenance, tool versions, artifact hashes and byte comparisons.
- `.restoration/gradle.log`: complete build output.
- `app/build/reports/tests/testBlueDebugUnitTest/`: upstream unit-test results when requested.

The report compares every DEX payload, native library and uncompressed ZIP entry with the original APK. The release
mapping is marked valid for the input only if **all DEX names and bytes match exactly**. Matching
a version number or successfully compiling does not establish that mapping correspondence.
DEX equality does not establish identical resources, signatures or runtime behavior.

## Measured result

On 2026-10-10, both **blueDebug** and optimized **blueRelease** built successfully. All **460 tests
passed**, with zero skips, using the explicit test harness patch below. The two timeline tests
also passed in three consecutive isolated runs. All nine restoration safeguard tests pass.

The unsigned release has **1,500 ZIP entries, all byte-identical to their counterparts in the
original APK**, including `classes.dex`, all 16 native libraries, the manifest and resources.
Only the original distributor's three v1 signature entries are absent: `META-INF/C38DDDB2.RSA`,
`META-INF/C38DDDB2.SF` and `META-INF/MANIFEST.MF`. APK signing blocks, ZIP headers/order and alignment
are outside this comparison. This establishes an exact match of the compared contents, not an
identical signed APK or a device runtime test.

The generated **R8 8.13.19 mapping is valid for the original input DEX**, because its entire DEX
payload is identical. The source checkout is clean after the temporary test patch is reversed.
Full machine-readable measurements are in [results.json](results.json). The older APK-only Java
export had 13,328 ECJ errors against Android 36; the current APK-only export has 10,122 against
Android 35 (see the README). Neither is counted as repaired by this restoration.

## Timeline test harness

The unmodified suite exhibited a timeout in the newest-first paging test (459/460 tests passed).
An isolated rerun instead returned an empty initial snapshot in the oldest-first test. These
tests create a ViewModel without assigning its main dispatcher to `runTest`'s scheduler or
clearing the ViewModel after the test. `test-dispatcher.patch` installs a `StandardTestDispatcher`
and uses a `ViewModelStore` to cancel the cached paging flow and event collectors on teardown.
Every assertion and all production source remain unchanged.

With `--fix-test-harness`, the script records the patch hash, applies it only during the build,
and reverses it on success or failure. Cleanup refuses to overwrite concurrent source edits.
Omit that flag to measure the exact unmodified upstream tests. Patched-harness results must not
be described as an unmodified upstream test pass.

## Evidence checks

```sh
python3 -m unittest discover -s scripts/tests -v
```

These tests cover mismatched inputs, changed/missing/additional DEX files, duplicate ZIP names,
native-library differences, ZIP-layout independence, and preserving local changes or a checkout
with a different origin or revision. They also verify temporary patch cleanup after a failed
build and refusal to overwrite edits made during a build.
Actual build and compile-diagnostic measurements are recorded in `results.json`.
