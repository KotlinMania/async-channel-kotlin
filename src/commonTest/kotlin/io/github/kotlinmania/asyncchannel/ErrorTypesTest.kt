// port-lint: ignore
// Tests for the four error types: SendError, TrySendError, RecvError, TryRecvError.
package io.github.kotlinmania.asyncchannel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SendErrorTest {
    @Test
    fun intoInnerReturnsValue() {
        assertEquals(42, SendError(42).intoInner())
        assertEquals("msg", SendError("msg").intoInner())
    }

    @Test
    fun equalityByValue() {
        assertEquals(SendError(1), SendError(1))
        assertNotEquals(SendError(1), SendError(2))
    }

    @Test
    fun hashCodeMatchesValue() {
        assertEquals(SendError(1).hashCode(), SendError(1).hashCode())
    }

    @Test
    fun displayMessage() {
        assertEquals("sending into a closed channel", SendError(1).toString())
    }
}

class TrySendErrorTest {
    @Test
    fun fullIntoInner() {
        val err: TrySendError<Int> = TrySendError.Full(7)
        assertEquals(7, err.intoInner())
    }

    @Test
    fun closedIntoInner() {
        val err: TrySendError<Int> = TrySendError.Closed(7)
        assertEquals(7, err.intoInner())
    }

    @Test
    fun isFullDiscriminator() {
        assertTrue(TrySendError.Full(0).isFull())
        assertFalse(TrySendError.Closed(0).isFull())
    }

    @Test
    fun isClosedDiscriminator() {
        assertFalse(TrySendError.Full(0).isClosed())
        assertTrue(TrySendError.Closed(0).isClosed())
    }

    @Test
    fun fullAndClosedAreNotEqual() {
        val full: TrySendError<Int> = TrySendError.Full(1)
        val closed: TrySendError<Int> = TrySendError.Closed(1)
        assertNotEquals(full, closed)
    }

    @Test
    fun fullEqualityByValue() {
        assertEquals(TrySendError.Full(1), TrySendError.Full(1))
        assertNotEquals(TrySendError.Full(1), TrySendError.Full(2))
    }

    @Test
    fun closedEqualityByValue() {
        assertEquals(TrySendError.Closed(1), TrySendError.Closed(1))
        assertNotEquals(TrySendError.Closed(1), TrySendError.Closed(2))
    }

    @Test
    fun displayMessages() {
        assertEquals("sending into a full channel", TrySendError.Full(0).toString())
        assertEquals("sending into a closed channel", TrySendError.Closed(0).toString())
    }
}

class RecvErrorTest {
    @Test
    fun singleInstance() {
        assertSame(RecvError, RecvError)
    }

    @Test
    fun displayMessage() {
        assertEquals("receiving from an empty and closed channel", RecvError.toString())
    }
}

class TryRecvErrorTest {
    @Test
    fun isEmptyDiscriminator() {
        assertTrue(TryRecvError.Empty.isEmpty())
        assertFalse(TryRecvError.Closed.isEmpty())
    }

    @Test
    fun isClosedDiscriminator() {
        assertFalse(TryRecvError.Empty.isClosed())
        assertTrue(TryRecvError.Closed.isClosed())
    }

    @Test
    fun displayMessages() {
        assertEquals("receiving from an empty channel", TryRecvError.Empty.toString())
        assertEquals("receiving from an empty and closed channel", TryRecvError.Closed.toString())
    }
}
