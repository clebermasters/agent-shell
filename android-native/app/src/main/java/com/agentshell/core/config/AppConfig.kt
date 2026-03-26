package com.agentshell.core.config

object AppConfig {
    const val APP_NAME = "AgentShell"

    // Terminal defaults
    const val DEFAULT_TERMINAL_COLS = 80
    const val DEFAULT_TERMINAL_ROWS = 24
    const val DEFAULT_FONT_SIZE = 14.0f
    const val MIN_FONT_SIZE = 8.0f
    const val MAX_FONT_SIZE = 32.0f
    const val FONT_SIZE_STEP = 1.0f

    // WebSocket
    const val WS_PING_INTERVAL_MS = 30_000L
    const val WS_PONG_TIMEOUT_MS = 20_000L
    const val WS_RECONNECT_DELAY_MS = 5_000L
    const val WS_RECONNECT_OFFLINE_THRESHOLD = 3

    // Timeouts
    const val CONNECTION_TIMEOUT_MS = 30_000L
    const val READ_TIMEOUT_MS = 30_000L

    // System stats
    const val SYSTEM_STATS_REFRESH_MS = 5_000L

    // Terminal buffer
    const val MAX_SCROLLBACK_LINES = 50_000
    const val PARAGRAPH_CACHE_SIZE = 8192
    const val LINE_PICTURE_CACHE_SIZE = 1024
    const val MAX_TERMINAL_CONTROLLERS = 20

    // Audio
    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_BIT_RATE = 128_000

    // Server list format: "host:port,Label|host:port,Label"
    fun parseServerList(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split(",", limit = 2)
            if (parts.size == 2) {
                val address = parts[0].trim()
                val label = parts[1].trim()
                if (address.isNotEmpty() && label.isNotEmpty()) address to label else null
            } else null
        }
    }
}
