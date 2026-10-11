#!/usr/bin/env bash
# Only the fresh AVD created below is touched. No phone, shared profile, or release key.
set -euo pipefail
QA_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${QA_WORK:?}" "${QA_OUT:?}" "${ANDROID_HOME:?}" "${ANDROID_AVD_HOME:?}"
export ANDROID_SERIAL=emulator-5554
PKG=cn.goff.shiben
RUNNER="$PKG.test/cn.focusledger.LedgerInstrumentation"
AVD=shiben-actions-api35
QEMU_PID=''
SYSTEM_PID=''
mkdir -p "$QA_OUT/runtime"
adbq() { timeout --signal=TERM --kill-after=3s 20s adb -s "$ANDROID_SERIAL" "$@"; }
cleanup() {
  code=$?
  trap - EXIT
  set +e
  # Never touch an existing emulator when a preflight check fails.
  if [[ -n "$QEMU_PID" ]] && kill -0 "$QEMU_PID" 2>/dev/null \
      && timeout 2s adb -s "$ANDROID_SERIAL" emu avd name 2>/dev/null | tr -d '\r' | grep -Fxq "$AVD"; then
    if ((code != 0)); then
      timeout --signal=TERM --kill-after=1s 6s python3 "$QA_ROOT/scripts/actions/collect-device.py" \
        "$QA_OUT/runtime/failure-device" --optional qa-actions-upgrade qa-v2 qa-v203 qa-reminders qa-graffiti \
        > "$QA_OUT/runtime/failure-collection.log" 2>&1
    fi
    timeout 2s adb -s "$ANDROID_SERIAL" logcat -d -b all -t 12000 -v threadtime > "$QA_OUT/runtime/system-final.log" 2>&1
    timeout 2s adb -s "$ANDROID_SERIAL" shell dumpsys notification --noredact > "$QA_OUT/runtime/notification-final.txt" 2>&1
    timeout 2s adb -s "$ANDROID_SERIAL" exec-out screencap -p > "$QA_OUT/runtime/final-screen.png"
    timeout 2s adb -s "$ANDROID_SERIAL" emu kill > "$QA_OUT/runtime/stop.txt" 2>&1
  fi
  if [[ -n "$QEMU_PID" ]]; then
    if kill -0 "$QEMU_PID" 2>/dev/null; then kill -TERM "$QEMU_PID"; fi
    sleep 1
    if kill -0 "$QEMU_PID" 2>/dev/null; then kill -KILL "$QEMU_PID"; fi
    wait "$QEMU_PID" 2>/dev/null
  fi
  printf '%s\n' "$code" > "$QA_OUT/runtime/exit-code.txt"
  exit "$code"
}
trap cleanup EXIT
trap 'exit 124' TERM INT

[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_OS:-} == Linux ]]
[[ -c /dev/kvm && -r /dev/kvm && -w /dev/kvm ]] || { echo 'KVM unavailable or not permitted; no software fallback' >&2; exit 2; }
timeout 15s "$ANDROID_HOME/emulator/emulator" -accel-check | tee "$QA_OUT/runtime/acceleration.txt"
[[ ! -e "$ANDROID_AVD_HOME/$AVD.avd" ]]
[[ -z $(adb devices | awk 'NR>1 && NF {print $1}') ]] || { echo 'Another device exists; refusing' >&2; exit 2; }
printf 'no\n' | timeout 30s "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
  --name "$AVD" --package 'system-images;android-35;default;x86_64' --device pixel_2
python3 - "$ANDROID_AVD_HOME/$AVD.avd/config.ini" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); d=dict(line.split('=',1) for line in p.read_text().splitlines() if '=' in line)
d.update({'hw.lcd.width':'480','hw.lcd.height':'800','hw.lcd.density':'160',
 'hw.ramSize':'2560','hw.cpu.ncore':'2','vm.heapSize':'576','hw.keyboard':'yes',
 'showDeviceFrame':'no','disk.dataPartition.size':'3G','hw.gpu.enabled':'yes','hw.gpu.mode':'swiftshader'})
p.write_text(''.join(k.strip()+'='+v.strip()+'\n' for k,v in d.items()))
PY
cp "$ANDROID_AVD_HOME/$AVD.avd/config.ini" "$QA_OUT/runtime/avd-config.ini"
"$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port 5554 -accel on -cores 2 -memory 2560 \
  -no-window -no-audio -no-boot-anim -no-metrics -no-snapshot -gpu swiftshader -feature -Vulkan \
  -camera-back none -camera-front none -show-kernel \
  > "$QA_OUT/runtime/emulator.log" 2>&1 &
