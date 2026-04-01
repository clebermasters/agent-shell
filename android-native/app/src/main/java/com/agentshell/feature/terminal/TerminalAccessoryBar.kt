package com.agentshell.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Horizontal accessory bar with special terminal keys.
 * Modifier state (CTRL/ALT/SHIFT) is owned by the parent (TerminalScreen)
 * so it applies to both accessory bar keys AND soft keyboard input.
 */
@Composable
fun TerminalAccessoryBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onShiftToggle: () -> Unit,
    onKeyPressed: (String) -> Unit,
    onModifiersReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun handleKey(key: String) {
        var sequence = when (key) {
            "ESC"   -> "\u001b"
            "TAB"   -> if (shiftActive) "\u001b[Z" else "\t"
            "UP"    -> "\u001b[A"
            "DOWN"  -> "\u001b[B"
            "LEFT"  -> "\u001b[D"
            "RIGHT" -> "\u001b[C"
            "HOME"  -> "\u001b[H"
            "END"   -> "\u001b[F"
            "PGUP"  -> "\u001b[5~"
            "PGDN"  -> "\u001b[6~"
            "DEL"   -> "\u001b[3~"
            "INS"   -> "\u001b[2~"
            "F1"    -> "\u001bOP"
            "F2"    -> "\u001bOQ"
            "F3"    -> "\u001bOR"
            "F4"    -> "\u001bOS"
            "F5"    -> "\u001b[15~"
            "F6"    -> "\u001b[17~"
            "F7"    -> "\u001b[18~"
            "F8"    -> "\u001b[19~"
            "F9"    -> "\u001b[20~"
            "F10"   -> "\u001b[21~"
            "F11"   -> "\u001b[23~"
            "F12"   -> "\u001b[24~"
            else    -> key
        }

        // Apply CTRL: single printable char → control code
        if (ctrlActive && sequence.length == 1) {
            val ch = sequence[0]
            sequence = when {
                ch in 'a'..'z' -> String(charArrayOf((ch.code - 0x60).toChar()))
                ch in 'A'..'Z' -> String(charArrayOf((ch.code - 0x40).toChar()))
                ch == ' '      -> "\u0000"
                ch == '['      -> "\u001b"
                ch == '\\'     -> "\u001c"
                ch == ']'      -> "\u001d"
                ch == '_'      -> "\u001f"
                else -> sequence
            }
        }

        // Apply ALT: prefix with ESC
        if (altActive && !sequence.startsWith("\u001b")) {
            sequence = "\u001b$sequence"
        }

        onKeyPressed(sequence)
        onModifiersReset()
    }

    Row(
        modifier = modifier
            .height(42.dp)
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModifierKey("CTRL", ctrlActive, onCtrlToggle)
        ModifierKey("ALT", altActive, onAltToggle)
        ModifierKey("SHIFT", shiftActive, onShiftToggle)
        BarKey("ESC") { handleKey("ESC") }
        BarKey("TAB") { handleKey("TAB") }
        RepeatableBarKey("\u25B2") { handleKey("UP") }
        RepeatableBarKey("\u25BC") { handleKey("DOWN") }
        RepeatableBarKey("\u25C0") { handleKey("LEFT") }
        RepeatableBarKey("\u25B6") { handleKey("RIGHT") }
        BarKey("/") { handleKey("/") }
        BarKey("-") { handleKey("-") }
        BarKey("_") { handleKey("_") }
        BarKey(":") { handleKey(":") }
        BarKey("HOME") { handleKey("HOME") }
        BarKey("END") { handleKey("END") }
        RepeatableBarKey("PGUP") { handleKey("PGUP") }
        RepeatableBarKey("PGDN") { handleKey("PGDN") }
        BarKey("INS") { handleKey("INS") }
        BarKey("DEL") { handleKey("DEL") }
        BarKey("F1") { handleKey("F1") }
        BarKey("F2") { handleKey("F2") }
        BarKey("F3") { handleKey("F3") }
        BarKey("F4") { handleKey("F4") }
        BarKey("F5") { handleKey("F5") }
        BarKey("F6") { handleKey("F6") }
        BarKey("F7") { handleKey("F7") }
        BarKey("F8") { handleKey("F8") }
        BarKey("F9") { handleKey("F9") }
        BarKey("F10") { handleKey("F10") }
        BarKey("F11") { handleKey("F11") }
        BarKey("F12") { handleKey("F12") }
    }
}

@Composable
private fun ModifierKey(label: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isActive) Color(0xFF1565C0) else Color(0xFF424242))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BarKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF303030))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

/**
 * Key that fires once on press, then repeats after [initialDelayMs] ms
 * at [repeatIntervalMs] ms intervals while held — like a physical keyboard key.
 */
@Composable
private fun RepeatableBarKey(
    label: String,
    initialDelayMs: Long = 400L,
    repeatIntervalMs: Long = 50L,
    onClick: () -> Unit,
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isPressed) Color(0xFF505050) else Color(0xFF303030))
            .pointerInput(onClick) {
                coroutineScope {
                    while (true) {
                        awaitPointerEventScope {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            isPressed = true
                            val job = launch {
                                onClick()
                                delay(initialDelayMs)
                                while (true) {
                                    onClick()
                                    delay(repeatIntervalMs)
                                }
                            }
                            waitForUpOrCancellation()
                            job.cancel()
                            isPressed = false
                        }
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
