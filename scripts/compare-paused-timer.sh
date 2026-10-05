#!/usr/bin/env bash
# The input files must be snapshots from our disposable emulator, never untrusted downloads.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BEFORE=${1:?pre-upgrade snapshot directory required}
AFTER=${2:?post-upgrade snapshot directory required}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/shiben-timer-compare.XXXXXX")
trap 'rm -rf "$OUT"' EXIT
python3 - "$BEFORE" "$AFTER" "$OUT" <<'PY'
from pathlib import Path
import sqlite3, sys
for side, root in zip(('before','after'), sys.argv[1:3]):
    with sqlite3.connect(Path(root)/'databases/focus-ledger.db') as db:
        rows = db.execute('SELECT record FROM timer_state WHERE singleton=1').fetchall()
    assert len(rows) == 1, f'{side}: one paused timer expected'
    (Path(sys.argv[3]) / f'{side}.bin').write_bytes(rows[0][0])
PY
JDK=${JAVA_HOME:-$ROOT/tools/jdk-21.0.2}
"$JDK/bin/javac" --release 17 -d "$OUT/classes" "$ROOT"/app/src/main/java/cn/focusledger/core/*.java "$ROOT/tests/upgrade/ComparePausedTimer.java"
"$JDK/bin/java" -cp "$OUT/classes" ComparePausedTimer "$OUT/before.bin" "$OUT/after.bin"
