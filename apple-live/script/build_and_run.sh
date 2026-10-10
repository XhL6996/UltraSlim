#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-run}"
BUILD_DIR="${ULTRASLIM_LIVE_BUILD_ROOT:-/tmp/ultraslim-live-swift}"
# Sign outside the synced checkout: FileProvider may re-add FinderInfo there.
APP="$BUILD_DIR/dist/UltraSlim Live.app"
if [[ "$MODE" != "--build-only" ]]; then pkill -x UltraSlimLive >/dev/null 2>&1 || true; fi
swift build --package-path "$ROOT_DIR" --scratch-path "$BUILD_DIR"
BIN="$(swift build --package-path "$ROOT_DIR" --scratch-path "$BUILD_DIR" --show-bin-path)/UltraSlimLive"
mkdir -p "$APP/Contents/MacOS"
cp "$BIN" "$APP/Contents/MacOS/UltraSlimLive"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>UltraSlimLive</string>
<key>CFBundleIdentifier</key><string>app.ultraslim.live.mac</string>
<key>CFBundleName</key><string>UltraSlim Live</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>1.0.0</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSMinimumSystemVersion</key><string>15.0</string>
<key>NSPrincipalClass</key><string>NSApplication</string>
<key>NSPhotoLibraryUsageDescription</key><string>把你选择的照片和配对视频作为 Live Photo 导入「照片」，用于同步到 iPhone。</string>
<key>NSPhotoLibraryAddUsageDescription</key><string>将你确认的 Live Photo 添加到「照片」。</string>
<key>CFBundleDocumentTypes</key><array><dict><key>CFBundleTypeName</key><string>UltraSlim Live Photo 导入包</string><key>CFBundleTypeRole</key><string>Viewer</string><key>LSHandlerRank</key><string>Alternate</string><key>LSItemContentTypes</key><array><string>public.zip-archive</string></array></dict></array>
</dict></plist>
PLIST
# Finder/preview metadata on a previously opened local build is not signable.
/usr/bin/xattr -dr com.apple.FinderInfo "$APP" 2>/dev/null || true
/usr/bin/xattr -dr com.apple.ResourceFork "$APP" 2>/dev/null || true
codesign --force --sign - "$APP"
codesign --verify --deep --strict "$APP"
mkdir -p "$ROOT_DIR/dist"
/usr/bin/ditto -c -k --norsrc --keepParent "$APP" "$ROOT_DIR/dist/UltraSlim-Live-Mac.zip"
case "$MODE" in
 --build-only) echo "$APP" ;;
 run) open -n "$APP" ;;
 --verify) open -n "$APP"; sleep 1; pgrep -x UltraSlimLive ;;
 --logs|--telemetry) open -n "$APP"; /usr/bin/log stream --info --style compact --predicate 'process == "UltraSlimLive"' ;;
 --debug) lldb -- "$APP/Contents/MacOS/UltraSlimLive" ;;
 *) echo "usage: $0 [run|--build-only|--verify|--logs|--telemetry|--debug]" >&2; exit 2 ;;
esac
