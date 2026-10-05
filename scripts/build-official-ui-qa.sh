#!/usr/bin/env bash
# Compile a shell-run UIAutomator dex jar. Never signs, installs, launches an
# emulator, calls adb, or changes production code / APKs / Gradle configuration.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source scripts/cloud-env.sh
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
UIAUTOMATOR_JAR="$ANDROID_HOME/platforms/android-35/uiautomator.jar"
JUNIT_JAR=$(find "$GRADLE_USER_HOME/caches/modules-2/files-2.1/junit/junit/4.13.2" -name junit-4.13.2.jar -type f -print -quit)
D8="$ANDROID_HOME/build-tools/35.0.0/d8"
for required in "$JAVA_HOME/bin/javac" "$JAVA_HOME/bin/jar" "$ANDROID_JAR" "$UIAUTOMATOR_JAR" "$JUNIT_JAR" "$D8"; do
  if [[ ! -f "$required" ]]; then
    printf 'Missing already-installed compile dependency: %s\n' "$required" >&2
    exit 2
  fi
done
OUTPUT="$ROOT/artifacts/v2.0.1/runtime/official-ui-qa.jar"
mkdir -p "$ROOT/build" "$(dirname "$OUTPUT")"
TEMP=$(mktemp -d "$ROOT/build/official-ui-qa.XXXXXX")
trap 'rm -rf -- "$TEMP"' EXIT
mkdir -p "$TEMP/classes" "$TEMP/dex"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -classpath "$ANDROID_JAR:$UIAUTOMATOR_JAR:$JUNIT_JAR" \
  -d "$TEMP/classes" tests/upgrade/OfficialUpgradeUi.java
"$JAVA_HOME/bin/jar" --create --file "$TEMP/classes.jar" -C "$TEMP/classes" .
"$D8" --release --min-api 26 --lib "$ANDROID_JAR" \
  --classpath "$UIAUTOMATOR_JAR" --classpath "$JUNIT_JAR" \
  --output "$TEMP/dex" "$TEMP/classes.jar"
"$JAVA_HOME/bin/jar" --create --file "$OUTPUT" -C "$TEMP/dex" classes.dex
"$JAVA_HOME/bin/jar" --list --file "$OUTPUT"
sha256sum "$OUTPUT"
printf '\nCompiled only; not run. On the authorized disposable emulator:\n'
printf '  adb push %s /data/local/tmp/\n' "${OUTPUT#"$ROOT"/}"
printf '  adb shell uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/official-ui-qa.jar -c cn.focusledger.qa.OfficialUpgradeUi -e mode seed\n'
printf '  # Install the same-certificate official code4 APK externally with adb install -r.\n'
printf '  adb shell uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/official-ui-qa.jar -c cn.focusledger.qa.OfficialUpgradeUi -e mode verify\n'
printf '  adb pull /data/local/tmp/shiben-official-upgrade\n'
