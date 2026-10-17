@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package io.github.kotlinmania.asyncchannel

import io.github.kotlinmania.futures.FusedStream
import io.github.kotlinmania.futures.Poll
import io.github.kotlinmania.futures.SizeHint
import io.github.kotlinmania.futures.TaskContext
import io.github.kotlinmania.futures.Yield
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.native.HiddenFromObjC

/**
 * The receiving side of a channel.
 *
 * Receivers can be cloned (via [clone]) and shared across coroutines. When all
 * receivers are gone or the channel is closed via [close], no more messages can
 * be sent. Buffered messages are drained on receive even after close.
 */
@HiddenFromObjC
public class Receiver<T> internal constructor(
    internal val state: ChannelState<T>,
) : FusedStream<T> {
    /**
     * Attempts to receive a message from the channel.
     *
     * If the channel is empty (and possibly closed), returns the matching
     * [TryRecvError] in [TryRecvOutcome.error]. On success, returns the
     * value in [TryRecvOutcome.value].
     */
    public fun tryRecv(): TryRecvOutcome<T> {
        val result = state.queue.tryReceive()
        return when {
            result.isSuccess -> {
                state.decrementSize()
                TryRecvOutcome.ok(result.getOrThrow())
            }

            result.isClosed -> {
                TryRecvOutcome.err(TryRecvError.Closed)
            }

            else -> {
                TryRecvOutcome.err(TryRecvError.Empty)
            }
        }
    }

    /**
     * Receives a message from the channel.
     *
     * If the channel is empty, suspends until there is a message.
     * If the channel is closed and drained, returns a [RecvOutcome] whose [RecvOutcome.error] is [RecvError].
     */
    public suspend fun recv(): RecvOutcome<T> =
        try {
            val value = state.queue.receive()
            state.decrementSize()
            RecvOutcome.ok(value)
        } catch (_: ClosedReceiveChannelException) {
            RecvOutcome.err()
        }

    /**
     * Returns the next message from the channel, or `null` if the channel is closed
     * and drained.
     */
    public suspend fun next(): T? =
        recv().value

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

    override fun pollNext(context: TaskContext): Poll<Yield<T>> =
        tryRecv().let { outcome ->
            if (outcome.isOk()) {
                Poll.Ready(Yield.Value(outcome.getOrThrow()))
            } else {
                when (outcome.error) {
                    TryRecvError.Closed -> Poll.Ready(Yield.End)
                    else -> Poll.Pending
                }
            }
        }

    override fun isTerminated(): Boolean = state.isClosed && state.size == 0

    override fun sizeHint(): SizeHint = SizeHint(state.size, state.cap)

    private val released: AtomicBoolean = AtomicBoolean(false)

    override fun toString(): String = "Receiver { .. }"
}

/**
 * A [Receiver] that does not prevent the channel from being closed.
 *
 * Created through [Receiver.downgrade]. Use [upgrade] to get a strong [Receiver] back.
 */
@HiddenFromObjC
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
 * Holds a successfully received message, so that a `null` message is
 * distinguishable from no message at all.
 */
internal class Received<T>(
    val value: T,
)

/**
 * Result of [Receiver.recv]: either a received message, or [RecvError] because
 * the channel is empty and closed.
 */
public class RecvOutcome<T> internal constructor(
    private val received: Received<T>?,
) {
    /** The received message, or `null` on failure. */
    public val value: T? get() = received?.value

    /** [RecvError] on failure, or `null` when a message was received. */
    public val error: RecvError? get() = if (received == null) RecvError else null

    /** Whether a message was received. */
    public fun isOk(): Boolean = received != null

    /** Whether receiving failed because the channel is empty and closed. */
    public fun isErr(): Boolean = received == null

    /** Returns the received message, or throws [NoSuchElementException] on failure. */
    public fun getOrThrow(): T {
        val r = received ?: throw NoSuchElementException("receiving from an empty and closed channel")
        return r.value
    }

    override fun equals(other: Any?): Boolean =
        other is RecvOutcome<*> && isOk() == other.isOk() && value == other.value

    override fun hashCode(): Int = if (received == null) -1 else received.value?.hashCode() ?: 0

    override fun toString(): String = if (received == null) "Err($RecvError)" else "Ok(${received.value})"

    internal companion object {
        fun <T> ok(value: T): RecvOutcome<T> = RecvOutcome(Received(value))

        fun <T> err(): RecvOutcome<T> = RecvOutcome(null)
    }
}

/**
 * Result of [Receiver.tryRecv]: either a received message, or [error] giving
 * the reason no message was available.
 */
public class TryRecvOutcome<T> internal constructor(
    private val received: Received<T>?,
    /** Why no message was received, or `null` when one was. */
    public val error: TryRecvError?,
) {
    /** The received message, or `null` on failure. */
    public val value: T? get() = received?.value

    /** Whether a message was received. */
    public fun isOk(): Boolean = received != null

    /** Whether no message was available. */
    public fun isErr(): Boolean = received == null

    /** Returns the received message, or throws [NoSuchElementException] with [error] on failure. */
    public fun getOrThrow(): T {
        val r = received ?: throw NoSuchElementException("no message available: $error")
        return r.value
    }

    override fun equals(other: Any?): Boolean =
        other is TryRecvOutcome<*> && isOk() == other.isOk() && value == other.value && error == other.error

    override fun hashCode(): Int = 31 * (value?.hashCode() ?: 0) + (error?.hashCode() ?: 0)

    override fun toString(): String = if (received == null) "Err($error)" else "Ok(${received.value})"

    internal companion object {
        fun <T> ok(value: T): TryRecvOutcome<T> = TryRecvOutcome(Received(value), null)

        fun <T> err(error: TryRecvError): TryRecvOutcome<T> = TryRecvOutcome(null, error)
    }
}
