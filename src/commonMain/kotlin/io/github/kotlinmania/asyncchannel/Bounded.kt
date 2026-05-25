// port-lint: source lib.rs
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.channels.Channel as KxChannel

/**
 * Creates a bounded channel.
 *
 * The created channel has space to hold at most [cap] messages at a time.
 *
 * Capacity must be a positive number; this function will throw
 * [IllegalArgumentException] if [cap] is zero.
 */
public fun <T> bounded(cap: Int): Pair<Sender<T>, Receiver<T>> {
    require(cap > 0) { "capacity cannot be zero" }
    val state = ChannelState<T>(
        queue = KxChannel(capacity = cap),
        cap = cap,
    )
    return Sender(state) to Receiver(state)
}

/**
 * Creates an unbounded channel.
 *
 * The created channel can hold an unlimited number of messages.
 */
public fun <T> unbounded(): Pair<Sender<T>, Receiver<T>> {
    val state = ChannelState<T>(
        queue = KxChannel(capacity = KxChannel.UNLIMITED),
        cap = null,
    )
    return Sender(state) to Receiver(state)
}
