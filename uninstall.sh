#!/bin/bash
set -e

INSTALL_DIR="/opt/agentshell"

echo "=== AgentShell Uninstaller ==="

echo "=== Step 1: Stopping and disabling service ==="
sudo systemctl stop agentshell 2>/dev/null || true
sudo systemctl disable agentshell 2>/dev/null || true
sudo rm -f /etc/systemd/system/agentshell.service
sudo systemctl daemon-reload

echo ""
echo "=== Step 2: Removing installed files ==="
sudo rm -rf "$INSTALL_DIR"

echo ""
echo "=== Uninstall complete ==="
echo "AgentShell has been removed from your system."
