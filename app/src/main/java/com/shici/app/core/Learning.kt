package com.shici.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class QueryEvent(
    val id: String,
    val entryKey: String,
    val rawText: String,
    val finalForm: String,
    val time: Long,
    val revoked: Boolean = false
)

data class LearnedWord(
    val entry: DictionaryEntry,
    val source: String,
    val version: String,
    val lastForm: String,
    val queryCount: Int,
    val firstAt: Long,
    val lastAt: Long,
    val mastered: Boolean,
    val note: String
)

data class LearningStats(
    val totalQueries: Int,
    val todayQueries: Int,
    val todayNew: Int,
    val learning: Int,
    val mastered: Int
)

object LearningRules {
    /** The event ID is the retry key; a different payload must never reuse it. */
    fun isDuplicate(existing: QueryEvent?, incoming: QueryEvent): Boolean {
        if (existing == null) return false
        require(existing.copy(revoked = false) == incoming.copy(revoked = false)) {
            "查询标识已用于不同内容"
        }
        return true
    }

    fun summarize(words: List<LearnedWord>, events: List<QueryEvent>, date: LocalDate, zone: ZoneId): LearningStats {
        val active = events.filterNot { it.revoked }
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return LearningStats(
            totalQueries = active.size,
            todayQueries = active.count { it.time >= start && it.time < end },
            todayNew = words.count { it.firstAt >= start && it.firstAt < end },
            learning = words.count { !it.mastered },
            mastered = words.count { it.mastered }
        )
    }

    fun localDate(time: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
}
