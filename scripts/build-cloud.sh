#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/cloud-env.sh
for REQUIRED in "$JAVA_HOME/bin/javac" "$ANDROID_HOME/platforms/android-35/android.jar" "$PWD/tools/gradle-8.11.1/bin/gradle"; do
  if [[ ! -f "$REQUIRED" ]]; then
    printf 'Missing official build tool: %s\nSee docs/DEPENDENCIES.md and docs/TEST_REPORT_V2.md.\n' "$REQUIRED" >&2
    exit 2
  fi
done
exec "$PWD/tools/gradle-8.11.1/bin/gradle" --no-daemon "$@"
