#!/usr/bin/env bash
#
# WebMux Plugin Installer for OpenCode
#
# This script installs the WebMux plugin to OpenCode's plugins directory.
# The plugin provides WebMux environment variables to skills.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
PLUGIN_SOURCE="${SCRIPT_DIR}/webmux.js"
OPENCODE_PLUGINS_DIR="${HOME}/.config/opencode/plugins"
PLUGIN_TARGET="${OPENCODE_PLUGINS_DIR}/webmux.js"
SESSION_DIR="${HOME}/.webmux"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if plugin source exists
if [ ! -f "$PLUGIN_SOURCE" ]; then
    log_error "Plugin file not found: $PLUGIN_SOURCE"
    exit 1
fi

# Create plugins directory if it doesn't exist
if [ ! -d "$OPENCODE_PLUGINS_DIR" ]; then
    log_info "Creating OpenCode plugins directory: $OPENCODE_PLUGINS_DIR"
    mkdir -p "$OPENCODE_PLUGINS_DIR"
fi

# Remove existing symlink or file if present
if [ -e "$PLUGIN_TARGET" ]; then
    log_info "Removing existing plugin: $PLUGIN_TARGET"
    rm -f "$PLUGIN_TARGET"
fi

# Create symlink to plugin
log_info "Installing WebMux plugin..."
ln -s "$PLUGIN_SOURCE" "$PLUGIN_TARGET"

# Create session directory if it doesn't exist
if [ ! -d "$SESSION_DIR" ]; then
    log_info "Creating WebMux session directory: $SESSION_DIR"
    mkdir -p "$SESSION_DIR"
fi

# Create placeholder session file if it doesn't exist
if [ ! -f "${SESSION_DIR}/acp_session" ]; then
    log_info "Creating placeholder session file..."
    cat > "${SESSION_DIR}/acp_session" << 'EOF'
{
  "sessionId": "",
  "cwd": "",
  "wsUrl": "ws://localhost:5173/ws"
}
EOF
fi

log_info "WebMux plugin installed successfully!"
echo ""
echo "Plugin location: $PLUGIN_TARGET"
echo "Session file: ${SESSION_DIR}/acp_session"
echo ""
echo "To update the current ACP session, write to the session file:"
echo '  echo '\''{"sessionId": "ses_xxx", "cwd": "/path", "wsUrl": "ws://localhost:5173/ws"}'\'' > ~/.webmux/acp_session'
echo ""
echo "Or use the WebMux backend to automatically update it."
