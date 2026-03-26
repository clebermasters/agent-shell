package com.agentshell.feature.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.terminal.XTermView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionName: String,
    windowIndex: Int = 0,
    onNavigateBack: () -> Unit,
    onSwitchToChat: ((sessionName: String, windowIndex: Int) -> Unit)? = null,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Sticky modifier state — shared between accessory bar and soft keyboard input
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }

    /**
     * Apply active modifiers to raw input from xterm.js (soft keyboard typing).
     * If no modifiers are active, pass through unchanged.
     */
    fun applyModifiers(data: String): String {
        if (!ctrlActive && !altActive && !shiftActive) return data

        var result = data

        // CTRL: single printable char → control code
        if (ctrlActive && result.length == 1) {
            val ch = result[0]
            result = when {
                ch in 'a'..'z' -> String(charArrayOf((ch.code - 0x60).toChar()))
                ch in 'A'..'Z' -> String(charArrayOf((ch.code - 0x40).toChar()))
                ch == ' '      -> "\u0000"  // Ctrl+Space = NUL
                ch == '['      -> "\u001b"  // Ctrl+[ = ESC
                ch == '\\'     -> "\u001c"  // Ctrl+\ = FS
                ch == ']'      -> "\u001d"  // Ctrl+] = GS
                ch == '_'      -> "\u001f"  // Ctrl+_ = US
                else -> result
            }
        }

        // ALT: prefix with ESC
        if (altActive && !result.startsWith("\u001b")) {
            result = "\u001b$result"
        }

        // SHIFT: uppercase single letters (xterm.js usually handles this, but for accessory bar keys)
        if (shiftActive && result.length == 1) {
            result = result.uppercase()
        }

        // Reset sticky modifiers after applying
        ctrlActive = false
        altActive = false
        shiftActive = false

        return result
    }

    // Volume keys + detach on leave
    DisposableEffect(sessionName) {
        com.agentshell.VolumeKeyHandler.onVolumeUp = { viewModel.zoomIn() }
        com.agentshell.VolumeKeyHandler.onVolumeDown = { viewModel.zoomOut() }
        onDispose {
            com.agentshell.VolumeKeyHandler.onVolumeUp = null
            com.agentshell.VolumeKeyHandler.onVolumeDown = null
            viewModel.detachSession()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(sessionName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onSwitchToChat != null) {
                        IconButton(onClick = { onSwitchToChat(sessionName, windowIndex) }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Switch to Chat")
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
        ) {
            XTermView(
                controller = viewModel.xTermController,
                onInput = { data ->
                    // Apply sticky modifiers from accessory bar to soft keyboard input
                    val modified = applyModifiers(data)
                    viewModel.onTerminalInput(modified)
                },
                onResize = { cols, rows -> viewModel.onTerminalResize(cols, rows) },
                onReady = { cols, rows ->
                    viewModel.onTerminalReady(sessionName, windowIndex, cols, rows)
                },
                onVolumeUp = { viewModel.zoomIn() },
                onVolumeDown = { viewModel.zoomOut() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            TerminalAccessoryBar(
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                onCtrlToggle = { ctrlActive = !ctrlActive },
                onAltToggle = { altActive = !altActive },
                onShiftToggle = { shiftActive = !shiftActive },
                onKeyPressed = { sequence ->
                    viewModel.onTerminalInput(sequence)
                },
                onModifiersReset = {
                    ctrlActive = false
                    altActive = false
                    shiftActive = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
