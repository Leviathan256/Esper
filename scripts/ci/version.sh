#!/usr/bin/env bash
# Computes Esper version metadata and writes it to $GITHUB_OUTPUT (or stdout).
#
# versionCode is minutes since 2020-01-01 UTC at the *commit* timestamp, for
# every channel. That gives one monotonically increasing number shared by
# nightly and stable builds, so Android will always accept an install over
# whatever is already on the device regardless of which channel it came from.
# (Android caps versionCode at 2100000000; this scheme has ~4000 years of room.)
#
# Usage:
#   scripts/ci/version.sh nightly
#   scripts/ci/version.sh stable v1.2.3
set -euo pipefail

CHANNEL="${1:?usage: version.sh <nightly|stable> [tag]}"
TAG="${2:-}"

EPOCH_2020=1577836800
COMMIT_EPOCH="$(git show -s --format=%ct HEAD)"
VERSION_CODE=$(( (COMMIT_EPOCH - EPOCH_2020) / 60 ))

if (( VERSION_CODE <= 0 )); then
  echo "ERROR: commit timestamp ${COMMIT_EPOCH} predates the 2020 epoch." >&2
  exit 1
fi

case "${CHANNEL}" in
  stable)
    if [[ -z "${TAG}" ]]; then
      echo "ERROR: stable builds require a tag argument." >&2
      exit 1
    fi
    VERSION_NAME="${TAG#v}"
    ASSET_NAME="Esper.apk"
    ;;
  nightly)
    VERSION_NAME="nightly-$(git rev-parse --short=7 HEAD)"
    ASSET_NAME="Esper-nightly.apk"
    ;;
  *)
    echo "ERROR: unknown channel '${CHANNEL}' (expected nightly or stable)." >&2
    exit 1
    ;;
esac

OUT="${GITHUB_OUTPUT:-/dev/stdout}"
{
  echo "version-name=${VERSION_NAME}"
  echo "version-code=${VERSION_CODE}"
  echo "asset-name=${ASSET_NAME}"
  echo "channel=${CHANNEL}"
} >> "${OUT}"
