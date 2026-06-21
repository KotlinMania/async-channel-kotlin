// port-lint: source lib.rs
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * The receiving side of a channel.
 *
 * Receivers can be cloned (via [clone]) and shared across coroutines. When all
 * receivers are gone or the channel is closed via [close], no more messages can
 * be sent. Buffered messages are drained on receive even after close.
 */
public class Receiver<T> internal constructor(
    internal val state: ChannelState<T>,
) {
    /**
     * Attempts to receive a message from the channel.
     *
     * If the channel is empty (and possibly closed), returns the matching
     * [TryRecvError] inside [TryRecvOutcome.Err]. On success, returns the
     * value inside [TryRecvOutcome.Ok].
     */
    public fun tryRecv(): TryRecvOutcome<T> {
        val result = state.queue.tryReceive()
        return when {
            result.isSuccess -> {
                state.decrementSize()
                TryRecvOutcome.Ok(result.getOrThrow())
            }
            result.isClosed -> TryRecvOutcome.Err(TryRecvError.Closed)
            else -> TryRecvOutcome.Err(TryRecvError.Empty)
        }
    }

    /**
     * Receives a message from the channel.
     *
     * If the channel is empty, suspends until there is a message.
     * If the channel is closed and drained, returns [RecvOutcome.Err] carrying [RecvError].
     */
    public suspend fun recv(): RecvOutcome<T> =
        try {
            val value = state.queue.receive()
            state.decrementSize()
            RecvOutcome.Ok(value)
        } catch (_: ClosedReceiveChannelException) {
            RecvOutcome.Err
        }

    /**
     * Returns the next message from the channel, or `null` if the channel is closed
     * and drained.
     */
    public suspend fun next(): T? =
        when (val outcome = recv()) {
            is RecvOutcome.Ok -> outcome.value
            is RecvOutcome.Err -> null
        }

    /**
     * Returns a cold [Flow] that emits every message from the channel and completes
     * when the channel is closed and drained.
     *
     * Each collect drains the shared channel through this receiver handle. Multiple
     * collectors over the same receiver share the channel's MPMC semantics.
     */
    public fun asFlow(): Flow<T> =
        flow {
            while (true) {
                val item = next() ?: return@flow
                emit(item)
            }
        }

    /**
     * Closes the channel.
     *
     * Returns `true` if this call has closed the channel and it was not closed already.
     * The remaining messages can still be received.
     */
    public fun close(): Boolean = state.close()

    /** Returns `true` if the channel is closed. */
    public fun isClosed(): Boolean = state.isClosed

    /** Returns `true` if the channel is empty. */
    public fun isEmpty(): Boolean = state.size == 0

    /**
     * Returns `true` if the channel is full.
     *
     * Unbounded channels are never full.
     */
    public fun isFull(): Boolean = state.cap != null && state.size >= state.cap

    /** Returns the number of messages in the channel. */
    public fun len(): Int = state.size

    /** Returns the channel capacity if it is bounded, or `null` for unbounded. */
    public fun capacity(): Int? = state.cap

    /** Returns the number of receivers for the channel. */
    public fun receiverCount(): Int = state.receiverCount.load()

    /** Returns the number of senders for the channel. */
    public fun senderCount(): Int = state.senderCount.load()

    /**
     * Creates another [Receiver] for the same channel.
     *
     * Increments the live receiver count.
     */
    public fun clone(): Receiver<T> {
        state.receiverCount.fetchAndAdd(1)
        return Receiver(state)
    }

    /**
     * Releases this [Receiver]'s reference to the channel.
     *
     * Decrements the live receiver count and closes the channel if no receivers
     * remain. Calling [release] more than once on the same handle is a no-op
     * after the first call.
     */
    public fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) {
            if (state.receiverCount.fetchAndAdd(-1) == 1) {
                state.close()
            }
        }
    }

    /** Downgrade the receiver to a weak reference. */
    public fun downgrade(): WeakReceiver<T> = WeakReceiver(state)

    /** Returns whether the receivers belong to the same channel. */
    public fun sameChannel(other: Receiver<T>): Boolean = state === other.state

    private val released: AtomicBoolean = AtomicBoolean(false)

    override fun toString(): String = "Receiver { .. }"
}

/**
 * A [Receiver] that does not prevent the channel from being closed.
 *
 * Created through [Receiver.downgrade]. Use [upgrade] to get a strong [Receiver] back.
 */
public class WeakReceiver<T> internal constructor(
    private val state: ChannelState<T>,
) {
    /**
     * Upgrade the [WeakReceiver] into a [Receiver].
     *
     * Returns `null` if the channel is already closed.
     */
    public fun upgrade(): Receiver<T>? {
        if (state.isClosed) return null
        state.receiverCount.fetchAndAdd(1)
        return Receiver(state)
    }

    override fun toString(): String = "WeakReceiver { .. }"
}

/**
 * Result of [Receiver.recv].
 */
public sealed interface RecvOutcome<out T> {
    /** Successfully received a message. */
    public data class Ok<T>(
        public val value: T,
    ) : RecvOutcome<T>

    /** Failed because the channel was empty and closed. */
    public data object Err : RecvOutcome<Nothing>
}

/**
 * Result of [Receiver.tryRecv].
 */
public sealed interface TryRecvOutcome<out T> {
    /** Successfully received a message. */
    public data class Ok<T>(
        public val value: T,
    ) : TryRecvOutcome<T>

    /** Failed to receive a message; carries the reason. */
    public data class Err(
        public val error: TryRecvError,
    ) : TryRecvOutcome<Nothing>
}
