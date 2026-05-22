// port-lint: source src/lib.rs
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.channels.ClosedSendChannelException

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
            result.isClosed -> TrySendError.Closed(msg)
            else -> TrySendError.Full(msg)
        }
    }

    /**
     * Sends a message into the channel.
     *
     * If the channel is full, this method suspends until there is space for a message.
     * If the channel is closed, this method returns [SendOutcome.Err] carrying [SendError].
     */
    public suspend fun send(msg: T): SendOutcome<T> {
        return try {
            state.queue.send(msg)
            state.incrementSize()
            SendOutcome.Ok
        } catch (_: ClosedSendChannelException) {
            SendOutcome.Err(SendError(msg))
        }
    }

    /**
     * Forcefully pushes a message into the channel.
     *
     * If the channel is full, this method replaces an existing message in the
     * channel and returns it as [ForceSendOutcome.Ok.replaced]. If the channel
     * is closed, this method returns an error.
     */
    public fun forceSend(msg: T): ForceSendOutcome<T> {
        while (true) {
            val result = state.queue.trySend(msg)
            when {
                result.isSuccess -> {
                    state.incrementSize()
                    return ForceSendOutcome.Ok(replaced = null)
                }
                result.isClosed -> return ForceSendOutcome.Err(SendError(msg))
                else -> {
                    val removed = state.queue.tryReceive()
                    if (removed.isSuccess) {
                        state.decrementSize()
                        val sendResult = state.queue.trySend(msg)
                        return when {
                            sendResult.isSuccess -> {
                                state.incrementSize()
                                ForceSendOutcome.Ok(replaced = removed.getOrThrow())
                            }
                            sendResult.isClosed -> ForceSendOutcome.Err(SendError(msg))
                            else -> continue
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

    /** Downgrade the sender to a weak reference. */
    public fun downgrade(): WeakSender<T> = WeakSender(state)

    /** Returns whether the senders belong to the same channel. */
    public fun sameChannel(other: Sender<T>): Boolean = state === other.state

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
 * Result of [Sender.send].
 *
 * Mirrors the upstream `Result<(), SendError<T>>` since [SendError] is generic
 * and Kotlin does not allow generic subclasses of [Throwable].
 */
public sealed interface SendOutcome<out T> {
    /** Successfully sent. */
    public data object Ok : SendOutcome<Nothing>

    /** Failed to send because the channel was closed. */
    public data class Err<T>(public val error: SendError<T>) : SendOutcome<T>
}

/**
 * Result of [Sender.forceSend].
 *
 * Mirrors the upstream `Result<Option<T>, SendError<T>>`.
 */
public sealed interface ForceSendOutcome<out T> {
    /** Successfully sent, optionally carrying the replaced message. */
    public data class Ok<T>(public val replaced: T?) : ForceSendOutcome<T>

    /** Failed to send because the channel was closed. */
    public data class Err<T>(public val error: SendError<T>) : ForceSendOutcome<T>
}