QEMU_PID=$!
deadline=$((SECONDS+180))
booted=false
while ((SECONDS < deadline)); do
  kill -0 "$QEMU_PID" 2>/dev/null || { echo 'Emulator exited during boot' >&2; exit 1; }
  if [[ $(adbq shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then booted=true; break; fi
  sleep 2
done
[[ $booted == true ]] || { echo 'Boot timed out; no reboot/retry' >&2; exit 124; }
[[ $(adbq shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
[[ $(adbq shell getprop ro.build.version.sdk | tr -d '\r') == 35 ]]
SYSTEM_PID=$(adbq shell pidof system_server | tr -d '\r')
[[ $SYSTEM_PID =~ ^[0-9]+$ ]]
sleep 20
[[ $(adbq shell pidof system_server | tr -d '\r') == "$SYSTEM_PID" ]]
adbq shell getprop > "$QA_OUT/runtime/guest-properties.txt"
adbq shell pm path android > "$QA_OUT/runtime/android-package.txt"
adbq shell input keyevent 82
adbq exec-out screencap -p > "$QA_OUT/runtime/boot-ready.png"

suite() {
  name=$1; limit=$2; runner=${3:-$RUNNER}; option=${4:-suite}
  [[ $(adbq shell pidof system_server | tr -d '\r') == "$SYSTEM_PID" ]] || { echo 'system_server restarted; stop' >&2; exit 1; }
  log="$QA_OUT/runtime/$name.log"
  printf 'Running %s, max %s seconds\n' "$name" "$limit"
  set +e
  timeout --signal=TERM --kill-after=10s "${limit}s" adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e "$option" "$name" "$runner" > "$log" 2>&1
  result=$?
  set -e
  cat "$log"
  python3 "$QA_ROOT/scripts/actions/check-instrumentation.py" "$log" "$result"
  if [[ $option == phase ]]; then grep -Fxq "ACTIONS_UPGRADE_PASS phase=$name" "$log"; fi
  [[ $(adbq shell pidof system_server | tr -d '\r') == "$SYSTEM_PID" ]]
}
collect() { destination=$1; shift; python3 "$QA_ROOT/scripts/actions/collect-device.py" "$QA_OUT/runtime/$destination" "$@"; }
fresh() {
  # All installs here are on this script's disposable AVD. No clear/uninstall occurs inside upgrade.
  if adbq shell pm path "$PKG" | grep -q '^package:'; then
    adbq uninstall "$PKG.test"
    adbq uninstall "$PKG"
  fi
}
adbq install -g "$QA_OUT/apks/current.apk"
adbq install -g "$QA_OUT/apks/current-tests.apk"
suite graffiti 180
collect grade-previews qa-graffiti
suite reminders 300
suite gallery 180
collect reminder-gallery qa-reminders

fresh before-general-reset
# Do not grant notification permission: the original all-suite exercises denied permission.
adbq install "$QA_OUT/apks/current.apk"
adbq install "$QA_OUT/apks/current-tests.apk"
suite all 240
suite gallery_ui 120
suite gallery_view 90
collect ui-gallery qa-v2 qa-v203

fresh before-upgrade-install
adbq install -g "$QA_OUT/apks/old202-upgrade.apk"
adbq install -g "$QA_OUT/apks/old202-upgrade-tests.apk"
UPGRADE="$PKG.test/cn.focusledger.ActionsUpgradeInstrumentation"
suite seed 180 "$UPGRADE" phase
collect upgrade-before qa-actions-upgrade
adbq shell am force-stop "$PKG"
adbq shell dumpsys notification --noredact > "$QA_OUT/runtime/upgrade-before-channels.txt"
# No uninstall, clear, grant/revoke, restore, clock change, or app data injection across this boundary.
adbq install -r "$QA_OUT/apks/current-upgrade.apk"
adbq install -r "$QA_OUT/apks/current-upgrade-tests.apk"
suite verify 120 "$UPGRADE" phase
collect upgrade-after qa-actions-upgrade
adbq shell dumpsys notification --noredact > "$QA_OUT/runtime/upgrade-after-channels.txt"
suite routing 150 "$UPGRADE" phase
collect upgrade-routing qa-actions-upgrade
printf 'All requested suites finished successfully on this API35 emulator. Formal-release certificate upgrade and realme behavior remain untested.\n' > "$QA_OUT/runtime/completed.txt"
