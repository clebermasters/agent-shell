package com.agentshell

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import com.agentshell.core.service.ConnectionService
import com.agentshell.core.util.NotificationHelper
import com.agentshell.data.remote.WebSocketService
import com.agentshell.data.services.AudioPlayerManager
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
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wsService: WebSocketService

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)

        val entryPoint = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        wsService = entryPoint.webSocketService()
        entryPoint.audioPlayerManager().connect()

        observeNotificationEvents()
        manageForegroundService()
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
                    NotificationHelper.show(this@AgentShellApp, title, body, id)
                }
        }
    }

    /**
     * Keep the foreground service alive for the full lifetime of the desired
     * websocket connection, including reconnect attempts while offline.
     */
    private fun manageForegroundService() {
        appScope.launch {
            wsService.keepAliveEnabled
                .collect { keepAlive ->
                    if (keepAlive) {
                        ConnectionService.start(this@AgentShellApp)
                    } else {
                        ConnectionService.stop(this@AgentShellApp)
                    }
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
                wsService.checkConnection()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
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
                        wsService.onNetworkAvailable()
                    }
                }
            }
        )
    }
}
