#!/bin/bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
output=""
identity="-"
architecture="$(uname -m)"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output) output="$2"; shift 2 ;;
    --identity) identity="$2"; shift 2 ;;
    --arch) architecture="$2"; shift 2 ;;
    *) echo "Usage: $0 --output /path/AgentShellBrowser.app [--identity NAME] [--arch arm64|x86_64|universal]" >&2; exit 2 ;;
  esac
done
[ "$(uname -s)" = Darwin ] || { echo "Build this native helper on macOS with Xcode Command Line Tools." >&2; exit 1; }
case "$output" in *.app) ;; *) echo "--output must name an .app bundle" >&2; exit 2 ;; esac
case "$architecture" in arm64|x86_64) architectures=("$architecture") ;; universal) architectures=(arm64 x86_64) ;; *) echo "Unsupported architecture" >&2; exit 2 ;; esac
source_dir="$project_dir/macos/AgentShellBrowser"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/agentshell-browser-build.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT
mkdir -p "$output/Contents/MacOS"
cp "$source_dir/Info.plist" "$output/Contents/Info.plist"
for arch in "${architectures[@]}"; do
  xcrun swiftc -swift-version 5 -parse-as-library -O -target "$arch-apple-macosx13.0" \
    -import-objc-header "$source_dir/Sources/ProcessBridge.h" \
    -framework AppKit -framework ScreenCaptureKit -framework CoreGraphics \
    -framework CoreMedia -framework CoreVideo -framework CoreImage -framework ImageIO -framework CryptoKit \
    "$source_dir"/Sources/*.swift -o "$build_dir/AgentShellBrowser-$arch"
done
if [ "${#architectures[@]}" -eq 1 ]; then
  cp "$build_dir/AgentShellBrowser-${architectures[0]}" "$output/Contents/MacOS/AgentShellBrowser"
else
  xcrun lipo -create "$build_dir/AgentShellBrowser-arm64" "$build_dir/AgentShellBrowser-x86_64" -output "$output/Contents/MacOS/AgentShellBrowser"
fi
chmod 755 "$output/Contents/MacOS/AgentShellBrowser"
codesign --force --deep --options runtime --sign "$identity" "$output"
codesign --verify --strict "$output"
echo "$output"
