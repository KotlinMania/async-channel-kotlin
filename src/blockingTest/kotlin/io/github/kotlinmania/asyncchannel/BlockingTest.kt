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

        assertSame(SendOutcome.Ok, s.sendBlocking(7))
        val first = r.tryRecv() as? TryRecvOutcome.Ok ?: error("expected Ok")
        assertEquals(7, first.value)

        assertSame(SendOutcome.Ok, s.sendBlocking(8))
        val second = runBlocking { r.recv() } as? RecvOutcome.Ok ?: error("expected Ok")
        assertEquals(8, second.value)

        assertSame(SendOutcome.Ok, runBlocking { s.send(9) })
        val third = r.recvBlocking() as? RecvOutcome.Ok ?: error("expected Ok")
        assertEquals(9, third.value)

        val empty = r.tryRecv() as? TryRecvOutcome.Err ?: error("expected Err")
        assertSame(TryRecvError.Empty, empty.error)
    }

    @Test
    fun sendBlockingRoundTrip() {
        val (s, r) = unbounded<Int>()

        assertSame(SendOutcome.Ok, s.sendBlocking(1))
        val outcome = r.recvBlocking()
        val ok = outcome as? RecvOutcome.Ok ?: error("expected Ok, got $outcome")
        assertEquals(1, ok.value)
    }

    @Test
    fun sendBlockingAfterClose() {
        val (s, r) = unbounded<Int>()

        s.close()

        val outcome = s.sendBlocking(7)
        val err = outcome as? SendOutcome.Err ?: error("expected Err, got $outcome")
        assertEquals(7, err.error.value)

        assertSame(RecvOutcome.Err, r.recvBlocking())
    }

    @Test
    fun recvBlockingAfterCloseDrainsBuffer() {
        val (s, r) = bounded<Int>(3)

        assertSame(SendOutcome.Ok, s.sendBlocking(1))
        assertSame(SendOutcome.Ok, s.sendBlocking(2))
        s.close()

        val first = r.recvBlocking() as? RecvOutcome.Ok ?: error("expected Ok")
        assertEquals(1, first.value)
        val second = r.recvBlocking() as? RecvOutcome.Ok ?: error("expected Ok")
        assertEquals(2, second.value)
        assertSame(RecvOutcome.Err, r.recvBlocking())
    }

    @Test
    fun trySendStillReportsFull() {
        val (s, _) = bounded<Int>(1)

        assertSame(SendOutcome.Ok, s.sendBlocking(1))
        val outcome = s.trySend(2)
        assertTrue(outcome is TrySendError.Full)
    }
}
