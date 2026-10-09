package com.shici.app.core

/** A valid frame stays pending until UI delivery; black frames never restart the deadline. */
class FreshFrameRequest(val token: Long, val deadlineMillis: Long, val body: PixelBox) {
    private var afterNanos = Long.MAX_VALUE
    private var delivering = false
    private var finished = false
    @Volatile var rejectedBlack = false
        private set

    @Synchronized fun arm(afterNanos: Long) {
        if (!finished && !delivering) this.afterNanos = afterNanos
    }
    @Synchronized fun canInspect(timestampNanos: Long, nowMillis: Long): Boolean =
        !finished && !delivering && afterNanos != Long.MAX_VALUE &&
            timestampNanos >= afterNanos && nowMillis < deadlineMillis

    @Synchronized fun offer(timestampNanos: Long, nowMillis: Long, clearlyBlack: Boolean): Boolean {
        if (!canInspect(timestampNanos, nowMillis)) return false
        if (clearlyBlack) { rejectedBlack = true; return false }
        delivering = true
        return true
    }
    @Synchronized fun complete(nowMillis: Long): Boolean {
        if (finished || !delivering || nowMillis >= deadlineMillis) return false
        finished = true
        return true
    }
    @Synchronized fun cancel() { finished = true }
}
