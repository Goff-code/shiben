#!/usr/bin/env bash
# Run this in a persistent terminal (exec_command tty=true). The shell, emulator,
# and adb must share the same execution sandbox's process/network namespace.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/cloud-env.sh
scripts/start-cloud-emulator.sh || printf "Boot did not complete yet; emulator console remains available for diagnosis.\n"
export ANDROID_SERIAL="emulator-${SHIBEN_EMULATOR_PORT:-5554}"
export PS1='shiben-emulator$ '
exec bash --noprofile --norc
