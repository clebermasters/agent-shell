plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.agentshell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.agentshell"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Sign release with debug key (Docker generates app/debug.keystore)
            // Replace with a proper release keystore for Play Store distribution
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

// ── Generate BuildConfig.kt from ../.env (mirrors Flutter's Docker-based generation) ──
// In Docker: Dockerfile generates BuildConfig.kt BEFORE Gradle runs → task skips.
// Locally: Gradle reads ../.env and generates BuildConfig.kt on every build.
val generateBuildConfig by tasks.registering {
    val envFile = rootProject.file("../.env")
    val outputDir = file("src/main/java/com/agentshell/core/config")
    val outputFile = file("$outputDir/BuildConfig.kt")

    // Only declare .env as input if it actually exists (avoids Docker failure)
    if (envFile.exists()) {
        inputs.file(envFile)
    }
    outputs.file(outputFile)

    onlyIf {
        // Skip if .env is missing AND BuildConfig.kt already exists (Docker path)
        envFile.exists() || !outputFile.exists()
    }

    doLast {
        val env = mutableMapOf<String, String>()
        if (envFile.exists()) {
            envFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx > 0) {
                        val key = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).trim()
                        env[key] = value
                    }
                }
            }
            val serverList = env["SERVER_LIST"] ?: ""
            val apiKey = env["OPENAI_API_KEY"] ?: ""
            val showThinking = env["SHOW_THINKING"] ?: "true"
            val showToolCalls = env["SHOW_TOOL_CALLS"] ?: "true"
            val authToken = env["AUTH_TOKEN"] ?: ""

            outputDir.mkdirs()
            outputFile.writeText(
                """
                |package com.agentshell.core.config
                |
                |/**
                | * Auto-generated from ../.env by Gradle task [generateBuildConfig].
                | * DO NOT EDIT — changes will be overwritten on next build.
                | * Docker builds also overwrite this file via Dockerfile.
                | */
                |object BuildConfig {
                |    const val DEFAULT_SERVER_LIST = "$serverList"
                |    const val DEFAULT_API_KEY = "$apiKey"
                |    const val DEFAULT_SHOW_THINKING = $showThinking
                |    const val DEFAULT_SHOW_TOOL_CALLS = $showToolCalls
                |    const val AUTH_TOKEN = "$authToken"
                |}
                """.trimMargin()
            )
            println("BuildConfig.kt generated from .env (SERVER_LIST: ${if (serverList.isNotEmpty()) "set" else "empty"})")
        } else {
            println("BuildConfig.kt: .env not found, skipping (Docker generates BuildConfig.kt via Dockerfile)")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(generateBuildConfig)
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity + Lifecycle
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Local Storage
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Markdown rendering
    implementation("io.noties.markwon:core:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:ext-strikethrough:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:ext-tables:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
    implementation("io.noties.markwon:syntax-highlight:4.6.2") {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }

    // Audio playback
    implementation("androidx.media3:media3-exoplayer:1.5.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // WebView
    implementation("androidx.webkit:webkit:1.12.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
