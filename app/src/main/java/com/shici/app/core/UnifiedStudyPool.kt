package com.shici.app.core

/**
 * Pure ordering for the unified new-word queue. The repository owns persistence, due-first ordering
 * and the global daily quota; this object only decides which new word to offer next.
 *
 * Order is fixed, not random and not reshuffled per day:
 * 1. personal vocabulary in its live recent-query order (newest query first);
 * 2. then the two fixed source books stably interleaved in their original book order
 *    (考研[0], 六级[0], 考研[1], 六级[1], …), skipping keys already emitted.
 *
 * Every member is already a normalized entry key (the study index validates that, and personal
 * members are persisted words keys), so dedup is by exact key: a word present in several sources
 * appears once and keeps its first position in that stream (personal first; inside the interleave,
 * whichever book is visited first at that step).
 */
object UnifiedStudyPool {
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
}
