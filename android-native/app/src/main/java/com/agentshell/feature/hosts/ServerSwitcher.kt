package com.agentshell.feature.hosts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.model.Host

/** Always-visible entry point; selecting a row connects immediately. */
@Composable
fun ServerSwitcherButton(
    host: Host?,
    connectionStatus: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.semantics {
            contentDescription = "Switch server"
            stateDescription = "${host?.name ?: "No server selected"}, ${connectionStatus.label()}"
        },
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(8.dp).background(
                    when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                        ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    }, CircleShape,
                ),
            )
            Column(Modifier.weight(1f)) {
                Text("Server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    host?.name ?: "Select server",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSwitcherSheet(
    hosts: List<Host>,
    selectedHost: Host?,
    connectionStatus: ConnectionStatus,
    onSelect: (Host) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text("Switch server", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                items(hosts, key = { it.id }) { host ->
                    val selected = selectedHost?.id == host.id
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(host) })
                            .semantics { contentDescription = "Connect to ${host.name}" }
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(host.name, style = MaterialTheme.typography.titleSmall)
                            Text("${host.address}:${host.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selected) Text(connectionStatus.label(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (selected) Icon(Icons.Default.CheckCircle, contentDescription = "Current server", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            TextButton(onClick = onManage, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                Text("Manage servers")
            }
        }
    }
}

private fun ConnectionStatus.label() = when (this) {
    ConnectionStatus.CONNECTED -> "Connected"
    ConnectionStatus.CONNECTING -> "Connecting…"
    ConnectionStatus.RECONNECTING -> "Reconnecting…"
    ConnectionStatus.OFFLINE -> "Offline"
}
