package com.agentshell.core.power

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

@Serializable
enum class ConnectionMode { LIVE, BATTERY_SAVER }

/** A user-selected mode and an absolute deadline, independent of any service instance. */
@Serializable
data class ConnectionModeState(
    val mode: ConnectionMode = ConnectionMode.LIVE,
    val liveUntilEpochMillis: Long? = null,
    val timerMinutes: Int = 0,
) {
    fun effectiveMode(nowMillis: Long): ConnectionMode =
        if (mode == ConnectionMode.LIVE && liveUntilEpochMillis?.let { it <= nowMillis } == true)
            ConnectionMode.BATTERY_SAVER else mode

    internal fun selectMode(selected: ConnectionMode, nowMillis: Long): ConnectionModeState = when {
        selected == ConnectionMode.BATTERY_SAVER -> copy(mode = selected, liveUntilEpochMillis = null)
        effectiveMode(nowMillis) == ConnectionMode.LIVE -> this
        else -> copy(mode = ConnectionMode.LIVE, liveUntilEpochMillis = deadline(nowMillis, timerMinutes))
    }

    internal fun selectTimer(minutes: Int, nowMillis: Long): ConnectionModeState {
        require(minutes in TIMER_PRESETS_MINUTES) { "Unsupported Live timer duration" }
        val effective = effectiveMode(nowMillis)
        return copy(mode = effective, timerMinutes = minutes,
            liveUntilEpochMillis = if (effective == ConnectionMode.LIVE) deadline(nowMillis, minutes) else null)
    }

    internal fun expire(expectedDeadline: Long, nowMillis: Long): ConnectionModeState =
        if (mode == ConnectionMode.LIVE && liveUntilEpochMillis == expectedDeadline && expectedDeadline <= nowMillis)
            copy(mode = ConnectionMode.BATTERY_SAVER, liveUntilEpochMillis = null)
        else this

    companion object {
        val TIMER_PRESETS_MINUTES = listOf(0, 1, 5, 15, 30, 60, 120, 240)

        private fun deadline(nowMillis: Long, minutes: Int): Long? {
            if (minutes == 0) return null
            val duration = minutes * 60_000L
            return if (nowMillis > Long.MAX_VALUE - duration) Long.MAX_VALUE else nowMillis + duration
        }
    }
}

/** A present but damaged record fails closed; an absent record preserves the Live default. */
internal object ConnectionModeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: ConnectionModeState): String = json.encodeToString(state)

    fun decode(raw: String?): ConnectionModeState {
        if (raw == null) return ConnectionModeState()
        return runCatching {
            val fields = json.parseToJsonElement(raw).jsonObject
            require(fields.containsKey("mode") && fields.containsKey("timerMinutes"))
            val state = json.decodeFromString<ConnectionModeState>(raw)
            require(state.timerMinutes in ConnectionModeState.TIMER_PRESETS_MINUTES)
            if (state.mode == ConnectionMode.LIVE) {
                require((state.timerMinutes == 0) == (state.liveUntilEpochMillis == null))
                state
            } else state.copy(liveUntilEpochMillis = null)
        }.getOrElse { ConnectionModeState(mode = ConnectionMode.BATTERY_SAVER) }
    }
}
