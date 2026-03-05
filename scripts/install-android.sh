#!/bin/bash

# WebMux Android Installer Script
# Installs the APK to a connected Android device via ADB
# Features:
# - Auto-detects connected device
# - Installs/replaces existing APK via USB or wireless
# - Shows device info
# - Works as standalone or called from build script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG_DIR="$SCRIPT_DIR/.config"
WIRELESS_IP_FILE="$CONFIG_DIR/wireless_ip"

# Default APK path
APK_PATH=""
WIRELESS_MODE=false
FORCE_INSTALL=false
LAUNCH_APP=false
PACKAGE_NAME="com.example.webmux"
ADB_PORT=5555

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Parse arguments
for arg in "$@"; do
    case "$arg" in
        --wireless|-w)
            WIRELESS_MODE=true
            ;;
        --force|-f)
            FORCE_INSTALL=true
            ;;
        --launch|-l)
            LAUNCH_APP=true
            ;;
        --help|-h)
            echo "Usage: $0 [apk_path] [options]"
            echo ""
            echo "Options:"
            echo "  --wireless, -w   Force wireless mode (skip USB)"
            echo "  --force, -f      Force install even on Work Profile (not recommended)"
            echo "  --launch, -l     Launch app after installation"
            echo "  --help, -h       Show this help"
            echo ""
            echo "Smart Features:"
            echo "  - Auto-detects USB connection"
            echo "  - If no USB, automatically tries wireless"
            echo "  - Saves wireless IP for future use"
            echo ""
            echo "Examples:"
            echo "  $0                                    # Smart install (USB -> wireless auto)"
            echo "  $0 ./app.apk                         # Install specific APK"
            echo "  $0 --wireless                        # Force wireless only"
            echo "  $0 --wireless --launch                # Wireless install and launch"
            exit 0
            ;;
        -*)
            # Ignore unknown options
            ;;
        *)
            if [ -z "$APK_PATH" ]; then
                APK_PATH="$arg"
            fi
            ;;
    esac
done

# Set default APK path if not provided
if [ -z "$APK_PATH" ]; then
    APK_PATH="$PROJECT_ROOT/webmux-flutter-release.apk"
fi

# Create config directory
mkdir -p "$CONFIG_DIR"

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
    exit 1
fi

# Check for existing wireless connection
check_wireless_connection() {
    adb devices 2>/dev/null | grep -E "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:$ADB_PORT	device$" | head -1
}

# Get device IP address
get_device_ip() {
    local ip=$(adb shell ip addr show wlan0 2>/dev/null | grep "inet " | awk '{print $2}' | cut -d'/' -f1)
    echo "$ip"
}

# Connect to device wirelessly
wireless_connect() {
    local ip="$1"
    echo -e "${BLUE}Connecting to $ip:$ADB_PORT...${NC}"
    adb connect "$ip:$ADB_PORT" 2>/dev/null
    sleep 2
}

# Setup wireless connection (requires USB first time)
setup_wireless() {
    echo -e "${YELLOW}Setting up wireless connection...${NC}"
    
    # Check if already connected via wireless
    if check_wireless_connection | grep -q .; then
        echo -e "${GREEN}Already connected via WiFi${NC}"
        return 0
    fi
    
    # Check for USB device
    usb_device=$(adb devices 2>/dev/null | grep -E "^[a-zA-Z0-9:.-]+	device$" | head -1 | cut -f1)
    
    if [ -z "$usb_device" ]; then
        echo -e "${RED}No USB device found.${NC}"
        echo ""
        echo -e "${YELLOW}To setup wireless for the first time:${NC}"
        echo "  1. Connect your phone via USB"
        echo "  2. Run: $0 --wireless"
        echo ""
        echo -e "${YELLOW}Or enter IP manually:${NC}"
        read -p "Device IP address: " manual_ip
        if [ -n "$manual_ip" ]; then
            wireless_connect "$manual_ip"
            if check_wireless_connection | grep -q .; then
                echo "$manual_ip" > "$WIRELESS_IP_FILE"
                echo -e "${GREEN}Connected! IP saved for future use.${NC}"
                return 0
            fi
        fi
        return 1
    fi
    
    # Get device IP
    local device_ip=$(get_device_ip)
    
    if [ -z "$device_ip" ]; then
        echo -e "${RED}Could not get device IP address. Make sure WiFi is enabled on device.${NC}"
        return 1
    fi
    
    echo -e "${BLUE}Device IP: $device_ip${NC}"
    
    # Enable TCPIP mode
    echo "Enabling ADB over TCP/IP..."
    adb tcpip $ADB_PORT 2>/dev/null
    
    # Disconnect USB
    echo "Disconnecting USB..."
    
    # Connect wirelessly
    wireless_connect "$device_ip"
    
    # Verify connection
    if check_wireless_connection | grep -q .; then
        echo "$device_ip" > "$WIRELESS_IP_FILE"
        echo -e "${GREEN}Wireless setup complete!${NC}"
        return 0
    else
        echo -e "${RED}Failed to connect wirelessly${NC}"
        return 1
    fi
}

