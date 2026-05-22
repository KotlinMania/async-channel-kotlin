// port-lint: source src/lib.rs
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.runBlocking

/**
 * Sends a message into the channel using the blocking strategy.
 *
 * If the channel is full, this function blocks the current thread until there
 * is room. If the channel is closed, it returns [SendOutcome.Err] carrying
 * [SendError].
 *
 * Mirrors upstream `Sender::send_blocking`, gated in Rust by
 * `cfg(all(feature = "std", not(target_family = "wasm")))`. The Kotlin port is
 * defined in the `blockingMain` intermediate source set that covers every
 * Kotlin target supporting thread-blocking `runBlocking`: jvm and every
 * native target. It is intentionally absent on js, wasmJs, and wasmWasi.
 *
 * This function should not be called from a coroutine — it will block the
 * dispatcher thread. Use [Sender.send] from asynchronous code.
 */
public fun <T> Sender<T>.sendBlocking(msg: T): SendOutcome<T> =
    runBlocking { send(msg) }

/**
 * Receives a message from the channel using the blocking strategy.
 *
 * If the channel is empty, this function blocks the current thread until a
 * message arrives. If the channel is closed and drained, it returns
 * [RecvOutcome.Err] carrying [RecvError].
 *
 * Mirrors upstream `Receiver::recv_blocking`, gated in Rust by
 * `cfg(all(feature = "std", not(target_family = "wasm")))`. The same
 * intermediate source set scoping applies as for [sendBlocking].
 *
 * This function should not be called from a coroutine — it will block the
 * dispatcher thread. Use [Receiver.recv] from asynchronous code.
 */
public fun <T> Receiver<T>.recvBlocking(): RecvOutcome<T> =
    runBlocking { recv() }
