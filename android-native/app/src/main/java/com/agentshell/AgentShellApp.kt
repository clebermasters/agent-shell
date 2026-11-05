package com.agentshell

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.agentshell.core.power.BackgroundWorkPolicy
import com.agentshell.core.power.ConnectionLifecycleCoordinator
import com.agentshell.core.power.NetworkTransport
import com.agentshell.core.service.ConnectionService
import com.agentshell.core.diagnostics.BatteryDiagnostics
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.core.util.NotificationHelper
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.repository.ChatActivityRepository
import com.agentshell.data.repository.RecentSessionsRepository
import com.agentshell.data.services.AudioPlayerManager
import com.agentshell.data.services.DeviceAdbReporter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@HiltAndroidApp
class AgentShellApp : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun webSocketService(): WebSocketService
        fun audioPlayerManager(): AudioPlayerManager
        fun chatActivityRepository(): ChatActivityRepository
        fun recentSessionsRepository(): RecentSessionsRepository
        fun deviceAdbReporter(): DeviceAdbReporter
        fun preferencesDataStore(): PreferencesDataStore
        fun backgroundWorkPolicy(): BackgroundWorkPolicy
        fun connectionLifecycleCoordinator(): ConnectionLifecycleCoordinator
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wsService: WebSocketService
    private lateinit var workPolicy: BackgroundWorkPolicy
    private lateinit var connectionCoordinator: ConnectionLifecycleCoordinator

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)

        val entryPoint = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        workPolicy = entryPoint.backgroundWorkPolicy()
        refreshWorkDeviceState()
        BatteryDiagnostics.observeWorkPolicy(workPolicy)
        registerWorkDeviceState()
        appScope.launch {
            entryPoint.preferencesDataStore().batteryDiagnosticsEnabled.collect {
                BatteryDiagnostics.setEnabled(this@AgentShellApp, it)
            }
        }
        wsService = entryPoint.webSocketService()
        connectionCoordinator = entryPoint.connectionLifecycleCoordinator()
        connectionCoordinator.start(appScope)
        entryPoint.audioPlayerManager().connect()
        entryPoint.chatActivityRepository().start()
        entryPoint.recentSessionsRepository()
        entryPoint.deviceAdbReporter().start()

        observeNotificationEvents()
        registerForegroundReconnect()
        registerNetworkReconnect()
    }

    /**
     * Observe WebSocket "notification-event" messages at the application level
     * so system notifications are posted even when the Alerts screen is not open.
     */
    @Suppress("UNCHECKED_CAST")
    private fun observeNotificationEvents() {
        appScope.launch {
            wsService.messages
                .filter { it["type"] == "notification-event" }
                .collect { msg ->
                    val notification = msg["notification"] as? Map<String, Any?> ?: return@collect
                    val title = notification["title"] as? String ?: return@collect
                    val body = notification["body"] as? String ?: ""
                    val id = notification["id"] as? String ?: return@collect
                    BatteryDiagnostics.event("notification_received")
                    NotificationHelper.show(this@AgentShellApp, title, body, id)
                }
        }
    }

    /**
     * When any Activity resumes (screen on, app foregrounded), check the WebSocket
     * connection and force a reconnect if it's dead. This mirrors Flutter's
     * WidgetsBindingObserver.didChangeAppLifecycleState(resumed) behavior.
     */
    private fun registerForegroundReconnect() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                android.util.Log.d("AgentShellApp", "Foreground resume: ${activity::class.java.simpleName} -> checkConnection()")
                refreshWorkDeviceState()
                connectionCoordinator.onForeground()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {
                workPolicy.activityStarted()
                BatteryDiagnostics.activityStarted()
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {
                workPolicy.activityStopped()
                BatteryDiagnostics.activityStopped()
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun registerNetworkReconnect() {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    appScope.launch {
                        refreshWorkDeviceState()
                        wsService.onNetworkAvailable()
                    }
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    appScope.launch { refreshWorkDeviceState() }
                }
                override fun onLost(network: Network) {
                    appScope.launch { refreshWorkDeviceState() }
                }
            }
        )
    }

    private fun registerWorkDeviceState() {
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshWorkDeviceState()
                if (::connectionCoordinator.isInitialized) connectionCoordinator.refreshClock()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun refreshWorkDeviceState() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity?.getNetworkCapabilities(it) }
        val transport = when {
            network == null -> NetworkTransport.NONE
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> NetworkTransport.VPN
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkTransport.WIFI
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkTransport.CELLULAR
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> NetworkTransport.ETHERNET
            else -> NetworkTransport.OTHER
        }
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        val metered = connectivity?.isActiveNetworkMetered ?: true
        val charging = getSystemService(BatteryManager::class.java)?.isCharging == true
        val connected = network != null
        val previous = workPolicy.state.value
        if (previous.transport != transport || previous.metered != metered || previous.charging != charging ||
            previous.connected != connected || previous.interactive != interactive) {
            // Close the previous interval while diagnostics still sees the old
            // network, instead of labeling Wi-Fi bytes as subsequent cellular use.
            BatteryDiagnostics.workDeviceStateChanging()
        }
        workPolicy.updateDevice(interactive, metered, charging, connected, transport)
    }
}
