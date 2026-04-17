package com.agentshell.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ProcessInfo(
    val pid: Long,
    val name: String,
    val cpuUsage: Double,
    val memoryBytes: Long,
) {
    val memoryFormatted: String get() = memoryBytes.formatBytes()
}

@Serializable
enum class ContainerRuntimeKind {
    DOCKER,
    PODMAN;

    val label: String
        get() = when (this) {
            DOCKER -> "Docker"
            PODMAN -> "Podman"
        }

    val wireValue: String
        get() = name.lowercase()
}

@Serializable
enum class ContainerActionKind {
    START,
    STOP,
    RESTART,
    KILL,
    PAUSE,
    RESUME;

    val label: String
        get() = when (this) {
            START -> "Start"
            STOP -> "Stop"
            RESTART -> "Restart"
            KILL -> "Kill"
            PAUSE -> "Pause"
            RESUME -> "Resume"
        }

    val wireValue: String
        get() = name.lowercase()
}

@Serializable
data class ContainerInfo(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val cpuUsage: Double? = null,
    val memoryUsageBytes: Long? = null,
    val memoryLimitBytes: Long? = null,
    val memoryPercent: Double? = null,
    val pids: Long? = null,
) {
    val shortId: String get() = id.take(12)
    val memoryUsageFormatted: String get() = memoryUsageBytes?.formatBytes() ?: "--"
    val memoryLimitFormatted: String get() = memoryLimitBytes?.formatBytes() ?: "--"
    val stateLabel: String get() = state.replaceFirstChar { it.uppercase() }
    val isRunning: Boolean get() = state == "running"
    val isPaused: Boolean get() = state == "paused"
    val isStartable: Boolean get() = state == "created" || state == "exited" || state == "dead"
}

@Serializable
data class ContainerRuntimeInfo(
    val runtime: ContainerRuntimeKind,
    val available: Boolean,
    val error: String? = null,
    val containers: List<ContainerInfo> = emptyList(),
) {
    val runningCount: Int get() = containers.count { it.state == "running" }
    val pausedCount: Int get() = containers.count { it.state == "paused" }
}

@Serializable
data class MetricPoint(
    val timestamp: Long,
    val value: Double,
)

@Serializable
data class SystemStats(
    val cpuUsage: Double,
    val cpuCores: Int,
    val cpuModel: String,
    val loadAvg5m: Double,
    val loadAvg15m: Double,
    val memoryUsage: Double,
    val memoryTotal: Long,
    val memoryUsed: Long,
    val memoryFree: Long,
    val diskUsage: Double,
    val diskTotal: Long,
    val diskUsed: Long,
    val diskFree: Long,
    val hostname: String,
    val platform: String,
    val arch: String,
    val uptime: String,
    val timestamp: Long,
    val topCpuProcesses: List<ProcessInfo> = emptyList(),
    val topMemProcesses: List<ProcessInfo> = emptyList(),
    val containerRuntimes: List<ContainerRuntimeInfo> = emptyList(),
) {
    val memoryUsedFormatted: String get() = memoryUsed.formatBytes()
    val memoryTotalFormatted: String get() = memoryTotal.formatBytes()
    val memoryFreeFormatted: String get() = memoryFree.formatBytes()
    val diskUsedFormatted: String get() = diskUsed.formatBytes()
    val diskTotalFormatted: String get() = diskTotal.formatBytes()
    val diskFreeFormatted: String get() = diskFree.formatBytes()
    val containerCount: Int get() = containerRuntimes.sumOf { it.containers.size }
    val runningContainerCount: Int get() = containerRuntimes.sumOf { it.runningCount }
}

fun Long.formatBytes(): String {
    return when {
        this < 1024L -> "$this B"
        this < 1024L * 1024L -> "${"%.1f".format(this / 1024.0)} KB"
        this < 1024L * 1024L * 1024L -> "${"%.1f".format(this / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(this / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
