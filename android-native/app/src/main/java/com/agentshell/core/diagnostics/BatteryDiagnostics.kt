package com.agentshell.core.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.PowerManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.agentshell.core.power.BackgroundWorkPolicy
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import okio.utf8Size
import java.io.File
import java.io.IOException
import java.time.Instant

/** Opt-in observation only. No wake locks, alarms, network requests, or connection-policy changes. */
@OptIn(ExperimentalCoroutinesApi::class)
object BatteryDiagnostics {
    const val TAG = "AgentShellPower"
    private val counters = BatteryDiagnosticCounters()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val fileLock = Any()
    private var context: Context? = null
    private var reporter: Job? = null
    private var pendingWrite: Job? = null
    private var activities = 0
    private var foreground = false
    private var screenOn = false
    private var previous: Sample? = null
    @Volatile private var workPolicy: BackgroundWorkPolicy? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            report("screen_changed")
            synchronized(this@BatteryDiagnostics) {
                screenOn = power()?.isInteractive == true
                counters.visibility(foreground, screenOn)
            }
        }
    }

    private data class Sample(val elapsed: Long, val uptime: Long, val cpu: Long, val rx: Long, val tx: Long)
    private fun sample() = Sample(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis(), Process.getElapsedCpuTime(),
        TrafficStats.getUidRxBytes(Process.myUid()), TrafficStats.getUidTxBytes(Process.myUid()))
    private fun power() = context?.getSystemService(PowerManager::class.java)
    private fun directory(ctx: Context) = File(ctx.filesDir, "battery-diagnostics")

    fun observeWorkPolicy(policy: BackgroundWorkPolicy) { workPolicy = policy }
    fun workDeviceStateChanging() = report("device_state_changed")

    @Synchronized fun setEnabled(ctx: Context, enabled: Boolean) {
        if (enabled == counters.enabled) return
        context = ctx.applicationContext
        if (enabled) {
            screenOn = power()?.isInteractive == true
            counters.visibility(foreground, screenOn)
            val now = sample()
            previous = now
            counters.enable(now.elapsed)
            ContextCompat.registerReceiver(context!!, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            report("enabled")
            reporter = scope.launch {
                while (isActive) { delay(60_000); report("interval") }
            }
        } else {
            report("disabled")
            counters.disable()
            reporter?.cancel(); reporter = null
            runCatching { context?.unregisterReceiver(receiver) }
            previous = null
        }
    }

    @Synchronized fun activityStarted() {
        activities++
        if (activities == 1) {
            report("foreground_changed")
            foreground = true
            counters.visibility(foreground, screenOn)
        }
    }

    @Synchronized fun activityStopped() {
        activities = (activities - 1).coerceAtLeast(0)
        if (activities == 0 && foreground) {
            report("foreground_changed")
            foreground = false
            counters.visibility(foreground, screenOn)
        }
    }

    fun wakeLock(held: Boolean) {
        counters.wakeLock(held, SystemClock.elapsedRealtime())
        report(if (held) "wake_acquired" else "wake_released")
    }

    fun socket(token: Any, role: DiagnosticSocketRole, connected: Boolean) = counters.socket(token, role, connected)
    fun event(name: String, role: DiagnosticSocketRole? = null) {
        counters.event(name, role)
        if (name == "service_started" || name == "service_stopped") report(name)
    }
    fun message(role: DiagnosticSocketRole, incoming: Boolean, text: String, type: String? = null) {
        if (!counters.enabled) return
        counters.transfer(role.name, type, incoming, text.utf8Size())
    }

    /** Body sizes only. OkHttp excludes upgraded WebSocket frames; those are counted separately. */
    val httpListenerFactory = EventListener.Factory { call ->
        if (!counters.enabled) return@Factory EventListener.NONE
        val path = call.request().url.encodedPath
        val category = when {
            path.startsWith("/api/browser") -> "http_browser"
            path.contains("/files") || path.contains("/attachments") || path.contains("/download") -> "http_files"
            path.endsWith("/transcriptions") || path.endsWith("/speech") -> "http_audio"
            else -> null
        }
        object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) = counters.httpStatus(response.code, category)
            override fun requestBodyEnd(call: Call, byteCount: Long) = counters.transfer("HTTP", category, false, byteCount)
            override fun responseBodyEnd(call: Call, byteCount: Long) = counters.transfer("HTTP", category, true, byteCount)
            override fun callFailed(call: Call, ioe: IOException) = counters.httpFailure(call.isCanceled(), category)
        }
    }

    @Synchronized private fun report(reason: String) {
        if (!counters.enabled) return
        val now = sample()
        val old = previous ?: now
        previous = now
        val elapsed = (now.elapsed - old.elapsed).coerceAtLeast(0)
        val values = counters.drain(now.elapsed)
        val header = "${Instant.now()} reason=$reason foreground=$foreground screen_on=$screenOn " +
            "schema=2 sdk=${Build.VERSION.SDK_INT} " +
            "window_ms=$elapsed process_cpu_ms=${(now.cpu - old.cpu).coerceAtLeast(0)} " +
            "suspend_ms=${(elapsed - (now.uptime - old.uptime)).coerceAtLeast(0)} " +
            "uid_rx_bytes=${delta(old.rx, now.rx)} uid_tx_bytes=${delta(old.tx, now.tx)} " +
            "device_idle=${power()?.isDeviceIdleMode == true} battery_exempt=${power()?.isIgnoringBatteryOptimizations(context!!.packageName) == true} " +
            "charging=${workPolicy?.state?.value?.charging ?: (context?.getSystemService(BatteryManager::class.java)?.isCharging == true)} " +
            "transport=${workPolicy?.state?.value?.transport?.name ?: "UNKNOWN"} metered=${workPolicy?.state?.value?.metered}"
        // Bound Logcat line size; every counter has a fixed enum/allowlisted name.
        val lines = listOf(header) + values.entries.chunked(8).map { group ->
            "${Instant.now()} counters " + group.joinToString(" ") { "${it.key}=${it.value}" }
        }
        lines.forEach { Log.i(TAG, it) }
        val ctx = context ?: return
        val earlier = pendingWrite
        pendingWrite = scope.launch {
            earlier?.join()
            runCatching { synchronized(fileLock) { append(directory(ctx), lines.joinToString("\n", postfix = "\n")) } }
                .onFailure { Log.w(TAG, "diagnostic_file_write_failed") }
        }
    }

    suspend fun shareIntent(ctx: Context): Intent {
        report("export")
        synchronized(this) { pendingWrite }?.join()
        return withContext(Dispatchers.IO) {
            val export = File(ctx.cacheDir, "battery-diagnostics").apply { mkdirs() }
                .resolve("agentshell-battery-diagnostics.txt")
            synchronized(fileLock) {
                export.outputStream().buffered().use { output ->
                    output.write("AgentShell battery diagnostics. Byte counts are UID totals or application payloads, not battery measurements. -1 means unavailable.\n".toByteArray())
                    for (name in listOf("previous.log", "current.log")) {
                        directory(ctx).resolve(name).takeIf { it.isFile }?.inputStream()?.use { it.copyTo(output) }
                    }
                }
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", export)
            Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share battery diagnostics")
        }
    }

    internal fun delta(before: Long, after: Long): Long = if (before < 0 || after < before) -1 else after - before

    internal fun append(dir: File, text: String) {
        dir.mkdirs()
        val current = dir.resolve("current.log")
        if (current.length() + text.toByteArray().size > 1_048_576) {
            val previous = dir.resolve("previous.log")
            if (previous.exists() && !previous.delete()) return
            if (current.exists() && !current.renameTo(previous)) return
        }
        current.appendText(text)
    }
}
