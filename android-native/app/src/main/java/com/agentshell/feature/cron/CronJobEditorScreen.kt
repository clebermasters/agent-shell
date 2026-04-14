package com.agentshell.feature.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.data.model.CronJob
import java.util.UUID

private val schedulePresets = listOf(
    "Every hour" to "0 * * * *",
    "Every day" to "0 0 * * *",
    "Every week" to "0 0 * * 0",
    "Every month" to "0 0 1 * *",
    "Every 5 min" to "*/5 * * * *",
    "Every 30 min" to "*/30 * * * *",
    "Weekdays 9am" to "0 9 * * 1-5",
)

private val scheduleDescriptions = mapOf(
    "* * * * *" to "Every minute",
    "0 * * * *" to "Every hour at minute 0",
    "*/5 * * * *" to "Every 5 minutes",
    "*/10 * * * *" to "Every 10 minutes",
    "*/15 * * * *" to "Every 15 minutes",
    "*/30 * * * *" to "Every 30 minutes",
    "0 0 * * *" to "Every day at midnight",
    "0 9 * * *" to "Every day at 9:00 AM",
    "0 0 * * 0" to "Every Sunday at midnight",
    "0 0 1 * *" to "Monthly on the 1st at midnight",
    "0 0 * * 1-5" to "Weekdays at midnight",
    "0 9 * * 1-5" to "Weekdays at 9:00 AM",
)

private val llmProviders = listOf(
    "openai" to "OpenAI Compatible",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronJobEditorScreen(
    existingJob: CronJob? = null,
    onNavigateUp: () -> Unit,
    viewModel: CronViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val isEditing = existingJob != null

    var name by remember { mutableStateOf(existingJob?.name ?: "") }
    var schedule by remember { mutableStateOf(existingJob?.schedule ?: "0 * * * *") }
    var command by remember { mutableStateOf(existingJob?.command ?: "") }
    var emailTo by remember { mutableStateOf(existingJob?.emailTo ?: "") }
    var tmuxSession by remember { mutableStateOf(existingJob?.tmuxSession ?: "") }
    var workdir by remember { mutableStateOf(existingJob?.workdir ?: "") }
    var prompt by remember { mutableStateOf(existingJob?.prompt ?: "") }
    var llmModel by remember { mutableStateOf(existingJob?.llmModel ?: "gpt-5.4") }
    var llmProvider by remember { mutableStateOf(existingJob?.llmProvider ?: "openai") }
    var logOutput by remember { mutableStateOf(existingJob?.logOutput ?: false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showAiOptions by remember { mutableStateOf(existingJob?.prompt?.isNotEmpty() ?: false) }
    var envEntries by remember {
        mutableStateOf<List<Pair<String, String>>>(
            existingJob?.environment?.entries?.map { it.key to it.value } ?: emptyList()
        )
    }

    val hasAiJob = showAiOptions && workdir.isNotBlank() && prompt.isNotBlank()
    val hasCommand = command.isNotBlank()
    val isValid = name.isNotBlank() && schedule.isNotBlank() && (hasCommand || hasAiJob)
    val scheduleDesc = scheduleDescriptions[schedule] ?: "Custom schedule"

    fun save() {
        if (!isValid) return
        val envMap = envEntries.filter { it.first.isNotBlank() }.associate { it.first to it.second }
        val job = CronJob(
            id = existingJob?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            schedule = schedule.trim(),
            command = command.trim(),
            enabled = existingJob?.enabled ?: true,
            lastRun = existingJob?.lastRun,
            nextRun = existingJob?.nextRun,
            createdAt = existingJob?.createdAt,
            emailTo = emailTo.trim().ifEmpty { null },
            logOutput = logOutput,
            tmuxSession = tmuxSession.trim().ifEmpty { null },
            environment = envMap.ifEmpty { null },
            workdir = if (showAiOptions) workdir.trim().ifEmpty { null } else null,
            prompt = if (showAiOptions) prompt.trim().ifEmpty { null } else null,
            llmProvider = if (showAiOptions) llmProvider else null,
            llmModel = if (showAiOptions) llmModel.trim().ifEmpty { null } else null,
        )
        if (isEditing) viewModel.updateJob(job) else viewModel.createJob(job)
        onNavigateUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Cron Job" else "Create Cron Job") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { save() }, enabled = isValid) {
                        Text(if (isEditing) "Update" else "Create")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Job Name") },
                placeholder = { Text("e.g., Backup Database") },
                modifier = Modifier.fillMaxWidth(),
            )

            // Schedule section
            Text("Schedule", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(schedulePresets) { (label, value) ->
                    FilterChip(
                        selected = schedule == value,
                        onClick = { schedule = value },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = schedule,
                onValueChange = { schedule = it },
                label = { Text("Cron Expression") },
                placeholder = { Text("* * * * * (minute hour day month weekday)") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(scheduleDesc, color = MaterialTheme.colorScheme.outline) },
            )

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Command") },
                placeholder = { Text("/home/user/scripts/backup.sh") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { viewModel.testCommand(command.trim()) },
                    enabled = command.isNotBlank() && !state.isTesting,
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Testing...")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Test Command")
                    }
                }
            }

            state.testOutput?.let { output ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = output,
                        fontFamily = FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    )
                }
            }

            // AI Options expandable section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAiOptions = !showAiOptions }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (showAiOptions) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("AI Options", style = MaterialTheme.typography.bodyMedium)
            }

            if (showAiOptions) {
                OutlinedTextField(
                    value = workdir,
                    onValueChange = { workdir = it },
                    label = { Text("Working Directory") },
                    placeholder = { Text("/home/user/project") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("AI Prompt") },
                    placeholder = { Text("What should the AI agent do?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = llmProviders.firstOrNull { it.first == llmProvider }?.second ?: llmProvider,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("LLM Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        llmProviders.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { llmProvider = value; expanded = false },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = llmModel,
                    onValueChange = { llmModel = it },
                    label = { Text("LLM Model") },
                    placeholder = { Text("gpt-5.4") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Advanced Options expandable section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (showAdvanced) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Advanced Options", style = MaterialTheme.typography.bodyMedium)
            }

            if (showAdvanced) {
                OutlinedTextField(
                    value = emailTo,
                    onValueChange = { emailTo = it },
                    label = { Text("Email Output To") },
                    placeholder = { Text("user@example.com") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Log command output to file", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = logOutput, onCheckedChange = { logOutput = it })
                }

                OutlinedTextField(
                    value = tmuxSession,
                    onValueChange = { tmuxSession = it },
                    label = { Text("Run in TMUX Session") },
                    placeholder = { Text("session-name") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Environment Variables", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("PATH", "HOME", "TZ", "LANG").forEach { preset ->
                        AssistChip(
                            onClick = { envEntries = envEntries + (preset to "") },
                            label = { Text(preset) },
                        )
                    }
                }

                for (index in envEntries.indices) {
                    val (key, value) = envEntries[index]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { newKey ->
                                val updated = envEntries.toMutableList()
                                updated[index] = newKey to value
                                envEntries = updated
                            },
                            label = { Text("Key") },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = value,
                            onValueChange = { newValue ->
                                val updated = envEntries.toMutableList()
                                updated[index] = key to newValue
                                envEntries = updated
                            },
                            label = { Text("Value") },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(3f),
                            singleLine = true,
                        )
                        IconButton(onClick = {
                            val updated = envEntries.toMutableList()
                            updated.removeAt(index)
                            envEntries = updated
                        }) {
                            Icon(
                                Icons.Default.RemoveCircleOutline,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                TextButton(
                    onClick = { envEntries = envEntries + ("" to "") },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add Variable")
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}
