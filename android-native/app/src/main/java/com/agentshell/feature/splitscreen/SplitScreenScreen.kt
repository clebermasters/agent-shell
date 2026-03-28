package com.agentshell.feature.splitscreen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.feature.splitscreen.model.PanelState
import com.agentshell.feature.splitscreen.model.PanelType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreenScreen(
    layoutId: String? = null,
    onNavigateBack: () -> Unit,
    viewModel: SplitScreenViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Handle back press: restore from maximize first, then navigate back
    BackHandler {
        if (state.maximizedPanelId != null) {
            viewModel.restoreFromMaximize()
        } else {
            onNavigateBack()
        }
    }

    // Session picker sheet
    if (state.showSessionPicker && state.pickerTargetPanelId != null) {
        SessionPickerSheet(
            tmuxSessions = state.tmuxSessions,
            acpSessions = state.acpSessions,
            onSelectTerminal = { session ->
                viewModel.changePanelSession(
                    panelId = state.pickerTargetPanelId!!,
                    panelType = PanelType.TERMINAL,
                    sessionName = session.name,
                )
            },
            onSelectChat = { session, windowIndex ->
                viewModel.changePanelSession(
                    panelId = state.pickerTargetPanelId!!,
                    panelType = PanelType.CHAT,
                    sessionName = session.name,
                    windowIndex = windowIndex,
                )
            },
            onSelectAcpChat = { session ->
                viewModel.changePanelSession(
                    panelId = state.pickerTargetPanelId!!,
                    panelType = PanelType.CHAT,
                    sessionName = session.sessionId,
                    isAcp = true,
                )
            },
            onDismiss = { viewModel.closeSessionPicker() },
        )
    }

    // Layout list sheet
    if (state.showLayoutList) {
        LayoutListSheet(
            layouts = state.availableLayouts,
            onSelect = { layout ->
                viewModel.loadLayout(layout.layout.id)
                viewModel.closeLayoutList()
            },
            onDelete = { layout -> viewModel.deleteLayout(layout.layout.id) },
            onDismiss = { viewModel.closeLayoutList() },
        )
    }

    // Layout editor sheet
    if (state.showLayoutEditor) {
        LayoutEditorSheet(
            currentName = state.layoutName,
            currentPanelCount = state.panels.size,
            onSave = { name, panelCount ->
                if (state.panels.isEmpty() || state.panels.size != panelCount) {
                    viewModel.createEmptyLayout(panelCount)
                }
                viewModel.saveLayout(name)
            },
            onCreateEmpty = { panelCount -> viewModel.createEmptyLayout(panelCount) },
            onDismiss = { viewModel.closeLayoutEditor() },
        )
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (state.maximizedPanelId != null) viewModel.restoreFromMaximize()
                    else onNavigateBack()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                }
                Text(
                    text = state.layoutName.ifEmpty { "Split Screen" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = { viewModel.toggleEditing() }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (state.isEditing) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = if (state.isEditing) "Done" else "Edit",
                        modifier = Modifier.size(20.dp),
                        tint = if (state.isEditing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { viewModel.openLayoutEditor() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { viewModel.openLayoutList() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Layouts", modifier = Modifier.size(20.dp))
                }
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .padding(2.dp),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.panels.isEmpty() -> {
                    EmptyState(
                        onCreateNew = { viewModel.openLayoutEditor() },
                        onLoadExisting = { viewModel.openLayoutList() },
                        hasLayouts = state.availableLayouts.isNotEmpty(),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    PanelGrid(
                        panels = state.panels,
                        focusedPanelId = state.focusedPanelId,
                        maximizedPanelId = state.maximizedPanelId,
                        isEditing = state.isEditing,
                        onFocusPanel = { viewModel.setFocusedPanel(it) },
                        onToggleMaximize = { viewModel.toggleMaximize(it) },
                        onSwapSession = { viewModel.openSessionPicker(it) },
                        onRemovePanel = { viewModel.removePanel(it) },
                        panelContent = { panel ->
                            PanelContentRouter(
                                panel = panel,
                                isFocused = state.focusedPanelId == panel.id,
                                viewModel = viewModel,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelContentRouter(
    panel: PanelState,
    isFocused: Boolean,
    viewModel: SplitScreenViewModel,
) {
    if (panel.sessionName.isEmpty()) {
        UnassignedPanel(onAssign = { viewModel.openSessionPicker(panel.id) })
        return
    }

    when (panel.panelType) {
        PanelType.TERMINAL -> {
            TerminalPanelContent(
                panelId = panel.id,
                sessionName = panel.sessionName,
                windowIndex = panel.windowIndex,
                isFocused = isFocused,
                onRequestFocus = { viewModel.setFocusedPanel(panel.id) },
            )
        }
        PanelType.CHAT -> {
            ChatPanelContent(
                panelId = panel.id,
                sessionName = panel.sessionName,
                windowIndex = panel.windowIndex,
                isAcp = panel.isAcp,
                isFocused = isFocused,
            )
        }
    }
}

@Composable
private fun UnassignedPanel(onAssign: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.AddCircleOutline,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAssign) {
                Text("Assign Session")
            }
        }
    }
}

@Composable
private fun EmptyState(
    onCreateNew: () -> Unit,
    onLoadExisting: () -> Unit,
    hasLayouts: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Default.DashboardCustomize,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            "No panel layout active",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        Button(onClick = onCreateNew) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Create New Layout")
        }
        if (hasLayouts) {
            OutlinedButton(onClick = onLoadExisting) {
                Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Load Saved Layout")
            }
        }
    }
}
