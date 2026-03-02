# WebMux Flutter Builder
# Stage 1: Build the APK
ARG BUILD_TYPE=debug
FROM webmux-flutter-base:latest AS builder

WORKDIR /app
COPY flutter/ ./

# Get dependencies
RUN --mount=type=cache,target=/root/.pub-cache \
    flutter pub get

# Build APK (debug or release)
RUN --mount=type=cache,target=/root/.pub-cache \
    --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/app/flutter/build \
    flutter build apk --$BUILD_TYPE

# Stage 2: Minimal image just to hold the artifact
FROM alpine:latest AS final
ARG BUILD_TYPE=debug
COPY --from=builder /app/build/app/outputs/flutter-apk/app-${BUILD_TYPE}.apk /webmux-flutter-${BUILD_TYPE}.apk
CMD ["sh", "-c", "echo 'APK is ready at /webmux-flutter-${BUILD_TYPE}.apk'"]
