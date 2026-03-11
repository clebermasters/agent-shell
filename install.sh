#!/bin/bash
set -e

INSTALL_DIR="/opt/agentshell"
SERVICE_USER="${SERVICE_USER:-$(whoami)}"
FRONTEND_PORT=5174
BACKEND_PORT=4010
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== AgentShell Installer ==="

if [ "$EUID" -eq 0 ]; then
    echo "Running as root - will install system-wide"
    SERVICE_USER="${SERVICE_USER:-root}"
else
    echo "Running as regular user - will install for current user"
    SERVICE_USER=$(whoami)
fi

echo "Installation directory: $INSTALL_DIR"
echo "Service user: $SERVICE_USER"
echo ""

echo "=== Step 1: Building Rust backend (release) ==="
cd "$PROJECT_DIR"
cd backend-rust
cargo build --release
echo "Backend built successfully"

echo ""
echo "=== Step 2: Installing files ==="
sudo systemctl stop agentshell 2>/dev/null || true
sudo mkdir -p "$INSTALL_DIR/backend"
sudo mkdir -p "$INSTALL_DIR/certs"

sudo cp "$PROJECT_DIR/backend-rust/target/release/agentshell-backend" "$INSTALL_DIR/backend/"
sudo cp -r "$PROJECT_DIR/certs/"* "$INSTALL_DIR/certs/" 2>/dev/null || true

cd "$INSTALL_DIR/backend"
sudo ln -sf ../certs certs
cd -

sudo chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

echo ""
echo "=== Step 3: Creating systemd service ==="

SERVICE_FILE="/etc/systemd/system/agentshell.service"

sudo tee "$SERVICE_FILE" > /dev/null << EOF
[Unit]
Description=AgentShell - Web-based TMUX session viewer
After=network.target

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$INSTALL_DIR/backend
ExecStart=$INSTALL_DIR/backend/agentshell-backend
Environment="RUST_LOG=info"
Environment="PATH=/home/cleber_rodrigues/.opencode/bin:/home/cleber_rodrigues/.cargo/bin:/usr/local/bin:/usr/bin:/bin"
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

echo "Created systemd service at $SERVICE_FILE"

echo ""
echo "=== Step 4: Starting service ==="
sudo systemctl daemon-reload
sudo systemctl enable agentshell
sudo systemctl start agentshell

echo ""
echo "=== Installation complete ==="
echo "AgentShell service is running!"
echo ""
echo "Frontend: http://localhost:$FRONTEND_PORT"
echo "Backend:  http://localhost:$BACKEND_PORT"
echo ""
echo "Commands:"
echo "  sudo systemctl status agentshell   # Check status"
echo "  sudo systemctl restart agentshell  # Restart"
echo "  sudo systemctl stop agentshell     # Stop"
