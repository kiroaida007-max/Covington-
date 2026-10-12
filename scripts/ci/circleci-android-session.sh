#!/usr/bin/env bash
set -euo pipefail
test -n "${ANDROID_HOME:-}"
if ! test -r /dev/kvm || ! test -w /dev/kvm; then
  echo 'The Android machine needs readable/writable KVM; software emulation is not accepted.' >&2
  exit 1
fi
sdkmanager 'emulator' 'system-images;android-30;default;x86_64'
printf 'no\n' | avdmanager create avd --force --name covington_ci_api30 --package 'system-images;android-30;default;x86_64'
mkdir -p runs/runtime
"$ANDROID_HOME/emulator/emulator" -avd covington_ci_api30 -port 5554 -accel on -no-window \
  -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim >runs/runtime/emulator.log 2>&1 &
emulator_pid=$!
cleanup() {
  "$ANDROID_HOME/platform-tools/adb" -s emulator-5554 emu kill >/dev/null 2>&1 || true
  if kill -0 "$emulator_pid" 2>/dev/null; then kill "$emulator_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM
adb="$ANDROID_HOME/platform-tools/adb"
timeout 360 "$adb" -s emulator-5554 wait-for-device
deadline=$((SECONDS + 360))
while [[ "$($adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" != '1' ]]; do
  kill -0 "$emulator_pid"
  if (( SECONDS >= deadline )); then echo 'Android boot deadline exceeded' >&2; exit 1; fi
  sleep 2
done
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$adb" -s emulator-5554 shell settings put global "$scale" 0
done
bash --noprofile --norc -e -o pipefail "$1"
