package com.shici.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import com.shici.app.ShiciApplication
import com.shici.app.core.DictionaryEntry
import com.shici.app.core.FeedbackKind
import com.shici.app.core.ReviewChoices
import com.shici.app.core.ReviewQuestion
import com.shici.app.core.ReviewUnavailableReason
import com.shici.app.core.StudyAdmission
import com.shici.app.core.StudyBook
import com.shici.app.core.StudyCard
import com.shici.app.core.StudyOverview
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
 * The real 复习 surface. One pending card, one question: the English headword plus four Chinese
 * meanings, exactly one of which is correct. There is no “认识 / 模糊 / 忘记” pre-choice and no
 * “显示答案” step: the user answers by tapping an option, which is the only real feedback.
 *
 * The question is a pure derivation of the pending card: [ReviewChoices] receives the card's own
 * resolved snapshot, a bounded list of other local entries and the persisted card token; it never
 * reads SQLite, scans the library on the main thread or invents a meaning. This screen only loads a
 * bounded candidate window on the application worker (validated book members resolved through the
 * app's read-only resolver, plus the live personal set). The same card token always yields the same
 * four options in the same order, so rotation, a redraw or a process restart cannot reshuffle the
 * answer; a newly dealt card has a new token and therefore a new correct position. When three clean
 * distractors are unavailable the card says the question is temporarily unavailable; it never pads
 * the question with a wrong or repeated meaning, never skips the card and never writes progress.
 *
 * The four labels are bound to the repository revision observed immediately before they were
 * resolved. A committed change from another surface between that read and the tap (for example a
 * candidate meaning edited through the supplement dictionary, which does not remove this pending
 * card) makes the tap a complete no-op: nothing is revealed, committed or shown as answered. This
 * surface's own reveal revision is accounted for, and its commit revision is published only after
 * the final admission, so a normal answer is not rejected by its own write.
 *
 * The repository remains the only ledger. A tap runs the original reveal write first (when the card
 * is still in its unrevealed phase) and then the original `commitStudyFeedback` admission chain:
 * correct -> `KNOW`, wrong -> `FORGET`. The commit's own `admitsFeedback` re-checks `answerRevealed`,
 * day bucket, scope, stage, quota and membership, so the reveal is not bypassed or replaced by a
 * weaker check. There is no path that upgrades a wrong answer to `KNOW`, no second feedback for the
 * same card, and the daily aggregates, reviews, backups and schema are untouched. Only
 * `COMMITTED` / `IDEMPOTENT` shows a result; a rejection/stale/failure only toasts and reloads the
 * real round, so a failed write is never shown as a correct answer. After a committed answer the
 * page stays on the outcome (the full correct meaning with its provenance) and a single “下一词”
 * continues; the pending card the repository deals during the next read is never duplicated or
 * skipped.
 *
 * Membership still comes from the one unified plan (due-first, personal + ky/cet6 daily mix,
 * deduplicated, stable pending across restarts) and is never changed here.
 *
 * [context] is the owning Activity: once it is destroyed, every queued worker and every posted
 * continuation checks [Activity.isDestroyed] and does nothing. [admission] is the cross-thread
 * linearization point, invalidated from [onDestroy] and admitted by the repository immediately before
 * each write transaction, so a destroy during a long read makes the later write refuse to start
 * while an already-admitted transaction may finish atomically.
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
    // One in-flight answer/settings write at a time so a double tap cannot submit two feedbacks.
    private var busy = false
    // Shared by every write this surface starts. onDestroy invalidates it once; the repository checks
    // it right before BEGIN, never while holding a lock across a read or a transaction.
    private val admission = StudyAdmission { !context.isDestroyed }
    // Main-thread-only view state. [mountedContent] is the page the host last mounted this screen on;
    // a result is only drawn into a still-attached page, otherwise it waits for the next render.
    private var mountedContent: LinearLayout? = null
    private var optionButtons = mutableListOf<Button>()
    private var pendingResult: AnswerResult? = null
    // Repository revision observed right after the result's commit. A later external change (delete,
    // restore, another page's write) makes the kept result stale display, so it is dropped instead of
    // being presented after a data change.
    private var resultVersion = -1L

    /** Invalidates every not-yet-admitted write of this surface. Called from Activity.onDestroy. */
    fun onDestroy() { admission.invalidate() }

    fun render(content: LinearLayout) {
        mountedContent = content
        optionButtons = mutableListOf()
        content.removeAllViews()
        // A kept result belongs to the world it was committed in. If the repository changed since
        // then (another page deleted/restored data, or a later external write), the result is stale
        // display and must not be shown as current; drop it and read the real round instead.
        val result = pendingResult
        if (result != null && resultVersion == app.repository.changes.version) {
            showResult(content, result)
            return
        }
        pendingResult = null
        resultVersion = -1L
        content.addView(context.text("复习", 26f, Paper.ink, true))
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
                val overview = StudyOverview.from(view, plan.scope, plan.personalKeys,
                    app.repository.studiedKeys())
                // The revision immediately before the option world is resolved. A later committed
                // change (another page editing a candidate meaning, a restore, a settings write)
                // advances this counter, so both the render gate and the answer admission can tell
                // that these four labels no longer describe the current data.
                val questionVersion = app.repository.changes.version
                StudyLoad(view, overview, view.card?.let { buildQuestion(it, plan.personalKeys) },
                    questionVersion)
            }
            main.post {
                if (context.isDestroyed) return@post
                if (token != generation) return@post
                // A committed answer already owns the page; a late read must not replace its result.
                if (pendingResult != null) return@post
                result.onSuccess { loaded ->
                    // Close the read-to-render window: if the world changed while the options were
                    // being resolved, do not paint them (and do not let them become a question);
                    // re-read instead. Only Ready options carry an answer, but the same guard also
                    // keeps the displayed snapshot consistent with them.
                    if (loaded.question is ReviewQuestion.Ready &&
                        loaded.questionVersion != app.repository.changes.version
                    ) {
                        load(content)
                        return@post
                    }
                    bind(content, loaded.view, loaded.overview, loaded.question,
                        loaded.questionVersion)
                }
                    .onFailure {
                        content.removeAllViews()
                        content.addView(context.text("复习暂时不可用：${it.message}", 18f, Paper.error))
                        content.addView(context.text("请返回上一页后重新进入复习再试。", 16f, Paper.secondary))
                    }
            }
        }
    }

    private data class StudyLoad(
        val view: StudyView,
        val overview: StudyOverview,
        val question: ReviewQuestion?,
        /** Committed revision observed immediately before the option world was resolved. */
        val questionVersion: Long
    )

    private data class UnifiedPlan(
        val membership: StudyMembership,
        val scope: StudyScopeSummary,
        val personalKeys: List<String>
    )

    /** A committed answer kept on screen until “下一词”; it is display state, never a ledger. */
    private data class AnswerResult(
        val headword: String,
        val correct: Boolean,
        val chosenLabel: String,
        val meaning: String,
        val provenance: String,
        val note: String
    )

    private data class AnswerAttempt(
        val committed: Boolean,
        val outcome: StudyCommitOutcome?,
        /** Repository revision read immediately after a committed answer; identifies its world. */
        val version: Long,
        /** The reveal write was refused; only used to word a refused-commit toast honestly. */
        val revealFailed: Boolean = false,
        /**
         * The question's own revision was no longer current before reveal/commit and the token was
         * not an already-committed one, or the repository's final admission refused it. Nothing was
         * written by this tap and no result may be shown as submitted.
         */
        val worldChanged: Boolean = false
    )

    /**
     * The one unified plan built for the same [now] used by the study view: unlearned personal
     * vocabulary in recent-query order and the two fixed source books sampled by a stable
     * `dayKey + key` hash, deduplicated by entry key and alternated. Today's committed new-word
     * count (not a UI counter) decides which stream goes first, so feedback reloads keep rotating.
     * Learned keys stay in the membership at the end but are never dealt as new; the repository
     * still keeps due reviews ahead of every new word. Snapshots resolve through the application's
     * unified resolver so the card shows the latest original-or-user-supplement meaning.
     */
    private fun planFor(now: Long): UnifiedPlan {
        val books = app.studyBooks.getOrThrow()
        val kaoyan = books.orderedMembers(StudyBook.KAOYAN) ?: error("学习词书缺少${StudyBook.KAOYAN.label}归属")
        val cet6 = books.orderedMembers(StudyBook.CET6) ?: error("学习词书缺少${StudyBook.CET6.label}归属")
        val personal = app.repository.personalMembers()
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

    // ---- Question -------------------------------------------------------------------------------

    /**
     * Builds the four-choice question for [card] on the worker only. The candidate set is a bounded
     * window (`token`-seeded) over the validated book members plus the live personal set, resolved
     * read-only through the app's unified resolver. No AI, network, meaning revision or invented
     * text is involved; when the generator cannot find three clean distractors it reports why.
     */
    private fun buildQuestion(card: StudyCard, personalKeys: List<String>): ReviewQuestion {
        val primary = runCatching {
            candidateEntries(card.entryKey, card.token, personalKeys, BOOK_CANDIDATES, MAX_CANDIDATES)
        }.getOrElse { emptyList() }
        if (primary.isEmpty() && app.lookupDictionary.isFailure && app.dictionary.isFailure) {
            return ReviewQuestion.Unavailable(ReviewUnavailableReason.NO_READABLE_DICTIONARY)
        }
        val first = runCatching { ReviewChoices.build(card.entry, primary, card.token) }
            .getOrElse { ReviewQuestion.Unavailable(ReviewUnavailableReason.NOT_ENOUGH_CANDIDATES) }
        if (first !is ReviewQuestion.Unavailable ||
            first.reason != ReviewUnavailableReason.NOT_ENOUGH_CANDIDATES) return first
        // One deterministic widening pass: the same card token always picks the same larger window,
        // so a retry/redraw still shows identical options, but a very common meaning is less likely
        // to get stuck with too few clean distractors in the small window.
        val wide = runCatching {
            candidateEntries(card.entryKey, card.token, personalKeys, WIDE_BOOK_CANDIDATES,
                WIDE_MAX_CANDIDATES)
        }.getOrElse { emptyList() }
        return runCatching { ReviewChoices.build(card.entry, wide, card.token) }
            .getOrElse { ReviewQuestion.Unavailable(ReviewUnavailableReason.NOT_ENOUGH_CANDIDATES) }
    }

    /**
     * A bounded, deterministic candidate window: up to [PERSONAL_CANDIDATES] live personal words
     * (recent first) plus a token-seeded slice of each fixed book, deduplicated by key and resolved
     * through [ShiciApplication.resolveEntry]. Reading a few dozen rows on the single worker is not
     * a library scan, and nothing here writes or depends on the main thread.
     */
    private fun candidateEntries(targetKey: String, token: String,
                                 personalKeys: List<String>,
                                 bookSeats: Int,
                                 maxCandidates: Int): List<DictionaryEntry> {
        val books = app.studyBooks.getOrThrow()
        val picked = LinkedHashSet<String>()
        // [personalKeys] is the personal set the plan already read for this exact load, so the
        // question does not re-query the library; it is bounded here, never a full scan.
        personalKeys.asSequence()
            .filter { it != targetKey }
            .take(PERSONAL_CANDIDATES)
            .forEach { picked += it }
        val kaoyan = ReviewChoices.sampleWindow(
            books.orderedMembers(StudyBook.KAOYAN).orEmpty(), "$token#kaoyan", bookSeats
        )
        val cet6 = ReviewChoices.sampleWindow(
            books.orderedMembers(StudyBook.CET6).orEmpty(), "$token#cet6", bookSeats
        )
        var index = 0
        val seats = maxOf(kaoyan.size, cet6.size)
        // Interleave the two books and stop at the hard cap, so one question never resolves more
        // than [maxCandidates] entries even when both books filled their whole window.
        while (index < seats && picked.size < maxCandidates) {
            kaoyan.getOrNull(index)?.let { if (it != targetKey && picked.size < maxCandidates) picked += it }
            cet6.getOrNull(index)?.let { if (it != targetKey && picked.size < maxCandidates) picked += it }
            index++
        }
        return picked.mapNotNull { app.resolveEntry(it) }
    }

    // ---- Rendering ------------------------------------------------------------------------------

    private fun bind(content: LinearLayout, view: StudyView, overview: StudyOverview,
                     question: ReviewQuestion?, questionVersion: Long) {
        content.removeAllViews()
        optionButtons = mutableListOf()
        content.addView(context.text("复习", 26f, Paper.ink, true))
        content.addView(context.badge(summaryLine(overview)))
        content.space(12)
        val card = view.card
        if (card == null) {
            content.addView(context.text(emptyMessage(overview), 18f, Paper.ink))
            content.addView(context.button(overview.entryLabel, primary = true) { if (!busy) load(content) })
        } else {
            content.addView(questionCard(content, card, view.credential, question, questionVersion))
        }
        content.space(12)
        content.addView(rulesDisclosure(overview))
    }

    /** A few persisted counts only; the long plan prose lives collapsed under 学习规则与目标. */
    private fun summaryLine(overview: StudyOverview): String =
        "今日新学 ${overview.introducedToday}/${overview.dailyNewGoal} · 到期复习 ${overview.dueNow}"

    /**
     * The single question: headword, part of speech and exactly four meaning options. Options are
     * ordinary tappable rows (48dp minimum), not a second action bar; the green primary colour stays
     * for the page's real actions. A card that cannot be asked shows a clear reason and a reload
     * button; it is never answered, skipped or written by this screen.
     */
    private fun questionCard(content: LinearLayout, card: StudyCard, credential: Long,
                             question: ReviewQuestion?, questionVersion: Long): LinearLayout = context.column().apply {
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        background = context.shape(Paper.surface, 14, true)
        addView(context.text(caption(card), 15f, Paper.green))
        addView(context.text(card.entry.headword, 32f, Paper.ink, true))
        addView(context.text(card.entry.partOfSpeech.ifBlank { "—" }, 15f, Paper.secondary))
        space(8)
        when (question) {
            is ReviewQuestion.Ready -> {
                addView(context.text("选出正确的中文释义", 16f, Paper.secondary))
                question.options.forEachIndexed { index, option ->
                    val button = context.button(option.label) {
                        answer(card, option.correct, option.label, credential, questionVersion)
                    }
                    button.textSize = 18f
                    button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    button.setTextColor(Paper.ink)
                    button.background = context.shape(Paper.surface, 10, true)
                    button.setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                    button.contentDescription = "选项 ${index + 1}：${option.label}"
                    // A rebuild that happens while one answer is still in flight must not present a
                    // tappable second option; [busy] rejects it anyway, this only makes that visible.
                    button.isEnabled = !busy
                    optionButtons += button
                    addView(button, LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = context.dp(8)
                    })
                }
            }
            else -> {
                val reason = (question as? ReviewQuestion.Unavailable)?.reason
                addView(context.text("本题暂不可用：${unavailableText(reason)}", 17f, Paper.warning))
                addView(context.text("不会用错误或重复释义凑选项，也不会记为学习进度。", 15f,
                    Paper.secondary))
                addView(context.button("重新读取", primary = true) { if (!busy) load(content) })
            }
        }
    }

    /** The outcome of a real committed answer, shown until “下一词” reads the next real card. */
    private fun showResult(content: LinearLayout, result: AnswerResult) {
        content.removeAllViews()
        optionButtons = mutableListOf()
        content.addView(context.text("复习", 26f, Paper.ink, true))
        content.addView(context.text(
            if (result.correct) "回答正确" else "回答错误",
            22f, if (result.correct) Paper.green else Paper.error, true
        ))
        content.space(12)
        val panel = context.column().apply {
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
            background = context.shape(Paper.surface, 14, true)
            addView(context.text(result.headword, 30f, Paper.ink, true))
            addView(context.text(
                if (result.correct) "回答正确，下次到期时间已更新。"
                else "回答错误，约 10 分钟后会再出现。",
                15f, Paper.secondary
            ))
            if (!result.correct) addView(context.text("你选了：${result.chosenLabel}", 15f, Paper.secondary))
            divider()
            addView(context.text("正确释义", 15f, Paper.secondary))
            addView(context.text(result.meaning, 18f, Paper.ink))
            addView(context.text(result.provenance, 15f, Paper.secondary))
            if (result.note.isNotBlank()) {
                space(8)
                addView(context.text("我的笔记", 15f, Paper.secondary))
                addView(context.text(result.note, 17f, Paper.ink))
            }
        }
        content.addView(panel)
        content.space(12)
        content.addView(context.button("下一词", primary = true) {
            if (!busy) {
                pendingResult = null
                resultVersion = -1L
                // Continue on the page still on screen; if the host already rebuilt it, let the host
                // mount the next real round instead of writing into a detached tree.
                if (content.isAttachedToWindow) {
                    content.removeAllViews()
                    content.addView(context.text("复习", 26f, Paper.ink, true))
                    content.addView(context.text("正在读取学习进度…", 16f, Paper.secondary))
                    load(content)
                } else {
                    onRerender()
                }
            }
        })
    }

    /**
     * The full rules, goal bounds and scope counts on demand. It only builds static text and the
     * existing goal row; it has no answer action and starts collapsed so the single question stays
     * the whole visible page.
     */
    private fun rulesDisclosure(overview: StudyOverview): View {
        val ui = context
        return ui.helpDisclosure("学习规则与目标") {
            addView(ui.text("四选一：从四个中文释义里选出正确的一项。答对推进一个阶段，答错回到第一阶段。", 16f, Paper.secondary))
            addView(ui.text("答错约 10 分钟后重新出现；答对按第 1、3、7、14、30 天递增。这是固定间隔复习，不是经过验证的记忆算法。", 16f, Paper.secondary))
            addView(ui.text("选项只来自本机已有词义，不联网、不生成新释义；释义不足时明确说本题暂不可用，不用错误或重复释义凑数。", 16f, Paper.secondary))
            addView(ui.text("到期复习优先；个人已查词与考研/六级原书共用同一个每日新学目标，按词条去重，同词只保留一份进度。", 16f, Paper.secondary))
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
            addView(goalRow(overview.dailyNewGoal))
            addView(ui.badge("词义来源：原始词书 / 本机补充"))
        }
    }

    /**
     * The daily goal label, current value and −5/+5 controls. On a phone-width window or with
     * enlarged text the label takes its own line above the controls instead of being squeezed into
     * a single column next to them. Only layout changes: both buttons keep calling [setGoal] with
     * exactly the same bounds-checked value as before, and no goal/state is touched here.
     */
    private fun goalRow(goal: Int): LinearLayout {
        val label = context.text("每日新词目标", 18f)
        val value = context.text("$goal", 22f, Paper.ink, true).apply {
            gravity = Gravity.CENTER; setPadding(context.dp(16), 0, context.dp(16), 0)
        }
        val minus = context.button("−5", primary = false) { setGoal(goal - 5) }
        val plus = context.button("+5", primary = false) { setGoal(goal + 5) }
        if (!context.stackedActions()) {
            return context.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                addView(minus)
                addView(value)
                addView(plus)
            }
        }
        // Full-width label on its own line, then the controls on one tidy row below.
        val controls = context.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(minus)
            addView(value)
            addView(plus)
        }
        return context.column().apply {
            addView(label, LinearLayout.LayoutParams(-1, -2))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
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

    // ---- Writes ---------------------------------------------------------------------------------

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

    /**
     * One tap on one option is the only real answer. The card's still-unrevealed phase is passed
     * through the original reveal write first, then the original commit admission decides:
     * correct -> KNOW, wrong -> FORGET. A refused reveal never commits; a refused/conflicted commit
     * never shows a result. The result is kept only for COMMITTED/IDEMPOTENT and only after the
     * repository accepted the write.
     *
     * [questionVersion] is the committed revision the four visible labels were resolved at. It is
     * checked before anything is written: a candidate meaning updated after the question was built
     * (even though the pending target card itself was untouched) makes the tap a complete no-op
     * instead of recording a verdict about labels the user no longer sees. When that revision has
     * already moved, the repository's read-only token classification still runs with its
     * new-feedback admission forced closed: an already-committed token keeps its real
     * IDEMPOTENT/CONFLICT/STALE classification (never a result, never a second write), and only an
     * uncommitted old question is reported as invalid. This surface's own reveal publishes exactly
     * one revision, so it is accounted for before committing; the commit's own revision is published
     * after its final admission and never causes a false refusal.
     */
    private fun answer(card: StudyCard, correct: Boolean, chosenLabel: String, credential: Long,
                       questionVersion: Long) {
        if (busy) return
        busy = true
        // A second physical tap cannot submit again; the guard above is the real protection.
        optionButtons.forEach { it.isEnabled = false }
        app.worker.execute {
            if (context.isDestroyed) return@execute
            val result = runCatching {
                val versionAtTap = app.repository.changes.version
                if (versionAtTap != questionVersion) {
                    // The resolved option world is already gone: do not reveal or write anything.
                    // Before calling this a stale question, use the repository's existing read-only
                    // token classification, which returns IDEMPOTENT/CONFLICT/STALE before it admits
                    // any new feedback. Forcing that admission closed (`canCommit = { false }`) keeps
                    // this call read-only and unable to touch the newly dealt card: an already
                    // committed token keeps its real classification (so the original “其它反馈”
                    // conflict prompt is not swallowed), while a token that was never committed stays
                    // REJECTED and is reported as an invalid question — never as a fake conflict. No
                    // result is ever built from this path.
                    val classified = app.repository.commitStudyFeedback(
                        card.token,
                        if (correct) FeedbackKind.KNOW else FeedbackKind.FORGET,
                        System.currentTimeMillis(), zone, credential, admission
                    ) { false }
                    AnswerAttempt(
                        committed = false,
                        outcome = classified,
                        version = versionAtTap,
                        worldChanged = classified == StudyCommitOutcome.REJECTED
                    )
                } else {
                    // The original reveal write runs first so a never-revealed card enters the exact
                    // phase the commit path expects. It is not the gate by itself: a false reveal (an
                    // already-revealed card, or a card this surface may no longer touch) still goes to
                    // the original commit, whose `admitsFeedback` re-checks answerRevealed plus day,
                    // scope, stage, quota and membership. Nothing here bypasses that admission.
                    val revealedNow = !card.answerRevealed &&
                        app.repository.revealStudyCard(card.token, credential, admission)
                    // Revealing is this surface's own write: it publishes exactly one revision when
                    // it really applied. Everything else must still be the question's world.
                    val expectedVersion = if (revealedNow) questionVersion + 1L else questionVersion
                    val afterReveal = app.repository.changes.version
                    if (afterReveal != expectedVersion) {
                        // An external commit landed between the tap and the reveal/commit boundary.
                        AnswerAttempt(false, null, afterReveal, worldChanged = true)
                    } else {
                        var worldChanged = false
                        // `canCommit` is the repository's final admission, taken after its long reads
                        // and immediately before the write. Comparing the same revision there also
                        // closes the worker-to-commit window; a mismatch refuses without a verdict.
                        // Re-check the activity too, exactly as before.
                        val outcome = app.repository.commitStudyFeedback(
                            card.token,
                            if (correct) FeedbackKind.KNOW else FeedbackKind.FORGET,
                            System.currentTimeMillis(), zone, credential, admission
                        ) {
                            !context.isDestroyed &&
                                (app.repository.changes.version == expectedVersion).also {
                                    if (!it) worldChanged = true
                                }
                        }
                        AnswerAttempt(
                            committed = outcome == StudyCommitOutcome.COMMITTED ||
                                outcome == StudyCommitOutcome.IDEMPOTENT,
                            outcome = outcome,
                            // Read on the same worker after the commit, so it includes this commit's
                            // own published revision and nothing can interleave between the two reads.
                            version = app.repository.changes.version,
                            revealFailed = !card.answerRevealed && !revealedNow,
                            worldChanged = worldChanged
                        )
                    }
                }
            }
            main.post {
                if (context.isDestroyed) return@post
                busy = false
                result.onSuccess { attempt ->
                    if (attempt.committed) {
                        // Invalidate any read still in flight so it cannot replace this result.
                        generation++
                        val answered = AnswerResult(
                            headword = card.entry.headword,
                            correct = correct,
                            chosenLabel = chosenLabel,
                            meaning = card.entry.meaning,
                            provenance = provenance(card.entry),
                            note = card.note
                        )
                        pendingResult = answered
                        resultVersion = attempt.version
                        // The host normally re-renders this page right after the commit; a page that
                        // is already detached only keeps the result for the next render.
                        mountedContent?.takeIf { it.isAttachedToWindow }?.let { showResult(it, answered) }
                    } else {
                        if (attempt.worldChanged) {
                            // The option world advanced after it was rendered (for example another
                            // page edited a candidate's meaning). Nothing was accepted for this tap,
                            // so never show a correct/wrong result; re-read the real round.
                            onToast("学习数据已变化，这道题已失效，未记录")
                        } else if (attempt.revealFailed &&
                            attempt.outcome == StudyCommitOutcome.REJECTED
                        ) {
                            // Neither the reveal nor the commit was accepted: the pending card is
                            // gone/expired, so the page must re-read the real round instead of
                            // showing any answer as recorded.
                            onToast("这张卡已失效，正在刷新")
                        } else when (attempt.outcome) {
                            StudyCommitOutcome.CONFLICT -> onToast("这张卡已用其它反馈提交过，未重复记录")
                            StudyCommitOutcome.STALE -> onToast("学习数据已变化，这张卡失效，未记录")
                            StudyCommitOutcome.REJECTED -> onToast("这张卡已不是当前待答卡，未记录")
                            else -> Unit
                        }
                        onRerender()
                    }
                }.onFailure {
                    onToast("提交失败，未记录：${it.message}")
                    onRerender()
                }
            }
        }
    }

    // ---- Small labels ---------------------------------------------------------------------------

    private fun caption(card: StudyCard): String {
        val base = if (card.newWord) "新词 · 首次学习" else "复习 · ${stageLabel(card.stageBefore)}"
        return if (card.answerRevealed) "$base · 旧待答卡" else base
    }

    private fun unavailableText(reason: ReviewUnavailableReason?): String = when (reason) {
        ReviewUnavailableReason.NO_READABLE_DICTIONARY -> "本机词库暂时不可用，无法准备四个释义选项。"
        ReviewUnavailableReason.NO_TARGET_MEANING -> "这张卡没有可用的中文释义。"
        ReviewUnavailableReason.NOT_ENOUGH_CANDIDATES -> "本机现有词义不足以凑出三个不同且不重复的干扰项。"
        else -> "暂时无法准备这道题。"
    }

    private fun provenance(entry: DictionaryEntry): String {
        val parts = listOf(entry.source, entry.version).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "来源 未标注" else "来源 ${parts.joinToString(" · ")}"
    }

    private fun stageLabel(stageBefore: Int): String =
        if (stageBefore < 0) "新词" else "第 ${stageBefore + 1} 阶段"

    private fun time(at: Long): String =
        Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

    private companion object {
        /** Live personal words offered as distractor candidates (recent-first, bounded). */
        const val PERSONAL_CANDIDATES = 12

        /** Seat window taken from each fixed book for one card token. */
        const val BOOK_CANDIDATES = 16

        /** Larger seat window used only once when the small window cannot supply three distractors. */
        const val WIDE_BOOK_CANDIDATES = 40

        /** Hard cap on resolved candidates per question; not a library scan. */
        const val MAX_CANDIDATES = 40

        /** Hard cap for the single widening pass (still bounded, still one worker, still read-only). */
        const val WIDE_MAX_CANDIDATES = 72
    }
}
