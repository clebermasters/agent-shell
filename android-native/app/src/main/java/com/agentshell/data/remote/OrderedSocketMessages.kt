package com.agentshell.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Bounded backpressure on OkHttp's reader thread, without a coroutine per packet. */
internal class OrderedSocketMessages<T>(
    scope: CoroutineScope,
    capacity: Int,
    consume: suspend (T) -> Unit,
) {
    private val queue = Channel<T>(capacity)
    private val consumer = scope.launch { for (message in queue) consume(message) }

    init { consumer.invokeOnCompletion { queue.cancel() } }

    // OkHttp invokes a socket's callbacks sequentially on its reader thread.
    // Blocking only this callback preserves wire order and bounds retained
    // messages when a consumer is slow; acknowledgements are never discarded.
    fun accept(message: T): Boolean = try {
        runBlocking { queue.send(message) }
        true
    } catch (_: CancellationException) {
        false
    } catch (_: ClosedSendChannelException) {
        false
    }

    /** A peer close still permits delivery of packets already read. */
    fun close() { queue.close() }

    /** Replacing/discarding the socket releases any blocked reader immediately. */
    fun cancel() { queue.cancel(); consumer.cancel() }
}
