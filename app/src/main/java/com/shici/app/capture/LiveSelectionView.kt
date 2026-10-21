package com.shici.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import com.shici.app.core.HighlighterPoint
import com.shici.app.core.HighlighterStroke
import com.shici.app.core.HighlighterTuning
import com.shici.app.ui.dp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Full-screen, fully transparent gesture/ink layer used only for one live lookup. It never holds,
 * draws or shows the captured screen: the original page stays visible and live underneath and only
 * the user's ink, a short hint and a cancel chip are painted by this view.
 *
 * No touch is forwarded or synthesised to the app below. A single-pointer stroke is handed over on
 * lift exactly once; light taps, multi-touch and cancel never submit.
 */
@SuppressLint("ViewConstructor")
class LiveSelectionView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onStroke: (HighlighterStroke) -> Unit,
    private val onTooShort: () -> Unit,
    private val onCancel: (String) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 255, 213, 79)
        style = Paint.Style.STROKE
        strokeWidth = HighlighterTuning.BRUSH_WIDTH_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(214, 36, 47, 44) }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val hintText = Paint(chipText).apply { textSize = 14f * resources.displayMetrics.scaledDensity }
    private var currentPoints: MutableList<HighlighterPoint>? = null
    private var currentPath: Path? = null
    private var pointerId = -1
    private var interrupted = false
    private var cancelPressed = false
    private var downX = 0f
    private var downY = 0f
    @Volatile private var armed = false
    private var windowReadyCallback: (() -> Unit)? = null
    private var windowReadyReported = false
    private var globalLayoutObserved = false
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { reportWindowReady() }

    init {
        contentDescription = "透明划词层：沿完整单词一笔划过，抬笔即查；轻点不查询，可点取消退出"
        isClickable = true
        isFocusable = true
    }

    /** The clean entry frame is ready; ink feedback and one-stroke submission may start. */
    fun arm() {
        armed = true
        invalidate()
    }

    /**
     * Reports the first moment this overlay is genuinely usable: attached to a window, laid out
     * with a non-zero size, and visible. Merely calling addView is not accepted as readiness.
     * The callback runs once on the main thread; a removed view or a newer lookup therefore cannot
     * report late. No timing callback is used to delay or fake the gesture start.
     */
    fun awaitWindowReady(callback: () -> Unit) {
        if (windowReadyReported) {
            callback()
            return
        }
        windowReadyCallback = callback
        if (isAttachedToWindow && isShown && width > 0 && height > 0) {
            // A post runs after the current traversal in case layout already completed.
            post { reportWindowReady() }
        }
        observeLayout()
    }

    /** Cancels a pending readiness report when the layer is removed or its lookup is superseded. */
    fun cancelWindowReadiness() {
        windowReadyCallback = null
        runCatching { viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener) }
        globalLayoutObserved = false
    }

    private fun observeLayout() {
        if (globalLayoutObserved) return
        globalLayoutObserved = true
        runCatching { viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener) }
    }

    private fun reportWindowReady() {
        val callback = windowReadyCallback ?: return
        if (windowReadyReported || !isAttachedToWindow || !isShown || width <= 0 || height <= 0) return
        windowReadyReported = true
        windowReadyCallback = null
        runCatching { viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener) }
        globalLayoutObserved = false
        callback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeLayout()
        reportWindowReady()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        reportWindowReady()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) reportWindowReady()
    }

    private fun cancelRect(): RectF {
        val halfWidth = (chipText.measureText("取消取词") + 32f * density) / 2f
        val chipHeight = 44f * density
        val centerX = width / 2f
        val top = height - chipHeight - context.dp(32)
        return RectF(centerX - halfWidth, top, centerX + halfWidth, top + chipHeight)
    }

    private fun hintRect(): RectF {
        val text = "沿完整单词一笔划过 · 抬笔即查"
        val halfWidth = (hintText.measureText(text) + 28f * density) / 2f
        val height = 34f * density
        val centerX = width / 2f
        val top = context.dp(56).toFloat()
        return RectF(centerX - halfWidth, top, centerX + halfWidth, top + height)
    }

    override fun onDraw(canvas: Canvas) {
        if (!armed) return
        currentPath?.let { canvas.drawPath(it, ink) }
        drawChip(canvas, hintRect(), "沿完整单词一笔划过 · 抬笔即查", hintText, 34f * density)
        drawChip(canvas, cancelRect(), "取消取词", chipText, 44f * density)
    }

    private fun drawChip(canvas: Canvas, box: RectF, text: String, paint: Paint, height: Float) {
        val radius = height / 2f
        canvas.drawRoundRect(box, radius, radius, chipPaint)
        val metrics = paint.fontMetrics
        val baseline = box.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, box.centerX(), baseline, paint)
    }

    private fun cancelCurrentStroke() {
        currentPoints = null
        currentPath = null
        pointerId = -1
    }

    private fun beginStroke(event: MotionEvent) {
        pointerId = event.getPointerId(0)
        currentPoints = mutableListOf(HighlighterPoint(event.x, event.y))
        currentPath = Path().apply { moveTo(event.x, event.y) }
    }

    private fun appendPoint(x: Float, y: Float) {
        val points = currentPoints ?: return
        val point = HighlighterPoint(x, y)
        if (points.lastOrNull() != point) {
            points += point
            currentPath?.lineTo(x, y)
        }
    }

    private fun appendEvent(event: MotionEvent) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) { cancelCurrentStroke(); return }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            for (history in 0 until event.historySize) {
                appendPoint(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
            }
        }
        appendPoint(event.getX(index), event.getY(index))
    }

    /** View-local coordinates are translated to screen pixels; the overlay is display-aligned. */
    private fun toScreen(point: HighlighterPoint): HighlighterPoint {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return HighlighterPoint(point.x + location[0], point.y + location[1])
    }

    private fun strokeLength(points: List<HighlighterPoint>): Float =
        points.zipWithNext().sumOf { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }.toFloat()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // While the clean entry frame is still being acquired the layer only swallows touches: it
        // neither draws ink (which would pollute the frame) nor lets the tap reach the app below.
        if (!armed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interrupted = false
                cancelPressed = cancelRect().contains(event.x, event.y)
                downX = event.x
                downY = event.y
                if (!cancelPressed) beginStroke(event)
            }
            MotionEvent.ACTION_MOVE -> if (!cancelPressed && !interrupted) appendEvent(event)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Any second pointer makes this attempt ambiguous: never bridge or submit it.
                interrupted = true
                cancelCurrentStroke()
            }
            MotionEvent.ACTION_UP -> {
                if (cancelPressed) {
                    cancelPressed = false
                    cancelCurrentStroke()
                    if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop &&
                        cancelRect().contains(event.x, event.y)) onCancel("已取消取词")
                    invalidate()
                    return true
                }
                if (interrupted) {
                    interrupted = false
                    cancelCurrentStroke()
                    invalidate()
                    onCancel("检测到多指操作，本次未查询")
                    return true
                }
                appendEvent(event)
                val points = currentPoints?.toList() ?: emptyList()
                cancelCurrentStroke()
                invalidate()
                if (points.size < 2 || strokeLength(points) < HighlighterTuning.MIN_STROKE_LENGTH_DP * density) {
                    onTooShort()
                    return true
                }
                onStroke(HighlighterStroke(points.map(::toScreen)))
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelCurrentStroke()
                invalidate()
                onCancel("取词手势已中断，本次未查询")
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
