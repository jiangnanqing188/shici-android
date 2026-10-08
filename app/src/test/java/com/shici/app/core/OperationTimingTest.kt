package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class OperationTimingTest {
    @Test fun measuredStagesExcludeUserSelectionTime() {
        var time = 10L
        val op = OperationTiming("anonymous", { time })
        time = 110
        assertEquals(100L, op.clickToSelection())
        assertNull(op.releaseToDefinition())
        time = 1010
        op.release()
        time = 1110
        assertEquals(100L, op.releaseToDefinition())
        assertEquals(1100L, op.elapsed())
    }
}
