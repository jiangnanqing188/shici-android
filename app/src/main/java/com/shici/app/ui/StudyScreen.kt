package com.shici.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
 * entrance: due reviews first, then new words mixed from the live personal query set (recent first)
 * and a per-day stable sample of the fixed ky/cet6 books, deduplicated by entry key. With both
 * streams available they alternate, starting from the personal stream on an even count of today's
 * committed new words and from the books on an odd one, so a reload after each feedback does not
 * always restart from the personal set; one stream alone fills the whole goal, so the default 20 new
 * words never require a lookup first. The collapsed 学习规则 says so and keeps due reviews separate
 * from that new-word goal. Membership is
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
        content.addView(context.text("背词", 26f, Paper.ink, true))
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
                // One `now` builds both the plan's day sample and the study day bucket, so a load
                // crossing midnight cannot plan for one day and count/deal for another.
                val now = System.currentTimeMillis()
                val plan = planFor(now)
                val view = app.repository.studyView(plan.membership, now, zone, admission)
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
                        content.addView(context.text("请返回上一页后重新进入背词再试。", 16f, Paper.secondary))
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
     * The one unified plan built for the same [now] used by the study view: unlearned personal
     * vocabulary in recent-query order and the two fixed source books sampled by a stable
     * `dayKey + key` hash, deduplicated by entry key and alternated. Today's committed new-word
     * count (not a UI counter) decides which stream goes first, so feedback reloads keep rotating.
     * 个人生词 is a runtime set (possibly empty), 考研/六级 are the pinned index; neither is turned
     * into a separate queue here. With no personal words the books alone fill the default 20 new
     * words without any lookup. Learned keys stay in the membership at the end but are never dealt
     * as new; the repository still keeps due reviews ahead of every new word. Snapshots resolve
     * through the application's unified resolver so the card shows the latest
     * original-or-user-supplement meaning instead of a stale word row.
     */
    private fun planFor(now: Long): UnifiedPlan {
        val books = app.studyBooks.getOrThrow()
        val kaoyan = books.orderedMembers(StudyBook.KAOYAN) ?: error("学习词书缺少${StudyBook.KAOYAN.label}归属")
        val cet6 = books.orderedMembers(StudyBook.CET6) ?: error("学习词书缺少${StudyBook.CET6.label}归属")
        val personal = app.repository.personalMembers()
        // Both reads are bounded, read-only and use the same day bucket as studyView. The committed
        // count decides the next pool's parity; the studied set keeps already-learned words out of
        // both new streams without touching any existing state.
        val dayKey = StudyRules.dayKey(now, zone)
        val studied = app.repository.studiedKeys()
        val introducedToday = app.repository.introducedNewCount(now, zone)
        val keys = UnifiedStudyPool.dailyMerged(personal, kaoyan, cet6, dayKey, studied, introducedToday)
        return UnifiedPlan(
            StudyMembership(StudyBook.ALL, keys) { app.resolveEntry(it) },
            StudyScopeSummary(personal.size, kaoyan.size, cet6.size, keys.size),
            personal
        )
    }

    private fun bind(content: LinearLayout, view: StudyView, overview: StudyOverview) {
        content.removeAllViews()
        content.addView(context.text("背词", 26f, Paper.ink, true))
        content.addView(context.text(summaryLine(overview), 18f, Paper.ink))
        content.addView(context.text(
            "原书抽词＋查过的词，每天合计 ${overview.dailyNewGoal} 个新词。",
            16f, Paper.secondary
        ))
        content.space(12)
        val card = view.card
        if (card == null) {
            content.addView(context.text(emptyMessage(overview), 18f, Paper.ink))
            content.addView(context.button(overview.entryLabel, primary = true) { if (!busy) load(content) })
        } else {
            content.addView(cardBlock(card, view.credential))
        }
        content.space(12)
        content.addView(goalRow(view.settings.dailyNewGoal))
        content.addView(rulesDisclosure(overview))
        content.space(8)
        content.addView(context.badge("词义来源：原始词书 / 本机补充"))
    }

    /** A few persisted counts only; the long plan prose lives collapsed under 学习规则. */
    private fun summaryLine(overview: StudyOverview): String {
        val base = "今日已新学 ${overview.introducedToday}/${overview.dailyNewGoal} · 到期复习 ${overview.dueNow} · 本次可学 ${overview.cardsAvailableNow} 张"
        return if (overview.dueNow == 0) overview.nextDueAt?.let { "$base · 下次到期 ${time(it)}" } ?: base else base
    }

    /**
     * The full rules, goal bounds and scope counts on demand. It only builds static text; it has no
     * business action and starts collapsed so the card, feedback buttons and goal row stay on top.
     */
    private fun rulesDisclosure(overview: StudyOverview): View {
        val ui = context
        return ui.helpDisclosure("学习规则") {
            addView(ui.text("固定间隔复习：第 1、3、7、14、30 天；只按你的真实反馈推进。", 16f, Paper.secondary))
            addView(ui.text("认识：进入下一阶段；模糊：阶段不变、明天再来；忘记：回到第一阶段，约 10 分钟后再出现。", 16f, Paper.secondary))
            addView(ui.text("个人已查词与考研/六级原书共用同一个每日新学目标，不各算一份；按词条去重，同词只保留一份进度。", 16f, Paper.secondary))
            addView(ui.text("两池都有词时按今日已提交的新词数轮流抽取，个人词先抽但不独占名额；某池不足时由另一池补上。", 16f, Paper.secondary))
            addView(ui.text("范围：个人生词 ${overview.scope.personal} + 考研 ${overview.scope.kaoyan} + 六级 ${overview.scope.cet6}，去重后 ${overview.scope.unified} 词。", 16f, Paper.secondary))
            val personalStatus = when {
                overview.scope.personal == 0 -> "个人生词为 0：直接发考研/六级原书新词卡，不需要先查词。"
                overview.personalUnlearned == 0 -> "个人生词都已学过：新学名额全部由考研/六级原书按当天抽词序补足。"
                else -> "个人还有 ${overview.personalUnlearned} 个未学生词，与考研/六级原书轮换混合，不独占新学名额。"
            }
            addView(ui.text(personalStatus, 16f, Paper.green))
            addView(ui.text("每日新学目标范围 ${StudyRules.MIN_DAILY_NEW_GOAL}–${StudyRules.MAX_DAILY_NEW_GOAL}（默认 ${StudyRules.DEFAULT_DAILY_NEW_GOAL}）；调整不会重置今天已学的词。", 16f, Paper.secondary))
            addView(ui.text("开始/继续只读取并接着今天已保存的进度和待答卡；不清空待答卡、学习进度或每日目标。待答卡不计入已新学。", 16f, Paper.secondary))
            addView(ui.text("今日已反馈 ${overview.todayAnswers} 次（涉及 ${overview.todayReviewed} 词）· 累计已学 ${overview.totalIntroduced} 词。", 16f, Paper.secondary))
        }
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

    private fun cardBlock(card: StudyCard, credential: Long): LinearLayout = context.column().apply {
        setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        background = context.shape(Paper.surface, 12, true)
        addView(context.text(if (card.newWord) "新词 · 首次学习" else "复习 · 阶段 ${stageLabel(card.stageBefore)}", 16f, Paper.green))
        addView(context.text(card.entry.headword, 32f, Paper.ink, true))
        addView(context.text(card.entry.partOfSpeech.ifBlank { "—" }, 16f, Paper.secondary))
        if (!card.answerRevealed) {
            space(8)
            addView(context.button("显示答案", primary = true) { reveal(card.token, credential) })
        } else {
            divider()
            addView(context.text(card.entry.meaning, 20f, Paper.ink))
            // The snapshot's own provenance is shown with the answer so a historical source/version is
            // never implied to be the current one. A user supplement is labelled by its source instead
            // of being presented as original word-book content.
            val provenance = listOf(card.entry.source, card.entry.version).filter { it.isNotBlank() }
            addView(context.text(if (provenance.isEmpty()) "来源 未标注" else "来源 ${provenance.joinToString(" · ")}",
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
        }
    }

    private fun emptyMessage(overview: StudyOverview): String {
        val nextDue = overview.nextDueAt
        return when {
            overview.dueNow > 0 -> "有 ${overview.dueNow} 个到期词，但暂时没有可显示的卡片；请点下方按钮重新读取。"
            overview.newRemaining > 0 -> "词库当前还有 ${overview.newRemaining} 个新词可学，但暂时没有可显示的卡片；请点下方按钮重新读取。"
            overview.goalRemaining > 0 -> "今日新学目标还剩 ${overview.goalRemaining} 词，但统一范围里已没有未学新词，也没有到期复习；可调低每日目标或稍后再看。"
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
