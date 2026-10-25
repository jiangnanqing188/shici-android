package com.shici.app.core

/**
 * Read-only, derived summary for the one unified study entrance. It is computed from the persisted
 * study round ([StudyView] / [StudyProgress] and the single pending card) plus the already loaded
 * source memberships; it owns no state, writes nothing and is never a second ledger.
 *
 * All new-word numbers are real words: [introducedToday] counts committed `new_word` feedback rows,
 * while [todayAnswers] is a feedback count and must never be presented as a word count. Due reviews
 * are kept separate from the daily new-word goal ([dueNow] is never folded into [dailyNewGoal]).
 *
 * Opening the page changes none of these values: [startedToday] is true only after a real feedback
 * row exists for today, so a merely rendered overview can never look completed.
 */
data class StudyOverview(
    val dailyNewGoal: Int,
    val introducedToday: Int,
    val newRemaining: Int,
    val dueNow: Int,
    val todayReviewed: Int,
    val todayAnswers: Int,
    val totalIntroduced: Int,
    val nextDueAt: Long?,
    val scope: StudyScopeSummary,
    /**
     * Real personal words that still have no study state, in the personal set's existing order.
     * They occupy the first available new-word positions; the fixed books fill the rest. Computed
     * from the persisted state keys, so it is a description, never a second queue.
     */
    val personalUnlearned: Int,
    val pendingCard: Boolean,
    val pendingNewWord: Boolean
) {
    /** New-word goal still unfilled today, independent of how many candidates the pool can offer. */
    val goalRemaining: Int get() = (dailyNewGoal - introducedToday).coerceAtLeast(0)

    /** Cards the repository says can be answered in this round: due reviews plus new words. */
    val cardsAvailableNow: Int get() = dueNow + newRemaining

    /** True only from real feedback rows; rendering the plan alone never marks the day as started. */
    val startedToday: Boolean get() = introducedToday > 0 || todayAnswers > 0

    val canStudyNow: Boolean get() = pendingCard || cardsAvailableNow > 0

    /** The safe entrance label. Reading it never clears a persisted pending card. */
    val entryLabel: String get() = when {
        pendingCard && pendingNewWord -> "继续学习 · 当前新词"
        pendingCard -> "继续学习 · 当前复习"
        canStudyNow -> "开始学习"
        else -> "刷新学习计划"
    }

    companion object {
        /**
         * [personalKeys] is the live personal set in its existing recent-first order; [studied] is
         * the set of keys that already have a persisted state. A personal key that already has a
         * state is not a new candidate, regardless of whether it is due or not.
         */
        fun from(
            view: StudyView,
            scope: StudyScopeSummary,
            personalKeys: List<String>,
            studied: Set<String>
        ): StudyOverview = StudyOverview(
            dailyNewGoal = view.settings.dailyNewGoal,
            introducedToday = view.progress.introducedToday,
            newRemaining = view.progress.newRemaining,
            dueNow = view.progress.dueNow,
            todayReviewed = view.progress.todayReviewed,
            todayAnswers = view.progress.todayAnswers,
            totalIntroduced = view.progress.totalIntroduced,
            nextDueAt = view.progress.nextDueAt,
            scope = scope,
            personalUnlearned = personalKeys.count { it !in studied },
            pendingCard = view.card != null,
            pendingNewWord = view.card?.newWord == true
        )
    }
}

/**
 * Sizes of the three sources that make up the one unified study scope. [unified] is the count after
 * deduplication by entry key, so [personal] + [kaoyan] + [cet6] may exceed it; that overlap is
 * exactly why one word keeps one shared progress. The sizes describe the whole scopes, not how many
 * words are still unlearned.
 */
data class StudyScopeSummary(
    val personal: Int,
    val kaoyan: Int,
    val cet6: Int,
    val unified: Int
)
