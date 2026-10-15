package com.shici.app.core

/**
 * In-process, thread-safe deletion generation shared by manual lookup and CaptureService OCR.
 *
 * A lookup captures one epoch when it starts and carries it through recognition, display, save and
 * retry. The repository rejects saving an old event only when its own word was deleted strictly
 * after that epoch, so deleting one word never invalidates an in-flight query for another word. A
 * failed deletion never advances the epoch. Nothing here is persisted; it only guards live async
 * callbacks and is cleared with the process.
 */
class DeletionGate {
    private var epoch = 0L
    private val deletedAt = HashMap<String, Long>()

    /** Taken once at query start; deliberately not refreshed when a late result is displayed. */
    @Synchronized fun capture(): Long = epoch

    /** Records a committed deletion. Call only after the delete transaction has succeeded. */
    @Synchronized fun recordDeletion(entryKey: String) {
        epoch += 1
        deletedAt[entryKey] = epoch
    }

    /** True when [entryKey] was deleted strictly after [captured] was taken. */
    @Synchronized fun isStale(entryKey: String, captured: Long): Boolean = (deletedAt[entryKey] ?: 0L) > captured
}
