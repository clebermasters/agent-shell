#!/usr/bin/env bash
set -euo pipefail
AGENTSHELL_PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/.cargo/bin:$PATH"
exec python3 "$AGENTSHELL_PROJECT/scripts/backend-runtime.py" run \
    --executable "${AGENTSHELL_BACKEND_BIN:-$AGENTSHELL_PROJECT/backend-rust/target/release/agentshell-backend}" \
    --working-directory "${AGENTSHELL_DATA_DIR:-$AGENTSHELL_PROJECT/backend-rust}" \
    --env-file "${AGENTSHELL_ENV_FILE:-$AGENTSHELL_PROJECT/.env}" "$@"
