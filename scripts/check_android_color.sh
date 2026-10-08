#!/bin/bash
# Compare SDR colors AND HDR gain maps with Android's API 34+ decoder.
# Use a quality-90+ unresized output; its format is sniffed from its contents.
# Only temporary device files are written; no app installation or gallery changes.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -lt 3 || $# -gt 5 ]]; then
  echo "usage: $0 <adb-serial> <source.jpg> <output.avif|output.jpg> [sample-edge=1600] [pixels|linear]" >&2
  exit 2
fi
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
java="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
work=$(mktemp -d)
device_work="/data/local/tmp/ultraslim-color-$$"
cleanup() {
  adb -s "$1" shell rm -rf "$device_work" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap 'cleanup "$1"' EXIT
"$java/bin/javac" -classpath "$sdk/platforms/android-36/android.jar" -d "$work" native/host_test/AndroidColorProfileTest.java
JAVA_HOME="$java" "$sdk/build-tools/36.0.0/d8" --lib "$sdk/platforms/android-36/android.jar" --output "$work" "$work/AndroidColorProfileTest.class"
adb -s "$1" shell mkdir -p "$device_work"
adb -s "$1" push "$work/classes.dex" "$device_work/test.dex" >/dev/null
adb -s "$1" push "$2" "$device_work/source.jpg" >/dev/null
adb -s "$1" push "$3" "$device_work/output.avif" >/dev/null
sample_edge="${4:-1600}"
[[ "$sample_edge" =~ ^[1-9][0-9]{0,5}$ ]] || { echo "invalid sample edge" >&2; exit 2; }
mode="${5:-pixels}"
[[ "$mode" == pixels || "$mode" == linear ]] || { echo "invalid comparison mode" >&2; exit 2; }
adb -s "$1" shell "CLASSPATH=$device_work/test.dex app_process $device_work AndroidColorProfileTest $device_work/source.jpg $device_work/output.avif $sample_edge $mode"
