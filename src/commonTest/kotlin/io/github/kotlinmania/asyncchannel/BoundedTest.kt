// port-lint: source tests/bounded.rs
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Two upstream tests at the forget-blocked-sender and forget-blocked-receiver
// positions in bounded tests pin a send or recv future, poll it exactly once,
// then keep the pinned future alive on the stack for 500 ms while other
// parallel tasks drive the channel. Under that model, an opening slot wakes
// the pinned future's waker but no executor polls it again, so the slot
// stays free for other senders or receivers. kotlinx.coroutines Channel does
// not separate "signal ready" from "transfer the value": when a waiter
// exists and a slot opens, the value is handed to the waiter's continuation,
// which then resumes. There is no equivalent of "polled once, kept alive in
// the wait list, never repolled". Faithfully porting those two upstream
// tests would require either a custom Kotlin channel implementation that
// exposes the upstream poll model or a different test that no longer
// exercises the upstream invariant. Both options break the translation rule
// that says don't invent a different test, so the two upstream tests are
// intentionally unported. The duration helper from the upstream bounded
// tests is unused on the Kotlin side because the only upstream callers are
// those two unported tests.

private fun assertSendOk(outcome: SendOutcome<*>) {
    assertSame(SendOutcome.Ok, outcome)
}

private fun <T> assertSendErr(outcome: SendOutcome<T>, expected: T) {
    val err = outcome as? SendOutcome.Err ?: error("expected Err, got $outcome")
    assertEquals(expected, err.error.value)
}

private fun <T> assertRecvOk(outcome: RecvOutcome<T>, expected: T) {
    val ok = outcome as? RecvOutcome.Ok ?: error("expected Ok, got $outcome")
    assertEquals(expected, ok.value)
}

private fun <T> assertTryRecvOk(outcome: TryRecvOutcome<T>, expected: T) {
    val ok = outcome as? TryRecvOutcome.Ok ?: error("expected Ok, got $outcome")
    assertEquals(expected, ok.value)
}

private fun <T> assertTryRecvErr(outcome: TryRecvOutcome<T>, expected: TryRecvError) {
    val err = outcome as? TryRecvOutcome.Err ?: error("expected Err, got $outcome")
    assertEquals(expected, err.error)
}

private fun <T> assertForceSendOk(outcome: ForceSendOutcome<T>, expected: T?) {
    val ok = outcome as? ForceSendOutcome.Ok ?: error("expected Ok, got $outcome")
    assertEquals(expected, ok.replaced)
}

class BoundedTest {
    @Test
    fun smoke() = runTest {
        val (s, r) = bounded<Int>(1)

        assertSendOk(s.send(7))
        assertTryRecvOk(r.tryRecv(), 7)

        assertSendOk(s.send(8))
        assertRecvOk(r.recv(), 8)

        assertTryRecvErr(r.tryRecv(), TryRecvError.Empty)
    }

    @Test
    fun capacity() {
        for (i in 1 until 10) {
            val (s, r) = bounded<Unit>(i)
            assertEquals(i, s.capacity())
            assertEquals(i, r.capacity())
        }
    }

    @Test
    fun lenEmptyFull() = runTest {
        val (s, r) = bounded<Unit>(2)

        assertEquals(0, s.len())
        assertEquals(true, s.isEmpty())
        assertEquals(false, s.isFull())
        assertEquals(0, r.len())
        assertEquals(true, r.isEmpty())
        assertEquals(false, r.isFull())

        assertSendOk(s.send(Unit))

        assertEquals(1, s.len())
        assertEquals(false, s.isEmpty())
        assertEquals(false, s.isFull())
        assertEquals(1, r.len())
        assertEquals(false, r.isEmpty())
        assertEquals(false, r.isFull())

        assertSendOk(s.send(Unit))

        assertEquals(2, s.len())
        assertEquals(false, s.isEmpty())
        assertEquals(true, s.isFull())
        assertEquals(2, r.len())
        assertEquals(false, r.isEmpty())
        assertEquals(true, r.isFull())

        assertRecvOk(r.recv(), Unit)

        assertEquals(1, s.len())
        assertEquals(false, s.isEmpty())
        assertEquals(false, s.isFull())
        assertEquals(1, r.len())
        assertEquals(false, r.isEmpty())
        assertEquals(false, r.isFull())
    }

