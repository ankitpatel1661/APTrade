#!/bin/sh
# Builds Shared.xcframework (the KMP core) for the Swift app to link.
# Run once per clone and after any change under shared/, before `swift build`.
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
./gradlew :shared:assembleSharedReleaseXCFramework --console=plain
echo "OK: shared/build/XCFrameworks/release/Shared.xcframework"
