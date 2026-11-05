package com.agentshell.core.diagnostics

/** Fixed labels only: never retain payloads, URLs, conversation names, or exceptions. */
enum class DiagnosticSocketRole { PRIMARY, SESSION, CHAT_MONITOR, OFFLINE_SYNC }

internal class BatteryDiagnosticCounters {
    @Volatile var enabled = false
        private set
    private var foreground = false
    private var screenOn = false
    private var wakeHeld = false
    private var serviceRunning = false
    private var wakeSince = 0L
    private var heldMillis = 0L
    private val sockets = mutableMapOf<Any, DiagnosticSocketRole>()
    private val counters = linkedMapOf<String, Long>()

    @Synchronized fun enable(now: Long) {
        counters.clear()
        heldMillis = 0
        wakeSince = now
        enabled = true
    }

    @Synchronized fun disable() { enabled = false; counters.clear() }

    @Synchronized fun visibility(foreground: Boolean, screenOn: Boolean) {
        this.foreground = foreground
        this.screenOn = screenOn
    }

    @Synchronized fun wakeLock(held: Boolean, now: Long) {
        if (wakeHeld == held) return
        if (wakeHeld && enabled) heldMillis += (now - wakeSince).coerceAtLeast(0)
        wakeHeld = held
        wakeSince = now
        event(if (held) "wake_acquired" else "wake_released")
    }

    @Synchronized fun socket(token: Any, role: DiagnosticSocketRole, connected: Boolean) {
        if (connected) sockets[token] = role
        else if (sockets.remove(token) != null) event("socket_disconnected", role)
    }

    @Synchronized fun event(name: String, role: DiagnosticSocketRole? = null) {
        if (name == "service_started") serviceRunning = true
        if (name == "service_stopped") serviceRunning = false
        if (!enabled) return
        // Call sites supply constants. This allowlist also prevents accidental log injection.
        if (name !in EVENTS) return
        add("${bucket()}.${role?.name ?: "APP"}.$name", 1)
    }

    @Synchronized fun transfer(role: String, type: String?, incoming: Boolean, bytes: Long) {
        if (!enabled || role !in ROLES) return
        val key = "${bucket()}.$role.${category(type)}.${if (incoming) "rx" else "tx"}"
        add("${key}_messages", 1)
        add("${key}_bytes", bytes.coerceAtLeast(0))
    }

    @Synchronized fun httpStatus(code: Int, type: String? = null) {
        if (!enabled) return
        val label = when (code) {
            401, 403, 404, 410, 429 -> code.toString()
            in 200..299 -> "2xx"
            in 300..399 -> "3xx"
            in 400..499 -> "other_4xx"
            in 500..599 -> "5xx"
            else -> "other"
        }
        add("${bucket()}.HTTP.${category(type)}.status_$label", 1)
    }

    @Synchronized fun httpFailure(cancelled: Boolean, type: String? = null) {
        if (enabled) add("${bucket()}.HTTP.${category(type)}.${if (cancelled) "cancelled" else "transport_failed"}", 1)
    }

    @Synchronized fun drain(now: Long): Map<String, Long> {
        if (wakeHeld) {
            heldMillis += (now - wakeSince).coerceAtLeast(0)
            wakeSince = now
        }
        return linkedMapOf<String, Long>().apply {
            put("wake_held_ms", heldMillis)
            put("wake_held", if (wakeHeld) 1 else 0)
            put("service_running", if (serviceRunning) 1 else 0)
            for (role in DiagnosticSocketRole.entries) put("sockets_${role.name}", sockets.values.count { it == role }.toLong())
            putAll(counters)
            counters.clear()
            heldMillis = 0
        }
    }

    private fun bucket() = "${if (foreground) "foreground" else "background"}.${if (screenOn) "screen_on" else "screen_off"}"
    private fun add(key: String, value: Long) { counters[key] = (counters[key] ?: 0) + value }

    companion object {
        private val ROLES = DiagnosticSocketRole.entries.map { it.name }.toSet() + "HTTP"
        private val EVENTS = setOf("service_started", "service_stopped", "wake_acquired", "wake_released",
            "socket_attempt", "socket_open", "socket_failed", "socket_closed", "socket_disconnected", "reconnect_scheduled", "heartbeat_timeout",
            "cache_sync_started", "cache_sync_finished", "cache_sync_failed", "notification_received",
            "attachment_attempt_started", "attachment_download_succeeded", "attachment_retry_scheduled",
            "attachment_unavailable", "attachment_auth_blocked", "attachment_cancelled", "attachment_size_mismatch",
            "service_timeout", "service_start_rejected", "connection_wake_renewed", "connection_timer_expired", "connection_mode_changed")

        internal fun category(type: String?): String = when (type) {
            "ping", "pong" -> "heartbeat"
            "input", "input-via-tmux", "output", "resize", "attach-session", "detach-session" -> "terminal"
            "chat-history", "get-chat-history", "chat-history-page", "chat-history-sync", "sync-chat-history",
            "get-chat-history-page", "get-history-sync-capabilities", "history-sync-capabilities" -> "history"
            "get-history-state", "history-state", "get-history-page", "history-page", "load-more-chat-history",
            "load-more-bound-chat-history" -> "history"
            "chat-event", "watch-chat-log", "unwatch-chat-log", "chat-binding", "chat-send", "chat-send-result" -> "chat"
            "stats", "get-stats", "claude-usage", "get-claude-usage", "codex-usage", "get-codex-usage" -> "stats"
            "notification-event", "get-notifications", "notifications-list", "get-agent-activities", "agent-activities", "agent-activity" -> "notifications"
            "list-sessions", "sessions-list", "list-windows", "windows-list", "acp-list-sessions", "acp-sessions-listed" -> "catalog"
            "send-file-to-chat", "file-content", "file-chunk", "file-upload", "file-download", "http_files" -> "files"
            "http_browser" -> "browser"
            "http_audio" -> "audio"
            else -> "other"
        }
    }
}
