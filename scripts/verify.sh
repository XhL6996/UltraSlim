#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ULTRASLIM_BUILD_ROOT="${ULTRASLIM_BUILD_ROOT:-$HOME/.cache/ultraslim-build}"
cmake -G Ninja -S native -B build-host-test -DULTRASLIM_HOST_TEST=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build-host-test --parallel 4
ctest --test-dir build-host-test --output-on-failure
./gradlew -Pkotlin.incremental=false :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
python3 scripts/check_apk_alignment.py "$ULTRASLIM_BUILD_ROOT/app/outputs/apk/debug/app-debug.apk" "$ULTRASLIM_BUILD_ROOT/app/outputs/apk/release/app-release-unsigned.apk"
if [[ "${1:-}" == "--device" ]]; then
  ./gradlew -Pkotlin.incremental=false :app:connectedDebugAndroidTest
fi
