# AgentShell Flutter Builder
# Stage 1: Build the APK
ARG BUILD_TYPE=debug
FROM agentshell-flutter-base:latest AS builder
ARG BUILD_TYPE
ARG SERVER_LIST=""
ARG OPENAI_API_KEY=""

WORKDIR /app
COPY flutter/ ./

# Copy debug keystore to default location
RUN mkdir -p /root/.android && \
    cp /app/android/debug.keystore /root/.android/debug.keystore

# Generate build config if env vars are provided
RUN if [ -n "$SERVER_LIST" ] || [ -n "$OPENAI_API_KEY" ]; then \
    echo "Generating build config..."; \
    mkdir -p /app/lib/core/config; \
    echo "// Auto-generated build configuration" > /app/lib/core/config/build_config.dart; \
    echo "// DO NOT EDIT - Generated at build time" >> /app/lib/core/config/build_config.dart; \
    echo "" >> /app/lib/core/config/build_config.dart; \
    echo "class BuildConfig {" >> /app/lib/core/config/build_config.dart; \
    echo "  BuildConfig._();" >> /app/lib/core/config/build_config.dart; \
    echo "  static const String defaultServerList = '$SERVER_LIST';" >> /app/lib/core/config/build_config.dart; \
    echo "  static const String defaultApiKey = '$OPENAI_API_KEY';" >> /app/lib/core/config/build_config.dart; \
    echo "}" >> /app/lib/core/config/build_config.dart; \
    echo "Build config generated"; \
    else \
    echo "No build config (env vars not set)"; \
    fi

# Get dependencies
RUN --mount=type=cache,target=/root/.pub-cache \
    flutter pub get

# Build APK (debug or release)
RUN --mount=type=cache,target=/root/.pub-cache \
    --mount=type=cache,target=/app/flutter/build \
    set -eux; \
    flutter build apk --$BUILD_TYPE; \
    apk_path=""; \
    for candidate in \
      "/app/build/app/outputs/flutter-apk/app-${BUILD_TYPE}.apk" \
      "/app/build/app/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk" \
      "/app/build/app/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}-unsigned.apk"; do \
      if [ -f "$candidate" ]; then \
        apk_path="$candidate"; \
        break; \
      fi; \
    done; \
    if [ -z "$apk_path" ]; then \
      echo "ERROR: APK not found for BUILD_TYPE=${BUILD_TYPE}"; \
      find /app/build/app/outputs -maxdepth 5 -type f -name "*.apk" -print || true; \
      exit 1; \
    fi; \
    cp "$apk_path" "/app/agentshell-flutter-${BUILD_TYPE}.apk"

# Stage 2: Minimal image just to hold the artifact
FROM alpine:latest AS final
ARG BUILD_TYPE=debug
COPY --from=builder /app/agentshell-flutter-${BUILD_TYPE}.apk /agentshell-flutter-${BUILD_TYPE}.apk
CMD ["sh", "-c", "echo 'APK is ready at /agentshell-flutter-${BUILD_TYPE}.apk'"]
