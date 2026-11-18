package com.agentshell.feature.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Freeze the source snapshot while the user chooses or previews a destination. */
private data class WindowChoice(val snapshot: ChromeWindows, val window: ChromeWindow, val operation: WindowOperation,
    val tab: ChromeTab? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserWindowsSheet(state: BrowserWindowsState, perform: (WindowAction) -> Unit,
    onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val catalog = state.catalog
    var choice by remember { mutableStateOf<WindowChoice?>(null) }
    val pending by rememberUpdatedState(state.pending)
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { value -> value != SheetValue.Hidden || !pending })) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Browser windows", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onRefresh, enabled = !state.pending) { Icon(Icons.Default.Refresh, "Refresh windows") }
                TextButton(onClick = onDismiss, enabled = !state.pending) { Text("Done") }
            }
            if (state.pending || catalog == null && state.readError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            listOfNotNull(state.readError, state.actionError).distinct().forEach { message ->
                Text(message, Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.error)
            }
            if (catalog != null) {
                if (catalog.windows.isEmpty()) Text("No regular windows are open in this profile.", Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 16.dp)) {
                    for (window in catalog.windows) {
                        item(key = "window-${window.id}") {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().clickable(enabled = !state.pending) {
                                perform(WindowAction(WindowOperation.FOCUS, catalog.revision, window.id))
                            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    val n = window.tabs.size
                                    val current = if (window.focused) " · Viewing" else ""
                                    Text("${window.name} · $n ${if (n == 1) "tab" else "tabs"}$current",
                                        style = MaterialTheme.typography.titleMedium)
                                    window.activeTab?.let { Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                        items(window.tabs, key = { "tab-${it.id}" }) { tab ->
                            WindowTabRow(tab, window, catalog, state.pending, perform) { choice = it }
                        }
                        if (catalog.windows.size > 1) item(key = "merge-${window.id}") {
                            TextButton(onClick = { choice = WindowChoice(catalog, window, WindowOperation.MERGE) },
                                enabled = !state.pending) { Text("Merge all into this window") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    choice?.let { selected ->
        WindowChoiceDialog(selected, catalog?.revision == selected.snapshot.revision && !state.pending,
            onCancel = { choice = null }, submit = { choice = null; perform(it) })
    }
}

@Composable
private fun WindowTabRow(tab: ChromeTab, window: ChromeWindow, catalog: ChromeWindows, pending: Boolean,
    perform: (WindowAction) -> Unit, choose: (WindowChoice) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    fun pick(operation: WindowOperation) {
        menu = false
        choose(WindowChoice(catalog, window, operation, tab))
    }
    fun act(operation: WindowOperation) {
        menu = false
        perform(WindowAction(operation, catalog.revision, window.id, tab.id))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(enabled = !pending) { act(WindowOperation.FOCUS) }
            .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tab.active) Icon(Icons.Default.Check, "Selected tab", Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(tab.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull("Pinned".takeIf { tab.pinned }, "Grouped".takeIf { tab.groupId != -1 },
                    "Side by side".takeIf { tab.paired }).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelSmall)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = !pending) { Icon(Icons.Default.MoreVert, "Options for ${tab.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Move to window…") }, enabled = catalog.windows.size > 1 && !tab.paired,
                    onClick = { pick(WindowOperation.MOVE) })
                DropdownMenuItem(text = { Text("Move to new window") }, enabled = window.tabs.size > 1 && !tab.paired,
                    onClick = { if (tab.groupId != -1) pick(WindowOperation.DETACH) else act(WindowOperation.DETACH) })
                if (catalog.splitSupported) {
                    if (tab.paired) DropdownMenuItem(text = { Text("Exit side by side") }, onClick = { act(WindowOperation.UNPAIR) })
                    else DropdownMenuItem(text = { Text("View side by side…") }, enabled = window.tabs.any(tab::canPairWith),
                        onClick = { pick(WindowOperation.PAIR) })
                }
            }
        }
    }
}

@Composable
private fun WindowChoiceDialog(choice: WindowChoice, valid: Boolean, onCancel: () -> Unit, submit: (WindowAction) -> Unit) {
    val (snapshot, window, op, tab) = choice
    fun send(target: Int? = null, partner: Int? = null) {
        submit(WindowAction(op, snapshot.revision, window.id.takeUnless { op == WindowOperation.MERGE }, tab?.id, target, partner))
    }
    val title = when (op) {
        WindowOperation.MERGE -> "Merge into ${window.name}?"
        WindowOperation.MOVE -> "Move tab to window"
        WindowOperation.DETACH -> "Move to new window?"
        else -> "View side by side"
    }
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!valid) Text("The window list changed. Go back to choose again.", color = MaterialTheme.colorScheme.error)
            when (op) {
                WindowOperation.MERGE -> {
                    val sources = snapshot.windows.filter { it.id != window.id }
                    val tabs = sources.sumOf { it.tabs.size }
                    Text("Move $tabs ${if (tabs == 1) "tab" else "tabs"} from ${sources.size} " +
                        "${if (sources.size == 1) "other window" else "other windows"} into ${window.name}.")
                    window.activeTab?.let { Text("Keep “${it.title}” selected.") }
                }
                WindowOperation.MOVE, WindowOperation.DETACH -> {
                    Text(tab!!.title)
                    if (tab.groupId != -1) Text("Moving this tab takes it out of its current group.")
                    if (op == WindowOperation.MOVE) {
                        if (window.tabs.size == 1) Text("Moving the last tab closes ${window.name}.")
                        for (other in snapshot.windows.filter { it.id != window.id }) {
                            TextButton(onClick = { send(target = other.id) }, enabled = valid) {
                                Text(listOfNotNull(other.name, other.activeTab?.title).joinToString(" · "))
                            }
                        }
                    }
                }
                WindowOperation.PAIR -> {
                    Text("Choose a tab to display beside “${tab!!.title}”.")
                    val first = window.tabs.indexOf(tab)
                    for ((index, other) in window.tabs.withIndex()) if (tab.canPairWith(other)) {
                        TextButton(onClick = { send(partner = other.id) }, enabled = valid) {
                            Column {
                                Text(other.title)
                                if (abs(index - first) > 1) Text("Move beside this tab", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
    }, confirmButton = {
        if (op == WindowOperation.MERGE)
            TextButton(onClick = { send(target = window.id) }, enabled = valid) { Text("Merge tabs") }
        if (op == WindowOperation.DETACH)
            TextButton(onClick = { send() }, enabled = valid) { Text("Move tab") }
    }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
