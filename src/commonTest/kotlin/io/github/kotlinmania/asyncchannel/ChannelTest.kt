// port-lint: ignore
// Smoke tests for bounded/unbounded channels backed by kotlinx.coroutines.
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BoundedFactoryTest {
    @Test
    fun zeroCapacityThrows() {
        assertFails { bounded<Int>(0) }
    }

    @Test
    fun positiveCapacityReportsCapacity() {
        val (s, _) = bounded<Int>(4)
        assertEquals(4, s.capacity())
    }
}

class UnboundedFactoryTest {
    @Test
    fun unboundedHasNoCapacity() {
        val (s, _) = unbounded<Int>()
        assertNull(s.capacity())
    }
}

class TrySendTryRecvTest {
    @Test
    fun unboundedTrySendThenTryRecv() {
        val (s, r) = unbounded<Int>()
        assertNull(s.trySend(7))
        when (val outcome = r.tryRecv()) {
            is TryRecvOutcome.Ok -> assertEquals(7, outcome.value)
            is TryRecvOutcome.Err -> error("expected Ok, got ${outcome.error}")
        }
    }

    @Test
    fun trySendOnFullReturnsFull() {
        val (s, _) = bounded<Int>(1)
        assertNull(s.trySend(1))
        when (val err = s.trySend(2)) {
            is TrySendError.Full -> assertEquals(2, err.value)
            else -> error("expected Full, got $err")
        }
    }

    @Test
    fun trySendOnClosedReturnsClosed() {
        val (s, _) = unbounded<Int>()
        s.close()
        when (val err = s.trySend(99)) {
            is TrySendError.Closed -> assertEquals(99, err.value)
            else -> error("expected Closed, got $err")
        }
    }

    @Test
    fun tryRecvOnEmptyReturnsEmpty() {
        val (_, r) = unbounded<Int>()
        when (val outcome = r.tryRecv()) {
            is TryRecvOutcome.Err -> assertEquals(TryRecvError.Empty, outcome.error)
            is TryRecvOutcome.Ok -> error("expected Empty, got ${outcome.value}")
        }
    }

    @Test
    fun tryRecvOnClosedDrainedReturnsClosed() {
        val (s, r) = unbounded<Int>()
        s.close()
        when (val outcome = r.tryRecv()) {
            is TryRecvOutcome.Err -> assertEquals(TryRecvError.Closed, outcome.error)
            is TryRecvOutcome.Ok -> error("expected Closed, got ${outcome.value}")
        }
    }
}

class SendRecvSuspendTest {
    @Test
    fun sendThenRecv() = runTest {
        val (s, r) = unbounded<String>()
        assertSame(SendOutcome.Ok, s.send("Hello"))
        val received = r.recv()
        assertIs<RecvOutcome.Ok<String>>(received)
        assertEquals("Hello", received.value)
    }

    @Test
    fun sendOnClosedReturnsSendError() = runTest {
        val (s, _) = unbounded<Int>()
        s.close()
        val outcome = s.send(42)
        assertIs<SendOutcome.Err<Int>>(outcome)
        assertEquals(42, outcome.error.value)
    }

    @Test
    fun recvOnClosedDrainedReturnsRecvError() = runTest {
        val (s, r) = unbounded<Int>()
        s.send(1)
        s.close()
        val drained = r.recv()
        assertIs<RecvOutcome.Ok<Int>>(drained)
        assertEquals(1, drained.value)
        assertSame(RecvOutcome.Err, r.recv())
    }

    @Test
    fun sendReceiveAcrossMultipleClones() = runTest {
        val (s, r) = unbounded<Int>()
        val s2 = s.clone()
        s.send(1)
        s2.send(2)
        val first = (r.recv() as RecvOutcome.Ok<Int>).value
        val second = (r.recv() as RecvOutcome.Ok<Int>).value
        assertEquals(setOf(1, 2), setOf(first, second))
        assertEquals(2, s.senderCount())
    }
}

class ChannelMetricsTest {
    @Test
    fun lenTracksBufferedMessages() = runTest {
        val (s, r) = unbounded<Int>()
        assertEquals(0, s.len())
        assertTrue(s.isEmpty())
        s.send(1)
        s.send(2)
        assertEquals(2, s.len())
        assertEquals(2, r.len())
        assertFalse(r.isEmpty())
        r.recv()
        assertEquals(1, r.len())
    }

    @Test
    fun isFullForBounded() = runTest {
        val (s, _) = bounded<Int>(1)
        assertFalse(s.isFull())
        s.send(1)
        assertTrue(s.isFull())
    }

    @Test
    fun unboundedIsNeverFull() = runTest {
        val (s, _) = unbounded<Int>()
        s.send(1)
        s.send(2)
        s.send(3)
        assertFalse(s.isFull())
    }
}

class WeakReferencesTest {
    @Test
    fun upgradeWeakSenderOnOpenChannel() {
        val (s, _) = unbounded<Int>()
        val weak = s.downgrade()
        val upgraded = weak.upgrade()
        assertNotNull(upgraded)
        assertTrue(s.sameChannel(upgraded))
    }

    @Test
    fun upgradeWeakSenderOnClosedChannelReturnsNull() {
        val (s, _) = unbounded<Int>()
        val weak = s.downgrade()
        s.close()
        assertNull(weak.upgrade())
    }

    @Test
    fun upgradeWeakReceiverOnOpenChannel() {
        val (_, r) = unbounded<Int>()
        val weak = r.downgrade()
        val upgraded = weak.upgrade()
        assertNotNull(upgraded)
        assertTrue(r.sameChannel(upgraded))
    }

    @Test
    fun upgradeWeakReceiverOnClosedChannelReturnsNull() {
        val (s, r) = unbounded<Int>()
        val weak = r.downgrade()
        s.close()
        assertNull(weak.upgrade())
    }
}
