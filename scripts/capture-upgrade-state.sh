#!/usr/bin/env bash
# Read-only copy of the app's durable data on a disposable cloud emulator.
# Stop the already-paused app first so SQLite and preferences are quiescent.
# For non-debug official packages, start adb root separately on the AOSP AVD
# and pass --root. This does not modify the APK or add any application backdoor.
set -euo pipefail
DEST=${1:?usage: capture-upgrade-state.sh OUTPUT_DIRECTORY [--root]}
MODE=${2:---run-as}
PKG=cn.goff.shiben
SERIAL=${ANDROID_SERIAL:-}
[[ "$SERIAL" == emulator-* ]] || { echo 'Refusing a non-emulator or implicit adb target' >&2; exit 2; }
[[ "$(adb -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Disposable emulator required' >&2; exit 2; }
[[ ! -e "$DEST" ]] || { echo 'Output already exists; preserve the earlier snapshot' >&2; exit 2; }
mkdir -p "$DEST"
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$SERIAL" shell dumpsys package "$PKG" > "$DEST/package.txt"
if [[ "$MODE" == --root ]]; then
    [[ "$(adb -s "$SERIAL" shell id -u | tr -d '\r')" == 0 ]] || { echo 'AOSP emulator adb is not root' >&2; exit 2; }
    adb -s "$SERIAL" exec-out sh -c "cd /data/user/0/$PKG && tar cf - databases shared_prefs files/daily-markdown-v2" > "$DEST/state.tar"
else
    [[ "$MODE" == --run-as ]] || exit 2
    adb -s "$SERIAL" exec-out run-as "$PKG" tar cf - databases shared_prefs files/daily-markdown-v2 > "$DEST/state.tar"
fi
tar xf "$DEST/state.tar" -C "$DEST"
sha256sum "$DEST/state.tar" > "$DEST/state.tar.sha256"
printf 'Captured durable database, daily Markdown and preferences in %s\n' "$DEST"
