package com.agentshell.feature.system

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentshell.data.model.ContainerActionKind
import com.agentshell.data.model.ContainerInfo
import com.agentshell.data.model.ContainerRuntimeInfo
import com.agentshell.data.model.MetricPoint
import com.agentshell.data.model.ProcessInfo
import com.agentshell.data.model.SystemStats

private enum class SystemTab(val label: String) {
    OVERVIEW("Overview"),
    CONTAINERS("Containers"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    isVisible: Boolean = true,
    viewModel: SystemViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(SystemTab.OVERVIEW) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.setScreenActive(false)
        }
    }

    LaunchedEffect(isVisible) {
        viewModel.setScreenActive(isVisible)
    }

    LaunchedEffect(selectedTab, isVisible) {
        viewModel.setContainerStatsEnabled(isVisible && selectedTab == SystemTab.CONTAINERS)
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "System",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(20.dp))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                SystemTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
                    )
                }
            }

            if (state.isLoading && state.stats == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                when (selectedTab) {
                    SystemTab.OVERVIEW -> SystemOverviewTab(state = state)
                    SystemTab.CONTAINERS -> SystemContainersTab(
                        state = state,
                        onAction = viewModel::performContainerAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemOverviewTab(state: SystemUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ResourceTrendCard(
                cpuHistory = state.cpuHistory,
                memoryHistory = state.memoryHistory,
                cpuUsage = state.stats?.cpuUsage,
                memoryUsage = state.stats?.memoryUsage,
            )
        }

        state.stats?.let { stats ->
            item { SystemSummaryCard(stats) }

            item {
                StatCard(
                    title = "CPU",
                    iconTint = usageColor(stats.cpuUsage),
                    icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                    value = "${"%.1f".format(stats.cpuUsage)}%",
                    subtitle = buildCpuSubtitle(stats),
                    progress = (stats.cpuUsage / 100.0).coerceIn(0.0, 1.0).toFloat(),
                    progressColor = usageColor(stats.cpuUsage),
                    extra = { LoadAvgRow(stats) },
                )
            }

            item {
                StatCard(
                    title = "Memory",
                    iconTint = usageColor(stats.memoryUsage),
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    value = "${stats.memoryUsedFormatted} / ${stats.memoryTotalFormatted}",
                    subtitle = "${"%.1f".format(stats.memoryUsage)}%  •  ${stats.memoryFreeFormatted} free",
                    progress = (stats.memoryUsage / 100.0).coerceIn(0.0, 1.0).toFloat(),
                    progressColor = usageColor(stats.memoryUsage),
                )
            }

            item {
                StatCard(
                    title = "Disk",
                    iconTint = usageColor(stats.diskUsage),
                    icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                    value = "${stats.diskUsedFormatted} / ${stats.diskTotalFormatted}",
                    subtitle = "${"%.1f".format(stats.diskUsage)}%  •  ${stats.diskFreeFormatted} free",
                    progress = (stats.diskUsage / 100.0).coerceIn(0.0, 1.0).toFloat(),
                    progressColor = usageColor(stats.diskUsage),
                )
            }

            if (stats.topCpuProcesses.isNotEmpty()) {
                item {
                    TopProcessesCard(
                        title = "Top CPU Processes",
                        icon = Icons.Default.Memory,
                        processes = stats.topCpuProcesses,
                        valueLabel = { process -> "${"%.1f".format(process.cpuUsage)}%" },
                        barColor = usageColor(stats.cpuUsage),
                        maxValue = stats.topCpuProcesses.maxOfOrNull { it.cpuUsage }?.toFloat()?.coerceAtLeast(1f) ?: 1f,
                        barValue = { process -> process.cpuUsage.toFloat() },
                    )
                }
            }

            if (stats.topMemProcesses.isNotEmpty()) {
                item {
                    TopProcessesCard(
                        title = "Top Memory Processes",
                        icon = Icons.Default.Storage,
                        processes = stats.topMemProcesses,
                        valueLabel = { process -> process.memoryFormatted },
                        barColor = Color(0xFF3B82F6),
                        maxValue = stats.topMemProcesses.maxOfOrNull { it.memoryBytes }?.toFloat()?.coerceAtLeast(1f) ?: 1f,
                        barValue = { process -> process.memoryBytes.toFloat() },
                    )
                }
            }

            item { SystemInfoCard(stats) }
        }
    }
}

@Composable
private fun SystemContainersTab(
    state: SystemUiState,
    onAction: (runtime: com.agentshell.data.model.ContainerRuntimeKind, containerId: String, action: ContainerActionKind) -> Unit,
) {
    val runtimes = state.stats?.containerRuntimes ?: emptyList()

    if (runtimes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No container runtimes reported yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(runtimes, key = { it.runtime.name }) { runtimeInfo ->
            ContainerRuntimeCard(
                runtimeInfo = runtimeInfo,
                pendingKeys = state.pendingContainerActions,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun ResourceTrendCard(
    cpuHistory: List<MetricPoint>,
    memoryHistory: List<MetricPoint>,
    cpuUsage: Double?,
    memoryUsage: Double?,
) {
    val cpuColor = Color(0xFFF97316)
    val memoryColor = Color(0xFF3B82F6)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Realtime Load", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Last ${maxOf(cpuHistory.size, memoryHistory.size)} samples",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendChip(label = "CPU", value = cpuUsage, color = cpuColor)
                    LegendChip(label = "RAM", value = memoryUsage, color = memoryColor)
                }
            }
            Spacer(Modifier.height(16.dp))
            TrendChart(
                cpuHistory = cpuHistory,
                memoryHistory = memoryHistory,
                cpuColor = cpuColor,
                memoryColor = memoryColor,
            )
        }
    }
}

@Composable
private fun LegendChip(label: String, value: Double?, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
            )
            Text(
                "$label ${value?.let { "${"%.1f".format(it)}%" } ?: "--"}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TrendChart(
    cpuHistory: List<MetricPoint>,
    memoryHistory: List<MetricPoint>,
    cpuColor: Color,
    memoryColor: Color,
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    val frameColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.05f)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val rows = 4
        val rowHeight = size.height / rows
        repeat(rows + 1) { row ->
            val y = row * rowHeight
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }

        fun drawSeries(points: List<MetricPoint>, color: Color) {
            if (points.size < 2) return
            val maxIndex = (points.size - 1).coerceAtLeast(1)
            val offsets = points.mapIndexed { index, point ->
                val x = size.width * (index.toFloat() / maxIndex.toFloat())
                val y = size.height - ((point.value.coerceIn(0.0, 100.0) / 100.0) * size.height).toFloat()
                Offset(x, y)
            }
            offsets.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = 6f,
                    cap = StrokeCap.Round,
                )
            }
        }

        drawSeries(cpuHistory, cpuColor)
        drawSeries(memoryHistory, memoryColor)
        drawRect(
            color = frameColor,
            style = Stroke(width = 2f),
        )
    }
}

