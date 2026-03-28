package com.agentshell.feature.splitscreen.model

enum class PanelType(val value: String) {
    TERMINAL("TERMINAL"),
    CHAT("CHAT");

    companion object {
        fun fromValue(value: String): PanelType =
            entries.find { it.value == value } ?: TERMINAL
    }
}
