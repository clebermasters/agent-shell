package com.agentshell.feature.terminal

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.terminal.XTermView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    sessionName: String,
    windowIndex: Int = 0,
    onNavigateBack: () -> Unit,
    onSwitchToChat: ((sessionName: String, windowIndex: Int) -> Unit)? = null,
    onNavigateToFileBrowser: ((path: String) -> Unit)? = null,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Voice button visibility (persisted in preferences)
    var showVoiceButton by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Session rename dialog state
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // Display name tracks renames within this session
    var displayName by remember { mutableStateOf(sessionName) }

    // Fullscreen toggle state
    var isFullscreen by remember { mutableStateOf(false) }

    // Terminal text selection mode
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectionText by remember { mutableStateOf("") }
    var hasSelection by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        showVoiceButton = viewModel.getShowVoiceButton()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording()
    }

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

    // ── Rename dialog ──────────────────────────────────────────────────────
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) {
                        viewModel.renameSession(renameText)
                        displayName = renameText
                    }
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── Fullscreen helper ────────────────────────────────────────────────
    fun toggleFullscreen() {
        val activity = context as? Activity ?: return
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Back button exits fullscreen instead of navigating away
    BackHandler(enabled = isFullscreen) { toggleFullscreen() }

    // Restore system bars when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            if (isFullscreen) {
                val activity = context as? Activity
                if (activity != null) {
                    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            text = displayName,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    renameText = displayName
                                    showRenameDialog = true
                                },
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Selection mode toggle
                        IconButton(onClick = {
                            if (isSelectionMode) {
                                isSelectionMode = false
                                selectionText = ""
                            } else {
                                viewModel.xTermController.getBufferText { text ->
                                    selectionText = text.trimEnd()
                                    isSelectionMode = true
                                }
                            }
                        }) {
                            Icon(
                                if (isSelectionMode) Icons.Default.Close else Icons.Default.SelectAll,
                                contentDescription = if (isSelectionMode) "Exit selection" else "Select text",
                                tint = if (isSelectionMode) Color(0xFFF97316) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        // Paste from clipboard
                        IconButton(onClick = {
                            clipboardManager.getText()?.text?.let { viewModel.onTerminalInput(it) }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                        // File browser shortcut
                        if (onNavigateToFileBrowser != null) {
                            IconButton(onClick = {
                                viewModel.navigateToFileBrowser { path ->
                                    onNavigateToFileBrowser(path)
                                }
                            }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "File Browser")
                            }
                        }
                        // Fullscreen toggle
                        IconButton(onClick = { toggleFullscreen() }) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen")
                        }
                        // Voice button toggle
                        IconButton(onClick = { showVoiceButton = !showVoiceButton }) {
                            Icon(
                                if (showVoiceButton) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Toggle voice button",
                            )
                        }
                        if (onSwitchToChat != null) {
                            IconButton(onClick = { onSwitchToChat(sessionName, windowIndex) }) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Switch to Chat")
                            }
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isFullscreen) Modifier.padding(paddingValues) else Modifier)
                .imePadding(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (isSelectionMode) {
                    // Selection overlay — native Android text selection
                    SelectionContainer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E))
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = selectionText,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = Color(0xFFD4D4D4),
                                lineHeight = 16.sp,
                            ),
                        )
                    }
                } else {
                    XTermView(
                        controller = viewModel.xTermController,
                        onInput = { data ->
                            val modified = applyModifiers(data)
                            viewModel.onTerminalInput(modified)
                        },
                        onResize = { cols, rows -> viewModel.onTerminalResize(cols, rows) },
                        onReady = { cols, rows ->
                            viewModel.onTerminalReady(sessionName, windowIndex, cols, rows)
                        },
                        onVolumeUp = { viewModel.zoomIn() },
                        onVolumeDown = { viewModel.zoomOut() },
                        onSelectionChanged = { hasText -> hasSelection = hasText },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }

                if (!isFullscreen) {
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

            if (showVoiceButton) {
                FloatingVoiceButton(
                    isRecording = uiState.isRecording,
                    isTranscribing = uiState.isTranscribing,
                    recordingDurationSeconds = uiState.recordingDuration,
                    onPressed = {
                        if (uiState.isRecording) {
                            viewModel.stopRecording()
                        } else {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.startRecording()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                )
            }
        }
    }
}
