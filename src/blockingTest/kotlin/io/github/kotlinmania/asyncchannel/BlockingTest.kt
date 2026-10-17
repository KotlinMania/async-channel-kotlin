// port-lint: source tests/bounded.rs
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlockingTest {
    @Test
    fun smokeBlocking() {
        val (s, r) = bounded<Int>(1)

        assertTrue(s.sendBlocking(7).isOk())
        val first = r.tryRecv()
        assertEquals(7, first.getOrThrow())

        assertTrue(s.sendBlocking(8).isOk())
        val second = runBlocking { r.recv() }
        assertEquals(8, second.getOrThrow())

        assertTrue(runBlocking { s.send(9) }.isOk())
        val third = r.recvBlocking()
        assertEquals(9, third.getOrThrow())

        val empty = r.tryRecv()
        assertTrue(empty.isErr(), "expected Err, got $empty")
        assertSame(TryRecvError.Empty, empty.error)
    }

    @Test
    fun sendBlockingRoundTrip() {
        val (s, r) = unbounded<Int>()

        assertTrue(s.sendBlocking(1).isOk())
        val outcome = r.recvBlocking()
        assertTrue(outcome.isOk(), "expected Ok, got $outcome")
        assertEquals(1, outcome.getOrThrow())
    }

    @Test
    fun sendBlockingAfterClose() {
        val (s, r) = unbounded<Int>()

        s.close()

        val outcome = s.sendBlocking(7)
        val err = outcome.error ?: error("expected Err, got $outcome")
        assertEquals(7, err.value)

        assertTrue(r.recvBlocking().isErr())
    }

    @Test
    fun recvBlockingAfterCloseDrainsBuffer() {
        val (s, r) = bounded<Int>(3)

        assertTrue(s.sendBlocking(1).isOk())
        assertTrue(s.sendBlocking(2).isOk())
        s.close()

        val first = r.recvBlocking()
        assertEquals(1, first.getOrThrow())
        val second = r.recvBlocking()
        assertEquals(2, second.getOrThrow())
        assertTrue(r.recvBlocking().isErr())
    }

    @Test
    fun trySendStillReportsFull() {
        val (s, _) = bounded<Int>(1)

        assertTrue(s.sendBlocking(1).isOk())
        val outcome = s.trySend(2)
        assertTrue(outcome is TrySendError.Full)
    }
}
