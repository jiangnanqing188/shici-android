package com.shici.app.capture

import android.os.SystemClock
import android.util.Log
import com.shici.app.BuildConfig
import com.shici.app.core.OperationTiming
import java.util.UUID

/** Debug-only logcat; no file, network, word, OCR text or image is emitted. */
class DebugPerformance {
    private var operation: OperationTiming? = null
    // Same-operation, content-free evidence. Every entry point below is a no-op in release.
    private val diagnostics = CaptureDiagnostics()
    fun click(width: Int = 0, height: Int = 0) {
        if (!BuildConfig.DEBUG) return
        val timing = OperationTiming(UUID.randomUUID().toString(), SystemClock::elapsedRealtime)
        operation = timing
        // Reuse this click's non-content timing id as the diagnostics summary identity, so
        // ShiciPerf and ShiciDiag lines for the same operation share one op id.
        diagnostics.begin(width, height, timing.id)
        emit("click", "pending")
    }
    fun selectionReady() {
        operation?.let { emit("selection_ready", "ok", "click_to_selection_ms=${it.clickToSelection()}") }
    }
    // The submit point is the real ACTION_UP of the single valid stroke; there is no Finish button.
    fun selectionSubmit() { operation?.release(); emit("selection_submit", "pending") }
    fun definitionVisible() {
        operation?.releaseToDefinition()?.let { emit("definition_visible", "ok", "submit_to_definition_ms=$it") }
    }
    fun stage(stage: String, status: String) {
        diagnostics.stage(stage, status)
        emit(stage, status)
    }
    /** Aggregated frame counters for the current operation; no per-frame log line. */
    fun frame(purpose: String, black: Boolean) {
        if (!BuildConfig.DEBUG) return
        diagnostics.frame(purpose, black)
    }
    /**
     * Emits one content-free summary of the stages observed so far without ending the operation.
     * Used when a terminal card (message / multi-word / dictionary miss) becomes visible, so the
     * evidence is readable even if the user never dismisses that card.
     */
    fun snapshot(result: String) {
        if (!BuildConfig.DEBUG) return
        diagnostics.flush(result)
    }
    /** Bounded motion diagnostics; fixed stage, enums/timings, no content or gesture coordinates. */
    fun motionProbe(match: com.shici.app.core.FrameMatch?, elapsedMillis: Long, stage: String = "motion_probe") {
        if (BuildConfig.DEBUG) Log.i("ShiciPerf", "stage=$stage reason=${match?.reason} duration_ms=$elapsedMillis " +
            "context=${match?.contextSamples} changed=${match?.changedContextSamples} " +
            "edges=${match?.edgeContextSamples} edge_changed=${match?.changedEdgeContextSamples}")
    }
    fun end(status: String, stage: String) {
        if (BuildConfig.DEBUG) {
            diagnostics.stage(stage, status)
            diagnostics.end(status)
        }
        emit(stage, status)
        operation = null
    }
    private fun emit(stage: String, status: String, additional: String = "") {
        if (!BuildConfig.DEBUG) return
        operation?.let {
            Log.i("ShiciPerf", "op=${it.id} stage=$stage status=$status elapsed_ms=${it.elapsed()} $additional".trim())
        }
    }
}
