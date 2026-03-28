package com.agentshell.feature.splitscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.terminal.XTermView
import com.agentshell.terminal.rememberXTermController

/**
 * Terminal content for a split-screen panel.
 *
 * **Backend constraint:** Only ONE PTY reader exists per WebSocket connection.
 * `Output` messages carry NO sessionName tag. Therefore only the **focused**
 * terminal panel receives live output. Non-focused panels keep their last
 * rendered state in the xterm.js WebView buffer.
 *
 * Flow:
 * 1. Panel not yet focused → shows placeholder (no WebView loaded).
 * 2. User taps panel → parent sets focus → WebView loads, attaches session.
 * 3. Focus moves away → WebView stays with its buffer, a transparent overlay
 *    intercepts the next tap to re-focus (WebView would otherwise eat touches).
 * 4. User taps back → overlay caught the tap via [onRequestFocus], panel
 *    re-attaches to its session.
 */
@Composable
fun TerminalPanelContent(
    panelId: String,
    sessionName: String,
    windowIndex: Int,
    isFocused: Boolean,
    onRequestFocus: () -> Unit = {},
) {
    val services = rememberSplitScreenServices()
    val terminalService = services.terminalService()
    val controller = rememberXTermController()

    // Track whether the WebView has ever been loaded
    var hasBeenActivated by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }
    var termCols by remember { mutableIntStateOf(80) }
    var termRows by remember { mutableIntStateOf(24) }

    // Activate the WebView the first time this panel gets focus
    if (isFocused && !hasBeenActivated) {
        hasBeenActivated = true
    }

    // When this panel gains focus AND xterm is ready, attach to the session.
    LaunchedEffect(isFocused, isReady, sessionName) {
        if (isFocused && isReady && sessionName.isNotEmpty()) {
            terminalService.onTerminalData = { data ->
                controller.writeData(data)
            }
            terminalService.attachSession(sessionName, termCols, termRows, windowIndex)
            controller.focus()
        }
    }

    // Detach when leaving composition entirely
    DisposableEffect(panelId) {
        onDispose {
            if (isFocused) {
                terminalService.detach()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        if (hasBeenActivated) {
            // WebView is loaded — render terminal
            XTermView(
                controller = controller,
                onInput = { data ->
                    if (isFocused) {
                        terminalService.sendInput(data)
                    }
                },
                onResize = { cols, rows ->
                    termCols = cols
                    termRows = rows
                    if (isFocused) {
                        terminalService.resize(cols, rows)
                    }
                },
                onReady = { cols, rows ->
                    termCols = cols
                    termRows = rows
                    isReady = true
                },
                modifier = Modifier.fillMaxSize(),
            )

            // When NOT focused, show a transparent overlay that intercepts taps
            // to re-focus this panel. Without this, the WebView consumes all
            // touches and the parent PanelContainer's clickable never fires.
            if (!isFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onRequestFocus() },
                )
            }
        } else {
            // Not yet activated — show a placeholder. Parent PanelContainer's
            // .clickable will catch taps here (no WebView to consume them).
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = Color(0xFF6B7280),
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        sessionName.ifEmpty { "Terminal" },
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap to activate",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7280),
                    )
                }
            }
        }
    }
}
