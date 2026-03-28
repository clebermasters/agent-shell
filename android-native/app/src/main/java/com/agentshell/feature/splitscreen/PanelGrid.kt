package com.agentshell.feature.splitscreen

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentshell.feature.splitscreen.model.PanelState

/**
 * Arranges panels in a responsive grid layout.
 * - 1 panel: fullscreen
 * - 2 panels: 2 columns
 * - 3 panels: top full-width + 2 bottom columns
 * - 4 panels: 2×2 grid
 * - 5 panels: top row 3 + bottom row 2
 * - 6 panels: 3×2 grid
 *
 * When [maximizedPanelId] is set, only that panel renders fullscreen.
 */
@Composable
fun PanelGrid(
    panels: List<PanelState>,
    focusedPanelId: String?,
    maximizedPanelId: String?,
    isEditing: Boolean,
    onFocusPanel: (String) -> Unit,
    onToggleMaximize: (String) -> Unit,
    onSwapSession: (String) -> Unit,
    onRemovePanel: (String) -> Unit,
    panelContent: @Composable (PanelState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sorted = panels.sortedBy { it.position }

    if (maximizedPanelId != null) {
        // Render only the maximized panel
        val panel = sorted.find { it.id == maximizedPanelId } ?: return
        PanelContainer(
            panel = panel,
            isFocused = true,
            isMaximized = true,
            isEditing = isEditing,
            onFocus = { onFocusPanel(panel.id) },
            onToggleMaximize = { onToggleMaximize(panel.id) },
            onSwapSession = { onSwapSession(panel.id) },
            onRemove = { onRemovePanel(panel.id) },
            modifier = modifier.fillMaxSize(),
        ) {
            panelContent(panel)
        }
        return
    }

    val spacing = 2.dp

    when (sorted.size) {
        0 -> { /* empty */ }
        1 -> {
            val p = sorted[0]
            PanelContainer(
                panel = p,
                isFocused = focusedPanelId == p.id,
                isMaximized = false,
                isEditing = isEditing,
                onFocus = { onFocusPanel(p.id) },
                onToggleMaximize = { onToggleMaximize(p.id) },
                onSwapSession = { onSwapSession(p.id) },
                onRemove = { onRemovePanel(p.id) },
                modifier = modifier.fillMaxSize(),
            ) {
                panelContent(p)
            }
        }
        2 -> {
            Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                sorted.forEach { p ->
                    PanelContainer(
                        panel = p,
                        isFocused = focusedPanelId == p.id,
                        isMaximized = false,
                        isEditing = isEditing,
                        onFocus = { onFocusPanel(p.id) },
                        onToggleMaximize = { onToggleMaximize(p.id) },
                        onSwapSession = { onSwapSession(p.id) },
                        onRemove = { onRemovePanel(p.id) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        panelContent(p)
                    }
                }
            }
        }
        else -> {
            // Generic grid: calculate rows of up to `cols` panels each
            val cols = if (sorted.size <= 4) 2 else 3
            val rows = sorted.chunked(cols)

            Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing)) {
                rows.forEach { rowPanels ->
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        rowPanels.forEach { p ->
                            PanelContainer(
                                panel = p,
                                isFocused = focusedPanelId == p.id,
                                isMaximized = false,
                                isEditing = isEditing,
                                onFocus = { onFocusPanel(p.id) },
                                onToggleMaximize = { onToggleMaximize(p.id) },
                                onSwapSession = { onSwapSession(p.id) },
                                onRemove = { onRemovePanel(p.id) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            ) {
                                panelContent(p)
                            }
                        }
                        // Fill remaining space if row is not full
                        repeat(cols - rowPanels.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
