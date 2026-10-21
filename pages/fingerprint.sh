#!/usr/bin/env bash
# Prints the release cert's SHA-256 in the two forms the passkey setup needs:
#   assetlinks      -> sha256_cert_fingerprints in .well-known/assetlinks.json
#   passkey origin  -> ESPER_RP_ORIGINS on the server
# Usage: pages/fingerprint.sh <keystore> <alias> <password>   (or env KS_PASS)
set -euo pipefail
ks="${1:?keystore path}"; alias="${2:-esper}"; pass="${3:-${KS_PASS:?password}}"
colon="$(keytool -list -v -keystore "$ks" -alias "$alias" -storepass "$pass" |
  grep -m1 'SHA256:' | sed 's/.*SHA256: *//' | tr -d ' ')"
[[ "$colon" =~ ^([0-9A-F]{2}:){31}[0-9A-F]{2}$ ]] || { echo "no SHA256 fingerprint found" >&2; exit 1; }
echo "assetlinks:     $colon"
echo "passkey origin: android:apk-key-hash:$(printf '%s' "${colon//:/}" | xxd -r -p | base64 -w0 | tr '+/' '-_' | tr -d '=')"
