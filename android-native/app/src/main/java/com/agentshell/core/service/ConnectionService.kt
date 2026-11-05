package com.agentshell.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.agentshell.MainActivity
import com.agentshell.R
import com.agentshell.core.diagnostics.BatteryDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** User-authorized live interaction with a remote computer; never owns archive synchronization. */
class ConnectionService : Service() {
    companion object {
        private const val CHANNEL_ID = "agentshell_connection"
        private const val NOTIFICATION_ID = 9001
        internal const val ACTION_START = "com.agentshell.connection.START"
        internal const val EXTRA_GENERATION = "connection_generation"
        internal const val WAKE_TIMEOUT_MS = 5 * 60_000L
        internal const val WAKE_RENEW_INTERVAL_MS = 4 * 60_000L

        fun start(context: Context): Boolean {
            val generation = ConnectionServiceRuntime.beginStart()
                ?: return ConnectionServiceRuntime.state.value.let {
                    ConnectionServiceRuntime.allowed(it) && (it.running || it.starting)
                }
            val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_GENERATION, generation)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
                true
            } catch (_: RuntimeException) {
                ConnectionServiceRuntime.failStart(generation)
                BatteryDiagnostics.event("service_start_rejected")
                false
            }
        }

        fun stop(context: Context) {
            ConnectionServiceRuntime.prepareStop()
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLeaseEndsAt = 0L
    private var owner: Long? = null
    private var lastStartId = 0
    private var expectedStop = false
    private var stopping = false
    private var timerExpired = false
    private var timeoutReported = false
    private var scheduledDeadline: Long? = null
    private val renewWake = Runnable { applyRuntimeState(renew = true) }
    private val expireLive = Runnable {
        // Handler time is monotonic, while the persisted selection is wall-clock
        // based. A clock rollback can leave this deadline in the future again.
        scheduledDeadline = null
        applyRuntimeState()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch { ConnectionServiceRuntime.state.collect { applyRuntimeState() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = maxOf(lastStartId, startId)
        val generation = intent?.getLongExtra(EXTRA_GENERATION, -1) ?: -1
        if (intent?.action != ACTION_START || !ConnectionServiceRuntime.currentStart(generation)) {
            ConnectionServiceRuntime.rejectStart(generation)
            // A delayed old request must not stop a newer, authorized live lease.
            // A fresh process receiving a sticky null intent has no such lease.
            if (owner == null && !ConnectionServiceRuntime.state.value.running &&
                !ConnectionServiceRuntime.state.value.starting) {
                expectedStop = true
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            // A newer valid start can arrive while an earlier stop is being
            // processed on this same Service instance.
            stopping = false
            expectedStop = false
            timerExpired = false
            timeoutReported = false
            scheduledDeadline = null
            owner = generation
            if (!ConnectionServiceRuntime.markRunning(generation)) {
                stopExpected()
                START_NOT_STICKY
            } else {
                BatteryDiagnostics.event("service_started")
                applyRuntimeState()
                START_NOT_STICKY
            }
        } catch (_: RuntimeException) {
            ConnectionServiceRuntime.failStart(generation)
            BatteryDiagnostics.event("service_start_rejected")
            stopExpected()
            START_NOT_STICKY
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ConnectionServiceRuntime.block(ConnectionServiceBlockReason.TIMEOUT)
        if (!timeoutReported) {
            timeoutReported = true
            BatteryDiagnostics.event("service_timeout")
        }
        stopExpected()
        // A repeated start may supersede the callback's startId. Always satisfy
        // Android's fatal timeout grace by stopping the actual service.
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewWake)
        handler.removeCallbacks(expireLive)
        releaseWakeLock()
        ConnectionServiceRuntime.stopped(owner, expectedStop)
        BatteryDiagnostics.event("service_stopped")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun applyRuntimeState(renew: Boolean = false) {
        if (owner == null || stopping) return
        val state = ConnectionServiceRuntime.state.value
        if (!ConnectionServiceRuntime.allowed(state)) {
            if (state.liveUntilEpochMillis?.let { it <= ConnectionServiceRuntime.nowEpochMillis() } == true) {
                if (!timerExpired) {
                    timerExpired = true
                    BatteryDiagnostics.event("connection_timer_expired")
                }
                ConnectionServiceRuntime.expire()
            }
            stopExpected()
            return
        }
        val deadlineChanged = state.liveUntilEpochMillis != scheduledDeadline
        if (deadlineChanged) {
            handler.removeCallbacks(expireLive)
            scheduledDeadline = state.liveUntilEpochMillis
            scheduledDeadline?.let { deadline ->
                handler.postDelayed(expireLive, (deadline - ConnectionServiceRuntime.nowEpochMillis()).coerceAtLeast(0))
            }
        }
        // Extending/removing a short timer must replace its short wake lease:
        // that lease may have no four-minute renewal scheduled. Unchanged
        // deadlines and repeated starts still leave the existing lease alone.
        if (state.wakeRequired) acquireWakeLock(state, renew || deadlineChanged) else releaseWakeLock()
    }

    private fun acquireWakeLock(state: ConnectionServiceRuntimeState, renew: Boolean) {
        val now = ConnectionServiceRuntime.nowEpochMillis()
        val remaining = state.liveUntilEpochMillis?.minus(now) ?: WAKE_TIMEOUT_MS
        if (remaining <= 0) { applyRuntimeState(); return }
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgentShell::ConnectionWakeLock")
            .apply { setReferenceCounted(false) }.also { wakeLock = it }
        val wasHeld = lock.isHeld
        val shortened = state.liveUntilEpochMillis?.let { it < wakeLeaseEndsAt } == true
        if (wasHeld && !renew && !shortened) return
        val timeout = minOf(WAKE_TIMEOUT_MS, remaining)
        lock.acquire(timeout)
        wakeLeaseEndsAt = now + timeout
        BatteryDiagnostics.wakeLock(true)
        if (wasHeld) BatteryDiagnostics.event("connection_wake_renewed")
        handler.removeCallbacks(renewWake)
        if (timeout > WAKE_RENEW_INTERVAL_MS) handler.postDelayed(renewWake, WAKE_RENEW_INTERVAL_MS)
    }

    private fun releaseWakeLock() {
        handler.removeCallbacks(renewWake)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wakeLeaseEndsAt = 0
        BatteryDiagnostics.wakeLock(false)
    }

    private fun stopExpected() {
        if (!stopping) {
            stopping = true
            expectedStop = true
            handler.removeCallbacks(renewWake)
            handler.removeCallbacks(expireLive)
            releaseWakeLock()
            ConnectionServiceRuntime.prepareStop()
            ConnectionServiceRuntime.stopped(owner, true)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live updates from your remote computer"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val tap = PendingIntent.getActivity(this, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AgentShell")
            .setContentText("Live updates from your remote computer")
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap).build()
    }
}
