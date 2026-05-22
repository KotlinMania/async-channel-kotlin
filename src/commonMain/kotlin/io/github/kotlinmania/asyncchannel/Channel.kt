// port-lint: source src/lib.rs
package io.github.kotlinmania.asyncchannel

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel as KxChannel

/**
 * Internal shared state for a channel.
 *
 * Mirrors the upstream `Channel<T>` private struct that backs both [Sender] and
 * [Receiver]. Wraps a [kotlinx.coroutines.channels.Channel] as the underlying
 * multi-producer/multi-consumer queue and tracks live sender/receiver counts
 * plus the approximate number of buffered messages.
 */
internal class ChannelState<T>(
    val queue: KxChannel<T>,
    val cap: Int?,
) {
    private val sizeCounter: AtomicInt = AtomicInt(0)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val closedSignal: CompletableDeferred<Unit> = CompletableDeferred()

    /** Number of currently active senders. */
    val senderCount: AtomicInt = AtomicInt(1)

    /** Number of currently active receivers. */
    val receiverCount: AtomicInt = AtomicInt(1)

    /** Approximate number of buffered messages. */
    val size: Int get() = sizeCounter.load()

    fun incrementSize() {
        sizeCounter.fetchAndAdd(1)
    }

    fun decrementSize() {
        sizeCounter.fetchAndAdd(-1)
    }

    /**
     * Closes the channel and notifies all blocked operations.
     *
     * Returns `true` if this call has closed the channel and it was not closed already.
     */
    fun close(): Boolean {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return false
        queue.close()
        closedSignal.complete(Unit)
        return true
    }

    /** Returns `true` if the channel has been closed for sending. */
    val isClosed: Boolean get() = closed.load()

    /** Suspends until the channel is closed. Returns immediately if already closed. */
    suspend fun awaitClosed() {
        closedSignal.await()
    }
}
