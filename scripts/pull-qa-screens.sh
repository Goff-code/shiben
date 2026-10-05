#!/usr/bin/env bash
# Pull only completed native screenshots, preserving earlier host copies on failures.
set -euo pipefail
OUT=${1:?output directory required}
[[ "${ANDROID_SERIAL:-}" == emulator-* ]] || { echo 'Explicit emulator serial required' >&2; exit 2; }
mkdir -p "$OUT"
TMP=$(mktemp "$OUT/.screens.XXXXXX.tar")
trap 'rm -f "$TMP"' EXIT
adb -s "$ANDROID_SERIAL" exec-out run-as cn.goff.shiben tar cf - files/qa-v2 > "$TMP"
tar tf "$TMP" >/dev/null
tar xf "$TMP" -C "$OUT" --exclude='*.tmp'
python3 - "$OUT/files/qa-v2" <<'PY'
from pathlib import Path
from PIL import Image
import sys
images = sorted(Path(sys.argv[1]).glob('*.png'))
assert images, 'No completed screenshot PNGs found'
for path in images:
    with Image.open(path) as image:
        size = image.size
        image.verify()
    print(f'PASS complete native screenshot {path.name}: {size[0]}x{size[1]}')
PY
mv "$TMP" "$OUT/screens.tar"
