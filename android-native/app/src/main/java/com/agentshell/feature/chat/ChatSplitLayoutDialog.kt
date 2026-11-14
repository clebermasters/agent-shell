package com.agentshell.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.agentshell.data.model.ChatSplitOrientation
import com.agentshell.data.model.ChatSplitPreferences

@Composable
internal fun ChatSplitLayoutDialog(
    preferences: ChatSplitPreferences,
    onOrientation: (ChatSplitOrientation) -> Unit,
    onSwap: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val config = LocalConfiguration.current
    val sideBySide = resolveChatSplitOrientation(preferences.orientation, config.screenWidthDp, config.screenHeightDp) == ChatSplitOrientation.SIDE_BY_SIDE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split layout") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Saved for this chat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    ChatSplitOrientation.AUTO to "Auto · follow screen",
                    ChatSplitOrientation.STACKED to "Top / bottom",
                    ChatSplitOrientation.SIDE_BY_SIDE to "Side by side",
                ).forEach { (orientation, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = preferences.orientation == orientation,
                                role = Role.RadioButton,
                                onClick = { onOrientation(orientation) },
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = preferences.orientation == orientation, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    if (sideBySide) {
                        if (preferences.chatFirst) "Chat left · Terminal right" else "Terminal left · Chat right"
                    } else {
                        if (preferences.chatFirst) "Chat top · Terminal bottom" else "Terminal top · Chat bottom"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                FilledTonalButton(onClick = onSwap, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (sideBySide) Icons.Default.SwapHoriz else Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Swap chat and terminal")
                }
                Text("Drag the divider to resize. Tap it for layout options.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onReset) { Text("Reset pane sizes") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
