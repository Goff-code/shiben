#!/usr/bin/env bash
set -euo pipefail
QA_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${QA_WORK:?}" "${QA_OUT:?}" "${JAVA_HOME:?}" "${ANDROID_HOME:?}"
BASE=b1838a8fb66b74b13c278991d097b9be48155688
OLD=bdbfafa8c4ec4344ae171ba1761f6ffea7017555
mkdir -p "$QA_WORK" "$QA_OUT/apks"
# Build source from the pinned genuine histories, never the mutable test branch.
for pair in "current:$BASE" "old202:$OLD"; do
  target=${pair%%:*}; revision=${pair#*:}
  if [[ -d "$QA_WORK/$target" ]]; then
    [[ $(git -C "$QA_WORK/$target" rev-parse HEAD) == "$revision" ]]
    git -C "$QA_WORK/$target" diff --exit-code
  else
    git -C "$QA_ROOT" worktree add --detach "$QA_WORK/$target" "$revision"
  fi
done
[[ $(git -C "$QA_WORK/current" rev-parse HEAD^{tree}) == a98e365ba03ed9622e17a43d9398853df2efa014 ]]
[[ $(git -C "$QA_WORK/old202" rev-parse HEAD^{tree}) == 063e7b57ad73b3c6f1cb8aef8f1d23023c2c414b ]]
GRADLE="$QA_TOOLS/gradle/bin/gradle"
COMMON=(--no-daemon --max-workers=2 -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs=-Xmx2048m)
"$GRADLE" -p "$QA_WORK/current" "${COMMON[@]}" testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest > "$QA_OUT/build-current.log" 2>&1
cp "$QA_WORK/current/app/build/outputs/apk/debug/app-debug.apk" "$QA_OUT/apks/current.apk"
cp "$QA_WORK/current/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$QA_OUT/apks/current-tests.apk"
FIXTURE="$QA_ROOT/tests/actions-upgrade/fixtures.init.gradle"
"$GRADLE" -p "$QA_WORK/old202" "${COMMON[@]}" assembleDebug > "$QA_OUT/build-old202-plain.log" 2>&1
cp "$QA_WORK/old202/app/build/outputs/apk/debug/app-debug.apk" "$QA_OUT/apks/old202-plain.apk"
for target in old202 current; do
  "$GRADLE" -p "$QA_WORK/$target" "${COMMON[@]}" -I "$FIXTURE" \
    -PactionsUpgradeFixtures="$QA_ROOT/tests/actions-upgrade" assembleDebug assembleDebugAndroidTest > "$QA_OUT/build-upgrade-$target.log" 2>&1
  cp "$QA_WORK/$target/app/build/outputs/apk/debug/app-debug.apk" "$QA_OUT/apks/$target-upgrade.apk"
  cp "$QA_WORK/$target/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" "$QA_OUT/apks/$target-upgrade-tests.apk"
done
# A test-source overlay must not alter the target app bytes.
cmp "$QA_OUT/apks/current.apk" "$QA_OUT/apks/current-upgrade.apk"
cmp "$QA_OUT/apks/old202-plain.apk" "$QA_OUT/apks/old202-upgrade.apk"
for apk in "$QA_OUT"/apks/*.apk; do
  "$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$apk" > "$apk.cert.txt"
  "$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$apk" > "$apk.badging.txt"
  "$ANDROID_HOME/build-tools/35.0.0/aapt" dump xmltree "$apk" AndroidManifest.xml > "$apk.manifest.txt"
done
python3 "$QA_ROOT/scripts/actions/verify-build.py" "$QA_OUT" "$QA_WORK/current"
git -C "$QA_WORK/current" diff --exit-code
git -C "$QA_WORK/old202" diff --exit-code
