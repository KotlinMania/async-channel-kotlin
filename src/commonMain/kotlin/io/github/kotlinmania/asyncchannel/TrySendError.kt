// port-lint: source lib.rs
package io.github.kotlinmania.asyncchannel

/** An error returned from [Sender.trySend]. */
public sealed class TrySendError<T> {
    /** The channel is full but not closed. */
    public class Full<T>(
        public val value: T,
    ) : TrySendError<T>() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Full<*>) return false
            return value == other.value
        }

        override fun hashCode(): Int = value?.hashCode() ?: 0

        override fun toString(): String = "sending into a full channel"
    }

    /** The channel is closed. */
    public class Closed<T>(
        public val value: T,
    ) : TrySendError<T>() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Closed<*>) return false
            return value == other.value
        }

        override fun hashCode(): Int = value?.hashCode() ?: 0

        override fun toString(): String = "sending into a closed channel"
    }

    /** Unwraps the message that couldn't be sent. */
    public fun intoInner(): T =
        when (this) {
            is Full -> value
            is Closed -> value
        }

    /** Returns `true` if the channel is full but not closed. */
    public fun isFull(): Boolean =
        when (this) {
            is Full -> true
            is Closed -> false
        }

    /** Returns `true` if the channel is closed. */
    public fun isClosed(): Boolean =
        when (this) {
            is Full -> false
            is Closed -> true
        }
}
