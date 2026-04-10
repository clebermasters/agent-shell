package com.agentshell.feature.system

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentshell.data.model.ProcessInfo
import com.agentshell.data.model.SystemStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    viewModel: SystemViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("System", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                }
            }
        },
    ) { paddingValues ->
        if (state.isLoading && state.stats == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // CPU Card
                StatCard(
                    title = "CPU",
                    icon = Icons.Default.Memory,
                    value = state.stats?.let { "${"%.2f".format(it.cpuUsage)}%" } ?: "--",
                    subtitle = state.stats?.let { buildCpuSubtitle(it) },
                    progress = state.stats?.let { (it.cpuUsage / 100.0).coerceIn(0.0, 1.0).toFloat() },
                    progressColor = usageColor(state.stats?.cpuUsage),
                    extra = state.stats?.let { stats -> { LoadAvgRow(stats) } },
                )

                // Memory Card
                StatCard(
                    title = "Memory",
                    icon = Icons.Default.Storage,
                    value = state.stats?.let { "${it.memoryUsedFormatted} / ${it.memoryTotalFormatted}" } ?: "--",
                    subtitle = state.stats?.let { "${"%.1f".format(it.memoryUsage)}%  •  ${it.memoryFreeFormatted} free" },
                    progress = state.stats?.let { (it.memoryUsage / 100.0).coerceIn(0.0, 1.0).toFloat() },
                    progressColor = usageColor(state.stats?.memoryUsage),
                )

                // Disk Card
                StatCard(
                    title = "Disk",
                    icon = Icons.Default.Storage,
                    value = state.stats?.let { "${it.diskUsedFormatted} / ${it.diskTotalFormatted}" } ?: "--",
                    subtitle = state.stats?.let { "${"%.1f".format(it.diskUsage)}%  •  ${it.diskFreeFormatted} free" },
                    progress = state.stats?.let { (it.diskUsage / 100.0).coerceIn(0.0, 1.0).toFloat() },
                    progressColor = usageColor(state.stats?.diskUsage),
                )

                // Top CPU Processes
                state.stats?.topCpuProcesses?.takeIf { it.isNotEmpty() }?.let { processes ->
                    TopProcessesCard(
                        title = "Top CPU Processes",
                        icon = Icons.Default.Memory,
                        processes = processes,
                        valueLabel = { p -> "${"%.1f".format(p.cpuUsage)}%" },
                        barColor = usageColor(processes.firstOrNull()?.cpuUsage),
                        maxValue = processes.maxOfOrNull { it.cpuUsage }?.toFloat()?.coerceAtLeast(1f) ?: 1f,
                        barValue = { p -> p.cpuUsage.toFloat() },
                    )
                }

                // Top Memory Processes
                state.stats?.topMemProcesses?.takeIf { it.isNotEmpty() }?.let { processes ->
                    TopProcessesCard(
                        title = "Top Memory Processes",
                        icon = Icons.Default.Storage,
                        processes = processes,
                        valueLabel = { p -> p.memoryFormatted },
                        barColor = Color(0xFF6366F1),
                        maxValue = processes.maxOfOrNull { it.memoryBytes }?.toFloat()?.coerceAtLeast(1f) ?: 1f,
                        barValue = { p -> p.memoryBytes.toFloat() },
                    )
                }

                // Uptime card
                Card(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("Uptime") },
                        leadingContent = { Icon(Icons.Default.Timer, contentDescription = null) },
                        supportingContent = {
                            Text(
                                state.stats?.uptime ?: "--",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                            )
                        },
                    )
                }

                // System info card
                state.stats?.takeIf { it.hostname.isNotEmpty() }?.let { stats ->
                    SystemInfoCard(stats)
                }
            }
        }
    }
}

@Composable
private fun TopProcessesCard(
    title: String,
    icon: ImageVector,
    processes: List<ProcessInfo>,
    valueLabel: (ProcessInfo) -> String,
    barColor: Color,
    maxValue: Float,
    barValue: (ProcessInfo) -> Float,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = barColor)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            processes.forEach { process ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        process.name,
                        fontSize = 12.sp,
                        modifier = Modifier.width(110.dp),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(6.dp))
                    LinearProgressIndicator(
                        progress = { (barValue(process) / maxValue).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(6.dp),
                        color = barColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        valueLabel(process),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(52.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatCard(
    title: String,
    icon: ImageVector,
    value: String,
    subtitle: String?,
    progress: Float?,
    progressColor: Color,
    extra: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = progressColor)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface))
            }
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            progress?.let {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            extra?.let {
                Spacer(Modifier.height(12.dp))
                it()
            }
        }
    }
}

@Composable
private fun LoadAvgRow(stats: SystemStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LoadAvgChip(label = "1m", value = stats.cpuUsage)
        LoadAvgChip(label = "5m", value = stats.loadAvg5m)
        LoadAvgChip(label = "15m", value = stats.loadAvg15m)
    }
}

@Composable
private fun LoadAvgChip(label: String, value: Double) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "$label: ${"%.2f".format(value)}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SystemInfoCard(stats: SystemStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("System", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            InfoRow(label = "Hostname", value = stats.hostname)
            if (stats.platform.isNotEmpty()) {
                InfoRow(label = "OS", value = "${stats.platform}  ${stats.arch}")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            modifier = Modifier.width(80.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun buildCpuSubtitle(stats: SystemStats): String {
    val parts = mutableListOf<String>()
    if (stats.cpuCores > 0) parts.add("${stats.cpuCores} cores")
    if (stats.cpuModel.isNotEmpty()) parts.add(stats.cpuModel)
    return parts.joinToString("  •  ")
}

private fun usageColor(usage: Double?): Color = when {
    usage == null -> Color(0xFF9CA3AF)
    usage < 50 -> Color(0xFF22C55E)
    usage < 80 -> Color(0xFFF97316)
    else -> Color(0xFFEF4444)
}
