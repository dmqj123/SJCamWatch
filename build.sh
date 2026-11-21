#!/usr/bin/env bash
# Build SJCamWatch INSIDE WSL only. No Windows toolchain is involved.
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-/home/dmqj/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

chmod +x gradlew

echo "== Java =="
java -version
echo "== SDK platforms =="
ls "$ANDROID_HOME/platforms"

# Maven Central is unreachable on this network. settings.gradle already puts the
# Aliyun mirrors + dl.google.com first, so resolve online once and cache it.
./gradlew --no-daemon --stacktrace assembleDebug

echo "== APK output =="
find app/build/outputs/apk -name "*.apk" -print
