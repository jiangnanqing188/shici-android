package com.shici.app.ui

import android.content.Context
import android.view.View
import android.widget.ScrollView
import com.shici.app.core.GuideTopic
import com.shici.app.core.UsageGuide

/**
 * Standalone, repeatable usage-guide page. Pure presentation: no repository, no application object,
 * no permissions, no preferences and no side effects. Every action is a user tap that calls
 * [onNavigate] with a stable route string from `GuideRoutes`; nothing on this page can enable
 * capture, query a word, download a model, delete data or restore a backup by itself.
 *
 * @param firstRun show the non-blocking first-use banner. The host decides this from its own
 *   `guide_seen_v1` state; this screen never reads or writes that preference.
 * @param initialTopicId optional deep link; the page scrolls to that topic after layout.
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
        content.addView(fallbackNotice(context))
        content.space(12)

        if (firstRun) {
            content.addView(firstRunBanner(context))
            content.space(12)
        }

        val topicViews = LinkedHashMap<String, View>()
        UsageGuide.topics.forEach { topic ->
            val card = topicCard(context, topic)
            topicViews[topic.id] = card
            content.addView(card)
            content.space(12)
        }

        content.addView(explanationNotice(context))
        content.space(8)
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

        // Deep links only scroll; they never expand, select or perform anything else.
        val anchor = initialTopicId?.let { topicViews[it] }
        if (anchor != null) {
            scroll.post { scroll.smoothScrollTo(0, anchor.top) }
        }
    }

    private fun fallbackNotice(context: Context): View = context.text(
        UsageGuide.NO_PERMISSION_FALLBACK, 15f, Paper.green
    ).apply {
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        background = context.shape(Paper.lightGreen, 8)
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

    private fun topicCard(context: Context, topic: GuideTopic): View {
        val card = context.column().apply {
            setPadding(context.dp(20), context.dp(16), context.dp(20), context.dp(16))
            background = context.shape(Paper.surface, 12, true)
        }
        card.addView(context.text(topic.title, 20f, Paper.ink, true))
        card.addView(context.text(topic.summary, 15f, Paper.secondary))

        if (topic.steps.isNotEmpty()) {
            card.space(4)
            topic.steps.forEachIndexed { index, step ->
                card.addView(context.text("${index + 1}. $step", 15f, Paper.ink))
            }
        }
        topic.notes.forEach { note ->
            card.space(4)
            card.addView(context.text(note, 14f, Paper.warning))
        }
        if (topic.actions.isNotEmpty()) {
            card.space(8)
            topic.actions.forEach { action ->
                card.addView(context.button(action.label) { onNavigate(action.route) })
            }
        }
        return card
    }

    private fun explanationNotice(context: Context): View = context.column().apply {
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        background = context.shape(Paper.lightGreen, 10)
        addView(context.text("引导只做说明", 16f, Paper.green, true))
        addView(context.text(UsageGuide.NO_AUTOMATION, 15f, Paper.green))
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
     * Tapping "open" and "skip" are the only exits; the card never opens by itself or performs work.
     */
    fun homeCard(
        context: Context,
        onOpenGuide: () -> Unit,
        onDismiss: () -> Unit
    ): View = context.column().apply {
        setPadding(context.dp(20), context.dp(16), context.dp(20), context.dp(16))
        background = context.shape(Paper.lightGreen, 12)
        addView(context.text(UsageGuide.homeCard.title, 20f, Paper.green, true))
        addView(context.text(UsageGuide.homeCard.message, 15f, Paper.green))
        addView(context.text(UsageGuide.homeCard.hint, 14f, Paper.green))
        addView(context.button(UsageGuide.homeCard.openLabel, true) { onOpenGuide() })
        addView(context.button(UsageGuide.homeCard.dismissLabel) {
            // Explicit "skip, don't show again". Persistence belongs to the host.
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
