#!/usr/bin/env bash
set -euo pipefail

: "${SOURCECRAFT_ROOT_DIRECTORY:?Run this script on a SourceCraft worker}"
: "${SOURCECRAFT_ENV:?SourceCraft environment export file is required}"

# GitHub Action environment changes are not inherited by native cubes.
# Install directly on the worker so every cube uses the same toolchain.
ci_sudo=()
if [[ "$EUID" -ne 0 ]]; then
  ci_sudo=(sudo)
fi
"${ci_sudo[@]}" apt-get update
"${ci_sudo[@]}" env DEBIAN_FRONTEND=noninteractive apt-get install -y \
  openjdk-21-jdk-headless curl unzip libfontconfig1 libfreetype6 fonts-dejavu-core

JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
ANDROID_HOME="$SOURCECRAFT_ROOT_DIRECTORY/android-sdk"
export JAVA_HOME ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

android_tools_archive=commandlinetools-linux-15859902_latest.zip
android_tools_sha256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
android_tools_temp=$(mktemp -d)
trap 'rm -rf -- "$android_tools_temp"' EXIT
curl --fail --location --retry 3 \
  "https://dl.google.com/android/repository/$android_tools_archive" \
  --output "$android_tools_temp/$android_tools_archive"
printf '%s  %s\n' "$android_tools_sha256" "$android_tools_temp/$android_tools_archive" | sha256sum --check
unzip -q "$android_tools_temp/$android_tools_archive" -d "$android_tools_temp/unpacked"
mkdir -p "$ANDROID_HOME/cmdline-tools"
mv "$android_tools_temp/unpacked/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --licenses < <(yes)
java -version
sdkmanager --version
printf 'JAVA_HOME=%s\nANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\nPATH=%s\n' \
  "$JAVA_HOME" "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$PATH" >> "$SOURCECRAFT_ENV"
