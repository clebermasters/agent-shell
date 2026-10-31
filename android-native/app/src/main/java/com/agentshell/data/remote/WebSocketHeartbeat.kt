package com.agentshell.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One outstanding probe per socket. Repeated checks never postpone its deadline. */
internal class WebSocketHeartbeat(
    private val scope: CoroutineScope,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onTimeout: (Long) -> Unit,
) {
    private data class Probe(val connection: Long, val serial: Long, val deadline: Long, val job: Job)
    private val lock = Any()
    private var serial = 0L
    private var pending: Probe? = null

    fun ping(connection: Long, timeoutMillis: Long, send: () -> Boolean) {
        var failed = false
        synchronized(lock) {
            val deadline = monotonicMillis() + timeoutMillis
            if (pending?.let { it.connection == connection && it.deadline <= deadline } == true) return
            pending?.job?.cancel()
            val probeSerial = ++serial
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay((deadline - monotonicMillis()).coerceAtLeast(0))
                val expired = synchronized(lock) {
                    if (pending?.serial != probeSerial) false
                    else { pending = null; true }
                }
                if (expired) onTimeout(connection)
            }
            // Publish and start before sending: a synchronous/very fast pong
            // must cancel this exact deadline, rather than the previous one.
            pending = Probe(connection, probeSerial, deadline, job)
            job.start()
            if (!send()) {
                if (pending?.serial == probeSerial) pending = null
                job.cancel()
                failed = true
            }
        }
        if (failed) onTimeout(connection)
    }

    fun pong(connection: Long) = synchronized(lock) {
        if (pending?.connection == connection) {
            pending?.job?.cancel()
            pending = null
        }
    }

    fun cancel() = synchronized(lock) {
        pending?.job?.cancel()
        pending = null
    }
}
