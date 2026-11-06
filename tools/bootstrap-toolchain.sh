#!/usr/bin/env bash
# Install the Android build toolchain this project needs, into an out-of-tree
# directory (default D:/harness/.build-env, override with DSH_BUILD_ENV).
#
#   bash tools/bootstrap-toolchain.sh
#
# Idempotent: existing Gradle/SDK pieces are left alone. Needs curl + unzip,
# ~800 MB of disk and a JDK 17+ (the script only checks for one).
set -euo pipefail

GRADLE_VERSION="${GRADLE_VERSION:-8.14.5}"
CMDTOOLS_BUILD="${CMDTOOLS_BUILD:-11076708}"   # commandlinetools-win-<build>_latest.zip
ROOT="${DSH_BUILD_ENV:-D:/harness/.build-env}"
SDK="$ROOT/android-sdk"

say() { printf '%s\n' "$*"; }

if command -v java >/dev/null 2>&1; then
  say "java: $(java -version 2>&1 | head -1)"
else
  say "!! no java on PATH; install a JDK 17+ (the project compiles with JDK 21)"; exit 1
fi

mkdir -p "$ROOT"

if [ ! -x "$ROOT/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  say "downloading Gradle $GRADLE_VERSION"
  curl -fsSL -o "$ROOT/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q "$ROOT/gradle.zip" -d "$ROOT"
  rm -f "$ROOT/gradle.zip"
else
  say "Gradle $GRADLE_VERSION already present"
fi

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" ] && [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  say "downloading Android command-line tools"
  curl -fsSL -o "$ROOT/cmdtools.zip" "https://dl.google.com/android/repository/commandlinetools-win-${CMDTOOLS_BUILD}_latest.zip"
  mkdir -p "$SDK/cmdline-tools"
  unzip -q "$ROOT/cmdtools.zip" -d "$SDK/cmdline-tools"
  # The archive unpacks to cmdline-tools/cmdline-tools; sdkmanager expects "latest".
  if [ -d "$SDK/cmdline-tools/cmdline-tools" ]; then
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  rm -f "$ROOT/cmdtools.zip"
else
  say "command-line tools already present"
fi

SM="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
[ -x "$SM" ] || SM="$SDK/cmdline-tools/latest/bin/sdkmanager"

say "accepting licenses and installing platform-tools, platforms;android-35, build-tools;35.0.0"
yes | "$SM" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
"$SM" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | tail -3

say ""
say "done. toolchain:"
say "  gradle : $ROOT/gradle-$GRADLE_VERSION/bin/gradle"
say "  sdk    : $SDK"
say "next    : bash tools/build-apk.sh"
