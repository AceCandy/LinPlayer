#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_PATH="${1:-}"
STOREPASS="${2:-}"
KEYPASS="${3:-}"
ALIAS="${4:-}"

if [[ -z "${KEYSTORE_PATH:-}" || -z "${STOREPASS:-}" || -z "${KEYPASS:-}" || -z "${ALIAS:-}" ]]; then
  echo "Usage: verify_android_signing.sh <keystorePath> <storepass> <keypass> <alias>" >&2
  exit 2
fi

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "Keystore file not found: $KEYSTORE_PATH" >&2
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/keytool" ]]; then
  echo "JAVA_HOME is not set or keytool missing; cannot verify signing." >&2
  exit 1
fi

echo "Verifying Android signing keystore (alias=$ALIAS)..."

"$JAVA_HOME/bin/keytool" -list \
  -keystore "$KEYSTORE_PATH" \
  -storepass "$STOREPASS" \
  -alias "$ALIAS" \
  >/dev/null

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "ok" > "$TMP_DIR/ok.txt"
"$JAVA_HOME/bin/jar" cf "$TMP_DIR/ok.jar" -C "$TMP_DIR" ok.txt

"$JAVA_HOME/bin/jarsigner" \
  -keystore "$KEYSTORE_PATH" \
  -storepass "$STOREPASS" \
  -keypass "$KEYPASS" \
  "$TMP_DIR/ok.jar" \
  "$ALIAS" \
  >/dev/null

"$JAVA_HOME/bin/jarsigner" -verify "$TMP_DIR/ok.jar" >/dev/null

echo "Android signing keystore verified."

