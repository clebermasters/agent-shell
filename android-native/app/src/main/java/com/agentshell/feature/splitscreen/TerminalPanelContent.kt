package com.agentshell.feature.splitscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.data.remote.SessionSocket
import com.agentshell.feature.terminal.TerminalAccessoryBar
import com.agentshell.terminal.XTermView
import com.agentshell.terminal.rememberXTermController

/** Terminal content with its own socket, so attaching it never replaces the chat watch. */
@Composable
fun TerminalPanelContent(
    panelId: String,
    sessionName: String,
    windowIndex: Int,
    isFocused: Boolean,
    onRequestFocus: () -> Unit = {},
    autoFocus: Boolean = true,
) {
    val services = rememberSplitScreenServices()
    val primarySocket = services.webSocketService()
    val primaryStatus by primarySocket.connectionStatus.collectAsStateWithLifecycle()
    val primaryGeneration by primarySocket.connectionGeneration.collectAsStateWithLifecycle()
    // The URL itself is not a Compose state. Re-read it when the host connects/reconnects.
    val webSocketUrl = remember(primaryStatus, primaryGeneration) { primarySocket.currentWebSocketUrl }
    val controller = rememberXTermController()
    val panelSocket = remember(panelId, webSocketUrl) { SessionSocket(services.okHttpClient()) }
    val isSocketConnected by panelSocket.isConnected.collectAsStateWithLifecycle()
    val connectionGeneration by panelSocket.connectionGeneration.collectAsStateWithLifecycle()

    var isReady by remember { mutableStateOf(false) }
    var termCols by remember { mutableIntStateOf(80) }
    var termRows by remember { mutableIntStateOf(24) }
    var attachedGeneration by remember(panelSocket, sessionName, windowIndex) { mutableLongStateOf(-1L) }
    var terminalError by remember(panelSocket, sessionName, windowIndex) { mutableStateOf<String?>(null) }
    var focusRequested by remember(panelId) { mutableStateOf(autoFocus) }
    val isAttached = isSocketConnected && attachedGeneration == connectionGeneration

    fun sendToTerminal(message: Map<String, Any?>) {
        val url = webSocketUrl ?: return
        // Never replay keys or old dimensions into a newly attached terminal after reconnect.
        if (isAttached && panelSocket.isConnected.value &&
            attachedGeneration == panelSocket.connectionGeneration.value
        ) panelSocket.sendIfConnected(message, url)
    }

    // Start collecting before opening the socket so a fast attach reply cannot be lost.
    LaunchedEffect(panelSocket, sessionName, windowIndex) {
        panelSocket.messages.collect { message ->
            val messageSession = message["sessionName"] as? String ?: message["session"] as? String
            if (messageSession != null && messageSession != sessionName) return@collect
            when (message["type"] as? String) {
                "attached" -> {
                    attachedGeneration = panelSocket.connectionGeneration.value
                    terminalError = null
                    val url = webSocketUrl ?: return@collect
                    // A drag can finish while attachment is in flight. Apply its latest size.
                    panelSocket.sendIfConnected(
                        mapOf("type" to "resize", "cols" to termCols, "rows" to termRows), url,
                    )
                }
                "output", "terminal_data", "terminal-history-chunk" -> {
                    val data = message["data"] as? String ?: return@collect
                    controller.writeData(data)
                }
                "error" -> terminalError = message["message"] as? String ?: "Unable to attach terminal"
                "disconnected" -> {
                    attachedGeneration = -1L
                    terminalError = "Terminal session ended"
                }
            }
        }
    }

    LaunchedEffect(panelSocket) {
        if (!webSocketUrl.isNullOrEmpty()) panelSocket.connect(webSocketUrl)
    }
    DisposableEffect(panelSocket) {
        onDispose { panelSocket.dispose() }
    }

    LaunchedEffect(panelSocket, isSocketConnected, connectionGeneration, isReady, sessionName, windowIndex) {
        if (isSocketConnected && isReady && sessionName.isNotEmpty()) {
            attachedGeneration = -1L
            terminalError = null
            controller.clear()
            panelSocket.sendIfConnected(
                mapOf(
                    "type" to "attach-session",
                    "sessionName" to sessionName,
                    "windowIndex" to windowIndex,
                    "cols" to termCols,
                    "rows" to termRows,
                ),
                webSocketUrl ?: return@LaunchedEffect,
            )
        }
    }

    // Inline chat starts unfocused; only a deliberate terminal tap requests keyboard focus.
    LaunchedEffect(isFocused, isReady, focusRequested) {
        if (isFocused && isReady && focusRequested) controller.focus()
    }

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var shiftActive by remember { mutableStateOf(false) }
    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(isFocused) {
        if (!isFocused) {
            ctrlActive = false
            altActive = false
            shiftActive = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            XTermView(
                controller = controller,
                onInput = { data ->
                    if (isFocused && isAttached) {
                        var processed = data
                        if (ctrlActive && processed.length == 1) {
                            val ch = processed[0]
                            processed = when {
                                ch in 'a'..'z' -> String(charArrayOf((ch.code - 0x60).toChar()))
                                ch in 'A'..'Z' -> String(charArrayOf((ch.code - 0x40).toChar()))
                                ch == ' ' -> "\u0000"
                                ch == '[' -> "\u001b"
                                ch == '\\' -> "\u001c"
                                ch == ']' -> "\u001d"
                                ch == '_' -> "\u001f"
                                else -> processed
                            }
                        }
                        if (altActive && !processed.startsWith("\u001b")) processed = "\u001b$processed"
                        if (shiftActive && processed.length == 1) processed = processed.uppercase()
                        ctrlActive = false
                        altActive = false
                        shiftActive = false
                        sendToTerminal(mapOf("type" to "input", "data" to processed))
                    }
                },
                onResize = { cols, rows ->
                    termCols = cols.coerceAtLeast(1)
                    termRows = rows.coerceAtLeast(1)
                    sendToTerminal(mapOf("type" to "resize", "cols" to termCols, "rows" to termRows))
                },
                onReady = { cols, rows ->
                    termCols = cols.coerceAtLeast(1)
                    termRows = rows.coerceAtLeast(1)
                    isReady = true
                },
                modifier = Modifier.fillMaxSize().onSizeChanged {
                    if (isReady) controller.fit()
                },
            )

            if (!isFocused) {
                Box(
                    modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = "Terminal for $sessionName, window $windowIndex"
                    }.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Focus terminal",
                    ) {
                        focusRequested = true
                        onRequestFocus()
                        if (isReady) controller.requestInputFocus()
                    },
                )
            }

            if (!isAttached || terminalError != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
                ) {
                    Row(
                        modifier = Modifier.heightIn(min = 24.dp).padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = terminalError ?: if (isSocketConnected) "Opening terminal…" else "Connecting terminal…",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (terminalError != null || !isSocketConnected) {
                            TextButton(onClick = { panelSocket.forceReconnect() }) { Text("Retry") }
                        }
                    }
                }
            }
        }

        if (isFocused && isKeyboardVisible) {
            TerminalAccessoryBar(
                ctrlActive = ctrlActive,
                altActive = altActive,
                shiftActive = shiftActive,
                onCtrlToggle = { ctrlActive = !ctrlActive },
                onAltToggle = { altActive = !altActive },
                onShiftToggle = { shiftActive = !shiftActive },
                onKeyPressed = { sequence -> sendToTerminal(mapOf("type" to "input", "data" to sequence)) },
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
