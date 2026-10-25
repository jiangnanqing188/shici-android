package com.shici.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shici.app.core.GuideAnimation
import com.shici.app.core.GuideTopic
import com.shici.app.core.UsageGuide

/**
 * Standalone, repeatable usage guide. The main area shows one real native demonstration step at a
 * time: a fictional screen, a finger or pen tip, a highlight that follows the word, the lift and the
 * resulting card. The steps only illustrate existing behaviour with invented words; this screen has
 * no repository, dictionary, permissions, downloads or preferences, and no demo control performs a
 * query or writes a study record.
 *
 * The long text help remains available as a secondary collapsed disclosure. Old topic ids and the
 * [UsageGuide.SEEN_PREFERENCE_KEY] contract are unchanged: only an explicit "skip" or "done" tap
 * calls [onDismissFirstRun]; opening the page, choosing a scene or replaying a step never does.
 *
 * Lifecycle: the integrator should call [onHostResume]/[onHostPause] from the Activity lifecycle and
 * [close] when the page is destroyed. The illustration also stops on its own when the view detaches;
 * a single bounded animator runs at a time, never a loop or a posted polling callback.
 *
 * @param firstRun show the non-blocking first-use banner. The host still owns the seen preference.
 * @param initialTopicId optional deep link. Topics with a demonstration select that scene; the
 *   remaining topics expand the long-help section and that topic instead of silently doing nothing.
 * @param onNavigate explicit user hand-off to another page; a stable `GuideRoutes` string.
 * @param onDismissFirstRun called only from the user's skip/done tap; the integrator persists it.
 * @param onBack explicit return to the host page; this screen never navigates on construction.
 */
