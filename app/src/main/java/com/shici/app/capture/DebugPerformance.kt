package com.shici.app.capture

import android.os.SystemClock
import android.util.Log
import com.shici.app.BuildConfig
import com.shici.app.core.OperationTiming
import java.util.UUID

/** Debug-only logcat; no file, network, word, OCR text or image is emitted. */
class DebugPerformance {
    private var operation: OperationTiming? = null
    fun click() {
        if (!BuildConfig.DEBUG) return
        operation = OperationTiming(UUID.randomUUID().toString(), SystemClock::elapsedRealtime)
        emit("click", "pending")
    }
    fun selectionReady() {
        operation?.let { emit("selection_ready", "ok", "click_to_selection_ms=${it.clickToSelection()}") }
    }
    // OperationTiming's internal release marker is reused; the public metric now starts at Finish.
    fun selectionSubmit() { operation?.release(); emit("selection_submit", "pending") }
    fun definitionVisible() {
        operation?.releaseToDefinition()?.let { emit("definition_visible", "ok", "submit_to_definition_ms=$it") }
    }
    fun stage(stage: String, status: String) { emit(stage, status) }
    fun end(status: String, stage: String) { emit(stage, status); operation = null }
    private fun emit(stage: String, status: String, additional: String = "") {
        if (!BuildConfig.DEBUG) return
        operation?.let {
            Log.d("ShiciPerf", "op=${it.id} stage=$stage status=$status elapsed_ms=${it.elapsed()} $additional".trim())
        }
    }
}
