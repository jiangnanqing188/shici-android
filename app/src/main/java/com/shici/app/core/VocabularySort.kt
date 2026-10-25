package com.shici.app.core

import java.util.Locale

/**
 * The four orders offered for the personal vocabulary list (生词本).
 *
 * [label] is the user-facing, full description. The UI may render a shorter chip text, but should
 * keep [label] in accessibility descriptions so the chord "最近/最早" is never confused with
 * "recently added" while "多/少" unambiguously means query count.
 *
 * The default order is [RECENT_FIRST]: most recently queried words first.
 */
enum class VocabularySort(val label: String) {
    RECENT_FIRST("最近查询"),
    OLDEST_FIRST("最早查询"),
    MOST_QUERIED("查询最多"),
    LEAST_QUERIED("查询最少")
}

/**
 * Pure ordering of an already-filtered vocabulary snapshot.
 *
 * Design rules:
 * - [apply] never mutates [words] and never reads or writes the repository, the query log, the
 *   export selection or any Android state. It only uses each [LearnedWord]'s existing
 *   [LearnedWord.lastAt] and [LearnedWord.queryCount]; it never increments a count.
 * - The full snapshot passed in is returned in the requested order: no word is dropped and no word
 *   is duplicated. Callers must pass the complete filtered list, not a page/slice.
 * - Ties on the primary value are broken by the entry key (headword) case-insensitively, then by
 *   the raw key. Words that are completely equal keep their incoming order because Kotlin's
 *   `sortedWith` is a stable sort, so repeated renders are deterministic.
 * - Ordering the display list never changes membership of the PDF export selection; export
 *   selections are keyed by `entry.key` and are order-independent.
 */
object VocabularyOrdering {

    private val headwordThenKey: Comparator<LearnedWord> =
        compareBy<LearnedWord> { it.entry.key.lowercase(Locale.ROOT) }
            .thenBy { it.entry.key }

    /**
     * Returns a new list with every element of [words] in [sort] order. Empty and single-element
     * inputs are copied, so a caller can never mutate the returned list back into the snapshot.
     *
     * This is intended for the vocabulary list's display path only (after search + "仅收藏"
     * filtering). Do not use it to change the source snapshot used for export/selection semantics.
     */
    fun apply(words: List<LearnedWord>, sort: VocabularySort): List<LearnedWord> {
        val primary: Comparator<LearnedWord> = when (sort) {
            VocabularySort.RECENT_FIRST -> compareByDescending { it.lastAt }
            VocabularySort.OLDEST_FIRST -> compareBy { it.lastAt }
            VocabularySort.MOST_QUERIED -> compareByDescending { it.queryCount }
            VocabularySort.LEAST_QUERIED -> compareBy { it.queryCount }
        }
        return words.sortedWith(primary.then(headwordThenKey))
    }
}
