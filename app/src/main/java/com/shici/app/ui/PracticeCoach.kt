package com.shici.app.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

/** One coach attached to a real window. A transparent hit hole preserves the real target's input. */
class PracticeCoach(
    private val host: ViewGroup,
    private val target: View?,
    message: String,
    private val onSkip: () -> Unit,
    onFinish: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    private val onTargetGone: () -> Unit
) : FrameLayout(host.context) {
    private val hole = RectF()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val card = context.column().apply {
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        background = context.shape(Paper.surface, 12, true)
        val copy = context.column().apply {
            addView(context.text(message, 17f, Paper.ink, true))
            addView(context.text("可直接操作，也可点下一步；不会代你查询或授权。", 13f, Paper.secondary))
        }
        addView(android.widget.ScrollView(context).apply { addView(copy) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val skip = context.button("跳过引导") { if (!closed) onSkip() }
        val next = if (onFinish != null) context.button("完成引导", true) { if (!closed) onFinish() }
            else context.button("下一步", true) { if (!closed) onNext?.invoke() }
        addView(context.pair(next, skip, horizontal = true, gap = 8), LinearLayout.LayoutParams(-1, -2))
    }
    private var closed = false
    private var pulse = 0f
    private val animation = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 450; repeatCount = 3; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
    }
    private val position = ViewTreeObserver.OnPreDrawListener {
        if (!closed) {
            if (target != null && (!target.isAttachedToWindow || !target.isShown)) {
                close(); onTargetGone()
            } else place()
        }
        true
    }
    init {
        setWillNotDraw(false)
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        host.addView(this, ViewGroup.LayoutParams(-1, -1))
        viewTreeObserver.addOnPreDrawListener(position)
        animation.start()
    }
    private fun place() {
        if (width <= 0 || height <= 0) return
        val origin = IntArray(2); getLocationOnScreen(origin)
        val visible = Rect(); getWindowVisibleDisplayFrame(visible)
        val available = RectF(
            max(0, visible.left - origin[0]).toFloat(), max(0, visible.top - origin[1]).toFloat(),
            min(width, visible.right - origin[0]).toFloat(), min(height, visible.bottom - origin[1]).toFloat())
        if (available.width() <= 0 || available.height() <= 0) return
        val previousHole = RectF(hole)
        hole.setEmpty()
        if (target != null) {
            val rect = Rect()
            if (!target.getGlobalVisibleRect(rect)) { close(); onTargetGone(); return }
            hole.set((rect.left - origin[0]).toFloat(), (rect.top - origin[1]).toFloat(),
                (rect.right - origin[0]).toFloat(), (rect.bottom - origin[1]).toFloat())
        }
        val gap = context.dp(12)
        val w = min(context.dp(360), (available.width().toInt() - gap * 2).coerceAtLeast(1))
        val belowRoom = (available.bottom - gap - hole.bottom - gap).toInt()
        val aboveRoom = (hole.top - gap - available.top - gap).toInt()
        val space = if (hole.isEmpty) available.height().toInt() - gap * 2 else max(belowRoom, aboveRoom)
        val maxHeight = space.coerceAtLeast(1)
        card.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        val cardHeight = card.measuredHeight
        val below = if (hole.isEmpty) available.top + gap else hole.bottom + gap
        val above = hole.top - gap - cardHeight
        val y = when {
            below + cardHeight <= available.bottom - gap -> below
            above >= available.top + gap -> above
            else -> available.top + gap
        }
        val x = (available.centerX() - w / 2f).coerceIn(available.left, (available.right - w).coerceAtLeast(available.left))
        val params = card.layoutParams as LayoutParams
        if (params.width != w || params.height != cardHeight || params.leftMargin != x.toInt() || params.topMargin != y.toInt()) {
            params.width = w; params.height = cardHeight; params.leftMargin = x.toInt(); params.topMargin = y.toInt()
            card.layoutParams = params
        }
        if (previousHole != hole) invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        if (!hole.isEmpty) canvas.clipOutRect(hole)
        canvas.drawColor(0x55000000)
        canvas.restoreToCount(saved)
        if (!hole.isEmpty) {
            ink.style = Paint.Style.STROKE; ink.strokeWidth = context.dp(2) + pulse * context.dp(2)
            ink.color = Paper.green
            canvas.drawRoundRect(hole, context.dp(8).toFloat(), context.dp(8).toFloat(), ink)
        }
    }
    private var passingTarget = false
    private var handlingCard = false
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            handlingCard = event.x >= card.left && event.x < card.right && event.y >= card.top && event.y < card.bottom
            passingTarget = !handlingCard && !hole.isEmpty && hole.contains(event.x, event.y)
        }
        // Keep the owner of DOWN for the whole gesture; an UP entering the hole cannot leak.
        if (passingTarget) return false
        if (handlingCard) return super.dispatchTouchEvent(event)
        return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = true
    fun close() {
        if (closed) return
        closed = true; animation.cancel()
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(position)
        (parent as? ViewGroup)?.removeView(this)
    }
    override fun onDetachedFromWindow() {
        animation.cancel()
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(position)
        closed = true
        super.onDetachedFromWindow()
    }
}
