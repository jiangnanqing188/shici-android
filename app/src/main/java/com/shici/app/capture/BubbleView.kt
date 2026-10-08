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

/** Native, service-context safe, with an explicit accessibility click path. */
class BubbleView(context: Context) : View(context) {
    var onDragStart: () -> Unit = {}
    var onDrag: (Float, Float) -> Unit = { _, _ -> }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    init {
        background = context.shape(Paper.green, 28)
        contentDescription = "拾词：点击框选查词，拖动改变位置"
        isClickable = true; isFocusable = true
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawText("词", width / 2f, height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; dragged = false; onDragStart() }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (abs(dx) + abs(dy) > touchSlop) dragged = true
                if (dragged) onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> if (!dragged) performClick()
            MotionEvent.ACTION_CANCEL -> dragged = true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
