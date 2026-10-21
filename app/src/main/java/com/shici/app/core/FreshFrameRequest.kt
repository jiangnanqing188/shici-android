package com.shici.app.core

/** A valid frame stays pending until UI delivery; black frames never restart the deadline. */
class FreshFrameRequest(val token: Long, val deadlineMillis: Long, val body: PixelBox) {
    private var afterNanos = Long.MAX_VALUE
    private var delivering = false
    private var finished = false
    @Volatile var rejectedBlack = false
        private set
    /**
     * Source timestamp of the accepted (non-black) image. It becomes the page-identity lower bound
     * once this frame is verified, so a newer frame captured after the lift but before the guard is
     * mounted is still checked. Zero means the source supplied no usable timestamp; the old
     * "no lower bound" safety semantics are then kept.
     */
    @Volatile var acceptedTimestampNanos = 0L
        private set

    @Synchronized fun arm(afterNanos: Long) {
        if (!finished && !delivering) this.afterNanos = afterNanos
    }
    @Synchronized fun canInspect(timestampNanos: Long, nowMillis: Long): Boolean =
        !finished && !delivering && afterNanos != Long.MAX_VALUE &&
            timestampNanos >= afterNanos && nowMillis < deadlineMillis

    /**
     * True once this request accepted a frame but its main-thread hand-off has not finished yet.
     * That interval must not become a blind spot: a newer source frame still has to be compared
     * with the accepted image's identity instead of being dropped because [canInspect] is false.
     */
    @Synchronized fun isAwaitingDelivery(): Boolean = delivering && !finished

    @Synchronized fun offer(timestampNanos: Long, nowMillis: Long, clearlyBlack: Boolean): Boolean {
        if (!canInspect(timestampNanos, nowMillis)) return false
        if (clearlyBlack) { rejectedBlack = true; return false }
        delivering = true
        acceptedTimestampNanos = timestampNanos
        return true
    }
    @Synchronized fun complete(nowMillis: Long): Boolean {
        if (finished || !delivering || nowMillis >= deadlineMillis) return false
        finished = true
        return true
    }
    @Synchronized fun cancel() { finished = true }
}
