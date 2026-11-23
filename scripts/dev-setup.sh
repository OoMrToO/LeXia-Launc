#!/usr/bin/env bash
# Local build environment for a fresh cloud session (idempotent): Android SDK 36, Gradle 8.13, local.properties.
# Then: export ANDROID_HOME=/opt/android-sdk; gradle --no-daemon -q -I scripts/mirror.init.gradle :nuvio:compileDebugKotlin
set -euo pipefail
SDK=/opt/android-sdk
cd "$(dirname "$0")/.."
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -sL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p "$SDK/cmdline-tools" && unzip -qo /tmp/cmdtools.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
[ -d "$SDK/platforms/android-36" ] || yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools" >/dev/null
if ! command -v gradle >/dev/null; then
  curl -sL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.13-bin.zip
  mkdir -p /opt/gradle && unzip -qo /tmp/gradle.zip -d /opt/gradle
  ln -sf /opt/gradle/gradle-8.13/bin/gradle /usr/local/bin/gradle
fi
echo "sdk.dir=$SDK" > local.properties
echo "ready: ANDROID_HOME=$SDK, $(gradle --version 2>/dev/null | grep -m1 Gradle)"
