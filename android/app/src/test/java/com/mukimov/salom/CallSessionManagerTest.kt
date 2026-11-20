package com.mukimov.salom

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CallSessionManagerTest {
    @After fun reset() { CallSessionManager.end("a"); CallSessionManager.end("b") }

    @Test fun sameInviteCannotCreateSecondCall() {
        assertTrue(CallSessionManager.incoming("a"))
        assertFalse(CallSessionManager.incoming("a"))
        assertFalse(CallSessionManager.incoming("b"))
        assertEquals(CallSessionManager.State.RINGING, CallSessionManager.current("a"))
    }

    @Test fun acceptIsAtomicAndOnlyOnce() {
        assertTrue(CallSessionManager.incoming("a"))
        assertTrue(CallSessionManager.accept("a"))
        assertFalse(CallSessionManager.accept("a"))
        assertEquals(CallSessionManager.State.CONNECTING, CallSessionManager.current("a"))
        CallSessionManager.connected("a")
        assertEquals(CallSessionManager.State.CONNECTED, CallSessionManager.current("a"))
        CallSessionManager.end("a")
        assertNull(CallSessionManager.current("a"))
        assertTrue(CallSessionManager.incoming("b"))
    }
}
