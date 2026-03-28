package com.agentshell.feature.splitscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.feature.splitscreen.model.PanelState
import com.agentshell.feature.splitscreen.model.PanelType

/**
 * Wrapper around each panel providing a header bar with controls,
 * a focus indicator border, and touch handling.
 */
@Composable
fun PanelContainer(
    panel: PanelState,
    isFocused: Boolean,
    isMaximized: Boolean,
    isEditing: Boolean,
    onFocus: () -> Unit,
    onToggleMaximize: () -> Unit,
    onSwapSession: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isFocused) 2.dp else 1.dp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp)),
    ) {
        // Header bar — NOT wrapped in clickable so buttons work
        PanelHeader(
            panel = panel,
            isFocused = isFocused,
            isMaximized = isMaximized,
            isEditing = isEditing,
            onToggleMaximize = onToggleMaximize,
            onSwapSession = onSwapSession,
            onRemove = onRemove,
        )

        // Content area — tapping here sets focus
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .clickable { onFocus() },
        ) {
            content()
        }
    }
}

@Composable
private fun PanelHeader(
    panel: PanelState,
    isFocused: Boolean,
    isMaximized: Boolean,
    isEditing: Boolean,
    onToggleMaximize: () -> Unit,
    onSwapSession: () -> Unit,
    onRemove: () -> Unit,
) {
    val bgColor = if (isFocused) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Type icon
        Icon(
            imageVector = when (panel.panelType) {
                PanelType.TERMINAL -> Icons.Default.Terminal
                PanelType.CHAT -> Icons.AutoMirrored.Filled.Chat
            },
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.width(6.dp))

        // Session name
        Text(
            text = panel.sessionName.ifEmpty { "Unassigned" },
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (panel.sessionName.isEmpty()) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        // Action buttons
        if (isEditing) {
            IconButton(onClick = onSwapSession, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.SwapHoriz, contentDescription = "Change session", modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove panel", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
            }
        }

        IconButton(onClick = onToggleMaximize, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = if (isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isMaximized) "Restore" else "Maximize",
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
