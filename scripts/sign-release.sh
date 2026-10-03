#!/usr/bin/env bash
# Standard release signing with YOUR key. No private key or password is committed.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
: "${KEYSTORE:?Set KEYSTORE to your private signing keystore path}"
: "${KEY_ALIAS:?Set KEY_ALIAS to its alias}"
: "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD securely in your local environment}"
ALIGNED="$(mktemp "${TMPDIR:-/tmp}/focus-ledger-aligned.XXXXXX.apk")"
trap 'rm -f "$ALIGNED"' EXIT
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk "$ALIGNED"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEYSTORE_PASSWORD --out focus-ledger-release.apk "$ALIGNED"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose focus-ledger-release.apk
