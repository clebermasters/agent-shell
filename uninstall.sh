#!/bin/bash
set -e

INSTALL_DIR="/opt/webmux"

echo "=== WebMux Uninstaller ==="

echo "=== Step 1: Stopping and disabling service ==="
sudo systemctl stop webmux 2>/dev/null || true
sudo systemctl disable webmux 2>/dev/null || true
sudo rm -f /etc/systemd/system/webmux.service
sudo systemctl daemon-reload

echo ""
echo "=== Step 2: Removing installed files ==="
sudo rm -rf "$INSTALL_DIR"

echo ""
echo "=== Uninstall complete ==="
echo "WebMux has been removed from your system."
