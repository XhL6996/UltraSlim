#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
for name in ULTRASLIM_KEYSTORE ULTRASLIM_STORE_PASSWORD ULTRASLIM_KEY_ALIAS ULTRASLIM_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing release signing variable: $name" >&2
    exit 2
  fi
done
export ULTRASLIM_BUILD_ROOT="${ULTRASLIM_BUILD_ROOT:-$HOME/.cache/ultraslim-build}"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
apksigner="$sdk_dir/build-tools/36.0.0/apksigner"
[[ -f "$ULTRASLIM_KEYSTORE" && -x "$apksigner" ]] || { echo "Check keystore and Android build tools paths" >&2; exit 2; }
python3 scripts/check_repository.py
# Signing credentials are supplied by the environment, never persisted in configuration cache.
./gradlew --no-configuration-cache -Pkotlin.incremental=false :app:assembleRelease :app:lintRelease
apk="$ULTRASLIM_BUILD_ROOT/app/outputs/apk/release/app-release.apk"
python3 scripts/check_apk_alignment.py "$apk"
certificate="$ULTRASLIM_BUILD_ROOT/release-certificate.txt"
"$apksigner" verify --verbose --print-certs "$apk" > "$certificate"
if grep -qi 'CN=Android Debug' "$certificate"; then
  echo "Debug certificate detected; this APK is not a public release" >&2
  exit 2
fi
python3 - "$apk" <<'PY'
import hashlib, pathlib, sys, zipfile
apk = pathlib.Path(sys.argv[1])
apk.with_name('SHA256SUMS').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + apk.name + '\n')
with zipfile.ZipFile(apk) as archive:
    apk.with_name('OPEN_SOURCE_NOTICES.txt').write_bytes(archive.read('assets/open-source-notices.txt'))
print(f'Verified signed APK: {apk}')
PY
