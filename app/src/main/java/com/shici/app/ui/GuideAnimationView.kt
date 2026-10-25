package com.shici.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import com.shici.app.core.GuideAnimation
import com.shici.app.core.UsageGuide
import kotlin.math.max
import kotlin.math.min

/**
 * A small native Canvas illustration for one guide step. It draws an invented screen, a moving finger
 * / pen tip, a highlight that follows the stroke, the lift and the resulting card. It never touches a
 * repository, permission, dictionary or preference, and it never performs a query: every word and
 * meaning here is fictional sample content.
 *
 * Lifecycle and safety:
 * - exactly one [ValueAnimator] exists at a time and it runs once, never in a loop;
 * - [onHostResume]/[onHostPause]/[close]/[onDetachedFromWindow] all cancel it, so nothing keeps
 *   drawing or posting after the host leaves, the view detaches or the page is destroyed;
 * - when the system disables animations the view draws a static key frame and a tap advances through
 *   the finite key frames from [GuideAnimation.keyPhaseCount]; with animations enabled a tap replays
 *   the current step. Every animation is bounded by [GuideAnimation.MIN_STEP_MS]/[MAX_STEP_MS].
 */
class GuideAnimationView(
    context: Context,
    initialStep: GuideAnimation.Step
) : View(context) {

    private var step: GuideAnimation.Step = initialStep
    private var animator: ValueAnimator? = null
    private var progress: Float = 0f
    private var staticPhase: Int = 0
    private var attached: Boolean = false
    private var resumed: Boolean = true
    private var closed: Boolean = false
    private var windowVisible: Boolean = true

    /** True when the system asks apps not to animate; the static key-frame path is used instead. */
    var animationsDisabled: Boolean = false
        private set

    private var motionModeListener: ((Boolean) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val boldFace = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val normalFace = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val starPath = Path()
    private var fingerXOverride: Float? = null

    init {
        animationsDisabled = systemAnimationsDisabled()
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = talkBackFor(initialStep)
        setOnClickListener { illustrationTapped() }
    }

    /** The step currently drawn. Read-only so a host or a test can confirm the selected deep link. */
    val shownStepId: String get() = step.id

    /** The scene currently selected. */
    val shownDemo: GuideAnimation.Demo get() = step.demo

    /** True only while a real animation is running; false after completion, pause, detach or close. */
    val isAnimating: Boolean get() = animator != null

    /**
     * Installs a one-time current-mode callback and reports the present mode immediately. The screen
     * uses this to show the reduced-motion hint without polling.
     */
    fun setMotionModeListener(listener: (Boolean) -> Unit) {
        motionModeListener = listener
        listener(animationsDisabled)
    }

    /** Draws [next] from its first frame and starts it only while attached and resumed. */
    fun showStep(next: GuideAnimation.Step) {
        if (closed) return
        if (step.id != next.id) {
            step = next
            staticPhase = 0
        }
        stopAnimation()
        progress = if (animationsDisabled) GuideAnimation.staticProgress(step.kind, staticPhase) else 0f
        contentDescription = talkBackFor(step)
        invalidate()
        startAnimationIfPossible()
    }

    /** Replays the current step from the beginning; still a single bounded run. */
    fun replay() {
        if (closed) return
        staticPhase = 0
        stopAnimation()
        progress = if (animationsDisabled) 0f else 0f
        invalidate()
        startAnimationIfPossible()
    }

    /** Host foreground: recompute the animation scale and restart the current step once. */
    fun onHostResume() {
        if (closed) return
        resumed = true
        refreshMotionMode()
        replay()
    }

    /** Host background: cancel the animator so no callback or invalidation survives in background. */
    fun onHostPause() {
        resumed = false
        stopAnimation()
    }

    /** Page destroyed: permanent, idempotent stop. A later attach/resume cannot revive it. */
    fun close() {
        closed = true
        resumed = false
        stopAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        // Seed from the platform visibility so an attach while already hidden never starts work.
        // A later onWindowVisibilityChanged always wins because it updates the same flag first.
        windowVisible = windowVisibility == VISIBLE
        refreshMotionMode()
        startAnimationIfPossible()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        // Defensive self-stop for hosts that never forward Activity lifecycle: a background window
        // cannot keep a ValueAnimator or an invalidate callback alive.
        if (!windowVisible) {
            stopAnimation()
        } else {
            startAnimationIfPossible()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = context.dp(250)
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(max(desiredHeight, suggestedMinimumHeight), heightMeasureSpec)
        )
    }

    private fun illustrationTapped() {
        if (closed) return
        refreshMotionMode()
        if (animationsDisabled) {
            val count = GuideAnimation.keyPhaseCount(step.kind)
            staticPhase = (staticPhase + 1) % count
            stopAnimation()
            progress = GuideAnimation.staticProgress(step.kind, staticPhase)
            invalidate()
            announceForAccessibility("${step.talkBack} 关键阶段 ${staticPhase + 1}，共 $count")
        } else {
            replay()
        }
    }

    private fun talkBackFor(value: GuideAnimation.Step): String = value.talkBack + " 演示图。"

    private fun systemAnimationsDisabled(): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled()) return true
        val scale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        } catch (_: Exception) {
            1f
        }
        return scale <= 0f
    }

    private fun refreshMotionMode() {
        val next = systemAnimationsDisabled()
        if (next == animationsDisabled) return
        animationsDisabled = next
        stopAnimation()
        if (!closed) {
            progress = if (next) GuideAnimation.staticProgress(step.kind, staticPhase) else 0f
            invalidate()
        }
        motionModeListener?.invoke(next)
    }

    /**
     * A running callback is legal only while it is the current animator *and* the host is still in
     * an active, attached, resumed and visible window. The identity/state test is repeated inside
     * every closure: a cancelled animator can still deliver a late update or end, and such a
     * callback must neither move the progress nor invalidate, nor touch a newer animator.
     */
    private fun canAnimate(): Boolean =
        !closed && attached && resumed && windowVisible

    private fun isCurrentAndActive(animation: ValueAnimator): Boolean =
        animator === animation && canAnimate()

    private fun startAnimationIfPossible() {
        stopAnimation()
        if (!canAnimate()) return
        if (animationsDisabled) {
            progress = GuideAnimation.staticProgress(step.kind, staticPhase)
            invalidate()
            return
        }
        val duration = step.durationMs
            .coerceIn(GuideAnimation.MIN_STEP_MS, GuideAnimation.MAX_STEP_MS)
            .toLong()
        val running = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (!isCurrentAndActive(animation)) return@addUpdateListener
                progress = (animation.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
        }
        running.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator !== running || !canAnimate()) return
                animator = null
                progress = 1f
                invalidate()
            }
        })
        animator = running
        running.start()
    }

    private fun stopAnimation() {
        val running = animator
        animator = null
        running?.cancel()
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val unit = min(w / 360f, h / 250f).coerceAtLeast(0.6f)
        val duration = step.durationMs.coerceIn(GuideAnimation.MIN_STEP_MS, GuideAnimation.MAX_STEP_MS)
        val frame = GuideAnimation.frameAt(
            step.kind,
            (progress.coerceIn(0f, 1f) * duration).toLong(),
            duration
        )

        fill.color = Paper.background
        canvas.drawRect(0f, 0f, w, h, fill)
        val screen = RectF(w * 0.035f, h * 0.04f, w * 0.965f, h * 0.96f)
        panel(canvas, screen, unit * 10f, Paper.surface, Paper.line)

        badge(canvas, UsageGuide.DEMO_BADGE, w * 0.07f, h * 0.115f, unit)
        label(canvas, "本地示意", w * 0.93f, h * 0.115f, unit * 11f, Paper.secondary, Paint.Align.RIGHT)

        fingerXOverride = null
        when (step.demo) {
            GuideAnimation.Demo.CAPTURE -> drawCapture(canvas, frame, unit, w, h)
            GuideAnimation.Demo.VOCABULARY -> drawVocabulary(canvas, frame, unit, w, h)
            GuideAnimation.Demo.STUDY -> drawStudy(canvas, frame, unit, w, h)
            GuideAnimation.Demo.LOOKUP_SUPPLEMENT -> drawLookupSupplement(canvas, frame, unit, w, h)
        }
        drawFinger(canvas, frame, unit, w, h)
    }

    // ---------------------------------------------------------------- capture

    private fun drawCapture(canvas: Canvas, frame: GuideAnimation.Frame, u: Float, w: Float, h: Float) {
        header(canvas, "拾词", u, w, h)
        when (step.kind) {
            GuideAnimation.Kind.ENTRY -> {
                label(canvas, "悬浮查词", w * 0.09f, h * 0.40f, u * 17f, Paper.ink, bold = true)
                label(canvas, "只在首页开启", w * 0.09f, h * 0.48f, u * 11f, Paper.secondary)
                val track = RectF(w * 0.70f, h * 0.355f, w * 0.91f, h * 0.415f)
                val knobRadius = track.height() * 0.42f
                val travel = track.width() - knobRadius * 2f - u * 4f
                val knobX = track.left + u * 2f + knobRadius + travel * frame.highlight
                fill.color = blend(Color.rgb(200, 207, 201), Paper.green, frame.highlight)
                canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, fill)
                fill.color = Color.WHITE
                canvas.drawCircle(knobX, track.centerY(), knobRadius, fill)
                if (frame.resultVisible) {
                    val state = "已开启（演示）"
                    chip(canvas, state, w * 0.09f, h * 0.685f, u * 12f, Paper.lightGreen, Paper.green, u)
                } else {
                    label(canvas, "当前：关闭", w * 0.09f, h * 0.68f, u * 12f, Paper.secondary)
                }
            }
            GuideAnimation.Kind.TAP_BUBBLE -> {
                pageLines(canvas, 0.09f, 0.28f, 0.62f, u, w, h)
                val bubble = RectF(w * 0.70f, h * 0.45f, w * 0.90f, h * 0.59f)
                val rippleRadius = u * (8f + 22f * frame.highlight)
                stroke.color = withAlpha(Paper.green, (120 * (1f - frame.highlight)).toInt())
                stroke.strokeWidth = u * 2f
                canvas.drawCircle(bubble.centerX(), bubble.centerY(), rippleRadius, stroke)
                panel(canvas, bubble, u * 12f, Paper.lightGreen, Paper.green)
                label(
                    canvas, "词", bubble.centerX(), bubble.centerY(),
                    u * 18f, Paper.green, Paint.Align.CENTER, bold = true
                )
                label(canvas, "点一下开始划词", w * 0.09f, h * 0.82f, u * 11f, Paper.secondary)
            }
            GuideAnimation.Kind.DRAG,
            GuideAnimation.Kind.LIFT,
            GuideAnimation.Kind.RESULT -> {
                val sentence = "The harvest was good this year."
                val word = "harvest"
                val sentenceSize = u * 15f
                val sentenceWidth = measure(sentence, sentenceSize, false)
                val startX = max(w * 0.08f, (w - sentenceWidth) / 2f)
                val baseline = h * 0.57f
                label(canvas, "原文页面（示意）", w * 0.09f, h * 0.27f, u * 11f, Paper.secondary)
                label(canvas, sentence, startX, baseline, sentenceSize, Paper.ink)
                val prefix = measure("The ", sentenceSize, false)
                val wordWidth = measure(word, sentenceSize, false)
                val wordLeft = startX + prefix
                val highlighted = wordWidth * frame.highlight
                if (step.kind == GuideAnimation.Kind.DRAG || step.kind == GuideAnimation.Kind.LIFT) {
                    // Keep the pen tip exactly on the word, whatever the sentence width is.
                    fingerXOverride = (wordLeft + highlighted) / w
                }
                fill.color = withAlpha(Paper.lightGreen, 210)
                canvas.drawRoundRect(
                    RectF(wordLeft - u, baseline - sentenceSize * 0.95f, wordLeft + highlighted, baseline + u * 4f),
                    u * 3f, u * 3f, fill
                )
                stroke.color = Paper.green
                stroke.strokeWidth = u * 2.5f
                canvas.drawLine(wordLeft, baseline + u * 3f, wordLeft + highlighted, baseline + u * 3f, stroke)
                if (step.kind == GuideAnimation.Kind.LIFT) label(
                    canvas, "抬笔 → 识别中…", w * 0.5f, h * 0.74f,
                    u * 11f, Paper.secondary, Paint.Align.CENTER
                )
                if (frame.resultVisible) resultCard(
                    canvas, frame, u, w, h,
                    word = "harvest",
                    meaning = "n. 收获；v. 收割",
                    tag = "真实查询成功 · 已收录",
                    note = "没有额外的保存按钮"
                )
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- vocabulary

    private fun drawVocabulary(canvas: Canvas, frame: GuideAnimation.Frame, u: Float, w: Float, h: Float) {
        header(canvas, "生词本", u, w, h)
        if (step.kind == GuideAnimation.Kind.SCOPE ||
            step.kind == GuideAnimation.Kind.CONFIRM_ONE ||
            step.kind == GuideAnimation.Kind.CONFIRM_TWO
        ) {
            val sheet = RectF(w * 0.07f, h * 0.19f, w * 0.93f, h * 0.88f)
            panel(canvas, sheet, u * 10f, Paper.surface, Paper.line)
            when (step.kind) {
                GuideAnimation.Kind.SCOPE -> {
                    label(canvas, "选择移除范围", w * 0.12f, h * 0.29f, u * 16f, Paper.ink, bold = true)
                    chip(canvas, "无默认项", w * 0.80f, h * 0.285f, u * 11f, Paper.lightGreen, Paper.green, u)
                    option(
                        canvas, w * 0.12f, h * 0.43f, w * 0.88f,
                        "仅撤销最近一次有效查询", "保留之前的查询与笔记", u, false
                    )
                    option(
                        canvas, w * 0.12f, h * 0.63f, w * 0.88f,
                        "删除该词全部学习记录", "含历史、笔记、进度与收藏", u, false
                    )
                    label(canvas, "请明确选择一种范围", w * 0.5f, h * 0.82f, u * 11f, Paper.secondary, Paint.Align.CENTER)
                }
                GuideAnimation.Kind.CONFIRM_ONE -> confirmDialog(
                    canvas, frame, u, w, h,
                    title = "确认移除范围？",
                    body = "核对：仅撤销最近一次，或删除全部记录。",
                    positive = "继续",
                    dangerous = false
                )
                else -> confirmDialog(
                    canvas, frame, u, w, h,
                    title = "再次确认（不可恢复）",
                    body = "确认后执行；个人补词与原词库不受影响。",
                    positive = "确认移除",
                    dangerous = true
                )
            }
            if (step.kind == GuideAnimation.Kind.CONFIRM_TWO && frame.highlight > 0.65f) {
                label(canvas, "演示不会真的删除", w * 0.5f, h * 0.855f, u * 11f, Paper.warning, Paint.Align.CENTER)
            }
            return
        }

        val card = RectF(w * 0.08f, h * 0.22f, w * 0.92f, h * 0.40f)
        panel(canvas, card, u * 10f, Paper.surface, Paper.line)
        label(canvas, "harvest", w * 0.12f, h * 0.345f, u * 20f, Paper.ink, bold = true)
        label(canvas, "n. 收获；v. 收割", w * 0.12f, h * 0.43f, u * 11f, Paper.secondary)
        val starX = w * 0.83f
        val starY = h * 0.30f
        val starProgress = if (step.kind == GuideAnimation.Kind.TAP_STAR) frame.highlight else 0f
        drawStar(canvas, starX, starY, u * 12f, starProgress, u)
        if (step.kind == GuideAnimation.Kind.TAP_STAR) {
            label(
                canvas,
                if (frame.highlight > 0.5f) "已收藏：只是标记，不改进度" else "点星标收藏：只是标记",
                w * 0.5f, h * 0.58f, u * 12f, Paper.secondary, Paint.Align.CENTER
            )
        } else {
            val sheet = RectF(w * 0.06f, h * 0.50f, w * 0.94f, h * 0.88f)
            panel(canvas, sheet, u * 10f, Paper.surface, Paper.line)
            label(canvas, "☆ 收藏", w * 0.11f, h * 0.60f, u * 14f, Paper.ink)
            label(canvas, "移除学习记录…", w * 0.11f, h * 0.70f, u * 14f, Paper.error)
            if (frame.highlight > 0.2f) {
                stroke.color = withAlpha(Paper.error, (190 * frame.highlight).toInt())
                stroke.strokeWidth = u * 1.5f
                canvas.drawRoundRect(
                    RectF(w * 0.08f, h * 0.63f, w * 0.92f, h * 0.73f),
                    u * 8f, u * 8f, stroke
                )
            }
            label(canvas, "先选范围，再确认两次", w * 0.5f, h * 0.82f, u * 11f, Paper.secondary, Paint.Align.CENTER)
        }
    }

    private fun option(
        canvas: Canvas, left: Float, top: Float, right: Float,
        title: String, detail: String, u: Float, selected: Boolean
    ) {
        stroke.color = if (selected) Paper.green else Paper.line
        stroke.strokeWidth = u * 1.5f
        canvas.drawCircle(left + u * 2f, top + u * 3f, u * 6f, stroke)
        if (selected) {
            fill.color = Paper.green
            canvas.drawCircle(left + u * 2f, top + u * 3f, u * 3f, fill)
        }
        label(canvas, title, left + u * 16f, top + u * 5f, u * 14f, Paper.ink, bold = true)
        label(canvas, detail, left + u * 16f, top + u * 19f, u * 11f, Paper.secondary)
        if (selected) {
            stroke.color = Paper.green
            stroke.strokeWidth = u * 1.5f
            canvas.drawRoundRect(
                RectF(left - u * 4f, top - u * 8f, right, top + u * 26f),
                u * 8f, u * 8f, stroke
            )
        }
    }

    private fun confirmDialog(
        canvas: Canvas, frame: GuideAnimation.Frame, u: Float, w: Float, h: Float,
        title: String, body: String, positive: String, dangerous: Boolean
    ) {
        fill.color = Color.argb(90, 36, 47, 44)
        canvas.drawRect(0f, 0f, w, h, fill)
        val dialog = RectF(w * 0.10f, h * 0.26f, w * 0.90f, h * 0.80f)
        panel(canvas, dialog, u * 12f, Paper.surface, Paper.line)
        label(canvas, title, w * 0.14f, h * 0.37f, u * 16f, Paper.ink, bold = true)
        label(canvas, body, w * 0.14f, h * 0.48f, u * 12f, Paper.secondary)
        label(canvas, "范围由你上一步明确选择；不会默认选中。", w * 0.14f, h * 0.56f, u * 11f, Paper.secondary)
        val cancel = RectF(w * 0.14f, h * 0.66f, w * 0.47f, h * 0.74f)
        val accept = RectF(w * 0.53f, h * 0.66f, w * 0.86f, h * 0.74f)
        textButton(canvas, cancel, "取消", false, false, u)
        textButton(canvas, accept, positive, true, dangerous && frame.highlight > 0.55f, u)
        if (frame.highlight > 0.3f) {
            stroke.color = withAlpha(if (dangerous) Paper.error else Paper.green, (170 * frame.highlight).toInt())
            stroke.strokeWidth = u * 1.5f
            canvas.drawRoundRect(accept, u * 8f, u * 8f, stroke)
        }
    }

    // ---------------------------------------------------------------- study

    private fun drawStudy(canvas: Canvas, frame: GuideAnimation.Frame, u: Float, w: Float, h: Float) {
        header(canvas, "背词", u, w, h)
        if (step.kind == GuideAnimation.Kind.STUDY_CARD || step.kind == GuideAnimation.Kind.MIX_POOL) {
            label(canvas, "新词队列", w * 0.5f, h * 0.28f, u * 16f, Paper.ink, Paint.Align.CENTER, bold = true)
            val left = RectF(w * 0.07f, h * 0.38f, w * 0.42f, h * 0.54f)
            val right = RectF(w * 0.58f, h * 0.38f, w * 0.93f, h * 0.54f)
            panel(canvas, left, u * 10f, Paper.lightGreen, null)
            panel(canvas, right, u * 10f, Paper.lightGreen, null)
            label(canvas, "个人生词", left.centerX(), left.centerY() + u * 2f, u * 13f, Paper.green, Paint.Align.CENTER, bold = true)
            label(canvas, "原书词", right.centerX(), right.centerY() - u * 3f, u * 13f, Paper.green, Paint.Align.CENTER, bold = true)
            label(canvas, "无需先查", right.centerX(), right.centerY() + u * 12f, u * 10f, Paper.secondary, Paint.Align.CENTER)
            arrow(canvas, left.centerX(), h * 0.56f, w * 0.40f, h * 0.68f, frame.highlight, u)
            arrow(canvas, right.centerX(), h * 0.56f, w * 0.60f, h * 0.68f, frame.highlight, u)
            if (frame.resultVisible) {
                panel(canvas, RectF(w * 0.17f, h * 0.68f, w * 0.83f, h * 0.82f), u * 10f, Paper.surface, Paper.green)
                label(canvas, "同一队列 · 默认合计 20", w * 0.5f, h * 0.755f, u * 14f, Paper.green, Paint.Align.CENTER, bold = true)
                label(canvas, "约 1:1；一池不足由另一池补足", w * 0.5f, h * 0.80f, u * 10f, Paper.secondary, Paint.Align.CENTER)
            }
            label(canvas, "没查过的原书词也能背", w * 0.5f, h * 0.89f, u * 11f, Paper.secondary, Paint.Align.CENTER)
            return
        }

        val card = RectF(w * 0.07f, h * 0.20f, w * 0.93f, h * 0.60f)
        panel(canvas, card, u * 10f, Paper.surface, Paper.line)
        label(canvas, "orbit", w * 0.5f, h * 0.36f, u * 24f, Paper.ink, Paint.Align.CENTER, bold = true)
        chip(canvas, "原书词", w * 0.50f, h * 0.43f, u * 11f, Paper.lightGreen, Paper.green, u)
        label(canvas, "到期复习优先 · 同一份进度", w * 0.5f, h * 0.51f, u * 11f, Paper.secondary, Paint.Align.CENTER)
        val buttons = listOf(
            Triple("认识", w * 0.10f, w * 0.37f),
            Triple("模糊", w * 0.39f, w * 0.66f),
            Triple("忘记", w * 0.68f, w * 0.95f)
        )
        val pressed = when (step.kind) {
            GuideAnimation.Kind.KNOW -> 0
            GuideAnimation.Kind.FUZZY -> 1
            GuideAnimation.Kind.FORGET -> 2
            else -> -1
        }
        buttons.forEachIndexed { index, entry ->
            val text = entry.first
            val active = index == pressed && frame.highlight > 0.25f
            val button = RectF(entry.second, h * 0.70f, entry.third, h * 0.82f)
            fill.color = when {
                !active -> Paper.lightGreen
                index == 2 -> Paper.error
                else -> Paper.green
            }
            canvas.drawRoundRect(button, u * 8f, u * 8f, fill)
            label(
                canvas, text, button.centerX(), button.centerY() + u * 5f,
                u * 13f,
                if (active) Color.WHITE else Paper.green,
                Paint.Align.CENTER,
                bold = active
            )
        }
        if (frame.resultVisible && pressed >= 0) {
            val outcome = when (pressed) {
                0 -> "下次：1 天后（之后 3/7/14/30 递增）"
                1 -> "下次：1 天后"
                else -> "10 分钟后重新学"
            }
            chip(canvas, outcome, w * 0.5f, h * 0.90f, u * 11f, Paper.lightGreen, Paper.green, u)
        }
    }

    // ---------------------------------------------------------------- lookup and supplement

    private fun drawLookupSupplement(
        canvas: Canvas, frame: GuideAnimation.Frame, u: Float, w: Float, h: Float
    ) {
        when (step.kind) {
            GuideAnimation.Kind.LOOKUP_INPUT -> {
                header(canvas, "查词", u, w, h)
                label(canvas, "手动输入（不依赖悬浮权限）", w * 0.09f, h * 0.28f, u * 11f, Paper.secondary)
                val input = RectF(w * 0.08f, h * 0.33f, w * 0.72f, h * 0.43f)
                panel(canvas, input, u * 8f, Paper.surface, Paper.line)
                label(canvas, "meadow", input.left + u * 10f, input.centerY() + u * 5f, u * 15f, Paper.ink)
                val query = RectF(w * 0.74f, h * 0.33f, w * 0.93f, h * 0.43f)
                textButton(canvas, query, "查询", true, false, u)
                label(canvas, "点查询或键盘搜索键", w * 0.09f, h * 0.58f, u * 11f, Paper.secondary)
            }
            GuideAnimation.Kind.LOOKUP_SUCCESS -> {
                header(canvas, "查词结果", u, w, h)
                resultCard(
                    canvas, frame, u, w, h,
                    word = "meadow",
                    meaning = "n. 草地；牧草地",
                    tag = "真实查询成功 · 已收录",
                    note = "失败或未命中不生成释义"
                )
            }
            GuideAnimation.Kind.SUPPLEMENT_EDIT,
            GuideAnimation.Kind.SUPPLEMENT_SAVE -> {
                header(canvas, "添加 / 补词", u, w, h)
                label(canvas, "词头", w * 0.09f, h * 0.25f, u * 11f, Paper.secondary)
                val head = RectF(w * 0.08f, h * 0.27f, w * 0.70f, h * 0.35f)
                panel(canvas, head, u * 8f, Paper.surface, Paper.line)
                label(canvas, "meadow", head.left + u * 9f, head.centerY() + u * 5f, u * 14f, Paper.ink)
                chip(canvas, "读取词头", w * 0.82f, h * 0.31f, u * 10f, Paper.lightGreen, Paper.green, u)
                field(canvas, "词性 n.", "n.", w * 0.08f, h * 0.41f, w * 0.92f, u)
                field(canvas, "释义", "草地（我的补充）", w * 0.08f, h * 0.53f, w * 0.92f, u)
                val save = RectF(w * 0.08f, h * 0.76f, w * 0.42f, h * 0.87f)
                val saved = step.kind == GuideAnimation.Kind.SUPPLEMENT_SAVE && frame.resultVisible
                textButton(canvas, save, if (saved) "已保存" else "保存补词", saved, false, u)
                if (saved) {
                    label(canvas, "已保存到本机个人补充", w * 0.47f, h * 0.83f, u * 12f, Paper.green)
                    chip(canvas, "不计查询", w * 0.21f, h * 0.94f, u * 10f, Paper.lightGreen, Paper.green, u)
                    chip(canvas, "不收录", w * 0.45f, h * 0.94f, u * 10f, Paper.lightGreen, Paper.green, u)
                    chip(canvas, "不进复习", w * 0.69f, h * 0.94f, u * 10f, Paper.lightGreen, Paper.green, u)
                } else {
                    label(canvas, "只写本机个人词义", w * 0.5f, h * 0.93f, u * 11f, Paper.secondary, Paint.Align.CENTER)
                }
            }
            GuideAnimation.Kind.CONTRAST -> {
                header(canvas, "两件事的区别", u, w, h)
                val left = RectF(w * 0.05f, h * 0.22f, w * 0.48f, h * 0.86f)
                val right = RectF(w * 0.52f, h * 0.22f, w * 0.95f, h * 0.86f)
                panel(canvas, left, u * 10f, Paper.surface, Paper.green)
                panel(canvas, right, u * 10f, Paper.surface, Paper.line)
                label(canvas, "真实查询", left.centerX(), h * 0.32f, u * 15f, Paper.green, Paint.Align.CENTER, bold = true)
                label(canvas, "保存补词", right.centerX(), h * 0.32f, u * 15f, Paper.ink, Paint.Align.CENTER, bold = true)
                listOf("成功才收录", "计入查询", "进入复习").forEachIndexed { index, value ->
                    label(canvas, "✓ $value", left.left + u * 10f, h * (0.46f + 0.12f * index), u * 12f, Paper.ink)
                }
                listOf("只写个人释义", "不计查询", "不收录").forEachIndexed { index, value ->
                    label(canvas, "• $value", right.left + u * 10f, h * (0.46f + 0.12f * index), u * 12f, Paper.ink)
                }
                label(canvas, "两件事不能互相替代", w * 0.5f, h * 0.92f, u * 11f, Paper.secondary, Paint.Align.CENTER)
            }
            else -> Unit
        }
    }

    private fun field(
        canvas: Canvas, hint: String, value: String,
        left: Float, top: Float, right: Float, u: Float
    ) {
        val rect = RectF(left, top, right, top + u * 22f)
        panel(canvas, rect, u * 8f, Paper.surface, Paper.line)
        label(canvas, hint, rect.left + u * 9f, rect.centerY() + u * 4f, u * 11f, Paper.secondary)
        label(canvas, value, rect.left + u * 55f, rect.centerY() + u * 5f, u * 13f, Paper.ink)
    }

    // ---------------------------------------------------------------- shared pieces

    private fun resultCard(
        canvas: Canvas, frame: GuideAnimation.Frame, u: Float, w: Float, h: Float,
        word: String, meaning: String, tag: String, note: String
    ) {
        fill.color = Color.argb(70, 36, 47, 44)
        canvas.drawRect(0f, 0f, w, h, fill)
        val scale = 0.86f + 0.14f * frame.highlight
        val cx = w * 0.5f
        val cy = h * 0.54f
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        val card = RectF(w * 0.10f, h * 0.28f, w * 0.90f, h * 0.78f)
        panel(canvas, card, u * 12f, Paper.surface, Paper.line)
        val alpha = (255 * frame.highlight).toInt().coerceIn(0, 255)
        label(canvas, word, w * 0.15f, h * 0.40f, u * 22f, withAlpha(Paper.ink, alpha), bold = true)
        label(canvas, meaning, w * 0.15f, h * 0.49f, u * 13f, withAlpha(Paper.secondary, alpha))
        chip(canvas, tag, w * 0.50f, h * 0.60f, u * 11f, withAlpha(Paper.lightGreen, alpha), withAlpha(Paper.green, alpha), u)
        label(canvas, note, w * 0.15f, h * 0.70f, u * 11f, withAlpha(Paper.secondary, alpha))
        canvas.restore()
    }

    private fun header(canvas: Canvas, title: String, u: Float, w: Float, h: Float) {
        label(canvas, title, w * 0.08f, h * 0.20f, u * 17f, Paper.ink, bold = true)
        stroke.color = Paper.line
        stroke.strokeWidth = u
        canvas.drawLine(w * 0.07f, h * 0.225f, w * 0.93f, h * 0.225f, stroke)
    }

    private fun pageLines(canvas: Canvas, left: Float, top: Float, right: Float, u: Float, w: Float, h: Float) {
        fill.color = Paper.line
        listOf(0.00f, 0.10f, 0.20f).forEach { offset ->
            canvas.drawRoundRect(
                RectF(w * left, h * (top + offset), w * right, h * (top + offset) + u * 7f),
                u * 3f, u * 3f, fill
            )
        }
    }

    private fun badge(canvas: Canvas, text: String, cx: Float, cy: Float, u: Float) {
        val width = measure(text, u * 10f, true) + u * 14f
        val rect = RectF(cx - width / 2f, cy - u * 8f, cx + width / 2f, cy + u * 8f)
        fill.color = Paper.lightGreen
        canvas.drawRoundRect(rect, u * 8f, u * 8f, fill)
        label(canvas, text, cx, cy + u * 4f, u * 10f, Paper.green, Paint.Align.CENTER, bold = true)
    }

    private fun chip(
        canvas: Canvas, text: String, cx: Float, cy: Float, size: Float,
        background: Int, foreground: Int, u: Float
    ): RectF {
        val width = measure(text, size, false) + u * 16f
        val rect = RectF(cx - width / 2f, cy - u * 9f, cx + width / 2f, cy + u * 9f)
        fill.color = background
        canvas.drawRoundRect(rect, u * 9f, u * 9f, fill)
        label(canvas, text, cx, cy + size * 0.35f, size, foreground, Paint.Align.CENTER)
        return rect
    }

    private fun panel(canvas: Canvas, rect: RectF, radius: Float, color: Int, border: Int?) {
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (border != null) {
            stroke.color = border
            stroke.strokeWidth = radius / 10f
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }
    }

    private fun textButton(
        canvas: Canvas, rect: RectF, text: String,
        primary: Boolean, dangerous: Boolean, u: Float
    ) {
        fill.color = if (primary) {
            if (dangerous) Paper.error else Paper.green
        } else {
            Paper.lightGreen
        }
        canvas.drawRoundRect(rect, u * 8f, u * 8f, fill)
        label(
            canvas, text, rect.centerX(), rect.centerY() + u * 5f, u * 13f,
            if (primary) Color.WHITE else Paper.green,
            Paint.Align.CENTER,
            bold = primary
        )
    }

    private fun arrow(
        canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float,
        progress: Float, u: Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f) return
        val x = startX + (endX - startX) * p
        val y = startY + (endY - startY) * p
        stroke.color = Paper.green
        stroke.strokeWidth = u * 2f
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(startX, startY, x, y, stroke)
        if (p > 0.9f) {
            fill.color = Paper.green
            canvas.drawCircle(endX, endY, u * 4f, fill)
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawFinger(
        canvas: Canvas, frame: GuideAnimation.Frame, u: Float, w: Float, h: Float
    ) {
        if (!frame.fingerVisible) return
        val cx = w * (fingerXOverride ?: frame.fingerX)
        val cy = h * frame.fingerY
        val stylus = step.kind == GuideAnimation.Kind.DRAG || step.kind == GuideAnimation.Kind.LIFT
        fill.color = Color.argb(36, 39, 101, 82)
        canvas.drawCircle(cx, cy, u * 17f, fill)
        if (stylus) {
            stroke.color = Paper.ink
            stroke.strokeWidth = u * 5f
            stroke.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx + u * 9f, cy - u * 27f, cx, cy, stroke)
            stroke.strokeCap = Paint.Cap.BUTT
            fill.color = Paper.green
            canvas.drawCircle(cx, cy, u * 3.5f, fill)
        } else {
            val radius = if (frame.fingerPressed) u * 8f else u * 9f
            fill.color = Paper.ink
            canvas.drawCircle(cx, cy, radius, fill)
            stroke.color = Color.WHITE
            stroke.strokeWidth = u * 2f
            canvas.drawCircle(cx, cy, radius, stroke)
            if (frame.fingerPressed) {
                stroke.color = withAlpha(Paper.green, 180)
                stroke.strokeWidth = u * 2f
                canvas.drawCircle(cx, cy, radius + u * 5f, stroke)
            }
        }
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, progress: Float, u: Float) {
        starPath.reset()
        for (index in 0 until 10) {
            val angle = Math.toRadians((-90 + index * 36).toDouble())
            val r = if (index % 2 == 0) radius else radius * 0.45f
            val x = cx + (r.toDouble() * Math.cos(angle)).toFloat()
            val y = cy + (r.toDouble() * Math.sin(angle)).toFloat()
            if (index == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
        }
        starPath.close()
        if (progress > 0.05f) {
            fill.color = withAlpha(Color.rgb(214, 162, 45), (255 * progress).toInt().coerceIn(0, 255))
            canvas.drawPath(starPath, fill)
        }
        stroke.color = Color.rgb(214, 162, 45)
        stroke.strokeWidth = u * 1.8f
        canvas.drawPath(starPath, stroke)
    }

    private fun label(
        canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false
    ) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        textPaint.typeface = if (bold) boldFace else normalFace
        canvas.drawText(value, x, y, textPaint)
    }

    private fun measure(value: String, size: Float, bold: Boolean): Float {
        textPaint.textSize = size
        textPaint.typeface = if (bold) boldFace else normalFace
        return textPaint.measureText(value)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(from: Int, to: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )
    }
}
