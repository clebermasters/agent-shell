package com.agentshell.data.local

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.agentshell.core.power.ConnectionMode
import com.agentshell.core.power.ConnectionModeState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ConnectionModePreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val modeKey = stringPreferencesKey("connection_mode")
    private data class Opened(val raw: DataStore<Preferences>, val prefs: PreferencesDataStore, val scope: CoroutineScope)

    private fun open(): Opened {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val raw = PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(temporary.root, "mode.preferences_pb") })
        return Opened(raw, PreferencesDataStore(raw), scope)
    }

    private suspend fun close(opened: Opened) { opened.scope.coroutineContext[Job]?.cancelAndJoin() }

    @After fun cleanup() = runBlocking {
        scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
    }

    @Test fun liveIsTheDefaultAndRepeatedLiveDoesNotExtendTheAbsoluteDeadline() = runBlocking {
        val opened = open()
        val prefs = opened.prefs
        assertEquals(ConnectionModeState(), prefs.connectionMode.first())
        assertNull(opened.raw.data.first()[modeKey])
        prefs.setLiveTimerMinutes(15, nowMillis = 1_000)
        val deadline = prefs.connectionMode.first().liveUntilEpochMillis
        repeat(100) { prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = 2_000L + it) }
        assertEquals(901_000L, deadline)
        assertEquals(deadline, prefs.connectionMode.first().liveUntilEpochMillis)
    }

    @Test fun processReopenPreservesTheDeadlineInsteadOfStartingTheTimerAgain() = runBlocking {
        val first = open()
        first.prefs.setLiveTimerMinutes(30, nowMillis = 10_000)
        val before = first.prefs.connectionMode.first()
        close(first)
        val reopened = open()
        assertEquals(before, reopened.prefs.connectionMode.first())
        reopened.prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = 40_000)
        assertEquals(1_810_000L, reopened.prefs.connectionMode.first().liveUntilEpochMillis)
    }

    @Test fun oneMinutePresetUsesItsActualDeadlineAndRepeatedLiveDoesNotExtendIt() = runBlocking {
        val prefs = open().prefs
        prefs.setLiveTimerMinutes(1, nowMillis = 10_000)
        assertEquals(1, prefs.connectionMode.first().timerMinutes)
        assertEquals(70_000L, prefs.connectionMode.first().liveUntilEpochMillis)
        prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = 40_000)
        assertEquals(70_000L, prefs.connectionMode.first().liveUntilEpochMillis)
        assertEquals(ConnectionMode.LIVE, prefs.connectionMode.first().effectiveMode(69_999))
        assertEquals(ConnectionMode.BATTERY_SAVER, prefs.connectionMode.first().effectiveMode(70_000))
        assertTrue(prefs.expireLiveTimer(70_000, nowMillis = 70_000))
        assertEquals(ConnectionModeState(ConnectionMode.BATTERY_SAVER, timerMinutes = 1), prefs.connectionMode.first())
    }

    @Test fun explicitTimerSelectionReplacesTheDeadlineAndUntimedSelectionClearsIt() = runBlocking {
        val prefs = open().prefs
        prefs.setLiveTimerMinutes(15, nowMillis = 1_000)
        prefs.setLiveTimerMinutes(15, nowMillis = 2_000)
        assertEquals(902_000L, prefs.connectionMode.first().liveUntilEpochMillis)
        prefs.setLiveTimerMinutes(60, nowMillis = 3_000)
        assertEquals(3_603_000L, prefs.connectionMode.first().liveUntilEpochMillis)
        prefs.setLiveTimerMinutes(0, nowMillis = 4_000)
        assertEquals(ConnectionMode.LIVE, prefs.connectionMode.first().mode)
        assertNull(prefs.connectionMode.first().liveUntilEpochMillis)
        assertFalse(prefs.expireLiveTimer(902_000, nowMillis = 5_000_000))
    }

    @Test fun saverRemembersThePresetWithoutStartingItsTimerUntilLiveIsSelected() = runBlocking {
        val prefs = open().prefs
        prefs.setConnectionMode(ConnectionMode.BATTERY_SAVER, nowMillis = 1_000)
        prefs.setLiveTimerMinutes(120, nowMillis = 2_000)
        assertEquals(ConnectionModeState(ConnectionMode.BATTERY_SAVER, timerMinutes = 120), prefs.connectionMode.first())
        prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = 3_000)
        assertEquals(7_203_000L, prefs.connectionMode.first().liveUntilEpochMillis)
        prefs.setConnectionMode(ConnectionMode.BATTERY_SAVER, nowMillis = 4_000)
        assertNull(prefs.connectionMode.first().liveUntilEpochMillis)
        assertEquals(120, prefs.connectionMode.first().timerMinutes)
        prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = 5_000)
        assertEquals(7_205_000L, prefs.connectionMode.first().liveUntilEpochMillis)
    }

    @Test fun staleExpiryCannotOverwriteANewerTimerOrAUserModeChoice() = runBlocking {
        val prefs = open().prefs
        prefs.setLiveTimerMinutes(15, nowMillis = 1_000)
        val oldDeadline = prefs.connectionMode.first().liveUntilEpochMillis!!
        val allowOldCallback = CompletableDeferred<Unit>()
        val stale = async {
            allowOldCallback.await()
            prefs.expireLiveTimer(oldDeadline, nowMillis = oldDeadline + 1)
        }
        prefs.setLiveTimerMinutes(30, nowMillis = oldDeadline - 1)
        val newDeadline = prefs.connectionMode.first().liveUntilEpochMillis
        allowOldCallback.complete(Unit)
        assertFalse(stale.await())
        assertEquals(newDeadline, prefs.connectionMode.first().liveUntilEpochMillis)
        prefs.setConnectionMode(ConnectionMode.BATTERY_SAVER, nowMillis = oldDeadline + 2)
        assertFalse(prefs.expireLiveTimer(oldDeadline, nowMillis = oldDeadline + 3))
        assertEquals(ConnectionMode.BATTERY_SAVER, prefs.connectionMode.first().mode)
        prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = oldDeadline + 4)
        assertFalse(prefs.expireLiveTimer(oldDeadline, nowMillis = oldDeadline + 5))
        assertEquals(ConnectionMode.LIVE, prefs.connectionMode.first().mode)
    }

    @Test fun expiryPersistsSaverAcrossClockChangesAndDoesNotChangeOtherPreferences() = runBlocking {
        val first = open()
        first.prefs.setThemeMode("dark")
        first.prefs.setBatteryDiagnosticsEnabled(true)
        first.prefs.setLiveTimerMinutes(15, nowMillis = 10_000)
        val due = first.prefs.connectionMode.first().liveUntilEpochMillis!!
        assertFalse(first.prefs.expireLiveTimer(due, nowMillis = due - 1))
        assertEquals(ConnectionMode.BATTERY_SAVER, first.prefs.connectionMode.first().effectiveMode(due + 1))
        assertTrue(first.prefs.expireLiveTimer(due, nowMillis = due + 365L * 24 * 60 * 60_000))
        close(first)
        val second = open()
        val saved = second.prefs.connectionMode.first()
        assertEquals(ConnectionMode.BATTERY_SAVER, saved.effectiveMode(0))
        assertEquals(15, saved.timerMinutes)
        assertNull(saved.liveUntilEpochMillis)
        assertEquals("dark", second.prefs.themeMode.first())
        assertTrue(second.prefs.batteryDiagnosticsEnabled.first())
    }

    @Test fun choosingAPresetAfterAnUnpersistedExpiryOnlyChangesTheRememberedPreset() = runBlocking {
        val prefs = open().prefs
        prefs.setLiveTimerMinutes(15, nowMillis = 1_000)
        val due = prefs.connectionMode.first().liveUntilEpochMillis!!
        prefs.setLiveTimerMinutes(60, nowMillis = due + 1)
        assertEquals(ConnectionModeState(ConnectionMode.BATTERY_SAVER, timerMinutes = 60), prefs.connectionMode.first())
        prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = due + 2)
        assertEquals(due + 2 + 3_600_000, prefs.connectionMode.first().liveUntilEpochMillis)
    }

    @Test fun damagedJsonFailsClosedAndModeWritesPreserveUnrelatedSecretsWithoutLoggingThem() = runBlocking {
        val opened = open()
        val secret = "local-secret-must-not-appear-in-logs"
        val unrelated = stringPreferencesKey("unrelated_secret")
        opened.raw.edit { it[modeKey] = "invalid-json $secret"; it[unrelated] = secret }
        assertEquals(ConnectionMode.BATTERY_SAVER, opened.prefs.connectionMode.first().mode)
        opened.prefs.setLiveTimerMinutes(30, nowMillis = 1_000)
        opened.prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = 2_000)
        assertEquals(secret, opened.raw.data.first()[unrelated])
        assertFalse(opened.raw.data.first()[modeKey]!!.contains(secret))
        assertFalse(ShadowLog.getLogs().any { it.msg?.contains(secret) == true || it.throwable?.message?.contains(secret) == true })
    }

    @Test fun unsupportedPresetIsRejectedWithoutChangingTheAtomicPreference() = runBlocking {
        val opened = open()
        opened.prefs.setLiveTimerMinutes(15, nowMillis = 1_000)
        val before = opened.raw.data.first()[modeKey]
        val failure = runCatching { opened.prefs.setLiveTimerMinutes(17, nowMillis = 2_000) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, opened.raw.data.first()[modeKey])
    }

    @Test fun anUnexpectedStoredPreferenceTypeAlsoFailsClosedAndCanBeRepaired() = runBlocking {
        val opened = open()
        opened.raw.edit { it[booleanPreferencesKey("connection_mode")] = true }
        assertEquals(ConnectionMode.BATTERY_SAVER, opened.prefs.connectionMode.first().mode)
        opened.prefs.setLiveTimerMinutes(15, nowMillis = 1_000)
        assertEquals(ConnectionMode.BATTERY_SAVER, opened.prefs.connectionMode.first().mode)
        opened.prefs.setConnectionMode(ConnectionMode.LIVE, nowMillis = 2_000)
        assertEquals(902_000L, opened.prefs.connectionMode.first().liveUntilEpochMillis)
    }

    @Test fun anIntendedSnapshotCanRecoverAFailedTimerWriteWithoutReDerivingUntimedLive() = runBlocking {
        val opened = open()
        var rejectFirstWrite = true
        val faulty = object : DataStore<Preferences> {
            override val data = opened.raw.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (rejectFirstWrite) {
                    rejectFirstWrite = false
                    throw IOException("simulated preference write failure")
                }
                return opened.raw.updateData(transform)
            }
        }
        val prefs = PreferencesDataStore(faulty)
        val selectedTimer = ConnectionModeState().selectTimer(15, nowMillis = 1_000)
        val failure = runCatching { prefs.storeConnectionModeState(selectedTimer) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("The failed write leaves old untimed Live on disk", ConnectionModeState(), prefs.connectionMode.first())
        val localFailClosed = selectedTimer.copy(mode = ConnectionMode.BATTERY_SAVER, liveUntilEpochMillis = null)
        val selectedLive = localFailClosed.selectMode(ConnectionMode.LIVE, nowMillis = 2_000)
        prefs.storeConnectionModeState(selectedLive)
        assertEquals(ConnectionModeState(ConnectionMode.LIVE, liveUntilEpochMillis = 902_000, timerMinutes = 15), prefs.connectionMode.first())
        close(opened)
        assertEquals(selectedLive, open().prefs.connectionMode.first())
    }

    @Test fun invalidFullSnapshotsAreRejectedBeforeChangingAnyStoredField() = runBlocking {
        val opened = open()
        opened.prefs.setLiveTimerMinutes(15, nowMillis = 1_000)
        val before = opened.raw.data.first()[modeKey]
        val invalid = listOf(
            ConnectionModeState(ConnectionMode.LIVE, liveUntilEpochMillis = 2_000, timerMinutes = 0),
            ConnectionModeState(ConnectionMode.LIVE, liveUntilEpochMillis = null, timerMinutes = 15),
            ConnectionModeState(ConnectionMode.BATTERY_SAVER, liveUntilEpochMillis = 2_000, timerMinutes = 15),
            ConnectionModeState(ConnectionMode.BATTERY_SAVER, timerMinutes = 17),
        )
        for (state in invalid) {
            val failure = runCatching { opened.prefs.storeConnectionModeState(state) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(before, opened.raw.data.first()[modeKey])
        }
    }
}
