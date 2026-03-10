#!/bin/bash
set -e

# AgentShell Flutter Build Script
# Features:
# - Supports both debug and release builds
# - Uses all available CPU cores for parallel compilation
# - Docker layer caching for faster subsequent builds
# - Auto-upload to S3 after successful build
# - Reads .env file for default server list and API key
# - Optional: auto-install to connected Android device

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FLUTTER_DIR="$PROJECT_ROOT/flutter"
DOCKER_DIR="$PROJECT_ROOT/docker/flutter"
ENV_FILE="$PROJECT_ROOT/.env"
INSTALL_SCRIPT="$PROJECT_ROOT/scripts/install-android.sh"

# Default to release build, no auto-install
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
            echo "  debug,release     Build type (default: release)"
            echo "  --install, -i    Auto-install to connected Android device (USB)"
            echo "  --wireless, -w   Auto-install via WiFi"
            echo "  --force, -f      Force install on Work Profile devices"
            echo "  --help, -h       Show this help message"
            exit 0
            ;;
    esac
done

# Read .env file if it exists
SERVER_LIST=""
OPENAI_API_KEY=""

if [ -f "$ENV_FILE" ]; then
    echo "Reading .env file..."
    while IFS='=' read -r key value; do
        # Skip comments and empty lines
        [[ -z "$key" || "$key" =~ ^# ]] && continue
        # Remove leading/trailing whitespace
        key=$(echo "$key" | xargs)
        value=$(echo "$value" | xargs)
        
        case "$key" in
            SERVER_LIST)
                SERVER_LIST="$value"
                ;;
            OPENAI_API_KEY)
                OPENAI_API_KEY="$value"
                ;;
        esac
    done < "$ENV_FILE"
    
    [ -n "$SERVER_LIST" ] && echo "  SERVER_LIST: set"
    [ -n "$OPENAI_API_KEY" ] && echo "  OPENAI_API_KEY: set"
fi

# S3 configuration
S3_BUCKET="s3://images.bitslovers.com/temp"
S3_KEY="agentshell-flutter-${BUILD_TYPE}.apk"
APK_FILENAME="agentshell-flutter-${BUILD_TYPE}.apk"

echo "Building Flutter ${BUILD_TYPE} APK..."
echo "  Build type: $BUILD_TYPE"

# Get number of CPU cores
CPU_CORES=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo "4")
echo "  CPU cores: $CPU_CORES"

# Update gradle.properties for parallel builds if not already done
if [ -f "$FLUTTER_DIR/android/gradle.properties" ]; then
    grep -q "org.gradle.parallel=true" "$FLUTTER_DIR/android/gradle.properties" || \
        echo "org.gradle.parallel=true" >> "$FLUTTER_DIR/android/gradle.properties"
    grep -q "org.gradle.daemon=true" "$FLUTTER_DIR/android/gradle.properties" || \
        echo "org.gradle.daemon=true" >> "$FLUTTER_DIR/android/gradle.properties"
    grep -q "org.gradle.caching=true" "$FLUTTER_DIR/android/gradle.properties" || \
        echo "org.gradle.caching=true" >> "$FLUTTER_DIR/android/gradle.properties"
fi

# Set pipefail to catch docker build failure
set -o pipefail

# Build the image with BUILD_TYPE argument and optional env vars
DOCKER_BUILDKIT=1 docker build \
    -t agentshell-flutter-builder:latest \
    -f "$DOCKER_DIR/Dockerfile" \
    "$PROJECT_ROOT" \
    --progress=plain \
    --build-arg BUILD_TYPE=$BUILD_TYPE \
    --build-arg BUILDKIT_INLINE_CACHE=1 \
    --build-arg SERVER_LIST="$SERVER_LIST" \
    --build-arg OPENAI_API_KEY="$OPENAI_API_KEY" \
    2>&1 | tee /tmp/flutter-build.log

# Check if build was successful
if [ $? -eq 0 ]; then
    # Remove old APK to be sure we get the new one
    rm -f "$PROJECT_ROOT/agentshell-flutter-debug.apk" "$PROJECT_ROOT/agentshell-flutter-release.apk"

    # Copy APK to project root
    echo "Copying APK to project root..."
    CONTAINER_ID=$(docker create agentshell-flutter-builder:latest)
    docker cp "$CONTAINER_ID:/$APK_FILENAME" "$PROJECT_ROOT/$APK_FILENAME"
    docker rm "$CONTAINER_ID"

    if [ -f "$PROJECT_ROOT/$APK_FILENAME" ]; then
        echo ""
        echo "APK built successfully!"
        ls -lh "$PROJECT_ROOT/$APK_FILENAME"

        # Cleanup dangling images to save space
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
    echo "Build failed! Check /tmp/flutter-build.log for details"
    exit 1
fi
