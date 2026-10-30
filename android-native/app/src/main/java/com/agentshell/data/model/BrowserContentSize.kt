package com.agentshell.data.model

/** Resolution density is independent of the viewer's local pinch magnification. */
enum class BrowserContentSize(val densityPercent: Int, val label: String, val description: String) {
    LARGER(75, "Larger text", "Bigger text and browser controls"),
    DEFAULT(100, "Default", "Keep the current screen fitting"),
    MORE(125, "More content", "A little smaller, with more room"),
    COMPACT(150, "Even more content", "Smaller text and browser controls"),
    MOST(200, "Most content", "The most room, with the smallest text");

    companion object {
        fun fromDensityPercent(value: Int?) = entries.firstOrNull { it.densityPercent == value } ?: DEFAULT
    }
}