    @Test
    fun tryRecv() = runTest {
        val (s, r) = bounded<Int>(100)

        launch {
            assertTryRecvErr(r.tryRecv(), TryRecvError.Empty)
            delay(1500)
            assertTryRecvOk(r.tryRecv(), 7)
            delay(500)
            assertTryRecvErr(r.tryRecv(), TryRecvError.Closed)
        }
        launch {
            delay(1000)
            assertSendOk(s.send(7))
            s.close()
        }
    }

    @Test
    fun recv() = runTest {
        val (s, r) = bounded<Int>(100)

        launch {
            assertRecvOk(r.recv(), 7)
            delay(1000)
            assertRecvOk(r.recv(), 8)
            delay(1000)
            assertRecvOk(r.recv(), 9)
            assertSame(RecvOutcome.Err, r.recv())
        }
        launch {
            delay(1500)
            assertSendOk(s.send(7))
            assertSendOk(s.send(8))
            assertSendOk(s.send(9))
            s.close()
        }
    }

    @Test
    fun trySend() = runTest {
        val (s, r) = bounded<Int>(1)

        launch {
            assertNull(s.trySend(1))
            val full = s.trySend(2)
            assertTrue(full is TrySendError.Full)
            assertEquals(2, full.value)
            delay(1500)
            assertNull(s.trySend(3))
            delay(500)
            val closed = s.trySend(4)
            assertTrue(closed is TrySendError.Closed)
            assertEquals(4, closed.value)
        }
        launch {
            delay(1000)
            assertTryRecvOk(r.tryRecv(), 1)
            assertTryRecvErr(r.tryRecv(), TryRecvError.Empty)
            assertRecvOk(r.recv(), 3)
            r.close()
        }
    }

    @Test
    fun send() = runTest {
        val (s, r) = bounded<Int>(1)

        val producer = async {
            assertSendOk(s.send(7))
            delay(1000)
            assertSendOk(s.send(8))
            delay(1000)
            assertSendOk(s.send(9))
            delay(1000)
            assertSendOk(s.send(10))
        }
        val consumer = async {
            delay(1500)
            assertRecvOk(r.recv(), 7)
            assertRecvOk(r.recv(), 8)
            assertRecvOk(r.recv(), 9)
            assertRecvOk(r.recv(), 10)
        }
        producer.await()
        consumer.await()
    }

    @Test
    fun closed() = runTest {
        val (s, r) = bounded<Int>(1)

        val producer = launch {
            assertSendOk(s.send(7))
            val watcher = launch { s.closed() }
            delay(500)
            assertTrue(watcher.isActive)
            delay(1000)
            watcher.join()
            assertFalse(watcher.isActive)
            s.closed()
        }
        val consumer = launch {
            assertRecvOk(r.recv(), 7)
            delay(500)
            r.close()
        }
        producer.join()
        consumer.join()
    }

    @Test
    fun forceSend() = runTest {
        val (s, r) = bounded<Int>(1)

        assertForceSendOk(s.forceSend(7), null)
        delay(1000)
        assertForceSendOk(s.forceSend(8), 7)
        delay(1000)
        assertForceSendOk(s.forceSend(9), 8)
        delay(1000)
        assertForceSendOk(s.forceSend(10), 9)

        assertRecvOk(r.recv(), 10)
    }

    @Test
    fun sendAfterClose() = runTest {
        val (s, r) = bounded<Int>(100)

        assertSendOk(s.send(1))
        assertSendOk(s.send(2))
        assertSendOk(s.send(3))

        r.close()

        assertSendErr(s.send(4), 4)
        val trySendOutcome = s.trySend(5)
        assertTrue(trySendOutcome is TrySendError.Closed)
        assertEquals(5, trySendOutcome.value)
        assertSendErr(s.send(6), 6)
    }

    @Test
    fun recvAfterClose() = runTest {
        val (s, r) = bounded<Int>(100)

        assertSendOk(s.send(1))
        assertSendOk(s.send(2))
        assertSendOk(s.send(3))

        s.close()

        assertRecvOk(r.recv(), 1)
        assertRecvOk(r.recv(), 2)
        assertRecvOk(r.recv(), 3)
        assertSame(RecvOutcome.Err, r.recv())
    }

