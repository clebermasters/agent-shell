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
) {
    val memoryUsedFormatted: String get() = memoryUsed.formatBytes()
    val memoryTotalFormatted: String get() = memoryTotal.formatBytes()
    val memoryFreeFormatted: String get() = memoryFree.formatBytes()
    val diskUsedFormatted: String get() = diskUsed.formatBytes()
    val diskTotalFormatted: String get() = diskTotal.formatBytes()
    val diskFreeFormatted: String get() = diskFree.formatBytes()
}

fun Long.formatBytes(): String {
    return when {
        this < 1024L -> "$this B"
        this < 1024L * 1024L -> "${"%.1f".format(this / 1024.0)} KB"
        this < 1024L * 1024L * 1024L -> "${"%.1f".format(this / (1024.0 * 1024.0))} MB"
        else -> "${"%.1f".format(this / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}
