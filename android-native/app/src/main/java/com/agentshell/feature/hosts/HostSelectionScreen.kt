package com.agentshell.feature.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.data.model.Host
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSelectionScreen(
    onNavigateBack: () -> Unit,
    viewModel: HostsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingHost by remember { mutableStateOf<Host?>(null) }

    // Show test result feedback
    LaunchedEffect(uiState.testConnectionResult) {
        when (val result = uiState.testConnectionResult) {
            is TestConnectionResult.Success -> {
                snackbarHostState.showSnackbar("Connection successful!")
                viewModel.clearTestResult()
            }
            is TestConnectionResult.Failure -> {
                snackbarHostState.showSnackbar("Connection failed: ${result.message}")
                viewModel.clearTestResult()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Server")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Server")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.hosts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No servers added.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(uiState.hosts, key = { it.id }) { host ->
                    val isSelected = uiState.selectedHost?.id == host.id
                    HostCard(
                        host = host,
                        isSelected = isSelected,
                        onTap = {
                            viewModel.selectHost(host)
                            onNavigateBack()
                        },
                        onEdit = { editingHost = host },
                        onDelete = {
                            if (uiState.hosts.size > 1) {
                                viewModel.deleteHost(host.id)
                            } else {
                                // Inform user via snackbar — can't delete last host
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        HostFormDialog(
            initialHost = null,
            isTesting = uiState.testConnectionResult is TestConnectionResult.Testing,
            onTestConnection = { host -> viewModel.testConnection(host) },
            onSave = { host ->
                viewModel.addHost(host)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    editingHost?.let { host ->
        HostFormDialog(
            initialHost = host,
            isTesting = uiState.testConnectionResult is TestConnectionResult.Testing,
            onTestConnection = { h -> viewModel.testConnection(h) },
            onSave = { updatedHost ->
                viewModel.editHost(updatedHost)
                editingHost = null
            },
            onDismiss = { editingHost = null },
        )
    }
}

@Composable
private fun HostCard(
    host: Host,
    isSelected: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = onTap,
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Dns,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = { Text(host.name) },
            supportingContent = {
                Column {
                    Text("${host.address}:${host.port}", style = MaterialTheme.typography.bodySmall)
                    host.lastConnected?.let { ts ->
                        val formatted = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                            .format(Date(ts))
                        Text(
                            "Last connected: $formatted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun HostFormDialog(
    initialHost: Host?,
    isTesting: Boolean,
    onTestConnection: (Host) -> Unit,
    onSave: (Host) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    var name by rememberSaveable { mutableStateOf(initialHost?.name ?: "") }
    var address by rememberSaveable { mutableStateOf(initialHost?.address ?: "") }
    var portText by rememberSaveable { mutableStateOf(initialHost?.port?.toString() ?: "4010") }

    var nameError by remember { mutableStateOf<String?>(null) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }

    fun buildHost(): Host? {
        nameError = null; addressError = null; portError = null
        var valid = true
        if (name.isBlank()) { nameError = "Name is required"; valid = false }
        if (address.isBlank()) { addressError = "Address is required"; valid = false }
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) { portError = "Enter a valid port (1–65535)"; valid = false }
        if (!valid) return null
        return Host(
            id = initialHost?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            address = address.trim(),
            port = port!!,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialHost == null) "Add Server" else "Edit Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = null },
                    label = { Text("Server Name") },
                    placeholder = { Text("e.g., Home PI") },
                    leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; addressError = null },
                    label = { Text("Address") },
                    placeholder = { Text("IP address or domain") },
                    leadingIcon = { Icon(Icons.Default.Router, contentDescription = null) },
                    isError = addressError != null,
                    supportingText = addressError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it; portError = null },
                    label = { Text("Port") },
                    placeholder = { Text("e.g., 4010") },
                    leadingIcon = { Icon(Icons.Default.Numbers, contentDescription = null) },
                    isError = portError != null,
                    supportingText = portError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))

                // Test Connection button
                TextButton(
                    onClick = {
                        val host = buildHost() ?: return@TextButton
                        onTestConnection(host)
                    },
                    enabled = !isTesting,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(if (isTesting) "Testing..." else "Test Connection")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val host = buildHost() ?: return@TextButton
                    onSave(host)
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
