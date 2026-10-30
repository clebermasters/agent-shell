package com.agentshell.feature.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.agentshell.data.model.BrowserContentSize

@Composable
internal fun BrowserContentSizeDialog(
    selected: BrowserContentSize,
    onSelect: (BrowserContentSize) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Content size") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("The whole browser stays fitted to your screen. More content makes text smaller and can use more data.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
            BrowserContentSize.entries.forEach { size ->
                Row(Modifier.fillMaxWidth().selectable(selected = selected == size, role = Role.RadioButton,
                    onClick = { onSelect(size) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == size, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(size.label, style = MaterialTheme.typography.bodyLarge)
                        Text(size.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
