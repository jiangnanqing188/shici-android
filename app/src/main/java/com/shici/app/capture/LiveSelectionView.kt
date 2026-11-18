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
import com.shici.app.core.PixelBox
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
    private val onCancel: (String) -> Unit,
    private val onGestureStart: () -> Unit = {},
    private val onGestureState: (String) -> Unit = {}
) : View(context) {
    private enum class Stage { PREPARING, SAMPLING, READY }
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
    @Volatile private var stage = Stage.PREPARING
    @Volatile private var armed = false
    // A gesture that began before this layer was ready is never turned into a synthetic stroke.
    // Its final UP/CANCEL only produces a clear redraw notice; a later DOWN starts a full stroke.
    private var preparationGesture = false
    private var preparationCancel = false
    private var preparationMulti = false
    private var preparationDownX = 0f
    private var preparationDownY = 0f
    // A redraw notice is painted by this layer, never a Toast: the entry/verify samples contain no
    // app-owned notice window, and the next DOWN clears it before the new stroke starts.
    private var notice: String? = null
    // Screen-coordinate rectangles occupied by this overlay's own ink and chips. The capture
    // thread reads this list while comparing the page during a stroke; it is replaced atomically
    // and never contains a whole-page mask, so the page pixels around the ink stay observable.
    @Volatile private var screenExclusions: List<PixelBox> = emptyList()
    private var windowReadyCallback: (() -> Unit)? = null
    private var windowReadyReported = false
    private var globalLayoutObserved = false
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { reportWindowReady() }

    init {
        contentDescription = "透明划词层：沿完整单词一笔划过，抬笔即查；轻点不查询，可点取消退出"
        isClickable = true
        isFocusable = true
    }

    /**
     * The clean entry frame is ready; ink feedback and one-stroke submission may start. A gesture
     * already in progress from [Stage.PREPARING]/[Stage.SAMPLING] is not revived by this call.
     */
    fun arm() {
        stage = Stage.READY
        armed = true
        updateScreenExclusions()
        invalidate()
    }

    /**
     * Hides the preparation prompt/cancel chips immediately before the clean entry frame is armed.
     * The layer remains mounted and continues to own every touch, but draws no pixels until [arm].
     */
    fun beginCleanEntry() {
        if (stage != Stage.PREPARING) return
        stage = Stage.SAMPLING
        updateScreenExclusions()
        invalidate()
    }

    /** True only after the window is attached, laid out with a real size, and visible. */
    fun isWindowReady(): Boolean =
        isAttachedToWindow && isShown && windowVisibility == VISIBLE && width > 0 && height > 0

    /** Paints the redraw notice inside the transparent layer, avoiding a Toast in either sample. */
    private fun showNotice(message: String) {
        notice = message
        updateScreenExclusions()
        invalidate()
    }

    private fun clearNotice() {
        if (notice == null) return
        notice = null
        updateScreenExclusions()
        invalidate()
    }

    /**
     * The exact screen rectangles this overlay covers with opaque pixels while READY: the stroke
     * path (inflated by the brush), the two chips and any redraw notice. A frame observer excludes
     * only these and compares the rest of the page; preparation/sampling return an empty list
     * because their prompt is hidden before the clean ENTRY frame and never joins a stroke.
     */
    fun currentScreenExclusions(): List<PixelBox> = screenExclusions

    private fun updateScreenExclusions() {
        // Preparation chips are never part of a stroke-time comparison. Sampling hides them before
        // the clean entry frame; only the READY layer publishes actual ink/chip exclusions.
        if (stage != Stage.READY || !armed) {
            screenExclusions = emptyList()
            return
        }
        val location = IntArray(2)
        getLocationOnScreen(location)
        val boxes = ArrayList<PixelBox>(3)
        fun add(box: RectF, padding: Float) {
            // Ink that is exactly horizontal or vertical has zero raw extent on one axis but is
            // still real: an axis may legitimately degenerate, so a zero-size raw box is not
            // "empty". Inflate by the real brush radius/edge padding first, then require a strict
            // positive area and screen clamp. Non-finite or inverted input is bad data, never ink;
            // it must not be squeezed through a NaN-to-zero conversion into a fake top-left mask.
            val left = box.left
            val top = box.top
            val right = box.right
            val bottom = box.bottom
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return
            if (right < left || bottom < top) return
            val screenLeft = left + location[0] - padding
            val screenTop = top + location[1] - padding
            val screenRight = right + location[0] + padding
            val screenBottom = bottom + location[1] + padding
            if (!screenLeft.isFinite() || !screenTop.isFinite() ||
                !screenRight.isFinite() || !screenBottom.isFinite()) return
            if (screenRight <= screenLeft || screenBottom <= screenTop) return
            val clampedLeft = screenLeft.toInt().coerceIn(0, screenWidth)
            val clampedTop = screenTop.toInt().coerceIn(0, screenHeight)
            val clampedRight = screenRight.toInt().coerceIn(0, screenWidth)
            val clampedBottom = screenBottom.toInt().coerceIn(0, screenHeight)
            if (clampedRight > clampedLeft && clampedBottom > clampedTop) {
                boxes += PixelBox(clampedLeft, clampedTop, clampedRight, clampedBottom)
            }
        }
        val inkBounds = RectF()
        var hasInk = false
        val path = currentPath
        if (path != null) {
            path.computeBounds(inkBounds, true)
            hasInk = true
        } else {
            currentPoints?.forEach { point ->
                if (!hasInk) {
                    inkBounds.set(point.x, point.y, point.x, point.y)
                    hasInk = true
                } else {
                    inkBounds.left = minOf(inkBounds.left, point.x)
                    inkBounds.top = minOf(inkBounds.top, point.y)
                    inkBounds.right = maxOf(inkBounds.right, point.x)
                    inkBounds.bottom = maxOf(inkBounds.bottom, point.y)
                }
            }
        }
        // No stroke means no ink box at all; the default (0,0,0,0) rect must never mask a corner.
        if (hasInk) add(inkBounds, ink.strokeWidth / 2f + 4f * density)
        add(hintRect(), 3f * density)
        add(cancelRect(), 3f * density)
        if (notice != null) add(noticeRect(), 3f * density)
        screenExclusions = boxes
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
        if (isWindowReady()) {
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
        if (windowReadyReported || !isWindowReady()) return
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
        updateScreenExclusions()
        reportWindowReady()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) reportWindowReady()
    }

    override fun onDetachedFromWindow() {
        cancelWindowReadiness()
        super.onDetachedFromWindow()
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

    private fun noticeRect(): RectF {
        val text = notice ?: return RectF()
        val maxWidth = (width - 32f * density).coerceAtLeast(1f)
        val halfWidth = (minOf(hintText.measureText(text) + 28f * density, maxWidth)) / 2f
        val top = hintRect().bottom + 8f * density
        return RectF(width / 2f - halfWidth, top, width / 2f + halfWidth, top + 34f * density)
    }

    override fun onDraw(canvas: Canvas) {
        when (stage) {
            Stage.PREPARING -> {
                drawChip(canvas, hintRect(), "正在准备划词…", hintText, 34f * density)
                drawChip(canvas, cancelRect(), "取消取词", chipText, 44f * density)
                notice?.let {
                    drawChip(canvas, noticeRect(), it, hintText, 34f * density)
                }
            }
            Stage.SAMPLING -> Unit
            Stage.READY -> {
                if (!armed) return
                updateScreenExclusions()
                currentPath?.let { canvas.drawPath(it, ink) }
                drawChip(canvas, hintRect(), "沿完整单词一笔划过 · 抬笔即查", hintText, 34f * density)
                drawChip(canvas, cancelRect(), "取消取词", chipText, 44f * density)
                notice?.let {
                    drawChip(canvas, noticeRect(), it, hintText, 34f * density)
                }
            }
        }
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
        updateScreenExclusions()
    }

    private fun beginStroke(event: MotionEvent) {
        pointerId = event.getPointerId(0)
        currentPoints = mutableListOf(HighlighterPoint(event.x, event.y))
        currentPath = Path().apply { moveTo(event.x, event.y) }
        updateScreenExclusions()
    }

    private fun appendPoint(x: Float, y: Float) {
        val points = currentPoints ?: return
        val point = HighlighterPoint(x, y)
        if (points.lastOrNull() != point) {
            points += point
            currentPath?.lineTo(x, y)
            updateScreenExclusions()
        }
    }

    private fun appendStrokeEvent(event: MotionEvent): Boolean {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) {
            onGestureState("pointer_lost")
            cancelCurrentStroke()
            showNotice("笔迹手势已中断，请重新划一笔")
            return false
        }
        // Coalesced history belongs to any event, not only ACTION_MOVE. A fast two-sample stroke
        // can carry its whole back-and-forth path in the lift event's history; dropping it would
        // turn a real word stroke into a straight jump between the first and last point.
        for (history in 0 until event.historySize) {
            appendPoint(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
        }
        appendPoint(event.getX(index), event.getY(index))
        return true
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
        // While preparing/sampling, the mounted layer is a real touch barrier but never starts a
        // stroke or lets a gesture reach the page below. The preparation prompt is the only thing
        // drawn before sampling; it is hidden before the clean ENTRY frame.
        val currentStage = stage
        if (currentStage != Stage.READY || !armed) {
            handlePreReadyTouch(event, currentStage)
            invalidate()
            return true
        }
        // A DOWN that happened before arm must not be completed with a synthetic start point or a
        // half stroke after arm. Consume its remaining events through the real lift/cancel, report
        // one clear redraw notice, then let the next DOWN start normally.
        if (preparationGesture) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // The system dropped the old stream; the new DOWN is a complete new gesture.
                resetPreparationGesture()
            } else {
                handleCrossReadyTouch(event)
                invalidate()
                return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearNotice()
                interrupted = false
                cancelPressed = cancelRect().contains(event.x, event.y)
                downX = event.x
                downY = event.y
                if (!cancelPressed) {
                    // Publish the initial ink geometry before opening the observation window.
                    beginStroke(event)
                    onGestureStart()
                }
            }
            MotionEvent.ACTION_MOVE -> if (!cancelPressed && !interrupted && currentPoints != null) {
                appendStrokeEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Any second pointer makes this attempt ambiguous: never bridge or submit it.
                if (!interrupted) onGestureState("multi_cancel")
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
                // A lift without an active stroke belongs to a discarded touch stream. It must
                // not turn into a spurious "too short" notice or a new query.
                if (currentPoints == null) {
                    onGestureState("lift_without_down")
                    showNotice("本次未收到完整手势，请重新划一笔")
                    invalidate()
                    return true
                }
                if (!appendStrokeEvent(event)) return true
                val points = currentPoints.orEmpty()
                if (points.size < 2 || strokeLength(points) < HighlighterTuning.MIN_STROKE_LENGTH_DP * density) {
                    onTooShort()
                    cancelCurrentStroke()
                    showNotice("轻点未提交，请沿完整单词一笔划过")
                    return true
                }
                // Keep the final ink/chip exclusion snapshot published while the synchronous
                // submission closes the capture-side observation window. Clearing it first can
                // race an ambient frame that is still being compared and turn the already removed
                // ink into false doubt for an otherwise static stroke.
                onStroke(HighlighterStroke(points.map(::toScreen)))
                if (isAttachedToWindow) {
                    cancelCurrentStroke()
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                interrupted = false
                cancelPressed = false
                onGestureState("ready_cancel")
                cancelCurrentStroke()
                invalidate()
                onCancel("取词手势已中断，本次未查询")
            }
        }
        invalidate()
        return true
    }

    private fun resetPreparationGesture() {
        preparationGesture = false
        preparationCancel = false
        preparationMulti = false
        preparationDownX = 0f
        preparationDownY = 0f
    }

    private fun isCancelTap(event: MotionEvent): Boolean =
        preparationCancel && !preparationMulti &&
            abs(event.x - preparationDownX) <= touchSlop &&
            abs(event.y - preparationDownY) <= touchSlop &&
            cancelRect().contains(event.x, event.y)

    /** Swallows a gesture that starts before this layer is ready and explains why it was not used. */
    private fun handlePreReadyTouch(event: MotionEvent, currentStage: Stage) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearNotice()
                resetPreparationGesture()
                preparationGesture = true
                if (currentStage == Stage.PREPARING) {
                    preparationCancel = cancelRect().contains(event.x, event.y)
                    preparationDownX = event.x
                    preparationDownY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (preparationGesture) {
                    preparationMulti = true
                    preparationCancel = false
                }
            }
            MotionEvent.ACTION_UP -> {
                val cancel = isCancelTap(event)
                val multi = preparationMulti
                resetPreparationGesture()
                if (!cancel) onGestureState(if (currentStage == Stage.PREPARING) "preparing_drop" else "sampling_drop")
                when {
                    cancel -> onCancel("已取消取词")
                    multi -> showNotice("多指未提交，请重新完整划一笔")
                    else -> showNotice("等出现「抬笔即查」，再完整划一笔")
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                resetPreparationGesture()
                // This cancels a touch stream, not the preparation owner. Explicit cancel still
                // exits via the chip; detach, resize and preparation timeouts retire the layer.
                onGestureState("pre_ready_cancel")
                showNotice("准备期间手势已中断，就绪后请重划")
            }
        }
    }

    /** Finishes a gesture whose DOWN crossed into READY without inventing a stroke. */
    private fun handleCrossReadyTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                preparationMulti = true
                preparationCancel = false
            }
            MotionEvent.ACTION_UP -> {
                val cancel = isCancelTap(event)
                val multi = preparationMulti
                resetPreparationGesture()
                if (!cancel) onGestureState("cross_ready_drop")
                when {
                    cancel -> onCancel("已取消取词")
                    multi -> showNotice("多指未提交，请用单指重新划一笔")
                    else -> showNotice("本次触摸开始于准备期，请重新划一笔")
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                resetPreparationGesture()
                onGestureState("cross_ready_cancel")
                showNotice("手势在准备切换时中断，请重划")
            }
        }
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
