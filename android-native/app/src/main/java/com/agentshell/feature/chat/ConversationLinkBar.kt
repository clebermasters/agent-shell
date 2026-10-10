package com.agentshell.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentshell.data.model.ChatBindingState

@Composable
fun ConversationLinkBar(state: ChatBindingState?, onRetry: () -> Unit) {
    if (state == null) return
    Surface(color = if (state.canSend) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            val text = state.binding?.let { "${state.tool.replaceFirstChar { c -> c.uppercase() }} · ${it.conversationId.take(8)}" } ?: state.detail
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!state.canSend && state.status != "checking") TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
