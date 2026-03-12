#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB_DIR="$PROJECT_ROOT/flutter/build/web"
OUT="$PROJECT_ROOT/agentshell-web.zip"

if [ ! -f "$WEB_DIR/index.html" ]; then
    echo "Web build not found. Building now..."
    cd "$PROJECT_ROOT/flutter" && ./build.sh web
fi

rm -f "$OUT"
cd "$WEB_DIR" && zip -r "$OUT" .

echo "Package ready: $OUT"
ls -lh "$OUT"
