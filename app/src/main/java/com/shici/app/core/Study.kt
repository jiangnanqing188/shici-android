package com.shici.app.core

import java.time.Instant
import java.time.ZoneId

/** Fixed first-version study books. Personal vocabulary is a runtime set, not a generated index. */
enum class StudyBook(val id: String, val label: String) {
    KAOYAN("ky", "考研"),
    CET6("cet6", "六级"),
    PERSONAL("personal", "个人生词");

    companion object {
        val default: StudyBook = KAOYAN
        fun fromId(id: String): StudyBook? = entries.firstOrNull { it.id == id }
    }
}

/** Exactly three feedback choices; no free-form grading. */
enum class FeedbackKind(val id: String) {
    KNOW("know"), FUZZY("fuzzy"), FORGET("forget");

    companion object {
        fun fromId(id: String): FeedbackKind? = entries.firstOrNull { it.id == id }
    }
}

data class StudySettings(
    val selectedBook: StudyBook = StudyBook.default,
    val dailyNewGoal: Int = StudyRules.DEFAULT_DAILY_NEW_GOAL
)

/**
 * One introduced word's shared progress across every book that contains its entry key.
 * [lastReviewSeq] names the durable commit sequence of the feedback that produced this exact
 * stage/due/last-review, so the true final commit is known without assuming a monotonic wall clock.
 */
data class StudyState(
    val entryKey: String,
    val book: StudyBook,
    val stage: Int,
    val dueAt: Long,
    val introducedAt: Long,
    val lastReviewAt: Long,
    val lastReviewSeq: Long = 0
)

/**
 * A pending card. [entry] is the immutable word snapshot taken when the card was dealt, preserved
 * across rotation/restart; the word is not written to `words` until a real feedback commits.
 * [note] is the word's current personal note (empty for a brand-new card), read live so a note
 * saved after the card was dealt is still shown with the answer.
 */
data class StudyCard(
    val token: String,
    val entryKey: String,
    val book: StudyBook,
    val stageBefore: Int,
    val newWord: Boolean,
    val answerRevealed: Boolean,
    val entry: DictionaryEntry,
    val dayKey: String,
    val createdAt: Long,
    val note: String = ""
)

/**
 * One committed real feedback. Tokens are the retry key; the row is never a QueryEvent.
 * [seq] is the durable commit order assigned inside the commit transaction. The wall clock,
 * token text and list position are never used as commit order.
 */
data class StudyReview(
    val token: String,
    val entryKey: String,
    val feedback: FeedbackKind,
    val stageBefore: Int,
    val stageAfter: Int,
    val dueAfter: Long,
    val reviewedAt: Long,
    val dayKey: String,
    val zoneId: String,
    val newWord: Boolean,
    val seq: Long = 0
)

/** Aggregates for the current selected book, re-derived from persisted rows, never cached. */
data class StudyProgress(
    val dueNow: Int,
    val newRemaining: Int,
    val introducedToday: Int,
    val todayReviewed: Int,
    val todayAnswers: Int,
    val nextDueAt: Long?,
    val totalIntroduced: Int
)

/** One read for the study UI: settings, the single pending card (if any) and today's aggregates. */
data class StudyView(
    val settings: StudySettings,
    val card: StudyCard?,
    val progress: StudyProgress,
    val credential: Long
)

/**
 * Fixed first-version schedule. This is **not** claimed to be a validated memory algorithm.
 *
 * A word starts at [StudyRules.NEW_STAGE_BEFORE] (not yet introduced). 认识 advances one stage and
 * is due after 1/3/7/14/30 days, capping at 30. 模糊 is not a promotion: the stage stays exactly as
 * it was (including a not-yet-introduced -1) and the card returns in 1 day. 忘记 sends the word back
 * to relearning ([StudyRules.NEW_STAGE_BEFORE]) and returns it in 10 minutes, so the next 认识
 * restarts at one day instead of skipping ahead. A word is "introduced" only on its first real
 * feedback; every later feedback is a review of a word that already has a persisted stage.
 */
object StudyRules {
    val INTERVALS_DAYS = intArrayOf(1, 3, 7, 14, 30)
    const val MAX_STAGE = 4
    const val NEW_STAGE_BEFORE = -1
    const val DEFAULT_DAILY_NEW_GOAL = 20
    const val MIN_DAILY_NEW_GOAL = 1
    const val MAX_DAILY_NEW_GOAL = 200
    const val FORGET_DELAY_MILLIS = 10L * 60L * 1000L
    const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    fun clampGoal(goal: Int): Int = goal.coerceIn(MIN_DAILY_NEW_GOAL, MAX_DAILY_NEW_GOAL)

    fun stageIntervalDays(stage: Int): Int = INTERVALS_DAYS[stage.coerceIn(0, MAX_STAGE)]

    /**
     * The stage a real feedback moves the word to. 认识 (KNOW) advances one stage and caps at [MAX_STAGE];
     * 模糊 (FUZZY) leaves the stage unchanged; 忘记 (FORGET) resets the word to relearning at
     * [NEW_STAGE_BEFORE] so a later 认识 restarts at the first interval.
     */
    fun nextStage(stageBefore: Int, feedback: FeedbackKind): Int = when (feedback) {
        FeedbackKind.KNOW -> (stageBefore + 1).coerceIn(0, MAX_STAGE)
        FeedbackKind.FUZZY -> stageBefore
        FeedbackKind.FORGET -> NEW_STAGE_BEFORE
    }

    fun dueAt(now: Long, stageBefore: Int, feedback: FeedbackKind): Long {
        // Every delay is a small positive number of milliseconds, so `now + delay` can only overflow
        // when `now` sits within `delay` of Long.MAX_VALUE. Clamp that sum to Long.MAX_VALUE: a
        // reliable future upper bound, never a negative value that would read as already due. All
        // ordinary timestamps are returned unchanged and no historical Long value is truncated here.
        val delay = when (feedback) {
            FeedbackKind.KNOW -> stageIntervalDays(nextStage(stageBefore, feedback)) * DAY_MILLIS
            FeedbackKind.FUZZY -> DAY_MILLIS
            FeedbackKind.FORGET -> FORGET_DELAY_MILLIS
        }
        return if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }

    /** The persisted day bucket: fixed at commit time so a later timezone change never re-buckets it. */
    fun dayKey(time: Long, zone: ZoneId): String = Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString()
}
