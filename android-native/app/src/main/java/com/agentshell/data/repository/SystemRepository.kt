package com.agentshell.data.repository

import com.agentshell.data.model.AiUsage
import com.agentshell.data.model.ProcessInfo
import com.agentshell.data.model.SystemStats
import com.agentshell.data.model.UsageWindow
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.remote.requestClaudeUsage
import com.agentshell.data.remote.requestCodexUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemRepository @Inject constructor(
    private val wsService: WebSocketService,
) {
    private val _stats = MutableStateFlow<SystemStats?>(null)
    val stats: StateFlow<SystemStats?> = _stats.asStateFlow()

    private val _claudeUsage = MutableStateFlow<AiUsage?>(null)
    val claudeUsage: StateFlow<AiUsage?> = _claudeUsage.asStateFlow()

    private val _codexUsage = MutableStateFlow<AiUsage?>(null)
    val codexUsage: StateFlow<AiUsage?> = _codexUsage.asStateFlow()

    fun requestStats() {
        wsService.send(mapOf("type" to "get-stats"))
    }

    fun requestClaudeUsage() {
        wsService.requestClaudeUsage()
    }

    fun requestCodexUsage() {
        wsService.requestCodexUsage()
    }

    @Suppress("UNCHECKED_CAST")
    fun parseClaudeUsage(msg: Map<String, Any?>) {
        try {
            fun parseBucket(key: String, label: String): UsageWindow? {
                val obj = msg[key] as? Map<String, Any?> ?: return null
                return UsageWindow(
                    label = label,
                    utilization = (obj["utilization"] as? Number)?.toDouble() ?: return null,
                    resetsAt = obj["resetsAt"] as? String ?: obj["resets_at"] as? String,
                )
            }
            val err = msg["error"] as? String
            _claudeUsage.value = AiUsage(
                provider = "claude",
                primary = parseBucket("fiveHour", "5h") ?: parseBucket("five_hour", "5h"),
                secondary = parseBucket("sevenDay", "7d") ?: parseBucket("seven_day", "7d"),
                error = err,
            )
        } catch (_: Exception) { }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseCodexUsage(msg: Map<String, Any?>) {
        try {
            fun parseWindow(key: String, fallbackLabel: String): UsageWindow? {
                val obj = msg[key] as? Map<String, Any?> ?: return null
                val durationMins = (obj["windowDurationMins"] as? Number)?.toLong()
                    ?: (obj["window_duration_mins"] as? Number)?.toLong()
                return UsageWindow(
                    label = formatWindowLabel(durationMins, fallbackLabel),
                    utilization = (obj["usedPercent"] as? Number)?.toDouble()
                        ?: (obj["used_percent"] as? Number)?.toDouble()
                        ?: return null,
                    resetsAt = obj["resetsAt"] as? String ?: obj["resets_at"] as? String,
                    windowDurationMins = durationMins,
                )
            }

            _codexUsage.value = AiUsage(
                provider = "codex",
                primary = parseWindow("primary", "session"),
                secondary = parseWindow("secondary", "week"),
                planType = msg["planType"] as? String ?: msg["plan_type"] as? String,
                limitId = msg["limitId"] as? String ?: msg["limit_id"] as? String,
                limitName = msg["limitName"] as? String ?: msg["limit_name"] as? String,
                error = msg["error"] as? String,
            )
        } catch (_: Exception) { }
    }

    /**
     * Parse an incoming "stats" message and emit to the StateFlow.
     *
     * Backend format:
     * ```json
     * {
     *   "type": "stats",
     *   "stats": {
     *     "cpu": { "usage": 12.5, "cores": 8, "model": "...", "loadAvg": [0.5, 0.8, 0.7] },
     *     "memory": { "total": 16000000000, "used": 8000000000, "free": 8000000000 },
     *     "disk": { "total": 500000000000, "used": 200000000000, "free": 300000000000 },
     *     "hostname": "myhost",
     *     "platform": "linux",
     *     "arch": "x86_64",
     *     "uptime": "5 days, 3:22"
     *   }
     * }
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    fun parseAndEmit(msg: Map<String, Any?>) {
        try {
            // The stats are nested under a "stats" key
            val statsObj = (msg["stats"] as? Map<String, Any?>) ?: msg
            val cpu = statsObj["cpu"] as? Map<String, Any?>
            val memory = statsObj["memory"] as? Map<String, Any?>
            val disk = statsObj["disk"] as? Map<String, Any?>
            val loadAvg = cpu?.get("loadAvg") as? List<*>

            val memTotal = (memory?.get("total") as? Number)?.toLong() ?: 0L
            val memUsed = (memory?.get("used") as? Number)?.toLong() ?: 0L
            val memFree = (memory?.get("free") as? Number)?.toLong() ?: 0L

            val diskTotal = (disk?.get("total") as? Number)?.toLong() ?: 0L
            val diskUsed = (disk?.get("used") as? Number)?.toLong() ?: 0L
            val diskFree = (disk?.get("free") as? Number)?.toLong() ?: 0L

            fun parseProcessList(key: String): List<ProcessInfo> {
                val raw = statsObj[key] as? List<*> ?: return emptyList()
                return raw.mapNotNull { item ->
                    val m = item as? Map<*, *> ?: return@mapNotNull null
                    ProcessInfo(
                        pid = (m["pid"] as? Number)?.toLong() ?: 0L,
                        name = m["name"] as? String ?: "",
                        cpuUsage = (m["cpuUsage"] as? Number)?.toDouble() ?: 0.0,
                        memoryBytes = (m["memoryBytes"] as? Number)?.toLong() ?: 0L,
                    )
                }
            }

            _stats.value = SystemStats(
                cpuUsage = (cpu?.get("usage") as? Number)?.toDouble() ?: 0.0,
                cpuCores = (cpu?.get("cores") as? Number)?.toInt() ?: 0,
                cpuModel = cpu?.get("model") as? String ?: "",
                loadAvg5m = if (loadAvg != null && loadAvg.size > 1)
                    (loadAvg[1] as? Number)?.toDouble() ?: 0.0 else 0.0,
                loadAvg15m = if (loadAvg != null && loadAvg.size > 2)
                    (loadAvg[2] as? Number)?.toDouble() ?: 0.0 else 0.0,
                memoryUsage = if (memTotal > 0) (memUsed.toDouble() / memTotal * 100) else 0.0,
                memoryTotal = memTotal,
                memoryUsed = memUsed,
                memoryFree = memFree,
                diskUsage = if (diskTotal > 0) (diskUsed.toDouble() / diskTotal * 100) else 0.0,
                diskTotal = diskTotal,
                diskUsed = diskUsed,
                diskFree = diskFree,
                hostname = statsObj["hostname"] as? String ?: "",
                platform = statsObj["platform"] as? String ?: "",
                arch = statsObj["arch"] as? String ?: "",
                uptime = formatUptime((statsObj["uptime"] as? Number)?.toLong() ?: 0L),
                timestamp = (statsObj["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                topCpuProcesses = parseProcessList("topProcessesByCpu"),
                topMemProcesses = parseProcessList("topProcessesByMemory"),
            )
        } catch (_: Exception) {
            // Silently ignore parse failures
        }
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0   -> "${days}d ${hours}h ${minutes}m"
            hours > 0  -> "${hours}h ${minutes}m"
            else       -> "${minutes}m"
        }
    }

    private fun formatWindowLabel(durationMins: Long?, fallback: String): String = when {
        durationMins == null -> fallback
        durationMins == 300L -> "5h"
        durationMins == 10080L -> "7d"
        durationMins % 1440L == 0L -> "${durationMins / 1440L}d"
        durationMins % 60L == 0L -> "${durationMins / 60L}h"
        else -> "${durationMins}m"
    }
}
