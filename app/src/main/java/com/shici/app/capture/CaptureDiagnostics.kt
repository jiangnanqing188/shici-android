package com.shici.app.capture

import android.os.SystemClock
import android.util.Log
import com.shici.app.BuildConfig

/**
 * Debug-only, content-free evidence for one live lookup operation.
 *
 * The trace is a bounded list of fixed stage/result labels plus non-content integers (screen size,
 * elapsed milliseconds, frame counters). It never records a headword, OCR text/line, meaning,
 * screenshot, pixel value, stroke path, file path or network payload. Nothing is written outside
 * logcat and nothing is emitted at all in a release build.
 *
 * Calls are made from the main thread only. [begin] starts a fresh operation and discards any
 * previous trace, [flush] emits one readable summary and clears the buffered stages, and [end]
 * flushes and releases the operation. The stage buffer is capped, so an operation that never ends
 * cannot grow memory without bound.
 */
class CaptureDiagnostics {
    private class StageEntry(val stage: String, val result: String, val atMillis: Long) {
        var count = 1
    }

    private var operationId: String? = null
    private var startedAt = 0L
    private var width = 0
    private var height = 0
    private var entryFrames = 0
    private var verifyFrames = 0
    private var blackFrames = 0
    private val stages = ArrayList<StageEntry>()
    private var dropped = 0

    /**
     * Starts a new operation and drops every byte of the previous trace. [operationId] is the
     * caller's content-free identity for this same operation, so the ShiciPerf timing lines and
     * ShiciDiag summary lines can be correlated by one op id instead of two unrelated UUIDs.
     */
    fun begin(width: Int, height: Int, operationId: String) {
        if (!BuildConfig.DEBUG) return
        this.operationId = operationId
        startedAt = SystemClock.elapsedRealtime()
        this.width = width
        this.height = height
        entryFrames = 0
        verifyFrames = 0
        blackFrames = 0
        stages.clear()
        dropped = 0
    }

    /** Aggregated, non-content frame counters; never logged per frame. */
    fun frame(purpose: String, black: Boolean) {
        if (!BuildConfig.DEBUG || operationId == null) return
        if (black) blackFrames++
        else when (purpose) {
            "entry" -> entryFrames++
            "verify" -> verifyFrames++
        }
    }

    /** Adds one fixed stage/result label; an identical consecutive pair is only counted. */
    fun stage(stage: String, result: String) {
        if (!BuildConfig.DEBUG || operationId == null) return
        val last = stages.lastOrNull()
        if (last != null && last.stage == stage && last.result == result) {
            last.count++
            return
        }
        if (stages.size >= MAX_STAGES) {
            dropped++
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        stages.add(StageEntry(stage, result, elapsed))
    }

    /**
     * Emits one summary line for the work observed so far, then clears the stage buffer. The
     * operation stays open so a later save/dismiss can still be correlated under the same op id.
     */
    fun flush(result: String) {
        if (!BuildConfig.DEBUG) return
        val op = operationId ?: return
        val total = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        val line = StringBuilder(160)
            .append("op=").append(op)
            .append(" result=").append(result)
            .append(" total_ms=").append(total)
            .append(" size=").append(width).append('x').append(height)
            .append(" frames=entry:").append(entryFrames)
            .append(",verify:").append(verifyFrames)
            .append(",black:").append(blackFrames)
            .append(" stages=")
        stages.forEachIndexed { index, entry ->
            if (index > 0) line.append(',')
            line.append(entry.stage).append(':').append(entry.result)
                .append('@').append(entry.atMillis)
            if (entry.count > 1) line.append('x').append(entry.count)
        }
        if (dropped > 0) line.append(" dropped=").append(dropped)
        Log.d(TAG, line.toString())
        stages.clear()
        dropped = 0
    }

    /** Flushes the final summary and releases all operation state. */
    fun end(result: String) {
        if (!BuildConfig.DEBUG) return
        flush(result)
        operationId = null
        startedAt = 0L
        width = 0
        height = 0
        entryFrames = 0
        verifyFrames = 0
        blackFrames = 0
        stages.clear()
        dropped = 0
    }

    private companion object {
        const val TAG = "ShiciDiag"
        // Bounded per-operation evidence; repeated identical stages are counted rather than stored.
        const val MAX_STAGES = 24
    }
}
