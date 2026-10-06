#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/cloud-env.sh
# Use a dedicated disposable AVD; never a user phone or an existing production profile.
if [[ ! -f "$ANDROID_AVD_HOME/shiben-202-clean-low.avd/config.ini" ]]; then
  printf 'no\n' | avdmanager create avd --name shiben-202-clean-low --package 'system-images;android-35;default;x86_64' --device pixel_2
fi
C="$ANDROID_AVD_HOME/shiben-202-clean-low.avd/config.ini"
python3 - "$C" <<'PY'
import sys
p=sys.argv[1];lines=open(p).read().splitlines();d={x.split('=',1)[0].strip():x.split('=',1)[1].strip() for x in lines if '=' in x}
d.update({'hw.lcd.width':'480','hw.lcd.height':'800','hw.lcd.density':'160','hw.ramSize':'2048','hw.cpu.ncore':'1','hw.keyboard':'yes','showDeviceFrame':'no','disk.dataPartition.size':'3G','hw.gpu.enabled':'yes','hw.gpu.mode':'swiftshader'})
open(p,'w').write(''.join(k+' = '+v+'\n' for k,v in d.items()))
PY
O="$PWD/artifacts/v2.0.2/official-clean"
mkdir -p "$O"
if adb -s emulator-5554 get-state >/dev/null 2>&1; then
 echo "An emulator is already connected on port 5554; preserve it and stop here." >&2
 exit 2
fi
nohup emulator -avd shiben-202-clean-low -port 5554 -no-window -no-audio -no-boot-anim -no-metrics -no-snapshot -accel off -gpu swiftshader -feature -Vulkan -memory 2048 -cores 1 -camera-back none -camera-front none > "$O/emulator.log" 2>&1 &
echo $! > "$O/emulator.pid"
for ((i=0;i<180;i++)); do
 if [[ "$(timeout 15 adb -s emulator-5554 shell -n getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
  adb -s emulator-5554 shell -n wm size 480x800
  adb -s emulator-5554 shell -n wm density 160
  for K in window_animation_scale transition_animation_scale animator_duration_scale; do adb -s emulator-5554 shell -n settings put global "$K" 0; done
  timeout 15 adb -s emulator-5554 shell -n svc bluetooth disable || true
  adb -s emulator-5554 shell -n input keyevent 82
  echo BOOT_READY_BACKGROUND_SETTLING
  sleep 120
  adb -s emulator-5554 shell -n pidof system_server > "$O/system-pid-stable-before.txt"
  sleep 30
  adb -s emulator-5554 shell -n pidof system_server > "$O/system-pid-stable-after.txt"
  cmp "$O/system-pid-stable-before.txt" "$O/system-pid-stable-after.txt"
  adb -s emulator-5554 shell -n pm path android
  adb -s emulator-5554 exec-out screencap -p > "$O/settled-system.png"
  echo CONTROLLED_AVD_READY
  exit 0
 fi
 kill -0 "$(cat "$O/emulator.pid")" || exit 1
 echo "Waiting for controlled boot, iteration $i"
 sleep 5
done
exit 1
