#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "${1:-.}" && pwd)
TEST_ROOT=$(cd "$(dirname "$0")" && pwd)
JDK="${JAVA_HOME:-$ROOT/tools/jdk-21.0.2}"
ANDROID_JAR="${ANDROID_JAR:-$ROOT/tools/android-sdk/platforms/android-35/android.jar}"
OUT=$(mktemp -d "${TMPDIR:-/tmp}/focus-ledger-host-test.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/main" "$OUT/test"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUT/main" \
  "$ROOT"/app/src/main/java/cn/focusledger/core/*.java \
  "$ROOT/app/src/main/java/cn/focusledger/LedgerStore.java" \
  "$ROOT/app/src/main/java/cn/focusledger/Reports.java" \
  "$ROOT/app/src/main/java/cn/focusledger/V2Reports.java" \
  "$ROOT/app/src/main/java/cn/focusledger/DailyArchive.java"
"$JDK/bin/javac" --release 17 -cp "$OUT/main:$ANDROID_JAR" -d "$OUT/test" \
  "$TEST_ROOT/cn/focusledger/StoreReportsHostTest.java"
"$JDK/bin/java" -cp "$OUT/test:$OUT/main:$ANDROID_JAR" cn.focusledger.StoreReportsHostTest
python3 "$TEST_ROOT/schema_test.py" "$ROOT"
