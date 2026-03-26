package com.agentshell.core.theme

import androidx.compose.ui.graphics.Color

/**
 * Standard 256-color terminal palette.
 * Colors 0-7: standard, 8-15: bright, 16-231: 6x6x6 color cube, 232-255: grayscale.
 */
object TerminalColors {

    // Standard 16 ANSI colors (matching xterm defaults)
    val ansi = intArrayOf(
        0xFF000000.toInt(), // 0  Black
        0xFFCD0000.toInt(), // 1  Red
        0xFF00CD00.toInt(), // 2  Green
        0xFFCDCD00.toInt(), // 3  Yellow
        0xFF0000EE.toInt(), // 4  Blue
        0xFFCD00CD.toInt(), // 5  Magenta
        0xFF00CDCD.toInt(), // 6  Cyan
        0xFFE5E5E5.toInt(), // 7  White
        0xFF7F7F7F.toInt(), // 8  Bright Black (Gray)
        0xFFFF0000.toInt(), // 9  Bright Red
        0xFF00FF00.toInt(), // 10 Bright Green
        0xFFFFFF00.toInt(), // 11 Bright Yellow
        0xFF5C5CFF.toInt(), // 12 Bright Blue
        0xFFFF00FF.toInt(), // 13 Bright Magenta
        0xFF00FFFF.toInt(), // 14 Bright Cyan
        0xFFFFFFFF.toInt(), // 15 Bright White
    )

    // Full 256-color palette (lazily built)
    val palette: IntArray by lazy { buildPalette() }

    private fun buildPalette(): IntArray {
        val p = IntArray(256)

        // 0-15: standard colors
        ansi.copyInto(p, 0)

        // 16-231: 6x6x6 color cube
        val levels = intArrayOf(0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF)
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    val idx = 16 + (36 * r) + (6 * g) + b
                    p[idx] = (0xFF shl 24) or (levels[r] shl 16) or (levels[g] shl 8) or levels[b]
                }
            }
        }

        // 232-255: grayscale ramp
        for (i in 0..23) {
            val v = 8 + (i * 10)
            p[232 + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        return p
    }

    fun paletteColor(index: Int): Color {
        return Color(palette[index.coerceIn(0, 255)])
    }

    fun trueColor(r: Int, g: Int, b: Int): Color {
        return Color(
            red = r.coerceIn(0, 255),
            green = g.coerceIn(0, 255),
            blue = b.coerceIn(0, 255),
        )
    }
}
