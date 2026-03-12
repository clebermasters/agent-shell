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
        debug|release|web|linux)
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
            echo "Usage: $0 [debug|release|web|linux] [options]"
            echo ""
            echo "Arguments:"
            echo "  debug,release,web,linux  Build type (default: release)"
            echo "  --install, -i            Auto-install to connected Android device (USB)"
            echo "  --wireless, -w           Auto-install via WiFi"
            echo "  --force, -f              Force install on Work Profile devices"
            echo "  --help, -h               Show this help message"
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
            SHOW_THINKING)
                SHOW_THINKING="$value"
                ;;
            SHOW_TOOL_CALLS)
                SHOW_TOOL_CALLS="$value"
                ;;
            AUTH_TOKEN)
                AUTH_TOKEN="$value"
                ;;
        esac
    done < "$ENV_FILE"
    
    [ -n "$SERVER_LIST" ] && echo "  SERVER_LIST: set"
    [ -n "$OPENAI_API_KEY" ] && echo "  OPENAI_API_KEY: set"
    [ -n "$SHOW_THINKING" ] && echo "  SHOW_THINKING: $SHOW_THINKING"
    [ -n "$SHOW_TOOL_CALLS" ] && echo "  SHOW_TOOL_CALLS: $SHOW_TOOL_CALLS"
    [ -n "$AUTH_TOKEN" ] && echo "  AUTH_TOKEN: set"
fi

# S3 configuration
S3_BUCKET="s3://images.bitslovers.com/temp"
S3_KEY="agentshell-flutter-${BUILD_TYPE}.apk"
APK_FILENAME="agentshell-flutter-${BUILD_TYPE}.apk"
WEB_FILENAME="agentshell-web.zip"

echo "Building Flutter ${BUILD_TYPE}..."
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
    --build-arg SHOW_THINKING="$SHOW_THINKING" \
    --build-arg SHOW_TOOL_CALLS="$SHOW_TOOL_CALLS" \
    --build-arg BUILD_TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)" \
    --build-arg AUTH_TOKEN="$AUTH_TOKEN" \
    2>&1 | tee /tmp/flutter-build.log

# Check if build was successful
if [ $? -eq 0 ]; then
    CONTAINER_ID=$(docker create agentshell-flutter-builder:latest)

    if [ "$BUILD_TYPE" = "web" ]; then
        # Copy web build output and zip it
        echo "Copying web build to project root..."
        rm -f "$PROJECT_ROOT/$WEB_FILENAME"
        docker cp "$CONTAINER_ID:/agentshell-web" /tmp/agentshell-web
        docker rm "$CONTAINER_ID"
        (cd /tmp && zip -r "$PROJECT_ROOT/$WEB_FILENAME" agentshell-web && rm -rf agentshell-web)

        if [ -f "$PROJECT_ROOT/$WEB_FILENAME" ]; then
            echo ""
            echo "Web build successful!"
            ls -lh "$PROJECT_ROOT/$WEB_FILENAME"
            docker image prune -f
        else
            echo "ERROR: Web build output not found!"
            exit 1
        fi
    elif [ "$BUILD_TYPE" = "linux" ]; then
        # Copy Linux build output and create archive
        echo "Copying Linux build to project root..."
        rm -f "$PROJECT_ROOT/agentshell-linux.tar.gz"
        docker cp "$CONTAINER_ID:/agentshell-linux" /tmp/agentshell-linux
        docker rm "$CONTAINER_ID"
        (cd /tmp && tar -czf "$PROJECT_ROOT/agentshell-linux.tar.gz" agentshell-linux && rm -rf agentshell-linux)

        if [ -f "$PROJECT_ROOT/agentshell-linux.tar.gz" ]; then
            echo ""
            echo "Linux build successful!"
            ls -lh "$PROJECT_ROOT/agentshell-linux.tar.gz"
            docker image prune -f
        else
            echo "ERROR: Linux build output not found!"
            exit 1
        fi
    else
        # Remove old APK to be sure we get the new one
        rm -f "$PROJECT_ROOT/agentshell-flutter-debug.apk" "$PROJECT_ROOT/agentshell-flutter-release.apk"

        # Copy APK to project root
        echo "Copying APK to project root..."
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
    fi
else
    echo "Build failed! Check /tmp/flutter-build.log for details"
    exit 1
fi
