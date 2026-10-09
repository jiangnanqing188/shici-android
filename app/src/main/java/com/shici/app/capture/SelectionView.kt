package com.shici.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.shici.app.core.*

// Only constructed with a live session bitmap, never inflated from XML or a layout editor.
@SuppressLint("ViewConstructor")
class SelectionView(context: Context, private val image: Bitmap, private val onSelected: (HighlighterSelection) -> Unit,
                    private val onInteractive: () -> Unit = {}) : View(context) {
    private val imageRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 255, 213, 79)
        style = Paint.Style.STROKE
        strokeWidth = HighlighterTuning.BRUSH_WIDTH_DP * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val strokes = mutableListOf<HighlighterStroke>()
    private val paths = mutableListOf<Path>()
    private var currentPoints: MutableList<HighlighterPoint>? = null
    private var currentPath: Path? = null
    private var pointerId = -1
    private var reportedInteractive = false
    init { contentDescription = "临时截图；沿文字涂划，可分多笔；点击完成划选才查询"; isFocusable = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) clearStrokes()
        val box = SelectionGeometry.imageBox(image.width, image.height, w, h)
        imageRect.set(box.left, box.top, box.right, box.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(30, 38, 35))
        canvas.drawBitmap(image, null, imageRect, imagePaint)
        if (!reportedInteractive) { reportedInteractive = true; onInteractive() }
        val saved = canvas.save()
        canvas.clipRect(imageRect)
        paths.forEach { canvas.drawPath(it, ink) }
        currentPath?.let { canvas.drawPath(it, ink) }
        currentPoints?.takeIf { it.size == 1 }?.first()?.let { canvas.drawPoint(it.x, it.y, ink) }
        canvas.restoreToCount(saved)
    }

    fun clearStrokes() {
        strokes.clear(); paths.clear(); cancelCurrentStroke(); invalidate()
    }

    /** Lifting a finger only finishes a stroke. This is the sole selection submission entry. */
    fun submit(): Boolean {
        if (width <= 0 || height <= 0 || currentPoints != null) return false
        val selection = HighlighterGeometry.selection(strokes, image.width, image.height,
            width, height, resources.displayMetrics.density) ?: return false
        onSelected(selection)
        return true
    }

    private fun cancelCurrentStroke() { currentPoints = null; currentPath = null; pointerId = -1 }
    private fun appendPoint(x: Float, y: Float) {
        val points = currentPoints ?: return
        val point = HighlighterPoint(x, y)
        if (points.lastOrNull() != point) { points += point; currentPath?.lineTo(x, y) }
    }
    private fun appendEvent(event: MotionEvent) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) { cancelCurrentStroke(); return }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            for (history in 0 until event.historySize)
                appendPoint(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
        }
        appendPoint(event.getX(index), event.getY(index))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelCurrentStroke()
                if (imageRect.contains(event.x, event.y)) {
                    pointerId = event.getPointerId(0)
                    currentPoints = mutableListOf(HighlighterPoint(event.x, event.y))
                    currentPath = Path().apply { moveTo(event.x, event.y) }
                }
            }
            MotionEvent.ACTION_MOVE -> appendEvent(event)
            MotionEvent.ACTION_UP -> {
                appendEvent(event)
                currentPoints?.let { points ->
                    val stroke = HighlighterStroke(points.toList())
                    if (HighlighterGeometry.strokeLength(stroke) >= HighlighterTuning.MIN_STROKE_LENGTH_DP * resources.displayMetrics.density) {
                        strokes += stroke; currentPath?.let { paths += it }
                    }
                }
                cancelCurrentStroke()
                performClick()
            }
            // Discard an interrupted/multi-touch stroke, never bridge pointer identities or lifts.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> cancelCurrentStroke()
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
