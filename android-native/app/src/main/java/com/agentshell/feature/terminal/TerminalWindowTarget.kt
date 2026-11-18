package com.agentshell.feature.terminal

/** Session-only navigation must use the real active window, including nonzero base indices. */
internal fun activeTerminalWindow(windows: List<*>): Int? {
    val parsed = windows.map { entry ->
        val window = entry as? Map<*, *> ?: return null
        val number = (window["id"] ?: window["index"]) as? Number ?: return null
        val index = number.toInt()
        if (index < 0 || number.toDouble() != index.toDouble()) return null
        index to (window["active"] == true)
    }
    val active = parsed.filter { it.second }
    return when {
        active.size == 1 -> active.single().first
        active.isEmpty() && parsed.size == 1 -> parsed.single().first
        else -> null
    }
}
