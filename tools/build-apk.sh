#!/usr/bin/env bash
# Build the debug APK with the out-of-tree toolchain.
#
#   bash tools/build-apk.sh
#
# Environment:
#   DSH_BUILD_ENV   toolchain root          (default D:/harness/.build-env)
#   JAVA_HOME       JDK 17+                 (default: the Microsoft JDK 21 on PATH)
#
# The SDK location goes into local.properties (forward slashes: Java properties
# treat backslash as an escape, and "D:\harness" silently becomes "D:harness").
set -euo pipefail

ROOT="${DSH_BUILD_ENV:-D:/harness/.build-env}"
SDK="$ROOT/android-sdk"
GRADLE="$(ls -d "$ROOT"/gradle-*/bin/gradle 2>/dev/null | tail -1)"

if [ -z "$GRADLE" ]; then
  echo "!! no Gradle under $ROOT - run: bash tools/bootstrap-toolchain.sh" >&2
  exit 1
fi
if [ ! -d "$SDK/platforms/android-35" ]; then
  echo "!! SDK platform 35 missing under $SDK - run: bash tools/bootstrap-toolchain.sh" >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.10.7-hotspot"
fi

printf 'sdk.dir=%s\n' "$SDK" > local.properties

cd "$(dirname "$0")/.."
"$GRADLE" :app:assembleDebug --no-daemon --console=plain "$@"

APK="app/build/outputs/apk/debug/app-debug.apk"
echo
echo "built: $APK"
ls -lh "$APK"
