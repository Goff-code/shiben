#!/usr/bin/env bash
# Starts only the named disposable cloud AVD. No user device or release key is used.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/cloud-env.sh
API=${SHIBEN_EMULATOR_API:-29}
AVD="shiben-v2-api$API"
PORT=${SHIBEN_EMULATOR_PORT:-5554}
SERIAL="emulator-$PORT"
LOG_DIR="$PWD/artifacts/v2/api$API"
mkdir -p "$LOG_DIR"
if [[ ! -f "$ANDROID_AVD_HOME/$AVD.avd/config.ini" ]]; then
  printf 'no\n' | avdmanager create avd --name "$AVD" --package "system-images;android-$API;default;x86_64" --device pixel_2
  cat >> "$ANDROID_AVD_HOME/$AVD.avd/config.ini" <<'CONFIG'
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=320
hw.keyboard=yes
showDeviceFrame=no
CONFIG
fi
adb start-server >/dev/null
if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
  nohup emulator -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-metrics -show-kernel \
    -no-snapshot -accel off -gpu swiftshader_indirect -memory 2048 -cores 4 \
    -camera-back none -camera-front none > "$LOG_DIR/emulator.log" 2>&1 &
  echo $! > "$LOG_DIR/emulator.pid"
fi
for ((i=0;i<120;i++)); do
  if [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    adb -s "$SERIAL" shell wm size 720x1280
    adb -s "$SERIAL" shell wm density 320
    adb -s "$SERIAL" shell settings put global window_animation_scale 0
    adb -s "$SERIAL" shell settings put global transition_animation_scale 0
    adb -s "$SERIAL" shell settings put global animator_duration_scale 1
    adb -s "$SERIAL" shell svc bluetooth disable >/dev/null 2>&1 || true
    adb -s "$SERIAL" shell input keyevent 82
    printf 'Disposable cloud emulator ready: %s (%s)\n' "$SERIAL" "$AVD"
    exit 0
  fi
  if [[ -f "$LOG_DIR/emulator.pid" ]] && ! kill -0 "$(cat "$LOG_DIR/emulator.pid")" 2>/dev/null; then
    cat "$LOG_DIR/emulator.log" >&2
    exit 1
  fi
  if ((i % 12 == 0)); then
    printf "Waiting for %s boot (%s seconds); adb: " "$AVD" "$((i * 5))"
    adb -s "$SERIAL" get-state 2>/dev/null || true
  fi
  sleep 5
done
printf 'Emulator did not report completed boot; inspect %s/emulator.log\n' "$LOG_DIR" >&2
exit 1
