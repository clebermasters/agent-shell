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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.terminal.XTermView
import com.agentshell.terminal.rememberXTermController

/**
 * Terminal content for a split-screen panel.
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
    val webSocketUrl = services.webSocketService().currentWebSocketUrl
    val controller = rememberXTermController()
    val panelSocket = remember(panelId) { SplitPanelSocket(services.okHttpClient()) }
    val isSocketConnected by panelSocket.isConnected.collectAsStateWithLifecycle()

    var isReady by remember { mutableStateOf(false) }
    var termCols by remember { mutableIntStateOf(80) }
    var termRows by remember { mutableIntStateOf(24) }

    LaunchedEffect(webSocketUrl) {
        if (!webSocketUrl.isNullOrEmpty()) {
            panelSocket.connect(webSocketUrl)
        }
    }

    DisposableEffect(panelId) {
        onDispose {
            panelSocket.dispose()
        }
    }

    LaunchedEffect(panelId) {
        panelSocket.messages.collect { message ->
            when (message["type"] as? String) {
                "output", "terminal_data", "terminal-history-chunk" -> {
                    val data = message["data"] as? String ?: return@collect
                    controller.writeData(data)
                }
            }
        }
    }

    LaunchedEffect(isSocketConnected, isReady, sessionName, windowIndex) {
        if (isSocketConnected && isReady && sessionName.isNotEmpty()) {
            controller.clear()
            panelSocket.send(
                mapOf(
                    "type" to "attach-session",
                    "sessionName" to sessionName,
                    "windowIndex" to windowIndex,
                    "cols" to termCols,
                    "rows" to termRows,
                ),
            )
            if (isFocused) {
                controller.focus()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        XTermView(
            controller = controller,
            onInput = { data ->
                if (isFocused) {
                    panelSocket.send(mapOf("type" to "input", "data" to data))
                }
            },
            onResize = { cols, rows ->
                termCols = cols
                termRows = rows
                if (isSocketConnected && isReady) {
                    panelSocket.send(
                        mapOf(
                            "type" to "resize",
                            "cols" to cols,
                            "rows" to rows,
                        ),
                    )
                }
            },
            onReady = { cols, rows ->
                termCols = cols
                termRows = rows
                isReady = true
            },
            modifier = Modifier.fillMaxSize(),
        )

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
    }
}
