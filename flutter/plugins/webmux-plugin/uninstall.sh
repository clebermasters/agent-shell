#!/usr/bin/env bash
#
# WebMux Plugin Uninstaller for OpenCode
#
# This script removes the WebMux plugin from OpenCode.
#

set -euo pipefail

OPENCODE_PLUGINS_DIR="${HOME}/.config/opencode/plugins"
PLUGIN_TARGET="${OPENCODE_PLUGINS_DIR}/webmux.js"
SESSION_FILE="${HOME}/.webmux/acp_session"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

# Remove plugin symlink
if [ -e "$PLUGIN_TARGET" ]; then
    log_info "Removing WebMux plugin: $PLUGIN_TARGET"
    rm -f "$PLUGIN_TARGET"
else
    log_info "WebMux plugin not found, nothing to remove"
fi

# Optionally remove session file (ask user)
if [ -f "$SESSION_FILE" ]; then
    read -p "Remove session file ($SESSION_FILE)? [y/N] " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        rm -f "$SESSION_FILE"
        log_info "Removed session file"
    fi
fi

log_info "WebMux plugin uninstalled successfully!"
