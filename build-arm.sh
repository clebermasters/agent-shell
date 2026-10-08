#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# AgentShell Backend — ARM Cross-Compilation Script
#
# Builds the Rust backend for ARM CPUs using 'cross' (Docker-based).
#
# Usage:
#   ./build-arm.sh                    # Build for aarch64 (64-bit ARM)
#   ./build-arm.sh --target aarch64   # Same as above (explicit)
#   ./build-arm.sh --target armv7     # Build for 32-bit ARM (armv7hf)
#   ./build-arm.sh --target musl      # Build static aarch64 binary (musl)
#   ./build-arm.sh --target all       # Build all targets
#   ./build-arm.sh --clean            # Clean before building
#   ./build-arm.sh --install          # Build + copy to deploy directory
#   ./build-arm.sh --install --target armv7  # Build armv7 + install
#   ./build-arm.sh --help
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/backend-rust"
OUTPUT_DIR="$SCRIPT_DIR/dist"
BINARY_NAME="agentshell-backend"

# Target mapping: alias → full Rust triple
declare -A TARGET_MAP=(
    ["aarch64"]="aarch64-unknown-linux-gnu"
    ["armv7"]="armv7-unknown-linux-gnueabihf"
    ["musl"]="aarch64-unknown-linux-musl"
)

ALL_TARGETS=("aarch64" "armv7" "musl")
TARGET_ALIAS="aarch64"
DO_CLEAN=false
DO_INSTALL=false

# ── Colors ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ── Help ────────────────────────────────────────────────────────────────────
usage() {
    cat <<EOF
AgentShell Backend — ARM Cross-Compilation

Usage: $(basename "$0") [OPTIONS]

Options:
  --target TARGET   Build target: aarch64 (default), armv7, musl, all
  --clean           Clean build artifacts before building
  --install         Copy binary to deploy directory after build
  -h, --help        Show this help

Targets:
  aarch64   aarch64-unknown-linux-gnu     (64-bit ARM, glibc — Pi 4/5, Graviton, etc.)
  armv7     armv7-unknown-linux-gnueabihf (32-bit ARM, hard-float — Pi 2/3 32-bit)
  musl      aarch64-unknown-linux-musl    (64-bit ARM, static binary — Alpine, minimal)

Examples:
  ./build-arm.sh                                    # Default: aarch64
  ./build-arm.sh --target armv7                     # 32-bit ARM
  ./build-arm.sh --target all                       # Build all three
  ./build-arm.sh --target musl --install            # Static binary + deploy
EOF
    exit 0
}

# ── Parse args ──────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)   TARGET_ALIAS="$2"; shift 2 ;;
        --clean)    DO_CLEAN=true; shift ;;
        --install)  DO_INSTALL=true; shift ;;
        -h|--help)  usage ;;
        *)          err "Unknown option: $1"; usage ;;
    esac
done

# ── Prerequisites check ─────────────────────────────────────────────────────
check_prerequisites() {
    if ! command -v docker &>/dev/null; then
        err "Docker is required for cross-compilation but not found."
        err "Install Docker: https://docs.docker.com/engine/install/"
        exit 1
    fi

    if ! docker info &>/dev/null; then
        err "Docker daemon is not running. Start it first:"
        err "  sudo systemctl start docker"
        exit 1
    fi

    if ! command -v cross &>/dev/null; then
        warn "'cross' not found. Installing via cargo..."
        cargo install cross --git https://github.com/cross-rs/cross
        if ! command -v cross &>/dev/null; then
            err "Failed to install 'cross'. Install manually:"
            err "  cargo install cross --git https://github.com/cross-rs/cross"
            exit 1
        fi
        ok "'cross' installed successfully."
    fi
}

# ── Build for a single target ───────────────────────────────────────────────
build_target() {
    local alias="$1"
    local triple="${TARGET_MAP[$alias]}"

    if [[ -z "$triple" ]]; then
        err "Unknown target alias: $alias"
        err "Valid targets: ${ALL_TARGETS[*]}"
        exit 1
    fi

    info "Building $BINARY_NAME for $alias ($triple)..."

    cd "$BACKEND_DIR"

    if [[ "$DO_CLEAN" == true ]]; then
        info "Cleaning build artifacts for $triple..."
        cross clean --release --target "$triple" 2>/dev/null || true
    fi

    cross build --release --target "$triple"

    local binary_path="$BACKEND_DIR/target/$triple/release/$BINARY_NAME"
    if [[ ! -f "$binary_path" ]]; then
        err "Build succeeded but binary not found at: $binary_path"
        exit 1
    fi

    local output_name="$BINARY_NAME-$alias"
    mkdir -p "$OUTPUT_DIR"
    cp "$binary_path" "$OUTPUT_DIR/$output_name"
    chmod +x "$OUTPUT_DIR/$output_name"

    local size
    size=$(du -h "$OUTPUT_DIR/$output_name" | cut -f1)
    ok "Built: dist/$output_name ($size)"

    # Show architecture verification
    if command -v file &>/dev/null; then
        local arch_info
        arch_info=$(file "$OUTPUT_DIR/$output_name" | sed 's/.*: //')
        info "  $arch_info"
    fi
}

# ── Install (copy to deploy dir) ────────────────────────────────────────────
install_binary() {
    local alias="$1"
    local output_name="$BINARY_NAME-$alias"
    local deploy_dir="/opt/agentshell/backend"

    if [[ ! -f "$OUTPUT_DIR/$output_name" ]]; then
        err "Binary not found: $OUTPUT_DIR/$output_name"
        exit 1
    fi

    info "Installing to $deploy_dir/$BINARY_NAME..."
    sudo mkdir -p "$deploy_dir"
    sudo cp "$OUTPUT_DIR/$output_name" "$deploy_dir/$BINARY_NAME"
    sudo chmod +x "$deploy_dir/$BINARY_NAME"

    if id "agentshell" &>/dev/null; then
        sudo chown agentshell:agentshell "$deploy_dir/$BINARY_NAME"
    fi

    ok "Installed. Restart with: sudo systemctl restart agentshell"
}

# ── Main ────────────────────────────────────────────────────────────────────
main() {
    echo ""
    info "AgentShell Backend — ARM Cross-Compilation"
    info "============================================"
    echo ""

    check_prerequisites

    if [[ "$TARGET_ALIAS" == "all" ]]; then
        for t in "${ALL_TARGETS[@]}"; do
            build_target "$t"
            echo ""
        done
    else
        build_target "$TARGET_ALIAS"
    fi

    if [[ "$DO_INSTALL" == true ]]; then
        echo ""
        if [[ "$TARGET_ALIAS" == "all" ]]; then
            err "--install cannot be used with --target all (which binary would be deployed?)"
            err "Use: ./build-arm.sh --target aarch64 --install"
            exit 1
        fi
        install_binary "$TARGET_ALIAS"
    fi

    echo ""
    ok "Done!"
    echo ""
    if [[ "$DO_INSTALL" == false ]]; then
        info "Binaries available in: dist/"
        info "To deploy: ./build-arm.sh --target $TARGET_ALIAS --install"
    fi
}

main
