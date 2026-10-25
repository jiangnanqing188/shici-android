package com.shici.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import com.shici.app.ShiciApplication
import com.shici.app.core.FeedbackKind
import com.shici.app.core.StudyBook
import com.shici.app.core.StudyCard
import com.shici.app.core.StudyOverview
import com.shici.app.core.StudyAdmission
import com.shici.app.core.StudyRules
import com.shici.app.core.StudyScopeSummary
import com.shici.app.core.StudyView
import com.shici.app.core.UnifiedStudyPool
import com.shici.app.data.StudyCommitOutcome
import com.shici.app.data.StudyMembership
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The real 背词 review surface. It draws from the repository's persisted study round and never keeps a
 * parallel in-memory ledger: a committed feedback is re-read, not patched. There is one unified
 * entrance: due reviews first, then new words from the personal query set (recent first) followed by
 * the fixed ky/cet6 books stably interleaved and deduplicated by entry key. The books are dealt even
 * when the personal set is empty, so the default 20 new words never require a lookup first; the
 * visible plan says so explicitly and separates due reviews from that new-word goal. Membership is
 * built here from the validated index and the live personal set; entry snapshots go through the
 * application's unified resolver so a user-supplied supplement is what the card shows. All database
 * work runs on the application's single worker; the view is rebuilt on the main thread after each
 * commit. The overview is derived only for display and is never persisted or used as a ledger.
 *
 * [context] is the owning Activity: once it is destroyed, every queued worker and every posted
 * continuation checks [Activity.isDestroyed] and does nothing, so an old screen can never touch a
 * detached view. Database writes are not protected by those fast checks alone: [admission] is the
 * cross-thread linearization point, invalidated from [onDestroy] and admitted by the repository
 * immediately before each write transaction. A destroy that happens during a long read therefore
 * makes the later transaction refuse to start, while a transaction admitted first may finish
 * atomically (it is never interrupted halfway).
 */
