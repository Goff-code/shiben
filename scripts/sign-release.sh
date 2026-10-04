#!/usr/bin/env bash
# Standard release signing with YOUR key. No private key or password is committed.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
: "${KEYSTORE:?Set KEYSTORE to your private signing keystore path}"
: "${KEY_ALIAS:?Set KEY_ALIAS to its alias}"
if [[ -n "${KEYSTORE_PASSWORD_FILE:-}" ]]; then
  [[ -r "$KEYSTORE_PASSWORD_FILE" ]] || { echo 'Password file is not readable' >&2; exit 1; }
  PASSWORD_SOURCE="file:$KEYSTORE_PASSWORD_FILE"
else
  : "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD or KEYSTORE_PASSWORD_FILE securely}"
  PASSWORD_SOURCE="env:KEYSTORE_PASSWORD"
fi
ALIGNED="$(mktemp "${TMPDIR:-/tmp}/shiben-aligned.XXXXXX.apk")"
trap 'rm -f "$ALIGNED"' EXIT
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk "$ALIGNED"
# apksigner first tries the store password for the private key. Passing one file
# for both options would read two lines; our PKCS12 store uses one shared password.
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "$PASSWORD_SOURCE" --out shiben-release.apk "$ALIGNED"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs shiben-release.apk
