// port-lint: source lib.rs
package io.github.kotlinmania.asyncchannel

/**
 * An error returned from [Sender.send].
 *
 * Received because the channel is closed.
 */
public class SendError<T>(public val value: T) {
    /** Unwraps the message that couldn't be sent. */
    public fun intoInner(): T = value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SendError<*>) return false
        return value == other.value
    }

    override fun hashCode(): Int = value?.hashCode() ?: 0

    override fun toString(): String = "sending into a closed channel"
}
