#!/usr/bin/env bash
set -euo pipefail

# Builds an Android release APK and writes the path to stdout.
# This repo currently does not include the Android project files; once they exist,
# this script is intended to be called by GitHub Actions.

if [[ ! -f "./gradlew" ]]; then
  echo "ERROR: ./gradlew not found. Add your Android/Gradle project to this repo, or update scripts/ci/build-android-release.sh for your build system." >&2
  exit 2
fi

chmod +x ./gradlew

# Common Android build task; adjust if your module name differs from :app
./gradlew :app:assembleRelease

# Resolve the first produced release APK
APK_PATH="$(ls -1 app/build/outputs/apk/release/*.apk 2>/dev/null | head -n 1 || true)"
if [[ -z "${APK_PATH}" ]]; then
  echo "ERROR: No APK found at app/build/outputs/apk/release/*.apk. Update the output glob in scripts/ci/build-android-release.sh." >&2
  exit 3
fi

echo "${APK_PATH}"
