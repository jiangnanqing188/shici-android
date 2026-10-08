package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class QueryGateTest {
    @Test fun nextSelectionDismissAndStopInvalidateOldCallbacks() {
        val gate = QueryGate()
        val selected = gate.next()
        assertTrue(gate.accepts(selected))
        val corrected = gate.next()
        assertFalse(gate.accepts(selected))
        assertTrue(gate.accepts(corrected))
        gate.next()
        assertFalse(gate.accepts(corrected))
    }
}
