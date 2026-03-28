package com.agentshell.feature.splitscreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentshell.data.model.PanelLayoutWithPanels
import com.agentshell.feature.splitscreen.model.PanelType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutListSheet(
    layouts: List<PanelLayoutWithPanels>,
    onSelect: (PanelLayoutWithPanels) -> Unit,
    onDelete: (PanelLayoutWithPanels) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Saved Layouts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (layouts.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.DashboardCustomize, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text("No saved layouts yet", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(layouts, key = { it.layout.id }) { layout ->
                        var showDeleteConfirm by remember { mutableStateOf(false) }

                        if (showDeleteConfirm) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirm = false },
                                title = { Text("Delete layout?") },
                                text = { Text("Delete \"${layout.layout.name}\"? This cannot be undone.") },
                                confirmButton = {
                                    TextButton(onClick = { onDelete(layout); showDeleteConfirm = false }) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                                },
                            )
                        }

                        ListItem(
                            headlineContent = { Text(layout.layout.name) },
                            supportingContent = {
                                val sessionNames = layout.panels
                                    .sortedBy { it.position }
                                    .map { p ->
                                        val icon = if (PanelType.fromValue(p.panelType) == PanelType.TERMINAL) "\uD83D\uDDA5" else "\uD83D\uDCAC"
                                        "$icon ${p.sessionName.ifEmpty { "?" }}"
                                    }
                                    .joinToString(" | ")
                                Text(
                                    "${layout.layout.panelCount} panels: $sessionNames",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Default.Dashboard, contentDescription = null)
                            },
                            trailingContent = {
                                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            },
                            modifier = Modifier.clickable { onSelect(layout) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
