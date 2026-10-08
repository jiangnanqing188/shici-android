package com.shici.app.capture

import android.content.Context
import android.content.Intent
import android.os.Looper

enum class CapturePhase { CLOSED, PREPARING, ACTIVE, STOPPING, ERROR }
data class CaptureState(val phase: CapturePhase, val message: String)

object CaptureStatus {
    @Volatile var state = CaptureState(CapturePhase.CLOSED, "已关闭")
        private set
    @Volatile var generation = 0L
        private set
    fun prepare(): Long {
        generation += 1
        update(CapturePhase.PREPARING, "准备中 · 等待权限确认")
        return generation
    }
    fun stop(context: Context) {
        if (state.phase == CapturePhase.STOPPING) return
        generation += 1
        val stopIntent = Intent(context, CaptureService::class.java)
        val running = context.stopService(stopIntent)
        update(if (running) CapturePhase.STOPPING else CapturePhase.CLOSED,
            if (running) "正在关闭 · 释放捕获与浮层" else "已关闭")
    }
    private val listeners = mutableSetOf<(CaptureState) -> Unit>()
    fun update(phase: CapturePhase, message: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        state = CaptureState(phase, message)
        listeners.toList().forEach { it(state) }
    }
    fun observe(listener: (CaptureState) -> Unit) { listeners += listener; listener(state) }
    fun remove(listener: (CaptureState) -> Unit) { listeners -= listener }
}