    @Test
    fun len() = runTest {
        val cap = 1000

        val (s, r) = bounded<Int>(cap)

        assertEquals(0, s.len())
        assertEquals(0, r.len())

        repeat(cap / 10) {
            for (i in 0 until 50) {
                assertSendOk(s.send(i))
                assertEquals(i + 1, s.len())
            }

            for (i in 0 until 50) {
                assertRecvOk(r.recv(), i)
                assertEquals(50 - i - 1, r.len())
            }
        }

        assertEquals(0, s.len())
        assertEquals(0, r.len())

        for (i in 0 until cap) {
            assertSendOk(s.send(i))
            assertEquals(i + 1, s.len())
        }

        for (i in 0 until cap) {
            assertRecvOk(r.recv(), i)
        }

        assertEquals(0, s.len())
        assertEquals(0, r.len())
    }

    @Test
    fun receiverCount() {
        val (s, r) = bounded<Unit>(5)
        val receiverClones = (0 until 20).map { r.clone() }

        assertEquals(21, s.receiverCount())
        assertEquals(21, r.receiverCount())

        receiverClones.forEach { it.release() }

        assertEquals(1, s.receiverCount())
        assertEquals(1, r.receiverCount())
    }

    @Test
    fun senderCount() {
        val (s, r) = bounded<Unit>(5)
        val senderClones = (0 until 20).map { s.clone() }

        assertEquals(21, s.senderCount())
        assertEquals(21, r.senderCount())

        senderClones.forEach { it.release() }

        assertEquals(1, s.senderCount())
        assertEquals(1, r.senderCount())
    }

    @Test
    fun closeWakesSender() = runTest {
        val (s, r) = bounded<Unit>(1)

        val producer = launch {
            assertSendOk(s.send(Unit))
            assertSendErr(s.send(Unit), Unit)
        }
        val closer = launch {
            delay(1000)
            r.release()
        }
        producer.join()
        closer.join()
    }

    @Test
    fun closeWakesReceiver() = runTest {
        val (s, r) = bounded<Unit>(1)

        val consumer = launch {
            assertSame(RecvOutcome.Err, r.recv())
        }
        val closer = launch {
            delay(1000)
            s.release()
        }
        consumer.join()
        closer.join()
    }

    @Test
    fun spsc() = runTest {
        val count = 100_000

        val (s, r) = bounded<Int>(3)

        val consumer = async {
            for (i in 0 until count) {
                assertRecvOk(r.recv(), i)
            }
            assertSame(RecvOutcome.Err, r.recv())
        }
        val producer = async {
            for (i in 0 until count) {
                assertSendOk(s.send(i))
            }
            s.close()
        }
        producer.await()
        consumer.await()
    }

    @Test
    fun mpmc() = runTest {
        val count = 25_000
        val workers = 4

        val (s, r) = bounded<Int>(3)
        val seen = IntArray(count)

        val consumers = List(workers) {
            async {
                for (i in 0 until count) {
                    val ok = r.recv() as RecvOutcome.Ok<Int>
                    seen[ok.value] += 1
                }
            }
        }
        val producers = List(workers) {
            async {
                for (i in 0 until count) {
                    assertSendOk(s.send(i))
                }
            }
        }
        producers.forEach { it.await() }
        consumers.forEach { it.await() }

        for (value in seen) {
            assertEquals(workers, value)
        }
    }

    @Test
    fun mpmcStream() = runTest {
        val count = 25_000
        val workers = 4

        val (s, r) = bounded<Int>(3)
        val seen = IntArray(count)

        val consumers = List(workers) {
            async {
                for (i in 0 until count) {
                    val value = r.next() ?: error("unexpected end of stream")
                    seen[value] += 1
                }
            }
        }
        val producers = List(workers) {
            async {
                for (i in 0 until count) {
                    assertSendOk(s.send(i))
                }
            }
        }
        producers.forEach { it.await() }
        consumers.forEach { it.await() }

        for (value in seen) {
            assertEquals(workers, value)
        }
    }

    @Test
    fun weak() = runTest {
        val (s, r) = bounded<Int>(3)

        val weakS = s.downgrade()
        val weakR = r.downgrade()

        val upgradedS = weakS.upgrade() ?: error("expected sender upgrade")
        assertSendOk(upgradedS.send(3))
        val upgradedR = weakR.upgrade() ?: error("expected receiver upgrade")
        assertRecvOk(upgradedR.recv(), 3)

        s.close()

        assertNull(weakS.upgrade())
        assertNull(weakR.upgrade())
    }
}
