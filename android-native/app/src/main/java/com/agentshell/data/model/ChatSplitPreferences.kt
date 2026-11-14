package com.agentshell.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChatSplitOrientation {
    AUTO,
    STACKED,
    SIDE_BY_SIDE,
}

/** Layout belongs to a chat target; resizing one orientation preserves the other. */
@Serializable
data class ChatSplitPreferences(
    val enabled: Boolean = false,
    val orientation: ChatSplitOrientation = ChatSplitOrientation.AUTO,
    val chatFirst: Boolean = true,
    val stackedFraction: Float = 0.6f,
    val sideBySideFraction: Float = 0.5f,
) {
    fun normalized() = copy(
        stackedFraction = normalizeFraction(stackedFraction, 0.6f),
        sideBySideFraction = normalizeFraction(sideBySideFraction, 0.5f),
    )

    /** Fractions describe chat's share, so swapping panes keeps the chat the same size. */
    fun fractionFor(resolvedOrientation: ChatSplitOrientation): Float = when (resolvedOrientation) {
        ChatSplitOrientation.SIDE_BY_SIDE -> normalizeFraction(sideBySideFraction, 0.5f)
        else -> normalizeFraction(stackedFraction, 0.6f)
    }

    fun withFraction(resolvedOrientation: ChatSplitOrientation, fraction: Float): ChatSplitPreferences =
        when (resolvedOrientation) {
            ChatSplitOrientation.SIDE_BY_SIDE -> copy(sideBySideFraction = normalizeFraction(fraction, 0.5f))
            else -> copy(stackedFraction = normalizeFraction(fraction, 0.6f))
        }

    companion object {
        const val MIN_FRACTION = 0.2f
        const val MAX_FRACTION = 0.8f

        private fun normalizeFraction(value: Float, fallback: Float): Float =
            if (value.isFinite()) value.coerceIn(MIN_FRACTION, MAX_FRACTION) else fallback
    }
}
