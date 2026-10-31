#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ "$(uname -s)" == Darwin ]]; then
    export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
    if ! command -v python3 >/dev/null 2>&1; then
        for argument in "$@"; do
            case "$argument" in
                --check|--dry-run|--help|-h)
                    echo "Python 3.9+ is required. Install Homebrew Python before this read-only command." >&2
                    exit 1
                    ;;
            esac
        done
        if ! command -v brew >/dev/null 2>&1; then
            echo "Homebrew is required to bootstrap Python. Install it from https://brew.sh, then retry." >&2
            exit 1
        fi
        brew install python
    fi
    exec python3 "$PROJECT_DIR/scripts/install-macos.py" "$@"
fi
# Bootstrap Python before handing off to the same dependency installer.
if ! command -v python3 >/dev/null 2>&1; then
    for argument in "$@"; do
        case "$argument" in
            --check|--help|-h)
                echo "Python 3 is missing. Run ./install.sh to bootstrap it on Debian/Ubuntu."
                exit 1
                ;;
        esac
    done
    if ! command -v apt-get >/dev/null 2>&1; then
        echo "Automatic dependency installation requires Debian/Ubuntu (apt-get)." >&2
        exit 1
    fi
    if [[ "$EUID" -eq 0 ]]; then
        apt-get update
        apt-get install -y python3
    else
        sudo apt-get update
        sudo apt-get install -y python3
    fi
fi
exec python3 "$PROJECT_DIR/scripts/install-backend.py" "$@"
