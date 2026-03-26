package com.agentshell.feature.dotfiles

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.data.model.DotFileTemplate
import com.agentshell.data.model.DotFileType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotfileTemplatesScreen(
    onNavigateUp: () -> Unit,
    viewModel: DotfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var pendingTemplate by remember { mutableStateOf<DotFileTemplate?>(null) }
    var targetPath by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.getTemplates() }

    pendingTemplate?.let { template ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("Apply Template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Apply \"${template.name}\" template?")
                    Text("This will overwrite the target file.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = targetPath,
                        onValueChange = { targetPath = it },
                        label = { Text("Target path") },
                        placeholder = { Text("~/.bashrc") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (targetPath.isNotBlank()) {
                            viewModel.applyTemplate(targetPath.trim(), template.name)
                        }
                        pendingTemplate = null
                    },
                    enabled = targetPath.isNotBlank(),
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTemplate = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Templates") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        if (state.templates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    Text("No templates available", color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.templates, key = { it.name }) { template ->
                    TemplateCard(
                        template = template,
                        onApply = {
                            targetPath = ""
                            pendingTemplate = template
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: DotFileTemplate, onApply: () -> Unit) {
    val typeColor = when (template.fileTypeEnum) {
        DotFileType.SHELL -> Color(0xFF22C55E)
        DotFileType.GIT -> Color(0xFFF97316)
        DotFileType.VIM -> Color(0xFF16A34A)
        DotFileType.TMUX -> Color(0xFF3B82F6)
        DotFileType.SSH -> Color(0xFFA855F7)
        DotFileType.OTHER -> Color(0xFF6B7280)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = typeColor.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small,
            ) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = typeColor, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(template.name, style = MaterialTheme.typography.titleSmall)
                Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Text(template.fileTypeEnum.displayName, style = MaterialTheme.typography.labelSmall, color = typeColor)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onApply) { Text("Apply") }
        }
    }
}
