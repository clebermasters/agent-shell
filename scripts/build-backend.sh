#!/usr/bin/env bash
set -euo pipefail
AGENTSHELL_PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
if [[ "$(uname -s)" == Darwin ]] && ! xcode-select -p >/dev/null 2>&1; then
    echo "Xcode Command Line Tools are required. Run xcode-select --install, finish installation, then retry." >&2
    exit 1
fi
if ! command -v cargo >/dev/null 2>&1; then
    echo "Cargo is missing. Run ./install.sh to install prerequisites, or install Rust first." >&2
    exit 1
fi
exec cargo build --release --locked --manifest-path "$AGENTSHELL_PROJECT/backend-rust/Cargo.toml" "$@"
