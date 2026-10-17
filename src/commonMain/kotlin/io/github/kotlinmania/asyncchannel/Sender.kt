// port-lint: source lib.rs
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.selects.select
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * The sending side of a channel.
 *
 * Senders can be cloned (via [clone]) and shared across coroutines. When the
 * channel is closed via [close] or by a [Receiver], no more messages can be
 * sent, but remaining buffered messages can still be received.
 */
public class Sender<T> internal constructor(
    internal val state: ChannelState<T>,
) {
    /**
     * Attempts to send a message into the channel.
     *
     * If the channel is full or closed, returns the matching [TrySendError]
     * variant. On success, returns `null`.
     */
    public fun trySend(msg: T): TrySendError<T>? {
        val result = state.queue.trySend(msg)
        return when {
            result.isSuccess -> {
                state.incrementSize()
                null
            }

            result.isClosed -> {
                TrySendError.Closed(msg)
            }

            else -> {
                TrySendError.Full(msg)
            }
        }
    }

    /**
     * Sends a message into the channel.
     *
     * If the channel is full, this method suspends until there is space for a message.
     * If the channel is closed, this method returns a [SendOutcome] whose [SendOutcome.error] carries [SendError].
     */
    public suspend fun send(msg: T): SendOutcome<T> {
        if (state.isClosed) return SendOutcome.err(SendError(msg))
        return try {
            // The underlying kotlinx Channel does not wake a suspended sender when
            // close() is called while buffered items remain (the sender keeps
            // waiting for a slot to open). Race the send against the channel's
            // closed signal so a close from the receiver side immediately fails
            // the suspended sender with [SendError].
            select<SendOutcome<T>> {
                state.queue.onSend(msg) {
                    state.incrementSize()
                    SendOutcome.ok()
                }
                state.closedSignal.onAwait {
                    SendOutcome.err(SendError(msg))
                }
            }
        } catch (_: ClosedSendChannelException) {
            SendOutcome.err(SendError(msg))
        }
    }

    /**
     * Forcefully pushes a message into the channel.
     *
     * If the channel is full, this method replaces an existing message in the
     * channel and returns it as [ForceSendOutcome.replaced]. If the channel
     * is closed, this method returns an error.
     */
    public fun forceSend(msg: T): ForceSendOutcome<T> {
        while (true) {
            val result = state.queue.trySend(msg)
            when {
                result.isSuccess -> {
                    state.incrementSize()
                    return ForceSendOutcome.ok(replaced = null)
                }

                result.isClosed -> {
                    return ForceSendOutcome.err(SendError(msg))
                }

                else -> {
                    val removed = state.queue.tryReceive()
                    if (removed.isSuccess) {
                        state.decrementSize()
                        val sendResult = state.queue.trySend(msg)
                        return when {
                            sendResult.isSuccess -> {
                                state.incrementSize()
                                ForceSendOutcome.ok(replaced = removed.getOrThrow())
                            }

                            sendResult.isClosed -> {
                                ForceSendOutcome.err(SendError(msg))
                            }

                            else -> {
                                continue
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Completes when the channel is closed.
     *
     * This allows the producers to get notified when interest in the produced values is
     * canceled and immediately stop doing work. Dropping the last receiver closes the
     * channel, so this also completes when all receivers have dropped.
     */
    public suspend fun closed() {
        state.awaitClosed()
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
     * Creates another [Sender] for the same channel.
     *
     * Increments the live sender count.
     */
    public fun clone(): Sender<T> {
        state.senderCount.fetchAndAdd(1)
        return Sender(state)
    }

    /**
     * Releases this [Sender]'s reference to the channel.
     *
     * Decrements the live sender count and closes the channel if no senders
     * remain. Calling [release] more than once on the same handle is a no-op
     * after the first call.
     */
    public fun release() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) {
            if (state.senderCount.fetchAndAdd(-1) == 1) {
                state.close()
            }
        }
    }

    /** Downgrade the sender to a weak reference. */
    public fun downgrade(): WeakSender<T> = WeakSender(state)

    /** Returns whether the senders belong to the same channel. */
    public fun sameChannel(other: Sender<T>): Boolean = state === other.state

    private val released: AtomicBoolean = AtomicBoolean(false)

    override fun toString(): String = "Sender { .. }"
}

/**
 * A [Sender] that does not prevent the channel from being closed.
 *
 * Created through [Sender.downgrade]. Use [upgrade] to get a strong [Sender] back.
 */
public class WeakSender<T> internal constructor(
    private val state: ChannelState<T>,
) {
    /**
     * Upgrade the [WeakSender] into a [Sender].
     *
     * Returns `null` if the channel is already closed.
     */
    public fun upgrade(): Sender<T>? {
        if (state.isClosed) return null
        state.senderCount.fetchAndAdd(1)
        return Sender(state)
    }

    override fun toString(): String = "WeakSender { .. }"
}

/**
 * Result of [Sender.send]: either the message was sent, or [error] carries the
 * message back because the channel is closed.
 */
public class SendOutcome<T> internal constructor(
    /** The failure, or `null` when the message was sent. */
    public val error: SendError<T>?,
) {
    /** Whether the message was sent. */
    public fun isOk(): Boolean = error == null

    /** Whether sending failed because the channel is closed. */
    public fun isErr(): Boolean = error != null

    override fun equals(other: Any?): Boolean = other is SendOutcome<*> && error == other.error

    override fun hashCode(): Int = error?.hashCode() ?: 0

    override fun toString(): String = if (error == null) "Ok" else "Err($error)"

    internal companion object {
        fun <T> ok(): SendOutcome<T> = SendOutcome(null)

        fun <T> err(error: SendError<T>): SendOutcome<T> = SendOutcome(error)
    }
}

/**
 * Result of [Sender.forceSend]: either the message was sent, optionally
 * displacing [replaced], or [error] carries the message back because the
 * channel is closed.
 */
public class ForceSendOutcome<T> internal constructor(
    /** The message that was displaced to make room, if any. */
    public val replaced: T?,
    /** The failure, or `null` when the message was sent. */
    public val error: SendError<T>?,
) {
    /** Whether the message was sent. */
    public fun isOk(): Boolean = error == null

    /** Whether sending failed because the channel is closed. */
    public fun isErr(): Boolean = error != null

    override fun equals(other: Any?): Boolean =
        other is ForceSendOutcome<*> && replaced == other.replaced && error == other.error

    override fun hashCode(): Int = 31 * (replaced?.hashCode() ?: 0) + (error?.hashCode() ?: 0)

    override fun toString(): String = if (error == null) "Ok(replaced=$replaced)" else "Err($error)"

    internal companion object {
        fun <T> ok(replaced: T?): ForceSendOutcome<T> = ForceSendOutcome(replaced, null)

        fun <T> err(error: SendError<T>): ForceSendOutcome<T> = ForceSendOutcome(null, error)
    }
}
