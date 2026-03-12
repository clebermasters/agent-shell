#!/bin/bash
# Deploy Flutter web build to S3 + purge Cloudflare cache.
#
# Reads config from .env or environment variables:
#   S3_WEB_BUCKET      S3 bucket URL (e.g. s3://your-bucket)
#   CF_API_KEY         Cloudflare Global API Key
#   CF_EMAIL           Cloudflare account email
#   CF_ZONE_ID         Cloudflare Zone ID (auto-detected from CF_DOMAIN if not set)
#   CF_DOMAIN          Domain to purge (default: derived from S3_WEB_BUCKET)
#
# Usage:
#   ./scripts/deploy-web.sh
#   ./scripts/deploy-web.sh --build   # also triggers a fresh web build first

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
WEB_ZIP="$PROJECT_ROOT/agentshell-web.zip"
TMP_BASE="/tmp/agentshell-web-deploy"
DO_BUILD=false

# Parse args
for arg in "$@"; do
    case "$arg" in
        --build|-b) DO_BUILD=true ;;
    esac
done

# Load .env
if [ -f "$ENV_FILE" ]; then
    while IFS='=' read -r key value || [ -n "$key" ]; do
        [[ -z "$key" || "$key" =~ ^# ]] && continue
        key=$(echo "$key" | xargs)
        value=$(echo "$value" | xargs)
        case "$key" in
            S3_WEB_BUCKET) [ -z "$S3_WEB_BUCKET" ] && S3_WEB_BUCKET="$value" ;;
            CF_API_KEY)    [ -z "$CF_API_KEY" ]    && CF_API_KEY="$value" ;;
            CF_EMAIL)      [ -z "$CF_EMAIL" ]      && CF_EMAIL="$value" ;;
            CF_ZONE_ID)    [ -z "$CF_ZONE_ID" ]    && CF_ZONE_ID="$value" ;;
            CF_DOMAIN)     [ -z "$CF_DOMAIN" ]     && CF_DOMAIN="$value" ;;
        esac
    done < "$ENV_FILE"
fi

# Validate required config
if [ -z "$S3_WEB_BUCKET" ]; then
    echo "ERROR: S3_WEB_BUCKET is not set. Add it to .env or pass as env var."
    exit 1
fi

# Derive CF_DOMAIN from bucket name if not set (s3://your-bucket → your-bucket)
if [ -z "$CF_DOMAIN" ]; then
    CF_DOMAIN="${S3_WEB_BUCKET#s3://}"
    CF_DOMAIN="${CF_DOMAIN%%/*}"
fi

# Build if requested
if [ "$DO_BUILD" = true ]; then
    echo "=== Building Flutter web ==="
    cd "$PROJECT_ROOT/flutter" && ./build.sh web
    cd "$PROJECT_ROOT"
fi

if [ ! -f "$WEB_ZIP" ]; then
    echo "ERROR: Web build not found at $WEB_ZIP"
    echo "Run './build.sh web' or use --build flag."
    exit 1
fi

# ── S3 DEPLOY ────────────────────────────────────────────────────────────────
echo ""
echo "=== Deploying to $S3_WEB_BUCKET ==="

rm -rf "$TMP_BASE"
mkdir -p "$TMP_BASE"
unzip -q "$WEB_ZIP" -d "$TMP_BASE"
WEB_ROOT="$TMP_BASE/agentshell-web"

NO_CACHE_FILES=(index.html flutter_service_worker.js manifest.json flutter_bootstrap.js flutter.js)

# Upload no-cache entry-point files first
echo "Uploading no-cache entry-point files..."
for f in "${NO_CACHE_FILES[@]}"; do
    if [ -f "$WEB_ROOT/$f" ]; then
        aws s3 cp "$WEB_ROOT/$f" "$S3_WEB_BUCKET/$f" \
            --cache-control "no-cache, no-store, must-revalidate" \
            --content-type "$(file --mime-type -b "$WEB_ROOT/$f")"
        echo "  $f"
    fi
done

# Sync everything else with long-lived cache (content-hashed assets are safe)
echo "Syncing hashed assets..."
EXCLUDES=()
for f in "${NO_CACHE_FILES[@]}"; do
    EXCLUDES+=(--exclude "$f")
done
aws s3 sync "$WEB_ROOT" "$S3_WEB_BUCKET" \
    "${EXCLUDES[@]}" \
    --cache-control "public, max-age=31536000, immutable" \
    --delete

# Re-upload no-cache files after sync (--delete would remove them otherwise)
echo "Re-pinning no-cache files..."
for f in "${NO_CACHE_FILES[@]}"; do
    if [ -f "$WEB_ROOT/$f" ]; then
        aws s3 cp "$WEB_ROOT/$f" "$S3_WEB_BUCKET/$f" \
            --cache-control "no-cache, no-store, must-revalidate" \
            --content-type "$(file --mime-type -b "$WEB_ROOT/$f")"
    fi
done

rm -rf "$TMP_BASE"
echo "S3 deploy complete."

# ── CLOUDFLARE CACHE PURGE ───────────────────────────────────────────────────
if [ -z "$CF_API_KEY" ] || [ -z "$CF_EMAIL" ]; then
    echo ""
    echo "WARNING: CF_API_KEY / CF_EMAIL not set — skipping Cloudflare cache purge."
    echo "Add them to .env or purge manually in the Cloudflare dashboard."
else
    echo ""
    echo "=== Purging Cloudflare cache for $CF_DOMAIN ==="

    # Auto-detect Zone ID if not provided
    if [ -z "$CF_ZONE_ID" ]; then
        ROOT_DOMAIN=$(echo "$CF_DOMAIN" | awk -F. '{print $(NF-1)"."$NF}')
        CF_ZONE_ID=$(curl -s "https://api.cloudflare.com/client/v4/zones?name=$ROOT_DOMAIN" \
            -H "X-Auth-Key: $CF_API_KEY" \
            -H "X-Auth-Email: $CF_EMAIL" \
            -H "Content-Type: application/json" \
            | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['result'][0]['id'])" 2>/dev/null)

        if [ -z "$CF_ZONE_ID" ]; then
            echo "ERROR: Could not auto-detect Cloudflare Zone ID for $ROOT_DOMAIN"
            echo "Set CF_ZONE_ID in .env manually."
            exit 1
        fi
        echo "  Zone ID: $CF_ZONE_ID"
    fi

    # Build list of URLs to purge
    PURGE_URLS=$(python3 -c "
import json
domain = 'https://$CF_DOMAIN'
files = [
    '/',
    '/index.html',
    '/main.dart.js',
    '/flutter_service_worker.js',
    '/flutter_bootstrap.js',
    '/flutter.js',
    '/manifest.json',
]
print(json.dumps({'files': [domain + f for f in files]}))
")

    RESULT=$(curl -s -X POST \
        "https://api.cloudflare.com/client/v4/zones/$CF_ZONE_ID/purge_cache" \
        -H "X-Auth-Key: $CF_API_KEY" \
        -H "X-Auth-Email: $CF_EMAIL" \
        -H "Content-Type: application/json" \
        --data "$PURGE_URLS")

    if echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if d.get('success') else 1)" 2>/dev/null; then
        echo "  Cloudflare cache purged successfully."
    else
        echo "  ERROR purging Cloudflare cache:"
        echo "$RESULT"
        exit 1
    fi
fi

echo ""
echo "✓ Deploy complete → https://$CF_DOMAIN"
