package com.agentshell.core.power

import android.app.Application
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.agentshell.core.service.ConnectionService
import com.agentshell.core.service.ConnectionServiceRuntime
import com.agentshell.data.local.PreferencesDataStore
import com.agentshell.data.remote.WebSocketService
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPowerManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionLifecycleCoordinatorTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var app: Application
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val now = AtomicLong()
    private val rejectWrites = AtomicBoolean(false)
    private lateinit var preferences: PreferencesDataStore
    private val policy = BackgroundWorkPolicy()
    private val client = OkHttpClient()
    private lateinit var socket: WebSocketService
    private lateinit var coordinator: ConnectionLifecycleCoordinator
    private val server = MockWebServer()
    private val peers = CopyOnWriteArrayList<WebSocket>()
    private val services = mutableListOf<ServiceController<ConnectionService>>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        now.set(System.currentTimeMillis())
        ConnectionServiceRuntime.resetForTests()
        ConnectionServiceRuntime.nowEpochMillis = now::get
        ConnectionNetworkGate.setAllowed(false)
        ShadowPowerManager.clearWakeLocks()
        shadowOf(app).clearStartedServices()
        val delegate = PreferenceDataStoreFactory.create(scope = storeScope,
            produceFile = { File(directory.root, "mode.preferences_pb") })
        preferences = PreferencesDataStore(object : DataStore<Preferences> {
            override val data = delegate.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (rejectWrites.get()) throw IOException("synthetic write failure")
                return delegate.updateData(transform)
            }
        })
        repeat(8) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { peers.add(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"ping\"")) webSocket.send("{\"type\":\"pong\"}")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { peers.remove(webSocket) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { peers.remove(webSocket) }
            }))
        }
        server.start()
        socket = WebSocketService(client)
        socket.connect(server.url("/ws").toString().replaceFirst("http:", "ws:"))
        coordinator = ConnectionLifecycleCoordinator(app, preferences, socket, policy).apply { nowMillis = now::get }
    }

    private fun pump() { scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle() }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(8_000) {
        while (true) { pump(); if (condition()) break; delay(10) }
    }

    private suspend fun visibleLive(): ConnectionService {
        policy.activityStarted()
        coordinator.start(scope)
        await { coordinator.state.value.ready && ConnectionServiceRuntime.state.value.starting && socket.isConnected }
        val intent = shadowOf(app).nextStartedService!!
        val controller = Robolectric.buildService(ConnectionService::class.java).create().also { services.add(it) }
        controller.get().onStartCommand(intent, 0, 1)
        await { ConnectionServiceRuntime.state.value.running }
        return controller.get()
    }

    @After fun cleanup() = runBlocking {
        coordinator.stopForTests()
        socket.dispose()
        scope.cancel(); scheduler.runCurrent()
        ConnectionServiceRuntime.prepareStop()
        services.forEach { it.destroy() }
        ConnectionServiceRuntime.resetForTests()
        ConnectionNetworkGate.setAllowed(true)
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(5_000) { while (peers.isNotEmpty()) delay(10) }
        storeScope.coroutineContext[Job]?.cancelAndJoin()
        server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun liveDefaultAndOneTapSaverReleaseTheWakeButKeepTheVisibleChatUsable() = runBlocking {
        val service = visibleLive()
        assertEquals(ConnectionMode.LIVE, coordinator.state.value.mode)
        assertNull(ShadowPowerManager.getLatestWakeLock())
        policy.activityStopped()
        await { ShadowPowerManager.getLatestWakeLock()?.isHeld == true }
        assertTrue(socket.isConnected)
        coordinator.selectMode(ConnectionMode.BATTERY_SAVER)
        await { coordinator.state.value.mode == ConnectionMode.BATTERY_SAVER && !socket.keepAliveEnabled.value && !policy.automaticNetworkingAllowed.value }
        assertFalse(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
        policy.activityStarted(); coordinator.onForeground()
        await { socket.isConnected }
        assertEquals(ConnectionMode.BATTERY_SAVER, coordinator.state.value.mode)
        assertFalse(ConnectionServiceRuntime.state.value.authorized)
    }

    @Test fun aRealTimerExpiresInBackgroundAndCannotBeExtendedByHealthOrNetworkCallbacks() = runBlocking {
        preferences.setLiveTimerMinutes(15, now.get())
        visibleLive()
        val deadline = preferences.connectionMode.first().liveUntilEpochMillis!!
        policy.activityStopped()
        await { ShadowPowerManager.getLatestWakeLock()?.isHeld == true }
        repeat(10) { socket.checkConnection(); socket.onNetworkAvailable() }
        assertEquals(deadline, preferences.connectionMode.first().liveUntilEpochMillis)
        now.set(deadline)
        scheduler.advanceTimeBy(15 * 60_000L); pump()
        await { coordinator.state.value.mode == ConnectionMode.BATTERY_SAVER && !socket.keepAliveEnabled.value }
        await { preferences.connectionMode.first().mode == ConnectionMode.BATTERY_SAVER }
        assertFalse(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        assertTrue(socket.connectionDesired.value)
    }

    @Test fun timeoutCannotRestartFromBackgroundButFreshForegroundRecoversOnce() = runBlocking {
        val service = visibleLive()
        policy.activityStopped()
        await { ShadowPowerManager.getLatestWakeLock()?.isHeld == true }
        service.onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        await { !ConnectionNetworkGate.allowed.value && !socket.keepAliveEnabled.value }
        repeat(10) { socket.onNetworkAvailable(); socket.checkConnection(); socket.forceReconnect() }
        policy.updateDevice(metered = true)
        scheduler.advanceTimeBy(180_000); pump()
        assertTrue(ConnectionServiceRuntime.state.value.blocked)
        assertNull(shadowOf(app).nextStartedService)
        policy.activityStarted(); coordinator.onForeground()
        await { socket.isConnected && ConnectionServiceRuntime.state.value.starting }
        assertFalse(ConnectionServiceRuntime.state.value.blocked)
        assertEquals(2L, socket.connectionGeneration.value)
        assertNotNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun anExpiredPersistedTimerNeverStartsAServiceAndForegroundDoesNotUndoManualDisconnect() = runBlocking {
        preferences.setLiveTimerMinutes(15, now.get() - 900_001)
        coordinator.start(scope)
        await { coordinator.state.value.ready }
        assertEquals(ConnectionMode.BATTERY_SAVER, coordinator.state.value.mode)
        assertNull(shadowOf(app).nextStartedService)
        policy.activityStarted(); coordinator.onForeground()
        await { socket.isConnected }
        socket.disconnect()
        await { !policy.automaticNetworkingAllowed.value }
        policy.activityStopped(); pump(); policy.activityStarted(); coordinator.onForeground(); pump()
        assertFalse(socket.connectionDesired.value)
        assertFalse(socket.keepAliveEnabled.value)
        assertFalse(policy.automaticNetworkingAllowed.value)
    }

    @Test fun wallClockChangeReschedulesTheSameDeadlineRatherThanWaitingForItsOldDelay() = runBlocking {
        preferences.setLiveTimerMinutes(15, now.get())
        coordinator.start(scope)
        await { coordinator.state.value.ready }
        val deadline = preferences.connectionMode.first().liveUntilEpochMillis!!
        now.addAndGet(10 * 60_000L); coordinator.refreshClock(); pump()
        scheduler.advanceTimeBy(5 * 60_000L - 1); pump()
        assertEquals(ConnectionMode.LIVE, coordinator.state.value.mode)
        now.set(deadline); scheduler.advanceTimeBy(1); pump()
        await { coordinator.state.value.mode == ConnectionMode.BATTERY_SAVER }
    }

    @Test fun failedTimerWriteFailsSaverAndRetryPersistsTheWholeIntendedLiveTimer() = runBlocking {
        coordinator.start(scope)
        await { coordinator.state.value.ready }
        rejectWrites.set(true)
        coordinator.selectTimer(15)
        await { coordinator.state.value.mode == ConnectionMode.BATTERY_SAVER && coordinator.state.value.saveError != null }
        assertFalse(ConnectionServiceRuntime.state.value.authorized)
        rejectWrites.set(false)
        coordinator.selectMode(ConnectionMode.LIVE)
        await { preferences.connectionMode.first().timerMinutes == 15 && coordinator.state.value.saveError == null }
        assertEquals(preferences.connectionMode.first().liveUntilEpochMillis, coordinator.state.value.liveUntilEpochMillis)
        assertEquals(ConnectionMode.LIVE, coordinator.state.value.mode)
    }

    @Test fun choosingSaverAfterAServiceFailureCannotBrieflyRearmTheOldLiveMode() = runBlocking {
        val service = visibleLive()
        service.onTimeout(0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        await { coordinator.state.value.systemPaused }
        assertNull(shadowOf(app).nextStartedService)
        coordinator.selectMode(ConnectionMode.BATTERY_SAVER)
        await { coordinator.state.value.mode == ConnectionMode.BATTERY_SAVER && socket.isConnected }
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(ConnectionServiceRuntime.state.value.authorized)
    }
}
