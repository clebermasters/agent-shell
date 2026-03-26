package com.agentshell

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.agentshell.core.service.ConnectionService
import com.agentshell.core.util.NotificationHelper
import com.agentshell.data.model.ConnectionStatus
import com.agentshell.data.remote.WebSocketService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltAndroidApp
class AgentShellApp : Application() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AppEntryPoint {
        fun webSocketService(): WebSocketService
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wsService: WebSocketService

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)

        val entryPoint = EntryPointAccessors.fromApplication(this, AppEntryPoint::class.java)
        wsService = entryPoint.webSocketService()

        observeNotificationEvents()
        manageForegroundService()
        registerForegroundReconnect()
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
     * Start/stop the foreground service based on WebSocket connection state.
     * The service keeps the process alive so the WebSocket survives background.
     */
    private fun manageForegroundService() {
        appScope.launch {
            wsService.connectionStatus
                .map { it == ConnectionStatus.CONNECTED }
                .distinctUntilChanged()
                .collect { connected ->
                    if (connected) {
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
}
