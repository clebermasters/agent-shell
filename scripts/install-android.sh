#!/bin/bash
set -e

# WebMux Android Installer Script
# Installs the APK to a connected Android device via ADB
# Features:
# - Auto-detects connected device
# - Installs/replaces existing APK
# - Shows device info
# - Works as standalone or called from build script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Default APK path
APK_PATH="${1:-$PROJECT_ROOT/webmux-flutter-release.apk}"
PACKAGE_NAME="com.example.webmux"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=========================================="
echo "  WebMux Android Installer"
echo "=========================================="
echo ""

# Check if ADB is available
if ! command -v adb &> /dev/null; then
    echo -e "${RED}Error: ADB not found. Please install Android SDK platform tools.${NC}"
    exit 1
fi

# Check if APK exists
if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}Error: APK not found at $APK_PATH${NC}"
    echo ""
    echo "Usage: $0 [apk_path]"
    echo "  apk_path: Path to APK file (default: ./webmux-flutter-release.apk)"
    exit 1
fi

echo "APK: $APK_PATH"
echo ""

# Check for connected device
echo "Checking for connected device..."
DEVICE_OUTPUT=$(adb devices 2>&1)
DEVICES=$(echo "$DEVICE_OUTPUT" | grep -E "^[a-zA-Z0-9:.-]+	device$" | wc -l)

if [ "$DEVICES" -eq 0 ]; then
    echo -e "${YELLOW}No device connected. Waiting for device...${NC}"
    adb wait-for-device
    echo -e "${GREEN}Device connected!${NC}"
elif [ "$DEVICES" -gt 1 ]; then
    echo -e "${YELLOW}Warning: Multiple devices connected. Using first one.${NC}"
fi

# Get device info
DEVICE_SERIAL=$(adb devices | grep -E "^[a-zA-Z0-9:.-]+	device$" | head -1 | cut -f1)
DEVICE_MODEL=$(adb shell getprop ro.product.model 2>/dev/null || echo "Unknown")
DEVICE_ANDROID=$(adb shell getprop ro.build.version.release 2>/dev/null || echo "Unknown")

echo ""
echo "Connected Device:"
echo "  Serial: $DEVICE_SERIAL"
echo "  Model:  $DEVICE_MODEL"
echo "  Android: $DEVICE_ANDROID"
echo ""

# Check if app is already installed
if adb shell pm list packages 2>/dev/null | grep -q "^package:$PACKAGE_NAME$"; then
    echo -e "${YELLOW}App already installed. Replacing...${NC}"
else
    echo "Installing app..."
fi

# Install APK
echo ""
echo "Installing APK..."
INSTALL_OUTPUT=$(adb install -r "$APK_PATH" 2>&1)

if echo "$INSTALL_OUTPUT" | grep -q "Success"; then
    echo -e "${GREEN}Installation successful!${NC}"
    echo ""
    
    # Optional: Launch app
    if [ "${2:-}" = "--launch" ] || [ "${2:-}" = "-l" ]; then
        echo "Launching app..."
        adb shell am start -n "$PACKAGE_NAME/com.example.webmux.MainActivity" 2>/dev/null || \
            echo -e "${YELLOW}Could not launch app automatically${NC}"
    fi
    
    echo ""
    echo -e "${GREEN}=========================================="
    echo "  Done! App is ready on your device."
    echo -e "==========================================${NC}"
    exit 0
fi

# If install failed due to signature mismatch, try uninstalling first
if echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    echo -e "${YELLOW}Signature mismatch detected. Uninstalling old version...${NC}"
    adb uninstall "$PACKAGE_NAME" 2>/dev/null || true
    
    echo "Retrying installation..."
    INSTALL_OUTPUT=$(adb install "$APK_PATH" 2>&1)
    
    if echo "$INSTALL_OUTPUT" | grep -q "Success"; then
        echo -e "${GREEN}Installation successful!${NC}"
        echo ""
        
        if [ "${2:-}" = "--launch" ] || [ "${2:-}" = "-l" ]; then
            echo "Launching app..."
            adb shell am start -n "$PACKAGE_NAME/com.example.webmux.MainActivity" 2>/dev/null || \
                echo -e "${YELLOW}Could not launch app automatically${NC}"
        fi
        
        echo ""
        echo -e "${GREEN}=========================================="
        echo "  Done! App is ready on your device."
        echo -e "==========================================${NC}"
        exit 0
    fi
fi

echo -e "${RED}Installation failed!${NC}"
echo "$INSTALL_OUTPUT"
exit 1
