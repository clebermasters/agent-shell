#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB_DIR="$PROJECT_ROOT/flutter/build/web"
PORT="${1:-8080}"

if [ ! -f "$WEB_DIR/index.html" ]; then
    echo "Web build not found. Building now..."
    cd "$PROJECT_ROOT/flutter" && ./build.sh web
fi

echo "Serving AgentShell web app at http://localhost:$PORT"
echo "Press Ctrl+C to stop."
cd "$WEB_DIR" && python3 -m http.server "$PORT"
