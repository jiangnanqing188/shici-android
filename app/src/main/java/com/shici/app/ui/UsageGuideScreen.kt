package com.shici.app.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import com.shici.app.core.GuideTopic
import com.shici.app.core.UsageGuide

/**
 * Standalone, repeatable usage-guide page. Pure presentation: no repository, no application object,
 * no permissions, no preferences and no side effects. Every action is a user tap that calls
 * [onNavigate] with a stable route string from `GuideRoutes`; nothing on this page can enable
 * capture, query a word, download a model, delete data or restore a backup by itself.
 *
 * Topics are collapsed by default: each card shows only its title and one-line summary, and the
 * steps, notes and page hand-offs appear after the user expands that card. A valid [initialTopicId]
 * is expanded during construction before the page scrolls to it, so a deep link never lands on
 * hidden text. Permission and automation notes live in one collapsed disclosure at the bottom
 * instead of being stacked above the list.
 *
 * @param firstRun show the non-blocking first-use banner. The host decides this from its own
 *   `guide_seen_v1` state; this screen never reads or writes that preference.
 * @param initialTopicId optional deep link; that topic starts expanded, then the page scrolls to it.
 * @param onNavigate explicit hand-off to another page; route strings match Activity page names.
 * @param onDismissFirstRun called only after the user taps "skip" or "done"; the integrator owns
 *   persisting `UsageGuide.SEEN_PREFERENCE_KEY` there. It records intent only, never navigates.
 * @param onBack explicit return to the host page; this screen never navigates on construction.
 */
class UsageGuideScreen(
    context: Context,
    firstRun: Boolean = false,
    initialTopicId: String? = null,
    private val onNavigate: (String) -> Unit,
    private val onDismissFirstRun: () -> Unit,
    private val onBack: () -> Unit
) {
    /** Complete scrollable page body. No sidebar and no root padding; the host body supplies those. */
    val view: View

    init {
        val content = context.column()
        content.addView(context.text(UsageGuide.TITLE, 28f, Paper.ink, true))
        content.space(4)
        content.addView(context.text(UsageGuide.INTRO, 16f, Paper.secondary))
        content.space(12)

        if (firstRun) {
            content.addView(firstRunBanner(context))
            content.space(12)
        }

        val topicViews = LinkedHashMap<String, View>()
        UsageGuide.topics.forEach { topic ->
            // Deep links expand their topic before the first layout instead of scrolling to a
            // GONE body; every other topic stays scannable as title + summary only.
            val card = topicCard(context, topic, expanded = topic.id == initialTopicId)
            topicViews[topic.id] = card
            content.addView(card)
            content.space(12)
        }

        // Complete permission/automation details stay available, but not stacked above the list.
        content.addView(
            context.helpDisclosure("权限与自动操作说明") {
                addView(context.text(UsageGuide.NO_PERMISSION_FALLBACK, 15f, Paper.green))
                addView(context.text(UsageGuide.NO_AUTOMATION, 15f, Paper.secondary))
            }
        )
        content.space(12)

        if (firstRun) {
            // Both "skip" and "done" are explicit dismissals; the host persists the flag in its own
            // callback and then returns. Nothing is saved merely by opening this page.
            content.addView(context.button(UsageGuide.FIRST_RUN_DONE_LABEL, true) {
                onDismissFirstRun()
                onBack()
            })
        } else {
            content.addView(context.button(UsageGuide.BACK_LABEL, true) { onBack() })
        }
        content.space(16)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        view = scroll

        // The target topic is already expanded above, so this only positions it on screen.
        val anchor = initialTopicId?.let { topicViews[it] }
        if (anchor != null) {
            scroll.post { scroll.smoothScrollTo(0, anchor.top) }
        }
    }

    private fun firstRunBanner(context: Context): View = context.column().apply {
        setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
        background = context.shape(Paper.surface, 12, true)
        addView(context.text(UsageGuide.FIRST_RUN_TITLE, 20f, Paper.green, true))
        addView(context.text(UsageGuide.FIRST_RUN_MESSAGE, 15f, Paper.secondary))
        addView(context.button(UsageGuide.FIRST_RUN_SKIP_LABEL) {
            onDismissFirstRun()
            onBack()
        })
    }

    private fun topicCard(context: Context, topic: GuideTopic, expanded: Boolean): View {
        val card = context.column().apply {
            setPadding(context.dp(20), context.dp(16), context.dp(20), context.dp(16))
            background = context.shape(Paper.surface, 12, true)
        }
        card.addView(context.text(topic.title, 20f, Paper.ink, true))
        card.addView(context.text(topic.summary, 15f, Paper.secondary))

        val body = context.column().apply {
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        if (topic.steps.isNotEmpty()) {
            body.space(4)
            topic.steps.forEachIndexed { index, step ->
                body.addView(context.text("${index + 1}. $step", 15f, Paper.ink))
            }
        }
        topic.notes.forEach { note ->
            body.space(4)
            body.addView(context.text(note, 14f, Paper.warning))
        }
        if (topic.actions.isNotEmpty()) {
            body.space(8)
            topic.actions.forEach { action ->
                body.addView(context.button(action.label) { onNavigate(action.route) })
            }
        }

        lateinit var toggle: Button
        fun applyState(expand: Boolean) {
            body.visibility = if (expand) View.VISIBLE else View.GONE
            toggle.text = if (expand) "收起步骤与操作 ▴" else "展开步骤与操作 ▾"
            toggle.contentDescription = "${topic.title}：" +
                (if (expand) "步骤与操作已展开，点按收起" else "步骤与操作已收起，点按展开")
        }
        toggle = context.button(if (expanded) "收起步骤与操作 ▴" else "展开步骤与操作 ▾") {
            applyState(body.visibility != View.VISIBLE)
        }
        toggle.contentDescription = "${topic.title}：" +
            (if (expanded) "步骤与操作已展开，点按收起" else "步骤与操作已收起，点按展开")
        card.addView(toggle, android.widget.LinearLayout.LayoutParams(-1, -2))
        card.addView(body, android.widget.LinearLayout.LayoutParams(-1, -2))
        return card
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
