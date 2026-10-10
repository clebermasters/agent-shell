package com.agentshell.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

fun resolvedDarkTheme(mode: String, systemDark: Boolean): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

data class ThemeControls(val mode: String, val setMode: (String) -> Unit)
val LocalThemeControls = staticCompositionLocalOf<ThemeControls?> { null }

@Composable
fun ThemeToggleButton() {
    val controls = LocalThemeControls.current ?: return
    val dark = resolvedDarkTheme(controls.mode, isSystemInDarkTheme())
    IconButton(onClick = { controls.setMode(if (dark) "light" else "dark") }) {
        Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
            contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode")
    }
}
