# AgentShell Flutter Builder
# Stage 1: Build the APK
ARG BUILD_TYPE=debug
FROM agentshell-flutter-base:latest AS builder
ARG BUILD_TYPE
ARG SERVER_LIST=""
ARG OPENAI_API_KEY=""
ARG SHOW_THINKING=""
ARG SHOW_TOOL_CALLS=""
ARG AUTH_TOKEN=""

WORKDIR /app
COPY flutter/ ./

# Set up debug keystore — generate a fresh one if not present in the repo.
# debug.keystore is gitignored so CI always generates one; local builds may already have it.
# The keystore must exist at android/debug.keystore (referenced by build.gradle.kts)
# and at /root/.android/debug.keystore (Flutter's default location).
RUN mkdir -p /root/.android && \
    if [ ! -f /app/android/debug.keystore ]; then \
      keytool -genkeypair -v \
        -keystore /app/android/debug.keystore \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US"; \
    fi && \
    cp /app/android/debug.keystore /root/.android/debug.keystore

# Generate build config (without BUILD_TIMESTAMP so this layer is cacheable across builds)
# BUILD_TIMESTAMP is injected post-compile by build.sh — it never changes compiled Dart code
RUN echo "Generating build config..."; \
    mkdir -p /app/lib/core/config; \
    echo "// Auto-generated build configuration" > /app/lib/core/config/build_config.dart; \
    echo "// DO NOT EDIT - Generated at build time" >> /app/lib/core/config/build_config.dart; \
    echo "" >> /app/lib/core/config/build_config.dart; \
    echo "class BuildConfig {" >> /app/lib/core/config/build_config.dart; \
    echo "  BuildConfig._();" >> /app/lib/core/config/build_config.dart; \
    echo "  static const String defaultServerList = '$SERVER_LIST';" >> /app/lib/core/config/build_config.dart; \
    echo "  static const String defaultApiKey = '$OPENAI_API_KEY';" >> /app/lib/core/config/build_config.dart; \
    echo "  static const bool defaultShowThinking = $([ -n "$SHOW_THINKING" ] && echo "$SHOW_THINKING" || echo "true");" >> /app/lib/core/config/build_config.dart; \
    echo "  static const bool defaultShowToolCalls = $([ -n "$SHOW_TOOL_CALLS" ] && echo "$SHOW_TOOL_CALLS" || echo "true");" >> /app/lib/core/config/build_config.dart; \
    echo "  static const String authToken = '$AUTH_TOKEN';" >> /app/lib/core/config/build_config.dart; \
    echo "}" >> /app/lib/core/config/build_config.dart; \
    echo "Build config generated"

# Initialize Linux platform directory if missing (flutter build linux requires it)
RUN if [ "$BUILD_TYPE" = "linux" ] && [ ! -d "linux" ]; then \
      flutter create --platforms=linux .; \
    fi

# Get dependencies
RUN --mount=type=cache,target=/root/.pub-cache \
    flutter pub get

# Build APK, Web, or Linux
RUN --mount=type=cache,target=/root/.pub-cache \
    --mount=type=cache,target=/root/.gradle \
    set -eux; \
    if [ "$BUILD_TYPE" = "web" ]; then \
      flutter build web --release; \
      cp -r /app/build/web /agentshell-web; \
    elif [ "$BUILD_TYPE" = "linux" ]; then \
      flutter build linux --release; \
      cp -r /app/build/linux/x64/release/bundle /agentshell-linux; \
    else \
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
      cp "$apk_path" "/app/agentshell-flutter-${BUILD_TYPE}.apk"; \
    fi

# Stage 2: Minimal image just to hold the artifact
FROM alpine:latest AS final
ARG BUILD_TYPE=debug
COPY --from=builder /app/agentshell-flutter-${BUILD_TYPE}.apk* /
COPY --from=builder /agentshell-web* /agentshell-web/
COPY --from=builder /agentshell-linux* /agentshell-linux/
CMD ["sh", "-c", "echo 'Build artifact ready'"]
