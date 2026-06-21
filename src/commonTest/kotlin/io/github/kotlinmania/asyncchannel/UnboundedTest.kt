// port-lint: source tests/unbounded.rs
package io.github.kotlinmania.asyncchannel

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun assertSendOk(outcome: SendOutcome<*>) {
    assertSame(SendOutcome.Ok, outcome)
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

class UnboundedTest {
    @Test
    fun smoke() =
        runTest {
            val (s, r) = unbounded<Int>()

            assertNull(s.trySend(7))
            assertTryRecvOk(r.tryRecv(), 7)

            assertSendOk(s.send(8))
            assertRecvOk(r.recv(), 8)
            assertTryRecvErr(r.tryRecv(), TryRecvError.Empty)
        }

    @Test
    fun capacity() {
        val (s, r) = unbounded<Unit>()
        assertNull(s.capacity())
        assertNull(r.capacity())
    }

    @Test
    fun lenEmptyFull() =
        runTest {
            val (s, r) = unbounded<Unit>()

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

            assertRecvOk(r.recv(), Unit)

            assertEquals(0, s.len())
            assertEquals(true, s.isEmpty())
            assertEquals(false, s.isFull())
            assertEquals(0, r.len())
            assertEquals(true, r.isEmpty())
            assertEquals(false, r.isFull())
        }

    @Test
    fun tryRecv() =
        runTest {
            val (s, r) = unbounded<Int>()

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
    fun recv() =
        runTest {
            val (s, r) = unbounded<Int>()

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
    fun trySend() {
        val (s, r) = unbounded<Int>()
        for (i in 0 until 1000) {
            assertNull(s.trySend(i))
        }

        r.close()
        val err = s.trySend(777)
        assertTrue(err is TrySendError.Closed)
        assertEquals(777, err.value)
    }

    @Test
    fun send() =
        runTest {
            val (s, r) = unbounded<Int>()
            for (i in 0 until 1000) {
                assertSendOk(s.send(i))
            }

            r.close()
            val outcome = s.send(777)
            assertTrue(outcome is SendOutcome.Err)
            assertEquals(777, outcome.error.value)
        }

    @Test
    fun sendAfterClose() =
        runTest {
            val (s, r) = unbounded<Int>()

            assertSendOk(s.send(1))
            assertSendOk(s.send(2))
            assertSendOk(s.send(3))

            r.close()

            val sendOutcome = s.send(4)
            assertTrue(sendOutcome is SendOutcome.Err)
            assertEquals(4, sendOutcome.error.value)
            val trySendOutcome = s.trySend(5)
            assertTrue(trySendOutcome is TrySendError.Closed)
            assertEquals(5, trySendOutcome.value)
        }

    @Test
    fun recvAfterClose() =
        runTest {
            val (s, r) = unbounded<Int>()

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
    fun len() =
        runTest {
            val (s, r) = unbounded<Int>()

            assertEquals(0, s.len())
            assertEquals(0, r.len())

            for (i in 0 until 50) {
                assertSendOk(s.send(i))
                assertEquals(i + 1, s.len())
            }

            for (i in 0 until 50) {
                assertRecvOk(r.recv(), i)
                assertEquals(50 - i - 1, r.len())
            }

            assertEquals(0, s.len())
            assertEquals(0, r.len())
        }

    @Test
    fun receiverCount() {
        val (s, r) = unbounded<Unit>()
        val receiverClones = (0 until 20).map { r.clone() }

        assertEquals(21, s.receiverCount())
        assertEquals(21, r.receiverCount())
        assertEquals(20, receiverClones.size)
    }

    @Test
    fun senderCount() {
        val (s, r) = unbounded<Unit>()
        val senderClones = (0 until 20).map { s.clone() }

        assertEquals(21, s.senderCount())
        assertEquals(21, r.senderCount())
        assertEquals(20, senderClones.size)
    }

    @Test
    fun closeWakesReceiver() =
        runTest {
            val (s, r) = unbounded<Unit>()

            launch {
                assertSame(RecvOutcome.Err, r.recv())
            }
            launch {
                delay(1000)
                s.close()
            }
        }

    @Test
    fun spsc() =
        runTest {
            val count = 100_000

            val (s, r) = unbounded<Int>()

            val consumer =
                async {
                    for (i in 0 until count) {
                        assertRecvOk(r.recv(), i)
                    }
                    assertSame(RecvOutcome.Err, r.recv())
                }
            val producer =
                async {
                    for (i in 0 until count) {
                        assertSendOk(s.send(i))
                    }
                    s.close()
                }
            producer.await()
            consumer.await()
        }

    @Test
    fun mpmc() =
        runTest {
            val count = 25_000
            val threads = 4

            val (s, r) = unbounded<Int>()
            val v = IntArray(count)

            val consumers =
                List(threads) {
                    async {
                        for (i in 0 until count) {
                            val ok = r.recv() as RecvOutcome.Ok<Int>
                            v[ok.value] += 1
                        }
                    }
                }
            val producers =
                List(threads) {
                    async {
                        for (i in 0 until count) {
                            assertSendOk(s.send(i))
                        }
                    }
                }
            producers.forEach { it.await() }
            consumers.forEach { it.await() }

            assertTryRecvErr(r.tryRecv(), TryRecvError.Empty)

            for (c in v) {
                assertEquals(threads, c)
            }
        }

    @Test
    fun mpmcStream() =
        runTest {
            val count = 25_000
            val threads = 4

            val (s, r) = unbounded<Int>()
            val v = IntArray(count)

            val consumers =
                List(threads) {
                    val receiver = r.clone()
                    async {
                        for (i in 0 until count) {
                            val value = receiver.next() ?: error("unexpected end of stream")
                            v[value] += 1
                        }
                    }
                }
            val producers =
                List(threads) {
                    async {
                        for (i in 0 until count) {
                            assertSendOk(s.send(i))
                        }
                    }
                }
            producers.forEach { it.await() }
            consumers.forEach { it.await() }

            assertTryRecvErr(r.tryRecv(), TryRecvError.Empty)

            for (c in v) {
                assertEquals(threads, c)
            }
        }

    @Test
    fun weak() =
        runTest {
            val (s, r) = unbounded<Int>()

            val weakS = s.downgrade()
            val weakR = r.downgrade()

            run {
                val s2 = weakS.upgrade() ?: error("expected upgrade")
                assertSendOk(s2.send(3))
                val r2 = weakR.upgrade() ?: error("expected upgrade")
                assertRecvOk(r2.recv(), 3)
            }

            s.close()

            run {
                assertNull(weakS.upgrade())
                assertNull(weakR.upgrade())
            }
        }
}