# Try to establish wireless connection
try_wireless_connection() {
    echo -e "${YELLOW}Attempting wireless connection...${NC}"
    
    # Check if already connected via wireless
    if check_wireless_connection | grep -q .; then
        echo -e "${GREEN}Already connected via WiFi${NC}"
        return 0
    fi
    
    # Try saved IP
    if [ -f "$WIRELESS_IP_FILE" ]; then
        saved_ip=$(cat "$WIRELESS_IP_FILE")
        echo -e "${YELLOW}Trying saved IP: $saved_ip${NC}"
        wireless_connect "$saved_ip"
        
        if check_wireless_connection | grep -q .; then
            echo -e "${GREEN}Connected to saved device${NC}"
            return 0
        fi
    fi
    
    # Check if device is connected via USB to setup wireless
    usb_device=$(adb devices 2>/dev/null | grep -E "^[a-zA-Z0-9:.-]+	device$" | head -1 | cut -f1)
    
    if [ -n "$usb_device" ]; then
        # USB connected - setup wireless
        setup_wireless
        return $?
    fi
    
    # No USB - try to find device on network
    echo -e "${YELLOW}Searching for device on network...${NC}"
    
    # Try saved IP first
    if [ -f "$WIRELESS_IP_FILE" ]; then
        saved_ip=$(cat "$WIRELESS_IP_FILE")
        wireless_connect "$saved_ip"
        if check_wireless_connection | grep -q .; then
            echo -e "${GREEN}Connected to saved device${NC}"
            return 0
        fi
    fi
    
    # Ask user for manual IP
    echo -e "${YELLOW}Could not auto-discover device. Enter IP manually:${NC}"
    read -p "Device IP address: " manual_ip
    if [ -n "$manual_ip" ]; then
        wireless_connect "$manual_ip"
        if check_wireless_connection | grep -q .; then
            echo "$manual_ip" > "$WIRELESS_IP_FILE"
            echo -e "${GREEN}Connected! IP saved for future use.${NC}"
            return 0
        fi
    fi
    
    return 1
}

# Main connection logic
connect_device() {
    # First, check for USB connection
    echo "Checking for USB device..."
    DEVICE_OUTPUT=$(adb devices 2>&1)
    USB_DEVICES=$(echo "$DEVICE_OUTPUT" | grep -E "^[a-zA-Z0-9:.-]+	device$" | wc -l)
    
    if [ "$USB_DEVICES" -gt 0 ]; then
        # USB device found
        if [ "$USB_DEVICES" -gt 1 ]; then
            echo -e "${YELLOW}Warning: Multiple USB devices connected. Using first one.${NC}"
        fi
        echo -e "${GREEN}USB device connected${NC}"
        WIRELESS_MODE=false
        return 0
    fi
    
    # No USB device found - try wireless
    echo -e "${YELLOW}No USB device found${NC}"
    
    if [ "$WIRELESS_MODE" = true ]; then
        # Explicit wireless mode requested
        try_wireless_connection
        return $?
    else
        # Smart mode: automatically try wireless
        echo -e "${BLUE}Smart mode: automatically trying wireless connection...${NC}"
        if try_wireless_connection; then
            WIRELESS_MODE=true
            return 0
        else
            echo -e "${RED}Could not establish any connection${NC}"
            echo -e "${YELLOW}Please connect your device via USB first to setup wireless.${NC}"
            return 1
        fi
    fi
}

# Connect to device
if ! connect_device; then
    echo -e "${RED}Failed to connect to device${NC}"
    exit 1
fi

# Get device info
if [ "$WIRELESS_MODE" = true ]; then
    # For wireless, get the wireless device serial
    DEVICE_SERIAL=$(adb devices | grep -E "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:$ADB_PORT	device$" | head -1 | cut -f1)
    if [ -z "$DEVICE_SERIAL" ]; then
        DEVICE_SERIAL=$(adb devices | grep -E "^[a-zA-Z0-9:.-]+	device$" | head -1 | cut -f1)
    fi
