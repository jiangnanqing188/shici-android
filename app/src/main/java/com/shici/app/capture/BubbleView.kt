package com.shici.app.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.shici.app.ui.Paper
import com.shici.app.ui.shape
import kotlin.math.abs

/**
 * Native, service-context safe floating entry with two visible actions: the left half starts a
 * highlighter stroke on the current page, the right half opens the compact in-page input box.
 * Both actions are plain, discoverable taps; dragging anywhere still moves the whole bubble.
 */
class BubbleView(context: Context) : View(context) {
    var onDragStart: () -> Unit = {}
    var onDrag: (Float, Float) -> Unit = { _, _ -> }
    /** Visible "输入" action. Tapping the right half calls this instead of starting a stroke. */
    var onInput: () -> Unit = {}
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 255, 255, 255)
        strokeWidth = resources.displayMetrics.density
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    init {
        background = context.shape(Paper.green, 28)
        contentDescription = "拾词：左半「词」可在原页面划词，右半「输入」打开输入框，拖动改变位置"
        isClickable = true; isFocusable = true
    }
    override fun onDraw(canvas: Canvas) {
        val baseline = height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
        val middle = width / 2f
        canvas.drawText("词", width * 0.28f, baseline, labelPaint)
        canvas.drawText("输入", width * 0.74f, baseline, labelPaint)
        val inset = height * 0.24f
        canvas.drawLine(middle, inset, middle, height - inset, dividerPaint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; dragged = false; onDragStart() }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (abs(dx) + abs(dy) > touchSlop) dragged = true
                if (dragged) onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> if (!dragged) {
                // The right half's visible label is the input entry; the left half keeps the
                // original click path so the highlighter action is unchanged for existing users.
                if (event.x >= width / 2f) onInput() else performClick()
            }
            MotionEvent.ACTION_CANCEL -> dragged = true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
