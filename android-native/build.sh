#!/bin/bash
set -e

# AgentShell Android Native Build Script
# Mirrors flutter/build.sh — Docker-based APK compilation
# Usage: ./build.sh [debug|release] [--install] [--wireless] [--force]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DOCKER_DIR="$PROJECT_ROOT/docker/android-native"
ENV_FILE="$PROJECT_ROOT/.env"
INSTALL_SCRIPT="$PROJECT_ROOT/scripts/install-android.sh"

# Default to release build
BUILD_TYPE="release"
AUTO_INSTALL=false
WIRELESS_INSTALL=false
FORCE_INSTALL=false

# Parse arguments
for arg in "$@"; do
    case "$arg" in
        debug|release)
            BUILD_TYPE="$arg"
            ;;
        --install|-i)
            AUTO_INSTALL=true
            ;;
        --wireless|-w)
            AUTO_INSTALL=true
            WIRELESS_INSTALL=true
            ;;
        --force|-f)
            FORCE_INSTALL=true
            ;;
        --help|-h)
            echo "Usage: $0 [debug|release] [options]"
            echo ""
            echo "Arguments:"
            echo "  debug, release       Build type (default: release)"
            echo "  --install, -i        Auto-install to connected Android device (USB)"
            echo "  --wireless, -w       Auto-install via WiFi"
            echo "  --force, -f          Force install on Work Profile devices"
            echo "  --help, -h           Show this help message"
            exit 0
            ;;
    esac
done

# Read .env file if it exists
SERVER_LIST=""
OPENAI_API_KEY=""
SHOW_THINKING=""
SHOW_TOOL_CALLS=""
AUTH_TOKEN=""

if [ -f "$ENV_FILE" ]; then
    echo "Reading .env file..."
    while IFS='=' read -r key value || [ -n "$key" ]; do
        [[ -z "$key" || "$key" =~ ^# ]] && continue
        key=$(echo "$key" | xargs)
        value=$(echo "$value" | xargs)

        case "$key" in
            SERVER_LIST)    SERVER_LIST="$value" ;;
            OPENAI_API_KEY) OPENAI_API_KEY="$value" ;;
            SHOW_THINKING)  SHOW_THINKING="$value" ;;
            SHOW_TOOL_CALLS) SHOW_TOOL_CALLS="$value" ;;
            AUTH_TOKEN)     AUTH_TOKEN="$value" ;;
        esac
    done < "$ENV_FILE"

    [ -n "$SERVER_LIST" ] && echo "  SERVER_LIST: set"
    [ -n "$OPENAI_API_KEY" ] && echo "  OPENAI_API_KEY: set"
    [ -n "$SHOW_THINKING" ] && echo "  SHOW_THINKING: $SHOW_THINKING"
    [ -n "$SHOW_TOOL_CALLS" ] && echo "  SHOW_TOOL_CALLS: $SHOW_TOOL_CALLS"
    [ -n "$AUTH_TOKEN" ] && echo "  AUTH_TOKEN: set"
fi

APK_FILENAME="agentshell-native-${BUILD_TYPE}.apk"

echo "Building Android Native ${BUILD_TYPE}..."
echo "  Build type: $BUILD_TYPE"

# Get number of CPU cores
CPU_CORES=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo "4")
echo "  CPU cores: $CPU_CORES"

# Ensure base image exists — pull from GHCR or build locally if missing
if ! docker image inspect agentshell-android-native-base:latest > /dev/null 2>&1; then
    echo "Base image not found locally. Fetching..."
    "$PROJECT_ROOT/scripts/build-android-native-base.sh"
fi

# Set pipefail to catch docker build failure
set -o pipefail

# Build the image
DOCKER_BUILDKIT=1 docker build \
    -t agentshell-android-native-builder:latest \
    -f "$DOCKER_DIR/Dockerfile" \
    "$PROJECT_ROOT" \
    --progress=plain \
    --build-arg BUILD_TYPE=$BUILD_TYPE \
    --build-arg BUILDKIT_INLINE_CACHE=1 \
    --build-arg SERVER_LIST="$SERVER_LIST" \
    --build-arg OPENAI_API_KEY="$OPENAI_API_KEY" \
    --build-arg SHOW_THINKING="$SHOW_THINKING" \
    --build-arg SHOW_TOOL_CALLS="$SHOW_TOOL_CALLS" \
    --build-arg AUTH_TOKEN="$AUTH_TOKEN" \
    2>&1 | tee /tmp/android-native-build.log

if [ $? -eq 0 ]; then
    CONTAINER_ID=$(docker create agentshell-android-native-builder:latest)

    # Remove old APK
    rm -f "$PROJECT_ROOT/agentshell-native-debug.apk" "$PROJECT_ROOT/agentshell-native-release.apk"

    # Copy APK to project root
    echo "Copying APK to project root..."
    docker cp "$CONTAINER_ID:/$APK_FILENAME" "$PROJECT_ROOT/$APK_FILENAME"
    docker rm "$CONTAINER_ID"

    if [ -f "$PROJECT_ROOT/$APK_FILENAME" ]; then
        echo ""
        echo "APK built successfully!"
        ls -lh "$PROJECT_ROOT/$APK_FILENAME"

        # Cleanup dangling images
        echo "Cleaning up dangling Docker images..."
        docker image prune -f

        # Auto-install to device if requested
        if [ "$AUTO_INSTALL" = true ]; then
            if [ -f "$INSTALL_SCRIPT" ]; then
                echo ""
                echo "=========================================="
                echo "Auto-installing to device..."
                echo "=========================================="
                INSTALL_ARGS=""
                [ "$WIRELESS_INSTALL" = true ] && INSTALL_ARGS="--wireless"
                [ "$FORCE_INSTALL" = true ] && INSTALL_ARGS="$INSTALL_ARGS --force"
                "$INSTALL_SCRIPT" "$PROJECT_ROOT/$APK_FILENAME" $INSTALL_ARGS
            else
                echo "Warning: Install script not found at $INSTALL_SCRIPT"
            fi
        fi
    else
        echo "ERROR: APK was not generated or could not be copied!"
        exit 1
    fi
else
    echo "Build failed! Check /tmp/android-native-build.log for details"
    exit 1
fi
