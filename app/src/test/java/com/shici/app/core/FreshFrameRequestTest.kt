package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class FreshFrameRequestTest {
    private fun request(token: Long = 1) = FreshFrameRequest(token, 4000, PixelBox(0, 0, 120, 80))

    @Test fun blackFramesKeepTheSameRequestPendingUntilANewValidFrame() {
        val request = request().apply { arm(100) }
        assertFalse(request.offer(101, 10, clearlyBlack = true))
        assertTrue(request.rejectedBlack)
        assertTrue(request.canInspect(102, 20))
        assertFalse(request.offer(102, 20, clearlyBlack = true))
        assertTrue(request.offer(103, 30, clearlyBlack = false))
        assertEquals(1L, request.token)
        assertTrue(request.complete(31))
        assertFalse(request.offer(104, 32, clearlyBlack = false))
    }

    @Test fun unarmedAndOldFramesCannotBeDelivered() {
        val request = request()
        assertFalse(request.offer(500, 10, clearlyBlack = false))
        request.arm(100)
        assertFalse(request.offer(99, 11, clearlyBlack = false))
        assertTrue(request.offer(100, 12, clearlyBlack = false))
    }

    @Test fun repeatedBlackFramesDoNotExtendTheOriginalDeadline() {
        val request = request().apply { arm(100) }
        assertFalse(request.offer(101, 3999, clearlyBlack = true))
        assertFalse(request.canInspect(102, 4000))
        assertFalse(request.offer(102, 4000, clearlyBlack = false))
    }

    @Test fun queuedValidFrameIsStillSubjectToTimeoutBeforeUiDelivery() {
        val request = request().apply { arm(100) }
        assertTrue(request.offer(101, 3999, clearlyBlack = false))
        assertFalse(request.complete(4000))
        assertFalse(request.canInspect(102, 4001))
    }

    @Test fun cancellationAndResizeOrStopInvalidateInFlightFrames() {
        val request = request().apply { arm(100) }
        assertTrue(request.offer(101, 10, clearlyBlack = false))
        request.cancel()
        assertFalse(request.complete(11))
        assertFalse(request.offer(102, 12, clearlyBlack = false))
        request.arm(0)
        assertFalse(request.canInspect(103, 13))
    }

    @Test fun cancellationDuringInspectionRejectsTheFrameBeforeItIsQueued() {
        val request = request().apply { arm(100) }
        assertTrue(request.canInspect(101, 10))
        request.cancel()
        assertFalse(request.offer(101, 11, clearlyBlack = false))
        assertFalse(request.complete(12))
    }

    @Test fun deadlineIsCheckedAgainAfterPixelInspection() {
        val request = request().apply { arm(100) }
        assertTrue(request.canInspect(101, 3999))
        assertFalse(request.offer(101, 4000, clearlyBlack = false))
        assertFalse(request.complete(4001))
    }

    @Test fun cancelledRequestCannotConsumeAReplacementRequest() {
        val old = request(1).apply { arm(100); cancel() }
        val replacement = request(2).apply { arm(200) }
        assertFalse(old.offer(201, 20, clearlyBlack = false))
        assertFalse(old.complete(21))
        assertTrue(replacement.offer(201, 20, clearlyBlack = false))
        assertTrue(replacement.complete(21))
    }

    @Test fun onlyOneValidFrameCanBeQueuedAndCompleted() {
        val request = request().apply { arm(100) }
        assertTrue(request.offer(101, 10, clearlyBlack = false))
        assertFalse(request.canInspect(102, 11))
        assertFalse(request.offer(102, 11, clearlyBlack = false))
        assertTrue(request.complete(12))
        assertFalse(request.complete(13))
    }
}
