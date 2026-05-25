// port-lint: source lib.rs
package io.github.kotlinmania.asyncchannel

/**
 * An error returned from [Receiver.recv].
 *
 * Received because the channel is empty and closed.
 */
public object RecvError {
    override fun toString(): String = "receiving from an empty and closed channel"
}
