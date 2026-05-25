// port-lint: source lib.rs
package io.github.kotlinmania.asyncchannel

/** An error returned from [Receiver.tryRecv]. */
public enum class TryRecvError {
    /** The channel is empty but not closed. */
    Empty,

    /** The channel is empty and closed. */
    Closed;

    /** Returns `true` if the channel is empty but not closed. */
    public fun isEmpty(): Boolean = this == Empty

    /** Returns `true` if the channel is empty and closed. */
    public fun isClosed(): Boolean = this == Closed

    override fun toString(): String = when (this) {
        Empty -> "receiving from an empty channel"
        Closed -> "receiving from an empty and closed channel"
    }
}
