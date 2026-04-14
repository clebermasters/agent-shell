#!/bin/bash
set -e

INSTALL_DIR="/opt/agentshell"
SERVICE_USER="${SERVICE_USER:-${SUDO_USER:-$(whoami)}}"
FRONTEND_PORT=5174
BACKEND_PORT=4010
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$PROJECT_DIR/.env"

get_user_home() {
    local user="$1"
    local home
    home=$(getent passwd "$user" 2>/dev/null | cut -d: -f6)
    if [ -z "$home" ]; then
        home=$(eval echo "~$user")
    fi
    echo "$home"
}

build_service_path() {
    local user="$1"
    local user_home="$2"
    local path_list=()
    local codex_bin=""
    local opencode_bin=""
    local bun_bin=""

    if command -v sudo >/dev/null 2>&1; then
        codex_bin=$(sudo -u "$user" bash -lc 'command -v codex 2>/dev/null || true')
        opencode_bin=$(sudo -u "$user" bash -lc 'command -v opencode 2>/dev/null || true')
        bun_bin=$(sudo -u "$user" bash -lc 'command -v bun 2>/dev/null || true')
    else
        codex_bin=$(command -v codex 2>/dev/null || true)
        opencode_bin=$(command -v opencode 2>/dev/null || true)
        bun_bin=$(command -v bun 2>/dev/null || true)
    fi

    [ -n "$codex_bin" ] && path_list+=("$(dirname "$codex_bin")")
    [ -n "$opencode_bin" ] && path_list+=("$(dirname "$opencode_bin")")
    [ -n "$bun_bin" ] && path_list+=("$(dirname "$bun_bin")")
    path_list+=(
        "$user_home/.local/bin"
        "$user_home/.npm-global/bin"
        "$user_home/.opencode/bin"
        "$user_home/.bun/bin"
        "$user_home/.cargo/bin"
        "/usr/local/bin"
        "/usr/bin"
        "/bin"
    )

    awk '
        BEGIN { RS=":"; ORS=":" }
        NF && !seen[$0]++ { print $0 }
    ' <<< "$(IFS=:; echo "${path_list[*]}")" | sed 's/:$//'
}

# Read runtime settings from .env if not already set
if [ -f "$ENV_FILE" ]; then
    if [ -z "$AUTH_TOKEN" ]; then
        AUTH_TOKEN=$(grep -E '^AUTH_TOKEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | xargs)
    fi

    if [ -z "$ALLOWED_ORIGIN" ]; then
        ALLOWED_ORIGIN=$(grep -E '^ALLOWED_ORIGIN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | xargs)
    fi
fi

echo "=== AgentShell Installer ==="

if [ "$EUID" -eq 0 ]; then
    echo "Running as root - will install system-wide"
    # When run via sudo, use SUDO_USER so the service runs as the real user
    # (needed for tmux session access)
    SERVICE_USER="${SERVICE_USER:-${SUDO_USER:-root}}"
else
    echo "Running as regular user - will install for current user"
    SERVICE_USER=$(whoami)
fi

SERVICE_HOME="$(get_user_home "$SERVICE_USER")"
SERVICE_PATH="$(build_service_path "$SERVICE_USER" "$SERVICE_HOME")"

echo "Installation directory: $INSTALL_DIR"
echo "Service user: $SERVICE_USER"
echo "Service home: $SERVICE_HOME"
echo "Service PATH: $SERVICE_PATH"
if [ -n "$AUTH_TOKEN" ]; then
    echo "AUTH_TOKEN: set (${#AUTH_TOKEN} chars)"
else
    echo "AUTH_TOKEN: NOT SET — backend will be open to all connections"
fi
if [ -n "$ALLOWED_ORIGIN" ]; then
    echo "ALLOWED_ORIGIN: $ALLOWED_ORIGIN"
else
    echo "ALLOWED_ORIGIN: not set (only localhost origins will be allowed)"
fi
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
Environment="AUTH_TOKEN=$AUTH_TOKEN"
Environment="ALLOWED_ORIGIN=$ALLOWED_ORIGIN"
Environment="PATH=$SERVICE_PATH"
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
