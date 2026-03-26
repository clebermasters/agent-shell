#!/bin/bash
# Build or pull the agentshell-android-native-base image.
# Tries GHCR first (fast), falls back to building locally.
#
# Usage:
#   ./scripts/build-android-native-base.sh          # auto: try GHCR, build if needed
#   ./scripts/build-android-native-base.sh --pull    # force pull from GHCR only
#   ./scripts/build-android-native-base.sh --build   # force local build only

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GHCR_IMAGE="ghcr.io/clebermasters/agentshell-android-native-base:latest"
LOCAL_TAG="agentshell-android-native-base:latest"
DOCKERFILE="$PROJECT_ROOT/docker/android-native-base/Dockerfile"

FORCE_PULL=false
FORCE_BUILD=false

for arg in "$@"; do
    case "$arg" in
        --pull)  FORCE_PULL=true ;;
        --build) FORCE_BUILD=true ;;
        --help)
            echo "Usage: $0 [--pull|--build]"
            echo "  (no flag)  Try GHCR first, build locally if pull fails"
            echo "  --pull     Force pull from GHCR only"
            echo "  --build    Force local build only"
            exit 0 ;;
    esac
done

# Check if image already exists locally
if docker image inspect "$LOCAL_TAG" > /dev/null 2>&1; then
    echo "Base image already exists locally: $LOCAL_TAG"
    docker images "$LOCAL_TAG"
    exit 0
fi

try_pull() {
    echo "Pulling base image from GHCR..."
    if docker pull "$GHCR_IMAGE"; then
        docker tag "$GHCR_IMAGE" "$LOCAL_TAG"
        echo "Base image ready: $LOCAL_TAG"
        return 0
    fi
    return 1
}

try_build() {
    echo "Building base image locally (this takes ~10-15 min)..."
    DOCKER_BUILDKIT=1 docker build \
        -t "$LOCAL_TAG" \
        -f "$DOCKERFILE" \
        "$PROJECT_ROOT" \
        --progress=plain
    echo "Base image built: $LOCAL_TAG"
}

if $FORCE_BUILD; then
    try_build
elif $FORCE_PULL; then
    try_pull
else
    try_pull || { echo "GHCR pull failed, building locally..."; try_build; }
fi
