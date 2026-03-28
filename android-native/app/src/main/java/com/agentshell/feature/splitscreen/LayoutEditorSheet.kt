package com.agentshell.feature.splitscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutEditorSheet(
    currentName: String,
    currentPanelCount: Int,
    onSave: (name: String, panelCount: Int) -> Unit,
    onCreateEmpty: (panelCount: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    var panelCount by remember { mutableIntStateOf(if (currentPanelCount > 0) currentPanelCount else 2) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Panel Layout", style = MaterialTheme.typography.titleMedium)

            // Name input
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Layout name") },
                placeholder = { Text("e.g., Dev Setup") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Panel count selector
            Text("Number of panels", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(2, 3, 4, 6).forEach { count ->
                    val isSelected = panelCount == count
                    FilterChip(
                        selected = isSelected,
                        onClick = { panelCount = count },
                        label = { Text("$count") },
                        leadingIcon = if (isSelected) {{ Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(16.dp)) }} else null,
                    )
                }
            }

            // Grid preview
            Text("Preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            GridPreview(panelCount = panelCount, modifier = Modifier.fillMaxWidth().height(120.dp))

            // Save button
            Button(
                onClick = {
                    val finalName = name.ifBlank { "Layout (${panelCount} panels)" }
                    onSave(finalName, panelCount)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = true,
            ) {
                Text("Save Layout")
            }
        }
    }
}

@Composable
private fun GridPreview(panelCount: Int, modifier: Modifier = Modifier) {
    val cols = if (panelCount <= 4) 2 else 3
    val rows = (panelCount + cols - 1) / cols
    val shape = RoundedCornerShape(6.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        var idx = 0
        repeat(rows) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(cols) {
                    if (idx < panelCount) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${idx + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        idx++
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
