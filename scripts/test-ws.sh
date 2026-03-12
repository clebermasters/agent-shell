#!/usr/bin/env bash
# Test WebSocket connection to the backend.
# Usage: ./scripts/test-ws.sh [host]
# Default host is read from CF_DOMAIN in .env (falls back to localhost:4010)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"

if [ -z "$1" ]; then
    CF_DOMAIN=$(grep -E '^CF_DOMAIN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | xargs)
    HOST="${CF_DOMAIN:-localhost:4010}"
else
    HOST="$1"
fi

# Use wss:// for domains, ws:// for localhost
if [[ "$HOST" == localhost* ]] || [[ "$HOST" == 127.* ]]; then
    URL="ws://${HOST}/ws"
else
    URL="wss://${HOST}/ws"
fi

echo "Testing WebSocket: $URL"

# Send list-sessions and print response
echo '{"type":"list-sessions"}' | websocat --no-close --one-message "$URL" 2>&1
