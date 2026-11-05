package com.agentshell.core.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionServiceBlockReason { TIMEOUT, START_REJECTED, SERVICE_STOPPED }

data class ConnectionServiceRuntimeState(
    val authorized: Boolean = false,
    val wakeRequired: Boolean = false,
    val liveUntilEpochMillis: Long? = null,
    val running: Boolean = false,
    val starting: Boolean = false,
    val blocked: Boolean = false,
    val blockReason: ConnectionServiceBlockReason? = null,
    val generation: Long = 0,
)

/** Process-local execution lease. Preferences and connection intent belong to the coordinator. */
object ConnectionServiceRuntime {
    private val mutableState = MutableStateFlow(ConnectionServiceRuntimeState())
    val state: StateFlow<ConnectionServiceRuntimeState> = mutableState.asStateFlow()
    private var pendingGeneration: Long? = null
    private var ownerGeneration: Long? = null
    private var expectedStop = false
    internal var nowEpochMillis: () -> Long = System::currentTimeMillis

    @Synchronized fun configure(authorized: Boolean, wakeRequired: Boolean, liveUntilEpochMillis: Long?) {
        val old = mutableState.value
        // Wake changes update an existing lease; they must not invalidate a
        // foreground start racing the Activity's background transition.
        val changedLease = old.authorized != authorized || old.liveUntilEpochMillis != liveUntilEpochMillis
        if (changedLease) pendingGeneration = null
        mutableState.value = old.copy(authorized = authorized, wakeRequired = wakeRequired,
            liveUntilEpochMillis = liveUntilEpochMillis,
            starting = if (changedLease) false else old.starting,
            generation = old.generation + if (changedLease) 1 else 0)
    }

    /** Only a visible, explicit recovery action may clear a platform failure latch. */
    @Synchronized fun clearBlockFromForeground() {
        val old = mutableState.value
        if (!old.blocked) return
        pendingGeneration = null
        mutableState.value = old.copy(blocked = false, blockReason = null, starting = false,
            generation = old.generation + 1)
    }

    @Synchronized fun block(reason: ConnectionServiceBlockReason) {
        val old = mutableState.value
        if (old.blocked) return
        pendingGeneration = null
        mutableState.value = old.copy(blocked = true, blockReason = reason, starting = false,
            generation = old.generation + 1)
    }

    /** Mark coordinator-requested teardown before calling Context.stopService(). */
    @Synchronized fun prepareStop() {
        expectedStop = true
        if (pendingGeneration == null) return
        pendingGeneration = null
        val old = mutableState.value
        mutableState.value = old.copy(starting = false, generation = old.generation + 1)
    }

    @Synchronized internal fun beginStart(): Long? {
        val old = mutableState.value
        if (!allowed(old) || old.running || old.starting) return null
        expectedStop = false
        pendingGeneration = old.generation
        mutableState.value = old.copy(starting = true)
        return old.generation
    }

    @Synchronized internal fun currentStart(generation: Long): Boolean =
        generation == mutableState.value.generation && pendingGeneration == generation && allowed(mutableState.value)

    @Synchronized internal fun markRunning(generation: Long): Boolean {
        if (!currentStart(generation) || expectedStop) return false
        ownerGeneration = generation
        pendingGeneration = null
        mutableState.value = mutableState.value.copy(running = true, starting = false)
        return true
    }

    @Synchronized internal fun failStart(generation: Long) {
        if (pendingGeneration != generation && ownerGeneration != generation) return
        block(ConnectionServiceBlockReason.START_REJECTED)
    }

    @Synchronized internal fun rejectStart(generation: Long) {
        if (pendingGeneration != generation) return
        pendingGeneration = null
        mutableState.value = mutableState.value.copy(starting = false)
    }

    @Synchronized internal fun stopped(owner: Long?, expected: Boolean) {
        if (owner == null || owner != ownerGeneration) return
        ownerGeneration = null
        val old = mutableState.value
        val unexpected = !expected && !expectedStop && !old.blocked
        if (unexpected) {
            pendingGeneration = null
            mutableState.value = old.copy(running = false, starting = false, blocked = true,
                blockReason = ConnectionServiceBlockReason.SERVICE_STOPPED, generation = old.generation + 1)
        } else mutableState.value = old.copy(running = false)
    }

    @Synchronized internal fun expire() {
        val old = mutableState.value
        val deadline = old.liveUntilEpochMillis ?: return
        if (deadline > nowEpochMillis()) return
        pendingGeneration = null
        mutableState.value = old.copy(authorized = false, wakeRequired = false, starting = false,
            generation = old.generation + if (old.authorized) 1 else 0)
    }

    internal fun allowed(value: ConnectionServiceRuntimeState = state.value): Boolean =
        value.authorized && !value.blocked && (value.liveUntilEpochMillis?.let { it > nowEpochMillis() } ?: true)

    @Synchronized fun resetForTests() {
        pendingGeneration = null
        ownerGeneration = null
        expectedStop = false
        nowEpochMillis = System::currentTimeMillis
        mutableState.value = ConnectionServiceRuntimeState()
    }
}
