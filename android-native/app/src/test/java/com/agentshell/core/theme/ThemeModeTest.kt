package com.agentshell.core.theme

import org.junit.Assert.*
import org.junit.Test

class ThemeModeTest {
    @Test fun explicit_choice_overrides_system_and_system_choice_follows_it() {
        assertFalse(resolvedDarkTheme("light", true))
        assertTrue(resolvedDarkTheme("dark", false))
        assertFalse(resolvedDarkTheme("system", false))
        assertTrue(resolvedDarkTheme("system", true))
    }
}