class UsageGuideScreen(
    private val context: Context,
    firstRun: Boolean = false,
    private val initialTopicId: String? = null,
    private val onNavigate: (String) -> Unit,
    private val onDismissFirstRun: () -> Unit,
    private val onBack: () -> Unit
) {
    /** Complete scrollable page body. No sidebar and no root padding; the host body supplies those. */
    val view: View

    private val initialDemo: GuideAnimation.Demo? = GuideAnimation.demoForTopic(initialTopicId)
    private var selectedDemo: GuideAnimation.Demo = initialDemo ?: GuideAnimation.Demo.CAPTURE
    private var stepIndex: Int = 0
    private var helpExpanded: Boolean = false

    private val sceneButtons = LinkedHashMap<GuideAnimation.Demo, Button>()
    private val topicCards = LinkedHashMap<String, TopicCard>()

    private lateinit var animationView: GuideAnimationView
    private lateinit var demoTitle: TextView
    private lateinit var demoSummary: TextView
    private lateinit var stepCounter: TextView
    private lateinit var promptView: TextView
    private lateinit var staticHint: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var replayButton: Button
    private lateinit var useButton: Button
    private lateinit var helpToggle: Button
    private lateinit var helpBody: LinearLayout
    private lateinit var scroll: ScrollView

    /** Selected demonstration scene, exposed read-only for host checks. */
    val shownDemo: GuideAnimation.Demo get() = selectedDemo

    /** Zero-based index of the step currently shown. */
    val shownStepIndex: Int get() = stepIndex

    /** Stable id of the step currently shown. */
    val shownStepId: String get() = currentStep.id

    private val currentStep: GuideAnimation.Step
        get() = GuideAnimation.step(selectedDemo, stepIndex)

    private val currentSteps: List<GuideAnimation.Step>
        get() = GuideAnimation.demo(selectedDemo).steps

    init {
        val content = context.column()
        content.addView(context.text(UsageGuide.TITLE, 28f, Paper.ink, true))
        content.space(4)
        content.addView(context.text(UsageGuide.INTRO, 16f, Paper.secondary))
        content.space(12)

        if (firstRun) {
            content.addView(firstRunBanner())
            content.space(12)
        }

        val demo = demoSection()
        content.addView(demo)
        content.space(16)

        // Primary exits sit right after the demonstration; the long text help is secondary and
        // collapsed at the bottom so it cannot push "跳过/完成" out of easy reach.
        content.addView(controlFooter())
        content.space(16)
        content.addView(longHelpSection())
        content.space(16)

        scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        view = scroll

        selectScene(selectedDemo, restart = false)

        // A deep link never lands on hidden content: demo topics select their scene, text topics
        // expand the long-help disclosure and that topic before scrolling.
        val linkedDemo = initialDemo
        val linkedTopic = initialTopicId
        if (linkedDemo != null) {
            scrollToDescendant(demo)
        } else if (linkedTopic != null) {
            setHelpExpanded(true)
            topicCards[linkedTopic]?.setExpanded(true)
            topicCards[linkedTopic]?.let { scrollToDescendant(it.card) }
        }
    }

    /** Host foreground hook: restarts the single bounded current animation. */
    fun onHostResume() {
        animationView.onHostResume()
    }

    /** Host background hook: cancels the animation; nothing continues in background. */
    fun onHostPause() {
        animationView.onHostPause()
    }

    /** Page destroyed: permanent stop; repeated calls are harmless. */
    fun close() {
        animationView.close()
    }

    // ---------------------------------------------------------------- demo area

    private fun demoSection(): View {
        val card = context.column().apply {
            setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
            background = context.shape(Paper.surface, 12, true)
        }

        val entries = GuideAnimation.Demo.values().toList()
        for (rowIndex in 0 until 2) {
            val row = context.row()
            for (columnIndex in 0 until 2) {
                val index = rowIndex * 2 + columnIndex
                if (index >= entries.size) break
                val demo = entries[index]
                val button = context.button("${index + 1}. ${demo.shortLabel}") { selectScene(demo) }
                button.contentDescription = "查看${demo.title}"
                sceneButtons[demo] = button
                row.addView(
                    button,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (columnIndex == 0) marginEnd = context.dp(8)
                    }
                )
            }
            card.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (rowIndex == 0) card.space(8)
        }
        card.space(8)

        demoTitle = context.text(selectedDemo.title, 21f, Paper.ink, true)
        demoSummary = context.text(GuideAnimation.demo(selectedDemo).summary, 15f, Paper.secondary)
        card.addView(demoTitle)
        card.addView(demoSummary)
        card.addView(context.text(UsageGuide.DEMO_NOTE, 12f, Paper.warning))

        staticHint = context.text(UsageGuide.STATIC_HINT, 13f, Paper.warning).apply {
            visibility = View.GONE
        }
        animationView = GuideAnimationView(context, currentStep)
        card.addView(animationView, LinearLayout.LayoutParams(-1, context.dp(250)))
        animationView.setMotionModeListener { disabled ->
            staticHint.visibility = if (disabled) View.VISIBLE else View.GONE
        }
        card.addView(staticHint)

        stepCounter = context.text("", 13f, Paper.secondary)
        promptView = context.text("", 17f, Paper.ink)
        card.addView(stepCounter)
        card.addView(promptView)

        val navigation = context.row()
        previousButton = context.button(UsageGuide.PREV_LABEL) {
            if (stepIndex > 0) {
                stepIndex -= 1
                applyStep()
            }
        }
        nextButton = context.button(UsageGuide.NEXT_LABEL, true) {
            if (stepIndex < currentSteps.lastIndex) {
                stepIndex += 1
                applyStep()
            }
        }
        replayButton = context.button(UsageGuide.REPLAY_LABEL) { animationView.replay() }
        navigation.addView(
            previousButton,
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) }
        )
        navigation.addView(
            nextButton,
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) }
        )
        navigation.addView(replayButton, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(navigation)
        card.space(8)

        // "去使用" is a separate, explicit page hand-off: it never dismisses the guide and never
        // performs a query, permission request or write. Full width keeps long labels uncut.
        useButton = context.button("${UsageGuide.USE_LABEL_PREFIX}${selectedDemo.action.label}") {
            onNavigate(selectedDemo.action.route)
        }
        card.addView(useButton, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun selectScene(demo: GuideAnimation.Demo, restart: Boolean = true) {
        val sceneChanged = selectedDemo != demo
        selectedDemo = demo
        if (sceneChanged || restart) stepIndex = 0
        demoTitle.text = demo.title
        demoSummary.text = GuideAnimation.demo(demo).summary
        useButton.text = "${UsageGuide.USE_LABEL_PREFIX}${demo.action.label}"
        useButton.contentDescription = "去使用：${demo.action.label}；只导航，不记录引导状态"
        sceneButtons.forEach { (candidate, button) ->
            val selected = candidate == demo
            button.background = context.shape(if (selected) Paper.green else Paper.lightGreen, 8)
            button.setTextColor(if (selected) Color.WHITE else Paper.green)
            button.contentDescription = if (selected) {
                "查看${candidate.title}，当前选中"
            } else {
                "查看${candidate.title}"
            }
        }
        applyStep()
    }

    private fun applyStep() {
        val step = currentStep
        animationView.showStep(step)
        stepCounter.text = "第 ${stepIndex + 1} / ${currentSteps.size} 步"
        promptView.text = step.prompt
        promptView.contentDescription = "第 ${stepIndex + 1} 步，共 ${currentSteps.size} 步：${step.prompt}"
        previousButton.isEnabled = stepIndex > 0
        previousButton.alpha = if (previousButton.isEnabled) 1f else 0.45f
        nextButton.isEnabled = stepIndex < currentSteps.lastIndex
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.45f
        replayButton.contentDescription = "重播第 ${stepIndex + 1} 步：${step.prompt}"
        previousButton.contentDescription = if (previousButton.isEnabled) {
            "上一步，返回第 $stepIndex 步"
        } else {
            "已是第一步"
        }
        nextButton.contentDescription = if (nextButton.isEnabled) {
            "下一步，查看第 ${stepIndex + 2} 步"
        } else {
            "已是最后一步"
        }
    }

    private fun firstRunBanner(): View = context.column().apply {
        setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
        background = context.shape(Paper.lightGreen, 12)
        addView(context.text(UsageGuide.FIRST_RUN_TITLE, 20f, Paper.green, true))
        addView(context.text(UsageGuide.FIRST_RUN_MESSAGE, 15f, Paper.secondary))
    }

    // ---------------------------------------------------------------- long help

    private fun longHelpSection(): View {
        val container = context.column()
        helpBody = context.column().apply { visibility = View.GONE }
        helpBody.addView(context.text(UsageGuide.LONG_HELP_DESCRIPTION, 14f, Paper.secondary))
        helpBody.space(8)

        UsageGuide.topics.forEach { topic ->
            val card = topicCard(topic, expanded = topic.id == initialTopicId)
            topicCards[topic.id] = card
            helpBody.addView(card.card)
            helpBody.space(10)
        }

        helpBody.addView(
            context.helpDisclosure("权限与自动操作说明") {
                addView(context.text(UsageGuide.NO_PERMISSION_FALLBACK, 15f, Paper.green))
                addView(context.text(UsageGuide.NO_AUTOMATION, 15f, Paper.secondary))
            }
        )

        helpToggle = context.button(longHelpToggleLabel(false)) { setHelpExpanded(!helpExpanded) }
        container.addView(helpToggle, LinearLayout.LayoutParams(-1, -2))
        container.addView(helpBody, LinearLayout.LayoutParams(-1, -2))
        updateHelpToggle()
        return container
    }

    private fun topicCard(topic: GuideTopic, expanded: Boolean): TopicCard {
        val card = context.column().apply {
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            background = context.shape(Paper.surface, 10, true)
        }
        card.addView(context.text(topic.title, 18f, Paper.ink, true))
        card.addView(context.text(topic.summary, 14f, Paper.secondary))

        val body = context.column().apply { visibility = View.GONE }
        topic.steps.forEachIndexed { index, step ->
            body.addView(context.text("${index + 1}. $step", 14f, Paper.ink))
        }
        topic.notes.forEach { note ->
            body.space(4)
            body.addView(context.text(note, 13f, Paper.warning))
        }
        if (topic.actions.isNotEmpty()) {
            body.space(8)
            body.addView(context.text("真实页面入口（只有你点按才会导航）：", 12f, Paper.secondary))
            topic.actions.forEach { action ->
                body.addView(context.button(action.label) { onNavigate(action.route) })
            }
        }

        var holder: TopicCard? = null
        val toggle = context.button("展开说明 ▾") {
            holder?.setExpanded(body.visibility != View.VISIBLE)
        }
        card.addView(toggle)
        card.addView(body)
        val result = TopicCard(card, body, toggle, topic.title)
        holder = result
        result.setExpanded(expanded)
        return result
    }

    private class TopicCard(
        val card: View,
        private val body: View,
        private val toggle: Button,
        private val title: String
    ) {
        fun setExpanded(expand: Boolean) {
            body.visibility = if (expand) View.VISIBLE else View.GONE
            toggle.text = if (expand) "收起说明 ▴" else "展开说明 ▾"
            toggle.contentDescription = "$title：${if (expand) "说明已展开，点按收起" else "说明已收起，点按展开"}"
        }
    }

    private fun setHelpExpanded(expand: Boolean) {
        helpExpanded = expand
        helpBody.visibility = if (expand) View.VISIBLE else View.GONE
        helpToggle.text = longHelpToggleLabel(expand)
        updateHelpToggle()
    }

    private fun longHelpToggleLabel(expand: Boolean): String =
        if (expand) "${UsageGuide.LONG_HELP_TITLE} ▴" else "${UsageGuide.LONG_HELP_TITLE} ▾"

    private fun updateHelpToggle() {
        helpToggle.contentDescription = "${UsageGuide.LONG_HELP_TITLE}，当前" +
            (if (helpExpanded) "已展开，点按收起" else "已收起，点按展开")
    }

    // ---------------------------------------------------------------- footer and scrolling

    private fun controlFooter(): View {
        val footer = context.column()
        val row = context.row()
        val skip = context.button(UsageGuide.SKIP_LABEL) { dismissAndBack() }
        skip.contentDescription = "跳过引导并返回；会记录以后不再自动提示"
        val done = context.button(UsageGuide.DONE_LABEL, true) { dismissAndBack() }
        done.contentDescription = "完成引导并返回；会记录以后不再自动提示"
        row.addView(
            skip,
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) }
        )
        row.addView(done, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(row)
        return footer
    }

    private fun dismissAndBack() {
        // Explicit user dismissal only: never reached from init, scene switching or animation.
        onDismissFirstRun()
        onBack()
    }

    private fun scrollToDescendant(target: View) {
        scroll.post {
            // One positioning pass only, and only while still attached: no polling or repeating post.
            if (!scroll.isAttachedToWindow || !target.isAttachedToWindow) return@post
            val rect = Rect()
            target.getDrawingRect(rect)
            // ScrollView's single child is a ViewGroup; the coordinate helper lives on ViewGroup.
            val content = scroll.getChildAt(0) as? ViewGroup
            if (content != null) {
                content.offsetDescendantRectToMyCoords(target, rect)
                scroll.smoothScrollTo(0, rect.top)
            }
        }
    }
}