class StudyScreen(
    private val app: ShiciApplication,
    private val context: Activity,
    private val onRerender: () -> Unit,
    private val onToast: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val zone = ZoneId.systemDefault()
    private var generation = 0
    // One in-flight commit/settings write at a time so a double tap cannot submit two feedbacks.
    private var busy = false
    // Shared by every write this surface starts. onDestroy invalidates it once; the repository checks
    // it right before BEGIN, never while holding a lock across a read or a transaction. The owner
    // liveness read is supplementary to the explicit cross-thread invalidation.
    private val admission = StudyAdmission { !context.isDestroyed }

    /** Invalidates every not-yet-admitted write of this surface. Called from Activity.onDestroy. */
    fun onDestroy() { admission.invalidate() }

    fun render(content: LinearLayout) {
        content.removeAllViews()
        content.addView(context.text("背词复习 · 统一背诵", 26f, Paper.ink, true))
        content.addView(context.text("固定间隔复习，非经过验证的记忆算法；只按你的真实反馈推进。", 16f, Paper.secondary))
        content.addView(context.text("个人已查词优先；不足时由考研/六级原书按序补足，默认合计 20 个新学词，无需先查词。", 16f, Paper.secondary))
        content.space(16)
        content.addView(context.text("正在读取学习进度…", 16f, Paper.secondary))
        load(content)
    }

    private fun load(content: LinearLayout) {
        val token = ++generation
        app.worker.execute {
            // A queued load of a screen whose activity is already gone must not even deal a card.
            if (context.isDestroyed) return@execute
            val result = runCatching {
                // planFor loads the personal set and the pinned book index (long reads); studyView
                // admits the pending-card write only after that, so a destroy during the load still
                // persists nothing. Reloading never clears an already persisted pending card: the
                // repository returns it for the same day/scope before considering a new deal.
                val plan = planFor()
                val view = app.repository.studyView(plan.membership, System.currentTimeMillis(), zone, admission)
                // Read-only and bounded: it only counts which personal words are still unlearned,
                // so the visible plan can say whether the books must fill the new-word slots.
                StudyLoad(
                    view,
                    StudyOverview.from(view, plan.scope, plan.personalKeys, app.repository.studiedKeys())
                )
            }
            main.post {
                if (context.isDestroyed) return@post
                if (token != generation) return@post
                result.onSuccess { bind(content, it.view, it.overview) }
                    .onFailure {
                        content.removeAllViews()
                        content.addView(context.text("背词暂时不可用：${it.message}", 18f, Paper.error))
                    }
            }
        }
    }

    private data class StudyLoad(val view: StudyView, val overview: StudyOverview)
    private data class UnifiedPlan(
        val membership: StudyMembership,
        val scope: StudyScopeSummary,
        val personalKeys: List<String>
    )

    /**
     * The one unified plan: personal vocabulary in recent-query order first, then the two fixed
     * source books interleaved in their original order, deduplicated by entry key. 个人生词 is a
     * runtime set (possibly empty), 考研/六级 are the pinned index; neither is turned into a
     * separate queue here. With no personal words the merged order is just the books, so the
     * default 20 new words are dealt without any lookup. Learned keys are filtered by the
     * repository (due states keep priority), and personal priority never bypasses a due review.
     * Snapshots resolve through the application's unified resolver so the card shows the latest
     * original-or-user-supplement meaning instead of a stale word row.
     */
    private fun planFor(): UnifiedPlan {
        val books = app.studyBooks.getOrThrow()
        val kaoyan = books.orderedMembers(StudyBook.KAOYAN) ?: error("学习词书缺少${StudyBook.KAOYAN.label}归属")
        val cet6 = books.orderedMembers(StudyBook.CET6) ?: error("学习词书缺少${StudyBook.CET6.label}归属")
        val personal = app.repository.personalMembers()
        val keys = UnifiedStudyPool.merged(personal, kaoyan, cet6)
        return UnifiedPlan(
            StudyMembership(StudyBook.ALL, keys) { app.resolveEntry(it) },
            StudyScopeSummary(personal.size, kaoyan.size, cet6.size, keys.size),
            personal
        )
    }

    private fun bind(content: LinearLayout, view: StudyView, overview: StudyOverview) {
        content.removeAllViews()
        content.addView(context.text("背词复习 · 统一背诵", 26f, Paper.ink, true))
        content.addView(context.text("固定间隔复习，非经过验证的记忆算法；只按你的真实反馈推进。", 16f, Paper.secondary))
        content.space(8)
        content.addView(planBlock(overview, content))
        content.space(12)
        content.addView(goalRow(view.settings.dailyNewGoal))
        content.addView(context.text("每日新学目标范围 ${StudyRules.MIN_DAILY_NEW_GOAL}–${StudyRules.MAX_DAILY_NEW_GOAL}（默认 ${StudyRules.DEFAULT_DAILY_NEW_GOAL}）；调整不会重置今天已学的词。", 16f, Paper.secondary))
        content.addView(context.text("释义来自原始词书（未逐条核对）或本机用户补充；不联网、不自动生成。", 16f, Paper.secondary))
        content.divider()
        val card = view.card
        if (card == null) {
            content.addView(context.text(emptyMessage(overview), 18f, Paper.ink))
            return
        }
        content.addView(cardBlock(card, view.credential))
    }

    private fun goalRow(goal: Int): LinearLayout = context.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(context.text("每日新词目标", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(context.button("−5", primary = false) { setGoal(goal - 5) })
        addView(context.text("$goal", 22f, Paper.ink, true).apply {
            gravity = Gravity.CENTER; setPadding(context.dp(16), 0, context.dp(16), 0)
        })
        addView(context.button("+5", primary = false) { setGoal(goal + 5) })
    }

    /**
     * The visible "今日计划 / 开始或继续学习" entrance. Every number comes from the persisted round
     * through [StudyOverview]; this block never writes, never counts a feedback as a word and never
     * folds due reviews into the new-word goal. The button only re-reads the round, so a persisted
     * pending card is resumed instead of being cleared or re-dealt.
     */
    private fun planBlock(overview: StudyOverview, content: LinearLayout): LinearLayout = context.column().apply {
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        background = context.shape(Paper.surface, 12, true)
        addView(context.text("今日计划", 20f, Paper.ink, true))
        addView(context.text("新学目标 ${overview.dailyNewGoal} 词/天（默认 ${StudyRules.DEFAULT_DAILY_NEW_GOAL}）。无需先查词：个人已查词优先；个人不足时按原书顺序直接从考研/六级补足，合计占 ${overview.dailyNewGoal} 个新学词。",
            16f, Paper.secondary))
        addView(context.text("统一范围：个人生词 ${overview.scope.personal} 词 + 考研 ${overview.scope.kaoyan} 词 + 六级 ${overview.scope.cet6} 词；按词条去重后 ${overview.scope.unified} 词，同词只保留一份进度。",
            16f, Paper.secondary))
        when {
            overview.scope.personal == 0 ->
                addView(context.text("个人生词为 0：现在会直接发考研/六级原书新词卡，不需要先查词。", 16f, Paper.green))
            overview.personalUnlearned == 0 ->
                addView(context.text("个人生词都已学过：新学名额由考研/六级原书按序补足。", 16f, Paper.green))
            else ->
                addView(context.text("个人还有 ${overview.personalUnlearned} 个未学生词，优先占用新学名额；用完后由考研/六级原书按序补足。", 16f, Paper.green))
        }
        addView(context.text("个人与词书共同占用同一个每日新学目标，不各算一份。", 16f, Paper.secondary))
        addView(context.text("本次可学 ${overview.cardsAvailableNow} 张卡：新词 ${overview.newRemaining} 张 + 到期复习 ${overview.dueNow} 张（到期复习另列，不占新学目标，优先出现）。",
            18f))
        addView(context.text("今日已新学 ${overview.introducedToday} / ${overview.dailyNewGoal} 词 · 目标剩余 ${overview.goalRemaining} 词 · 当前还可新学 ${overview.newRemaining} 词（受目标与可用词库共同限制）。",
            16f, Paper.secondary))
        addView(context.text("今日已反馈 ${overview.todayAnswers} 次（涉及 ${overview.todayReviewed} 词）· 累计已学 ${overview.totalIntroduced} 词。",
            16f, Paper.secondary))
        if (!overview.startedToday && overview.pendingCard) {
            addView(context.text("今天还没有提交反馈：当前卡只是待答，不计入已新学，也不算完成。", 16f, Paper.secondary))
        }
        overview.nextDueAt?.let { addView(context.text("下一张到期：${time(it)}", 16f, Paper.secondary)) }
        addView(context.button(overview.entryLabel, primary = true) { if (!busy) load(content) })
        addView(context.text("开始/继续只读取并接着今天已保存的进度和待答卡；不会清空待答卡、学习进度或每日目标。",
            16f, Paper.secondary))
    }

    private fun cardBlock(card: StudyCard, credential: Long): LinearLayout = context.column().apply {
        setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        background = context.shape(Paper.surface, 12, true)
        addView(context.text(if (card.newWord) "新词 · 首次学习" else "复习 · 阶段 ${stageLabel(card.stageBefore)}", 16f, Paper.green))
        addView(context.text(card.entry.headword, 32f, Paper.ink, true))
        addView(context.text(card.entry.partOfSpeech.ifBlank { "—" }, 16f, Paper.secondary))
        if (!card.answerRevealed) {
            space(8)
            addView(context.button("显示答案", primary = true) { reveal(card.token, credential) })
            addView(context.text("先自己回忆词义，再看答案，然后选择真实反馈。", 16f, Paper.secondary))
        } else {
            divider()
            addView(context.text(card.entry.meaning, 20f, Paper.ink))
            // The snapshot's own provenance is shown with the answer so a historical source/version is
            // never implied to be the current one. A user supplement is labelled by its source instead
            // of being presented as original word-book content.
            val provenance = listOf(card.entry.source, card.entry.version).filter { it.isNotBlank() }
            addView(context.text(if (provenance.isEmpty()) "释义来源 未标注" else "释义来源 ${provenance.joinToString(" · ")}",
                16f, Paper.secondary))
            if (card.note.isNotBlank()) {
                space(8)
                addView(context.text("我的笔记", 16f, Paper.secondary))
                addView(context.text(card.note, 18f, Paper.ink))
            }
            space(8)
            addView(context.text("这次记得怎么样？", 18f, bold = true))
            val row = context.row()
            FeedbackKind.entries.forEach { kind ->
                val button = context.button(kind.label(), primary = kind == FeedbackKind.KNOW) {
                    submit(card.token, kind, credential)
                }
                row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) })
            }
            addView(row)
            addView(context.text("${FeedbackKind.KNOW.label()}：进入下一阶段；${FeedbackKind.FUZZY.label()}：阶段不变、明天再来；${FeedbackKind.FORGET.label()}：回到第一阶段、约 10 分钟后再出现。", 16f, Paper.secondary))
        }
    }

    private fun emptyMessage(overview: StudyOverview): String {
        val nextDue = overview.nextDueAt
        return when {
            overview.dueNow > 0 -> "有 ${overview.dueNow} 个到期词，但暂时没有可显示的卡片；请刷新学习计划。"
            overview.newRemaining > 0 -> "词库当前还有 ${overview.newRemaining} 个新词可学，但暂时没有可显示的卡片；请刷新学习计划。"
            overview.goalRemaining > 0 -> "今日新学目标还剩 ${overview.goalRemaining} 词，但统一范围里已没有未学新词；没有到期复习。"
            nextDue != null -> "今日新学目标已完成（${overview.introducedToday} / ${overview.dailyNewGoal} 词），没有到期复习；下一张 ${time(nextDue)} 到期。"
            else -> "今日新学目标已完成（${overview.introducedToday} / ${overview.dailyNewGoal} 词），统一范围内已全部学过，且暂无到期复习。"
        }
    }

    private fun setGoal(goal: Int) {
        if (busy) return
        busy = true
        app.worker.execute {
            if (context.isDestroyed) return@execute
            val result = runCatching {
                // The unified scope is the only study setting the screen still exposes; the goal write
                // keeps the current global goal semantics but always persists the unified scope.
                app.repository.updateStudySettings(StudyBook.ALL, goal, admission)
            }
            main.post {
                if (context.isDestroyed) return@post
                busy = false
                result.onFailure { onToast("目标设置失败：${it.message}") }
                onRerender()
            }
        }
    }

    private fun reveal(token: String, credential: Long) {
        if (busy) return
        busy = true
        app.worker.execute {
            if (context.isDestroyed) return@execute
            val result = runCatching { app.repository.revealStudyCard(token, credential, admission) }
            main.post {
                if (context.isDestroyed) return@post
                busy = false
                if (result.getOrNull() != true) onToast("这张卡已失效，正在刷新")
                onRerender()
            }
        }
    }

    private fun submit(token: String, kind: FeedbackKind, credential: Long) {
        if (busy) return
        busy = true
        app.worker.execute {
            if (context.isDestroyed) return@execute
            val result = runCatching {
                // Re-check the activity on the worker right before the write too. The repository then
                // performs its own admission check immediately before BEGIN, after every long read
                // (including the settings/personal-member reads), so a destroy that lands between
                // canCommit and BEGIN still writes nothing.
                app.repository.commitStudyFeedback(token, kind, System.currentTimeMillis(), zone,
                    credential, admission) {
                    !context.isDestroyed
                }
            }
            main.post {
                if (context.isDestroyed) return@post
                busy = false
                result.onSuccess { outcome ->
                    when (outcome) {
                        StudyCommitOutcome.COMMITTED, StudyCommitOutcome.IDEMPOTENT -> Unit
                        StudyCommitOutcome.CONFLICT -> onToast("这张卡已用其它反馈提交过，未重复记录")
                        StudyCommitOutcome.STALE -> onToast("学习数据已变化，这张卡失效，未记录")
                        StudyCommitOutcome.REJECTED -> onToast("这张卡已不是当前待答卡，未记录")
                    }
                }.onFailure { onToast("提交失败，未记录：${it.message}") }
                onRerender()
            }
        }
    }

    private fun stageLabel(stageBefore: Int): String =
        if (stageBefore < 0) "新词" else "第 ${stageBefore + 1} 阶段"

    private fun FeedbackKind.label(): String = when (this) {
        FeedbackKind.KNOW -> "认识"
        FeedbackKind.FUZZY -> "模糊"
        FeedbackKind.FORGET -> "忘记"
    }

    private fun time(at: Long): String =
        Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
