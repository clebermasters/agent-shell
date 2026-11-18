package com.agentshell.feature.chat

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import com.agentshell.data.model.ChatSplitOrientation
import com.agentshell.data.model.ChatSplitPreferences

@Composable
internal fun DeploymentWorkspace(
    preferences: ChatSplitPreferences,
    onOrientation: (ChatSplitOrientation) -> Unit,
    onResize: (ChatSplitOrientation, Float) -> Unit,
    onSwap: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
    chat: @Composable () -> Unit,
    browser: @Composable () -> Unit,
) {
    var showLayout by rememberSaveable { mutableStateOf(false) }
    val hostView = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Deployment", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { showLayout = true }) { Icon(Icons.Default.Tune, "Split layout") }
                IconButton(onClick = onExit) { Icon(Icons.Default.Close, "Close split") }
            }
            HorizontalDivider()
            ChatSplitPane(
                preferences = preferences,
                onResize = onResize,
                onSwap = onSwap,
                onConfigure = { showLayout = true },
                companionPaneName = "browser",
                onChatInteraction = {
                    // Release the remote keyboard even if the user taps a chat message.
                    val focused = hostView.rootView.findFocus()
                    if (focused is WebView) {
                        keyboard?.hide()
                        focused.clearFocus()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                chat = chat,
                companionPane = browser,
            )
        }
    }
    if (showLayout) {
        ChatSplitLayoutDialog(
            preferences = preferences,
            onOrientation = onOrientation,
            onSwap = onSwap,
            onReset = onReset,
            onDismiss = { showLayout = false },
            companionPaneName = "Browser",
            savedFor = "Saved for this server",
        )
    }
}
