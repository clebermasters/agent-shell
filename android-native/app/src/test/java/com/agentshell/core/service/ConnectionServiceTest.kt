package com.agentshell.core.service

import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionServiceTest {
    private lateinit var app: Application
    private lateinit var controller: ServiceController<ConnectionService>
    private lateinit var service: ConnectionService
    private var now = 1_800_000_000_000L

    @Before fun setup() {
        ConnectionServiceRuntime.resetForTests()
        ConnectionServiceRuntime.nowEpochMillis = { now }
        ShadowPowerManager.clearWakeLocks()
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).clearStartedServices()
        controller = Robolectric.buildService(ConnectionService::class.java).create()
        service = controller.get()
    }

    @After fun cleanup() {
        ConnectionServiceRuntime.prepareStop()
        controller.destroy()
        ConnectionServiceRuntime.resetForTests()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun startLive(wakeRequired: Boolean = true, deadline: Long? = null, startId: Int = 1): Intent {
        ConnectionServiceRuntime.configure(true, wakeRequired, deadline)
        assertTrue(ConnectionService.start(app))
        val intent = shadowOf(app).nextStartedService!!
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, startId))
        shadowOf(Looper.getMainLooper()).idle()
        return intent
    }

    private fun latestWakeLock(): PowerManager.WakeLock = ShadowPowerManager.getLatestWakeLock()!!

    private fun advance(millis: Long) {
        now += millis
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    @Test fun unauthorizedServiceAndStickyNullRestartCannotAcquireAWakeLock() {
        assertFalse(ConnectionService.start(app))
        assertNull(shadowOf(app).nextStartedService)
        ConnectionServiceRuntime.configure(true, true, null)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertFalse(ConnectionServiceRuntime.state.value.running)
        assertNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun liveServiceUsesTheDeclaredConnectedDeviceTypeAndCorrectPermission() {
        startLive(wakeRequired = false)
        assertTrue(ConnectionServiceRuntime.state.value.running)
        assertFalse(ConnectionServiceRuntime.state.value.starting)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertNull(ShadowPowerManager.getLatestWakeLock())
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
            @Suppress("DEPRECATION")
            val declared = app.packageManager.getServiceInfo(ComponentName(app, ConnectionService::class.java), 0)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, declared.foregroundServiceType)
        }
        @Suppress("DEPRECATION")
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" in permissions)
        assertTrue("android.permission.CHANGE_WIFI_MULTICAST_STATE" in permissions)
        assertFalse("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in permissions)
    }

    @Test fun aCpuWakeLeaseExpiresAfterFiveMinutesIfTheMainRenewalCannotRun() {
        startLive()
        val wake = latestWakeLock()
        assertTrue(wake.isHeld)
        assertFalse(shadowOf(wake).isReferenceCounted)
        // Advance elapsed time without executing the four-minute service handler.
        ShadowSystemClock.advanceBy(Duration.ofMillis(ConnectionService.WAKE_TIMEOUT_MS + 1))
        now += ConnectionService.WAKE_TIMEOUT_MS + 1
        assertFalse("An abandoned connection wake lease must be finite", wake.isHeld)
    }

    @Test fun thereIsOnlyOneFourMinuteRenewalAndSaverReleasesImmediately() {
        val intent = startLive()
        val wake = latestWakeLock()
        assertEquals(1, shadowOf(wake).timesHeld)
        repeat(5) {
            assertTrue(ConnectionService.start(app))
            service.onStartCommand(intent, 0, it + 2)
            ConnectionServiceRuntime.configure(true, true, null)
        }
        assertEquals(1, shadowOf(wake).timesHeld)
        assertNull(shadowOf(app).nextStartedService)
        advance(ConnectionService.WAKE_RENEW_INTERVAL_MS)
        assertEquals(2, shadowOf(wake).timesHeld)
        ConnectionServiceRuntime.configure(false, false, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(wake.isHeld)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        advance(ConnectionService.WAKE_TIMEOUT_MS)
        assertEquals(2, shadowOf(wake).timesHeld)
        assertFalse(ConnectionServiceRuntime.state.value.blocked)
    }

    @Test fun foregroundOrOfflineWakeNotRequiredReleasesTheCpuWithoutLosingTheService() {
        startLive()
        val wake = latestWakeLock()
        ConnectionServiceRuntime.configure(true, false, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(wake.isHeld)
        assertTrue(ConnectionServiceRuntime.state.value.running)
        assertFalse(shadowOf(service).isForegroundStopped)
        advance(ConnectionService.WAKE_TIMEOUT_MS)
        assertEquals(1, shadowOf(wake).timesHeld)
    }

    @Test fun repeatingStartsAndReconnectsCannotExtendTheAbsoluteSelectedDeadline() {
        val deadline = now + 120_000
        val intent = startLive(deadline = deadline)
        val wake = latestWakeLock()
        advance(60_000)
        repeat(5) {
            ConnectionServiceRuntime.configure(true, true, deadline)
            assertTrue(ConnectionService.start(app))
            service.onStartCommand(intent, 0, it + 2)
        }
        assertEquals(deadline, ConnectionServiceRuntime.state.value.liveUntilEpochMillis)
        assertEquals(1, shadowOf(wake).timesHeld)
        advance(60_000)
        assertFalse(wake.isHeld)
        assertFalse(ConnectionServiceRuntime.state.value.authorized)
        assertFalse(ConnectionServiceRuntime.state.value.running)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(ConnectionServiceRuntime.state.value.blocked)
    }

    @Test fun changingAnActiveDeadlineRecapsTheWakeAndDoesNotNeedANewServiceStart() {
        startLive(deadline = now + 600_000)
        val wake = latestWakeLock()
        val earlier = now + 30_000
        ConnectionServiceRuntime.configure(true, true, earlier)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ConnectionServiceRuntime.state.value.running)
        assertEquals(2, shadowOf(wake).timesHeld)
        assertNull(shadowOf(app).nextStartedService)
        advance(30_000)
        assertFalse(wake.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aWallClockRollbackReschedulesTheRemainingAbsoluteExpiry() {
        val deadline = now + 60_000
        startLive(deadline = deadline)
        now -= 30_000 // The Handler's monotonic expiry will arrive too early.
        advance(60_000)
        assertTrue(ConnectionServiceRuntime.state.value.running)
        assertEquals(deadline, ConnectionServiceRuntime.state.value.liveUntilEpochMillis)
        advance(30_000)
        assertFalse(ConnectionServiceRuntime.state.value.running)
        assertFalse(ConnectionServiceRuntime.state.value.authorized)
        assertFalse(latestWakeLock().isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun removingAShortTimerRearmsOneFiniteRenewableLeaseWithoutAnotherStart() {
        startLive(deadline = now + 60_000)
        val wake = latestWakeLock()
        advance(30_000)
        ConnectionServiceRuntime.configure(true, true, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, shadowOf(wake).timesHeld)
        assertNull(ConnectionServiceRuntime.state.value.liveUntilEpochMillis)
        assertNull(shadowOf(app).nextStartedService)
        repeat(5) { ConnectionServiceRuntime.configure(true, true, null) }
        assertEquals(2, shadowOf(wake).timesHeld)
        // The original minute has elapsed, and exactly one renewal is now due.
        advance(ConnectionService.WAKE_RENEW_INTERVAL_MS)
        assertTrue(wake.isHeld)
        assertTrue(ConnectionServiceRuntime.state.value.running)
        assertEquals(3, shadowOf(wake).timesHeld)
        // Even the renewed lease remains bounded if the main handler stalls.
        ShadowSystemClock.advanceBy(Duration.ofMillis(ConnectionService.WAKE_TIMEOUT_MS + 1))
        now += ConnectionService.WAKE_TIMEOUT_MS + 1
        assertFalse(wake.isHeld)
    }

    @Test fun extendingAShortTimerKeepsWakeUntilTheNewDeadlineWithoutSlidingIt() {
        startLive(deadline = now + 60_000)
        val wake = latestWakeLock()
        advance(20_000)
        val extended = now + 120_000
        ConnectionServiceRuntime.configure(true, true, extended)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, shadowOf(wake).timesHeld)
        repeat(5) { ConnectionServiceRuntime.configure(true, true, extended) }
        assertEquals(2, shadowOf(wake).timesHeld)
        assertNull(shadowOf(app).nextStartedService)
        advance(41_000)
        assertTrue("The replaced one-minute lease must not drop Live wake", wake.isHeld)
        assertTrue(ConnectionServiceRuntime.state.value.running)
        assertEquals(extended, ConnectionServiceRuntime.state.value.liveUntilEpochMillis)
        advance(79_000)
        assertFalse(wake.isHeld)
        assertFalse(ConnectionServiceRuntime.state.value.authorized)
        assertFalse(ConnectionServiceRuntime.state.value.running)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun anExpiredOrStaleStartCannotAcquireAndCannotStopANewerValidSession() {
        ConnectionServiceRuntime.configure(true, true, now + 1_000)
        assertTrue(ConnectionService.start(app))
        val stale = shadowOf(app).nextStartedService!!
        ConnectionServiceRuntime.configure(false, false, null)
        ConnectionServiceRuntime.configure(true, true, now + 600_000)
        assertTrue(ConnectionService.start(app))
        val current = shadowOf(app).nextStartedService!!
        service.onStartCommand(current, 0, 2)
        val wake = latestWakeLock()
        service.onStartCommand(stale, 0, 1)
        assertTrue(wake.isHeld)
        assertTrue(ConnectionServiceRuntime.state.value.running)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals(1, shadowOf(wake).timesHeld)
    }

    @Test fun aStartWhoseTimerElapsedBeforeDeliveryNeverPromotesOrAcquires() {
        ConnectionServiceRuntime.configure(true, true, now + 1_000)
        assertTrue(ConnectionService.start(app))
        val intent = shadowOf(app).nextStartedService!!
        now += 1_000
        service.onStartCommand(intent, 0, 1)
        assertFalse(ConnectionServiceRuntime.state.value.running)
        assertFalse(ConnectionServiceRuntime.state.value.starting)
        assertNull(ShadowPowerManager.getLatestWakeLock())
        assertNull(shadowOf(service).lastForegroundNotification)
    }

    @Test @Config(sdk = [35]) fun anOldStartIdTimeoutStopsImmediatelyAndRepeatedCleanupKeepsItsLatch() {
        val intent = startLive()
        val wake = latestWakeLock()
        service.onStartCommand(intent, 0, 2)
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertFalse(wake.isHeld)
        assertFalse(ConnectionServiceRuntime.state.value.running)
        assertEquals(ConnectionServiceBlockReason.TIMEOUT, ConnectionServiceRuntime.state.value.blockReason)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNotEquals("Timeout cleanup must not condition stopping on its stale startId", 1, shadowOf(service).stopSelfId)
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        ConnectionService.stop(app)
        ConnectionServiceRuntime.configure(true, true, null)
        assertFalse(ConnectionService.start(app))
        assertEquals(ConnectionServiceBlockReason.TIMEOUT, ConnectionServiceRuntime.state.value.blockReason)
        assertFalse(wake.isHeld)
    }

    @Test fun rejectedForegroundStartsAreLatchedAndCannotSpinOnReconnect() {
        ConnectionServiceRuntime.configure(true, true, null)
        val denied = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("synthetic platform start rejection")
            }
        }
        assertFalse(ConnectionService.start(denied))
        assertEquals(ConnectionServiceBlockReason.START_REJECTED, ConnectionServiceRuntime.state.value.blockReason)
        repeat(5) { ConnectionServiceRuntime.configure(true, true, null); assertFalse(ConnectionService.start(app)) }
        assertNull(shadowOf(app).nextStartedService)
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test fun unexpectedDestructionBlocksARepeatedBackgroundStart() {
        startLive()
        val wake = latestWakeLock()
        service.onDestroy()
        assertFalse(wake.isHeld)
        assertFalse(ConnectionServiceRuntime.state.value.running)
        assertEquals(ConnectionServiceBlockReason.SERVICE_STOPPED, ConnectionServiceRuntime.state.value.blockReason)
        assertFalse(ConnectionService.start(app))
    }
}
