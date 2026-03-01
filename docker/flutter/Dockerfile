# WebMux Flutter Builder
# Builds the Flutter Android app from source

# Build from the Flutter base image
FROM webmux-flutter-base:latest AS builder

# Set working directory
WORKDIR /app

# Copy Flutter project files
COPY flutter/ ./

# Get dependencies
RUN --mount=type=cache,target=/root/.pub-cache \
    flutter pub get

# Build debug APK with parallel compilation and build cache
# CRITICAL: We MUST include the .pub-cache mount here too, otherwise the compiler can't find the packages!
RUN --mount=type=cache,target=/root/.pub-cache \
    --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/app/flutter/build \
    flutter build apk --debug

# Create output directory
RUN mkdir -p /output

# Copy APK to output
RUN cp build/app/outputs/flutter-apk/app-debug.apk /output/webmux-flutter-debug.apk

# Default command - APK is already copied to output
CMD ["sh", "-c", "echo 'Flutter APK built successfully'"]
