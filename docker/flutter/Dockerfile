# WebMux Flutter Builder
# Stage 1: Build the APK
ARG BUILD_TYPE=debug
FROM webmux-flutter-base:latest AS builder
ARG BUILD_TYPE

WORKDIR /app
COPY flutter/ ./

# Get dependencies
RUN --mount=type=cache,target=/root/.pub-cache \
    flutter pub get

# Build APK (debug or release)
RUN --mount=type=cache,target=/root/.pub-cache \
    --mount=type=cache,target=/root/.gradle \
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
    cp "$apk_path" "/app/webmux-flutter-${BUILD_TYPE}.apk"

# Stage 2: Minimal image just to hold the artifact
FROM alpine:latest AS final
ARG BUILD_TYPE=debug
COPY --from=builder /app/webmux-flutter-${BUILD_TYPE}.apk /webmux-flutter-${BUILD_TYPE}.apk
CMD ["sh", "-c", "echo 'APK is ready at /webmux-flutter-${BUILD_TYPE}.apk'"]
