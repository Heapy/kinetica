#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
if [[ "${1:-}" != "--skip-build" ]]; then
  ./kotlin build -m harmon-native
fi

binary="$repo_root/build/tasks/_harmon-native_linkMacosArm64Debug/harmon-native.kexe"
app="$repo_root/build/apps/Harmon Preview.app"
test -f "$binary"
mkdir -p "$app/Contents/MacOS"
cp "$binary" "$app/Contents/MacOS/harmon-native"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>harmon-native</string>
  <key>CFBundleIdentifier</key><string>io.heapy.kinetica.harmon-preview</string>
  <key>CFBundleName</key><string>Harmon Preview</string>
  <key>CFBundleDisplayName</key><string>Harmon Preview</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
plutil -lint "$app/Contents/Info.plist"
codesign --force --sign - "$app"
printf '%s\n' "$app"
