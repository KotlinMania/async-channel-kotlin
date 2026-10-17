// Smoke tests for bounded/unbounded channels backed by kotlinx.coroutines.
package io.github.kotlinmania.asyncchannel

import io.github.kotlinmania.futures.Poll
import io.github.kotlinmania.futures.TaskContext
import io.github.kotlinmania.futures.Yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val outcome = r.tryRecv()
        assertTrue(outcome.isOk(), "expected Ok, got $outcome")
        assertEquals(7, outcome.getOrThrow())
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
        val outcome = r.tryRecv()
        assertTrue(outcome.isErr(), "expected Empty, got $outcome")
        assertEquals(TryRecvError.Empty, outcome.error)
    }

    @Test
    fun tryRecvOnClosedDrainedReturnsClosed() {
        val (s, r) = unbounded<Int>()
        s.close()
        val outcome = r.tryRecv()
        assertTrue(outcome.isErr(), "expected Closed, got $outcome")
        assertEquals(TryRecvError.Closed, outcome.error)
    }
}

class SendRecvSuspendTest {
    @Test
    fun sendThenRecv() =
        runTest {
            val (s, r) = unbounded<String>()
            assertTrue(s.send("Hello").isOk())
            val received = r.recv()
            assertTrue(received.isOk(), "expected Ok, got $received")
            assertEquals("Hello", received.getOrThrow())
        }

    @Test
    fun sendOnClosedReturnsSendError() =
        runTest {
            val (s, _) = unbounded<Int>()
            s.close()
            val outcome = s.send(42)
            val err = outcome.error ?: error("expected Err, got $outcome")
            assertEquals(42, err.value)
        }

    @Test
    fun recvOnClosedDrainedReturnsRecvError() =
        runTest {
            val (s, r) = unbounded<Int>()
            s.send(1)
            s.close()
            val drained = r.recv()
            assertTrue(drained.isOk(), "expected Ok, got $drained")
            assertEquals(1, drained.getOrThrow())
            assertTrue(r.recv().isErr())
        }

    @Test
    fun sendReceiveAcrossMultipleClones() =
        runTest {
            val (s, r) = unbounded<Int>()
            val s2 = s.clone()
            s.send(1)
            s2.send(2)
            val first = r.recv().getOrThrow()
            val second = r.recv().getOrThrow()
            assertEquals(setOf(1, 2), setOf(first, second))
            assertEquals(2, s.senderCount())
        }
}

class ChannelMetricsTest {
    @Test
    fun lenTracksBufferedMessages() =
        runTest {
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
    fun isFullForBounded() =
        runTest {
            val (s, _) = bounded<Int>(1)
            assertFalse(s.isFull())
            s.send(1)
            assertTrue(s.isFull())
        }

    @Test
    fun unboundedIsNeverFull() =
        runTest {
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

class ReceiverStreamTest {
    @Test
    fun streamPollNextReceivesMessages() {
        val (s, r) = unbounded<Int>()
        val cx = TaskContext()
        assertFalse(r.isTerminated())
        assertEquals(Poll.Pending, r.pollNext(cx))

        s.trySend(42)
        val poll1 = r.pollNext(cx)
        assertIs<Poll.Ready<Yield<Int>>>(poll1)
        val yield1 = poll1.value
        assertIs<Yield.Value<Int>>(yield1)
        assertEquals(42, yield1.value)

        s.close()
        val poll2 = r.pollNext(cx)
        assertIs<Poll.Ready<Yield<Int>>>(poll2)
        assertEquals(Yield.End, poll2.value)
        assertTrue(r.isTerminated())
    }

    @Test
    fun streamSizeHint() {
        val (s, r) = bounded<Int>(10)
        assertEquals(0, r.sizeHint().lower)
        assertEquals(10, r.sizeHint().upper)
        s.trySend(1)
        assertEquals(1, r.sizeHint().lower)
        assertEquals(10, r.sizeHint().upper)
    }
}
