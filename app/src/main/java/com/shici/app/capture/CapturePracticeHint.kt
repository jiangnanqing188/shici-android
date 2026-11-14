package com.shici.app.capture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.shici.app.ui.*
import kotlin.math.min

/** A small instruction beside the actual bubble, removed together with it before frame sampling. */
internal class CapturePracticeHint(
    private val context: Context,
    private val windows: WindowManager,
    private val anchor: View,
    private val available: Rect,
    message: String,
    private val onSkip: () -> Unit,
    private val onNext: () -> Unit,
    nextLabel: String = "下一步"
) {
    private var closed = false
    private var mounted = false
    private val view = context.column().apply {
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(6))
        background = context.shape(Paper.surface, 10, true)
        addView(context.text(message, 15f, Paper.ink, true))
        addView(context.button(nextLabel, true) { if (!closed && isAttachedToWindow) onNext() }, LinearLayout.LayoutParams(-1, -2))
        addView(context.button("结束引导") { if (!closed && isAttachedToWindow) onSkip() }, LinearLayout.LayoutParams(-1, -2))
    }
    private val pulse = ValueAnimator.ofFloat(1f, 0.45f).apply {
        duration = 400; repeatCount = 3; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { if (!closed && anchor.isAttachedToWindow) anchor.alpha = it.animatedValue as Float }
    }
    fun show() {
        if (closed || !anchor.isAttachedToWindow) return
        val width = min(context.dp(280), (available.width() - context.dp(16)).coerceAtLeast(1))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((available.height() - context.dp(16)).coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        val height = view.measuredHeight.coerceAtLeast(1)
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        val left = location[0].coerceIn(available.left, (available.right - width).coerceAtLeast(available.left))
        val proposed = if (location[1] + anchor.height + height + context.dp(8) <= available.bottom)
            location[1] + anchor.height + context.dp(8) else location[1] - height - context.dp(8)
        val top = proposed.coerceIn(available.top, (available.bottom - height).coerceAtLeast(available.top))
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = left; y = top }
        // Failure is a guide-only failure. The existing capture service remains usable.
        try { windows.addView(view, params); mounted = true; pulse.start() }
        catch (_: WindowManager.BadTokenException) { close() }
        catch (_: SecurityException) { close() }
    }
    fun close() {
        if (closed) return
        closed = true; pulse.cancel(); anchor.alpha = 1f
        if (mounted) { windows.removeViewImmediate(view); mounted = false }
    }
}
