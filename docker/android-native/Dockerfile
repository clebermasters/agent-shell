# AgentShell Android Native Builder
# Stage 1: Build the APK
ARG BUILD_TYPE=debug
FROM agentshell-android-native-base:latest AS builder
ARG BUILD_TYPE
ARG SERVER_LIST=""
ARG OPENAI_API_KEY=""
ARG SHOW_THINKING=""
ARG SHOW_TOOL_CALLS=""
ARG AUTH_TOKEN=""

WORKDIR /app

# ── Layer 1: Gradle build files only (cached until deps change) ──────────────
# Copy only the files needed for dependency resolution — this layer is cached
# as long as build.gradle.kts, settings.gradle.kts, and gradle/ don't change.
COPY android-native/build.gradle.kts android-native/settings.gradle.kts android-native/gradle.properties ./
COPY android-native/gradle/ ./gradle/
COPY android-native/gradlew ./
COPY android-native/app/build.gradle.kts ./app/build.gradle.kts
COPY android-native/app/proguard-rules.pro ./app/proguard-rules.pro

# Create minimal source structure so Gradle can resolve dependencies
RUN mkdir -p app/src/main/java && \
    echo '<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android"/>' \
    > app/src/main/AndroidManifest.xml

# Download and cache all dependencies (this layer survives source code changes)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon || true

# ── Layer 2: Full source copy (invalidated on any source change) ─────────────
COPY android-native/ ./

# Set up debug keystore — generate a fresh one if not present
RUN mkdir -p /root/.android && \
    if [ ! -f /app/app/debug.keystore ]; then \
      keytool -genkeypair -v \
        -keystore /app/app/debug.keystore \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US"; \
    fi && \
    cp /app/app/debug.keystore /root/.android/debug.keystore

# Generate build config with build-time values (intentionally after COPY to
# avoid invalidating the dependency cache when .env values change)
RUN echo "Generating build config..."; \
    mkdir -p /app/app/src/main/java/com/agentshell/core/config; \
    cat > /app/app/src/main/java/com/agentshell/core/config/BuildConfig.kt <<KOTLIN
package com.agentshell.core.config

object BuildConfig {
    const val DEFAULT_SERVER_LIST = "$SERVER_LIST"
    const val DEFAULT_API_KEY = "$OPENAI_API_KEY"
    const val DEFAULT_SHOW_THINKING = $([ -n "$SHOW_THINKING" ] && echo "$SHOW_THINKING" || echo "true")
    const val DEFAULT_SHOW_TOOL_CALLS = $([ -n "$SHOW_TOOL_CALLS" ] && echo "$SHOW_TOOL_CALLS" || echo "true")
    const val AUTH_TOKEN = "$AUTH_TOKEN"
}
KOTLIN

# ── Layer 3: Compile (Gradle cache persisted across builds) ──────────────────
RUN --mount=type=cache,target=/root/.gradle \
    set -eux; \
    if [ "$BUILD_TYPE" = "release" ]; then \
      ./gradlew assembleRelease --no-daemon; \
    else \
      ./gradlew assembleDebug --no-daemon; \
    fi

# Copy artifact to known location
RUN set -eux; \
    apk_path=""; \
    for candidate in \
      "/app/app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk" \
      "/app/app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}-unsigned.apk"; do \
      if [ -f "$candidate" ]; then \
        apk_path="$candidate"; \
        break; \
      fi; \
    done; \
    if [ -z "$apk_path" ]; then \
      echo "ERROR: APK not found for BUILD_TYPE=${BUILD_TYPE}"; \
      find /app/app/build/outputs -maxdepth 5 -type f -name "*.apk" -print || true; \
      exit 1; \
    fi; \
    cp "$apk_path" "/app/agentshell-native-${BUILD_TYPE}.apk"

# Stage 2: Minimal image just to hold the artifact
FROM alpine:latest AS final
ARG BUILD_TYPE=debug
COPY --from=builder /app/agentshell-native-${BUILD_TYPE}.apk /
CMD ["sh", "-c", "echo 'Build artifact ready'"]