else
    DEVICE_SERIAL=$(adb devices | grep -E "^[a-zA-Z0-9:.-]+	device$" | head -1 | cut -f1)
fi
DEVICE_MODEL=$(adb shell -s "$DEVICE_SERIAL" getprop ro.product.model 2>/dev/null || echo "Unknown")
DEVICE_ANDROID=$(adb shell -s "$DEVICE_SERIAL" getprop ro.build.version.release 2>/dev/null || echo "Unknown")
CONNECTION_TYPE=$(echo "$DEVICE_SERIAL" | grep -q ":" && echo "WiFi" || echo "USB")

echo ""
echo "Connected Device:"
echo "  Serial:    $DEVICE_SERIAL"
echo "  Model:     $DEVICE_MODEL"
echo "  Android:   $DEVICE_ANDROID"
echo "  Type:      $CONNECTION_TYPE"
echo ""

# Check for Work Profile
check_work_profile() {
    local users_output=$($ADB_CMD shell pm list users 2>/dev/null)
    local user_count=$(echo "$users_output" | grep -c "UserInfo{" || echo "0")
    
    if [ "$user_count" -gt 1 ]; then
        return 0  # Work Profile detected
    fi
    return 1  # No Work Profile
}

if [ "$FORCE_INSTALL" = false ]; then
    if check_work_profile; then
        echo ""
        echo -e "${RED}=========================================="
        echo "  Work Profile Detected!"
        echo "==========================================${NC}"
        echo ""
        echo -e "${YELLOW}This device has a Work Profile enabled.${NC}"
        echo "Installing apps on Work Profile can cause issues."
        echo ""
        echo -e "To force installation anyway, run with ${GREEN}--force${NC} flag"
        echo ""
        echo -e "${YELLOW}Alternatively, disable Work Profile in:${NC}"
        echo "  Settings > Accounts > Work Profile"
        echo ""
        exit 1
    fi
else
    echo -e "${YELLOW}Warning: Force install enabled (Work Profile check skipped)${NC}"
fi

# Get the adb command with device selection
get_adb_cmd() {
    if [ -n "$DEVICE_SERIAL" ]; then
        echo "adb -s $DEVICE_SERIAL"
    else
        echo "adb"
    fi
}

ADB_CMD=$(get_adb_cmd)

# Check if app is already installed
if $ADB_CMD shell pm list packages 2>/dev/null | grep -q "^package:$PACKAGE_NAME$"; then
    echo -e "${YELLOW}App already installed. Replacing...${NC}"
else
    echo "Installing app..."
fi

# Install APK
echo ""
echo "Installing APK..."
INSTALL_OUTPUT=$($ADB_CMD install -r "$APK_PATH" 2>&1) || true

if echo "$INSTALL_OUTPUT" | grep -q "Success"; then
    echo -e "${GREEN}Installation successful!${NC}"
    echo ""
    
    if [ "$LAUNCH_APP" = true ]; then
        echo "Launching app..."
        $ADB_CMD shell am start -n "$PACKAGE_NAME/com.example.webmux.MainActivity" 2>/dev/null || \
            echo -e "${YELLOW}Could not launch app automatically${NC}"
    fi
    
    echo ""
    echo -e "${GREEN}=========================================="
    echo "  Done! App is ready on your device."
    echo "==========================================${NC}"
    exit 0
fi

# If install failed due to signature mismatch, try uninstalling first
if echo "$INSTALL_OUTPUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    echo -e "${YELLOW}Signature mismatch detected. Uninstalling old version...${NC}"
    $ADB_CMD uninstall "$PACKAGE_NAME" 2>/dev/null || true
    
    echo "Retrying installation..."
    INSTALL_OUTPUT=$($ADB_CMD install "$APK_PATH" 2>&1)
    
    if echo "$INSTALL_OUTPUT" | grep -q "Success"; then
        echo -e "${GREEN}Installation successful!${NC}"
        echo ""
        
        if [ "$LAUNCH_APP" = true ]; then
            echo "Launching app..."
            $ADB_CMD shell am start -n "$PACKAGE_NAME/com.example.webmux.MainActivity" 2>/dev/null || \
                echo -e "${YELLOW}Could not launch app automatically${NC}"
        fi
        
        echo ""
        echo -e "${GREEN}=========================================="
        echo "  Done! App is ready on your device."
        echo "==========================================${NC}"
        exit 0
    fi
fi

echo -e "${RED}Installation failed!${NC}"
echo "$INSTALL_OUTPUT"
exit 1
