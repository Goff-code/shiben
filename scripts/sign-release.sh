#!/usr/bin/env bash
# Run only on the user's computer after explicit approval of the release identity.
# Never creates a key. Passwords are entered locally or supplied by a protected file.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your local Android SDK}"
: "${KEYSTORE:?Set KEYSTORE to your private V2 signing keystore path}"
: "${KEY_ALIAS:?Set KEY_ALIAS to the existing V2 key alias}"
INPUT="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
OUTPUT="${2:-shiben-release.apk}"
[[ -r "$INPUT" ]] || { echo 'Unsigned APK is missing' >&2; exit 1; }
[[ -r "$KEYSTORE" ]] || { echo 'Local release keystore is missing' >&2; exit 1; }
ALIGNED="$(mktemp "${TMPDIR:-/tmp}/shiben-aligned.XXXXXX.apk")"
trap 'rm -f "$ALIGNED"' EXIT
BT="$ANDROID_HOME/build-tools/35.0.0"
"$BT/zipalign" -f -p 4 "$INPUT" "$ALIGNED"
ARGS=(sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --out "$OUTPUT")
if [[ -n "${KEYSTORE_PASSWORD_FILE:-}" ]]; then
  [[ -r "$KEYSTORE_PASSWORD_FILE" ]] || { echo 'Protected password file is not readable' >&2; exit 1; }
  ARGS+=(--ks-pass "file:$KEYSTORE_PASSWORD_FILE")
fi
# No --ks-pass means apksigner prompts securely in the local terminal.
"$BT/apksigner" "${ARGS[@]}" "$ALIGNED"
"$BT/apksigner" verify --verbose --print-certs "$OUTPUT"
"$BT/zipalign" -c -p 4 "$OUTPUT"
printf '\nSigned APK: %s\nDo not upload the keystore or password. Keep the same V2 key for every update.\n' "$OUTPUT"
