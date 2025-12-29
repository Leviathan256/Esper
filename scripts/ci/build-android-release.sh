#!/usr/bin/env bash
set -euo pipefail

# Builds an Android release APK and writes the path to stdout.
# Prefer using the Gradle wrapper when it works, but fall back to `gradle`
# (useful in CI if the wrapper JAR isn't checked in).

GRADLE_CMD=""
if [[ -f "./gradlew" ]]; then
  chmod +x ./gradlew
  if ./gradlew -v >/dev/null 2>&1; then
    GRADLE_CMD="./gradlew"
  fi
fi

if [[ -z "${GRADLE_CMD}" ]]; then
  if command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
  else
    echo "ERROR: No working Gradle found. Either commit a working Gradle wrapper (including gradle-wrapper.jar) or ensure `gradle` is installed in CI." >&2
    exit 2
  fi
fi

# Common Android build task; adjust if your module name differs from :app
"${GRADLE_CMD}" :app:assembleRelease

# Resolve the first produced release APK
APK_PATH="$(ls -1 app/build/outputs/apk/release/*.apk 2>/dev/null | head -n 1 || true)"
if [[ -z "${APK_PATH}" ]]; then
  echo "ERROR: No APK found at app/build/outputs/apk/release/*.apk. Update the output glob in scripts/ci/build-android-release.sh." >&2
  exit 3
fi

echo "${APK_PATH}"