/**
 * Entry points the integrator can place in existing pages without subclassing or changing this file.
 * Neither helper reads state or persists anything: the HOME host decides whether to show the card and
 * writes `guide_seen_v1` only in its explicit [onDismiss] callback; Settings supplies [onOpenGuide].
 */
object UsageGuideEntries {

    /**
     * Non-blocking HOME newcomer card. Call only when the host's own `guide_seen_v1` flag is unset.
     * One short sentence plus the two existing exits keeps the home page scannable; the full steps
     * remain in the guide. Tapping "open" and "skip" are the only exits, and the card never opens
     * itself or performs work.
     */
    fun homeCard(
        context: Context,
        onOpenGuide: () -> Unit,
        onDismiss: () -> Unit
    ): View = context.column().apply {
        setPadding(context.dp(20), context.dp(16), context.dp(20), context.dp(16))
        background = context.shape(Paper.lightGreen, 12)
        addView(context.text(UsageGuide.FIRST_RUN_TITLE, 20f, Paper.green, true))
        addView(
            context.text(
                "先从首页开启悬浮查词，再划词或手动查词；随时可看完整引导。",
                15f,
                Paper.green
            )
        )
        addView(context.button("查看引导", true) { onOpenGuide() })
        addView(context.button("以后再看") {
            // Existing explicit-dismiss callback; persistence belongs to the host.
            onDismiss()
        })
    }

    /** Settings help entry. Clicking the row asks the host to open the full guide page. */
    fun settingsEntry(
        context: Context,
        onOpenGuide: () -> Unit
    ): View = context.column().apply {
        setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
        background = context.shape(Paper.surface, 10, true)
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(64)
        contentDescription = "打开使用引导"
        setOnClickListener { onOpenGuide() }
        addView(context.text(UsageGuide.SETTINGS_ENTRY_TITLE, 18f, Paper.ink, true))
        addView(context.text(UsageGuide.SETTINGS_ENTRY_DESCRIPTION, 15f, Paper.secondary))
        addView(context.text("打开 ›", 16f, Paper.green, true))
    }
}
