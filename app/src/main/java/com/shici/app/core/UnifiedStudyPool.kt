package com.shici.app.core

/**
 * Pure ordering for the unified new-word queue. The repository owns persistence, due-first ordering
 * and the global daily quota; this object only decides which new word to offer next.
 *
 * [merged] is the legacy fixed order (personal recent-first, then ky/cet6 stably interleaved) and is
 * kept unchanged for existing non-UI callers. The study UI uses [dailyMerged] instead: today's
 * unlearned original-book keys are re-ordered by a stable hash of `dayKey + key`, so each day draws a
 * different, recomputable sample from the books, and that daily book stream is alternated with the
 * live personal stream. Every member is already a normalized entry key, so dedup is by exact key.
 */
object UnifiedStudyPool {
    /**
     * Legacy order, unchanged: personal vocabulary in its live recent-query order (newest query
     * first); then the two fixed source books stably interleaved in their original book order
     * (考研[0], 六级[0], 考研[1], 六级[1], …), skipping keys already emitted.
     *
     * Every member is already a normalized entry key (the study index validates that, and personal
     * members are persisted words keys), so dedup is by exact key: a word present in several sources
     * appears once and keeps its first position in that stream (personal first; inside the interleave,
     * whichever book is visited first at that step).
     */
    fun merged(personal: List<String>, kaoyan: List<String>, cet6: List<String>): List<String> {
        val seen = HashSet<String>(personal.size + kaoyan.size + cet6.size)
        val ordered = ArrayList<String>(personal.size + kaoyan.size + cet6.size)
        fun emit(key: String) {
            if (seen.add(key)) ordered += key
        }
        personal.forEach { emit(it) }
        var kaoyanIndex = 0
        var cet6Index = 0
        while (kaoyanIndex < kaoyan.size || cet6Index < cet6.size) {
            if (kaoyanIndex < kaoyan.size) emit(kaoyan[kaoyanIndex++])
            if (cet6Index < cet6.size) emit(cet6[cet6Index++])
        }
        return ordered
    }

    /**
     * Today's real new-word order: unlearned personal words in their live recent-query order plus
     * unlearned original-book words (deduplicated across 考研/六级 and against the personal stream)
     * in a stable per-day sampled order. When both streams have candidates they are alternated; the
     * first stream is the personal one on even [introducedToday] and the original-book one on odd
     * [introducedToday]. Because that count is read from committed `new_word` feedback rows, a reload
     * after every feedback flips the priority instead of always restarting from the personal pool.
     * If one stream runs out, the other fills the remaining positions; with no personal words at all
     * the books alone still provide the full daily goal.
     *
     * The book sample is sorted by a stable FNV-1a hash of `dayKey + key` (tied by the key itself),
     * not by [Random] or a shuffle, so the same day, members and progress always yield the same next
     * card across restarts and devices, while a new day draws a different sample. Newly queried
     * personal words can appear in the not-yet-dealt positions; already committed counts, today's
     * pending card and existing progress are never changed here.
     *
     * [studied] is the persisted state-key set. Learned keys are not new candidates, but every input
     * member is still returned (after all candidates, in [merged] order) so the membership keeps all
     * due/reviewable members and the old pending-card chain intact.
     */
    fun dailyMerged(
        personal: List<String>,
        kaoyan: List<String>,
        cet6: List<String>,
        dayKey: String,
        studied: Set<String>,
        introducedToday: Int
    ): List<String> {
        val personalCandidates = personal.filter { it !in studied }.distinct()
        val personalSet = personalCandidates.toHashSet()
        val bookSeen = HashSet<String>(kaoyan.size + cet6.size)
        val bookCandidates = ArrayList<String>(kaoyan.size + cet6.size)
        fun addBook(key: String) {
            if (key !in studied && key !in personalSet && bookSeen.add(key)) bookCandidates += key
        }
        kaoyan.forEach { addBook(it) }
        cet6.forEach { addBook(it) }
        bookCandidates.sortWith(
            compareBy<String> { dailyOrder(dayKey, it) }.thenBy { it }
        )

        val ordered = ArrayList<String>(personalCandidates.size + bookCandidates.size)
        var personalIndex = 0
        var bookIndex = 0
        val bothPooled = personalCandidates.isNotEmpty() && bookCandidates.isNotEmpty()
        var takePersonal = !bothPooled || introducedToday % 2 == 0
        while (personalIndex < personalCandidates.size || bookIndex < bookCandidates.size) {
            val fromPersonal = when {
                personalIndex >= personalCandidates.size -> false
                bookIndex >= bookCandidates.size -> true
                else -> takePersonal
            }
            if (fromPersonal) ordered += personalCandidates[personalIndex++]
            else ordered += bookCandidates[bookIndex++]
            takePersonal = !fromPersonal
        }

        // Learned members keep their membership (and therefore any due/reviewable state and an old
        // pending card), but never appear as new candidates: they go after every candidate.
        val emitted = ordered.toHashSet()
        merged(personal, kaoyan, cet6).forEach { if (emitted.add(it)) ordered += it }
        return ordered
    }

    /**
     * Stable 64-bit FNV-1a over `dayKey`, a NUL separator and `key`. Pure arithmetic, so the same day
     * and word produce the same position on every process start and device; no Random or shuffle.
     */
    private fun dailyOrder(dayKey: String, key: String): Long {
        var hash = 0xcbf29ce484222325uL.toLong()
        for (ch in dayKey) hash = (hash xor ch.code.toLong()) * 0x100000001b3L
        hash = hash * 0x100000001b3L
        for (ch in key) hash = (hash xor ch.code.toLong()) * 0x100000001b3L
        return hash
    }
}