@Composable
private fun SystemSummaryCard(stats: SystemStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Host Summary", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("Uptime ${stats.uptime}") },
                    leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("${stats.runningContainerCount}/${stats.containerCount} running") },
                    leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryMetric(label = "CPU", value = "${"%.1f".format(stats.cpuUsage)}%")
                SummaryMetric(label = "RAM", value = "${"%.1f".format(stats.memoryUsage)}%")
                SummaryMetric(label = "Disk", value = "${"%.1f".format(stats.diskUsage)}%")
            }
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ContainerRuntimeCard(
    runtimeInfo: ContainerRuntimeInfo,
    pendingKeys: Set<String>,
    onAction: (runtime: com.agentshell.data.model.ContainerRuntimeKind, containerId: String, action: ContainerActionKind) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(runtimeInfo.runtime.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !runtimeInfo.available -> "Unavailable"
                            runtimeInfo.containers.isEmpty() -> "No containers found"
                            else -> "${runtimeInfo.runningCount}/${runtimeInfo.containers.size} running"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("${runtimeInfo.containers.size} total") })
                    AssistChip(onClick = {}, label = { Text("${runtimeInfo.pausedCount} paused") })
                }
            }

            runtimeInfo.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        error,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (runtimeInfo.containers.isEmpty()) {
                Text(
                    "No containers reported",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                runtimeInfo.containers.forEachIndexed { index, container ->
                    if (index > 0) HorizontalDivider()
                    ContainerCard(
                        runtime = runtimeInfo.runtime,
                        container = container,
                        isPending = pendingKeys.contains("${runtimeInfo.runtime.wireValue}:${container.id}"),
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerCard(
    runtime: com.agentshell.data.model.ContainerRuntimeKind,
    container: ContainerInfo,
    isPending: Boolean,
    onAction: (runtime: com.agentshell.data.model.ContainerRuntimeKind, containerId: String, action: ContainerActionKind) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val primaryAction = when {
        container.isPaused -> ContainerActionKind.RESUME
        container.isStartable -> ContainerActionKind.START
        else -> ContainerActionKind.STOP
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateDot(color = containerStateColor(container.state))
                    Text(container.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            container.stateLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    container.image,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${container.shortId}  •  ${container.status}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }, enabled = !isPending) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Container actions")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    ContainerActionKind.entries.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            leadingIcon = { Icon(actionIcon(action), contentDescription = null) },
                            enabled = isContainerActionEnabled(container, action) && !isPending,
                            onClick = {
                                showMenu = false
                                onAction(runtime, container.id, action)
                            },
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ContainerMetric(label = "CPU", value = container.cpuUsage?.let { "${"%.1f".format(it)}%" } ?: "--")
            ContainerMetric(
                label = "Memory",
                value = if (container.memoryUsageBytes != null || container.memoryLimitBytes != null) {
                    "${container.memoryUsageFormatted} / ${container.memoryLimitFormatted}"
                } else {
                    "--"
                },
            )
            ContainerMetric(label = "PIDs", value = container.pids?.toString() ?: "--")
        }

        if (container.memoryPercent != null) {
            LinearProgressIndicator(
                progress = { (container.memoryPercent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = usageColor(container.memoryPercent),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { onAction(runtime, container.id, primaryAction) },
                enabled = !isPending,
            ) {
                Icon(actionIcon(primaryAction), contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(primaryAction.label)
            }
            TextButton(
                onClick = { onAction(runtime, container.id, ContainerActionKind.RESTART) },
                enabled = !isPending && isContainerActionEnabled(container, ContainerActionKind.RESTART),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Restart")
            }
            if (isPending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Applying", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ContainerMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StateDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun TopProcessesCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        process.name,
                        fontSize = 12.sp,
                        modifier = Modifier.width(120.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        modifier = Modifier.width(64.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    iconTint: Color,
    icon: @Composable () -> Unit,
    value: String,
    subtitle: String?,
    progress: Float?,
    progressColor: Color,
    extra: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides iconTint) {
                    icon()
                }
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleLarge)
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (extra != null) {
                Spacer(Modifier.height(12.dp))
                extra()
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
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(
            text = "$label ${"%.2f".format(value)}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SystemInfoCard(stats: SystemStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("System Info", style = MaterialTheme.typography.titleMedium)
            }
            InfoRow(label = "Hostname", value = stats.hostname)
            if (stats.platform.isNotEmpty()) {
                InfoRow(label = "OS", value = "${stats.platform}  ${stats.arch}")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            label,
            modifier = Modifier.width(80.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun buildCpuSubtitle(stats: SystemStats): String {
    val parts = mutableListOf<String>()
    if (stats.cpuCores > 0) parts += "${stats.cpuCores} cores"
    if (stats.cpuModel.isNotBlank()) parts += stats.cpuModel
    return parts.joinToString("  •  ")
}

private fun usageColor(usage: Double?): Color = when {
    usage == null -> Color(0xFF94A3B8)
    usage < 50 -> Color(0xFF22C55E)
    usage < 80 -> Color(0xFFF97316)
    else -> Color(0xFFEF4444)
}

private fun containerStateColor(state: String): Color = when (state) {
    "running" -> Color(0xFF22C55E)
    "paused" -> Color(0xFFF59E0B)
    "restarting" -> Color(0xFF3B82F6)
    "exited", "dead" -> Color(0xFFEF4444)
    else -> Color(0xFF94A3B8)
}

private fun isContainerActionEnabled(container: ContainerInfo, action: ContainerActionKind): Boolean =
    when (action) {
        ContainerActionKind.START -> container.isStartable
        ContainerActionKind.STOP -> container.isRunning || container.isPaused
        ContainerActionKind.RESTART -> container.state != "dead"
        ContainerActionKind.KILL -> container.state != "dead"
        ContainerActionKind.PAUSE -> container.isRunning
        ContainerActionKind.RESUME -> container.isPaused
    }

private fun actionIcon(action: ContainerActionKind) = when (action) {
    ContainerActionKind.START -> Icons.Default.PlayArrow
    ContainerActionKind.STOP -> Icons.Default.Stop
    ContainerActionKind.RESTART -> Icons.Default.RestartAlt
    ContainerActionKind.KILL -> Icons.Default.Delete
    ContainerActionKind.PAUSE -> Icons.Default.Pause
    ContainerActionKind.RESUME -> Icons.Default.PlayArrow
}
