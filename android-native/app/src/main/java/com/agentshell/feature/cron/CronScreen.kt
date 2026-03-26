package com.agentshell.feature.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.data.model.CronJob
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(
    onNavigateToEditor: (CronJob?) -> Unit,
    viewModel: CronViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.testOutput) {
        state.testOutput?.let {
            snackbarHostState.showSnackbar(
                message = it.take(200),
                duration = SnackbarDuration.Long,
            )
            viewModel.clearTestOutput()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cron Jobs") },
                actions = {
                    IconButton(onClick = { viewModel.requestJobs() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onNavigateToEditor(null) }) {
                Icon(Icons.Default.Add, contentDescription = "Add cron job")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                state.error != null -> ErrorState(error = state.error!!, onRetry = { viewModel.requestJobs() })
                state.isLoading && state.jobs.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.jobs.isEmpty() -> EmptyCronState()
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(state.jobs, key = { it.id }) { job ->
                            var showDeleteDialog by remember { mutableStateOf(false) }

                            if (showDeleteDialog) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteDialog = false },
                                    title = { Text("Delete Cron Job") },
                                    text = { Text("Delete \"${job.name}\"?") },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                viewModel.deleteJob(job.id)
                                                showDeleteDialog = false
                                            },
                                        ) {
                                            Text("Delete", color = MaterialTheme.colorScheme.error)
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteDialog = false }) {
                                            Text("Cancel")
                                        }
                                    },
                                )
                            }

                            CronJobCard(
                                job = job,
                                onEdit = { onNavigateToEditor(job) },
                                onToggle = { viewModel.toggleJob(job.id) },
                                onDelete = { showDeleteDialog = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CronJobCard(
    job: CronJob,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (job.enabled) Color(0xFF22C55E) else Color(0xFF9CA3AF),
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = job.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (job.enabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (job.enabled) "Disable" else "Enable",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatSchedule(job.schedule),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = if (job.enabled) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = job.command,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            job.nextRun?.let { nextRun ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Next: ${formatNextRun(nextRun)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun EmptyCronState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Text("No cron jobs", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Text("Tap + to create a new job", color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("Error", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

private fun formatSchedule(schedule: String): String {
    return mapOf(
        "0 * * * *" to "Every hour",
        "*/5 * * * *" to "Every 5 minutes",
        "*/10 * * * *" to "Every 10 minutes",
        "*/15 * * * *" to "Every 15 minutes",
        "*/30 * * * *" to "Every 30 minutes",
        "0 0 * * *" to "Daily at midnight",
        "0 9 * * *" to "Daily at 9:00 AM",
        "0 0 * * 0" to "Weekly on Sunday",
        "0 0 1 * *" to "Monthly on the 1st",
        "0 0 * * 1-5" to "Weekdays at midnight",
    )[schedule] ?: schedule
}

private fun formatNextRun(nextRunStr: String): String {
    return try {
        val next = Instant.parse(nextRunStr)
        val now = Instant.now()
        if (next.isBefore(now)) return "Overdue"
        val diffMin = ChronoUnit.MINUTES.between(now, next)
        val diffHr = ChronoUnit.HOURS.between(now, next)
        val diffDay = ChronoUnit.DAYS.between(now, next)
        when {
            diffDay > 0 -> "in $diffDay day${if (diffDay > 1) "s" else ""}"
            diffHr > 0 -> "in $diffHr hour${if (diffHr > 1) "s" else ""}"
            diffMin > 0 -> "in $diffMin minute${if (diffMin > 1) "s" else ""}"
            else -> "soon"
        }
    } catch (_: Exception) {
        nextRunStr
    }
}
