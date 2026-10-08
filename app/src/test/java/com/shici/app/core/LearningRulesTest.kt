package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LearningRulesTest {
    private val entry = DemoDictionary.entries.first()
    private fun event(id: String, time: Long, revoked: Boolean = false) = QueryEvent(id, entry.key, entry.headword, entry.headword, time, revoked)
    private fun word(first: Long, mastered: Boolean = false) = LearnedWord(entry, DemoDictionary.SOURCE,
        DemoDictionary.VERSION, entry.headword, 1, first, first, mastered, "note")

    @Test fun sameEventRetryIsDuplicateIncludingRevocation() {
        val incoming = event("same", 100)
        assertFalse(LearningRules.isDuplicate(null, incoming))
        assertTrue(LearningRules.isDuplicate(incoming, incoming))
        assertTrue(LearningRules.isDuplicate(incoming.copy(revoked = true), incoming))
    }
    @Test(expected = IllegalArgumentException::class) fun differentPayloadCannotReuseEventId() {
        LearningRules.isDuplicate(event("same", 100), event("same", 101))
    }
    @Test fun newUserQueryUsesAnotherId() {
        assertFalse(LearningRules.isDuplicate(null, event("new", 100)))
    }
    @Test fun localDayStartInclusiveEndExclusiveAndRevokedExcluded() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 10, 8)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val stats = LearningRules.summarize(listOf(word(start), word(start - 1, true)), listOf(
            event("before", start - 1), event("start", start), event("end", end), event("revoked", start + 1, true)
        ), day, zone)
        assertEquals(LearningStats(3, 1, 1, 1, 1), stats)
        assertEquals(day, LearningRules.localDate(start, zone))
    }
    @Test fun daylightSavingDayIsNotHardcodedAs24Hours() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 11, 1)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val lastHour = start + 24 * 60 * 60 * 1000L
        assertEquals(1, LearningRules.summarize(emptyList(), listOf(event("late", lastHour)), day, zone).todayQueries)
    }
    @Test fun emptyStoreHasOnlyZeros() {
        assertEquals(LearningStats(0, 0, 0, 0, 0), LearningRules.summarize(emptyList(), emptyList(), LocalDate.now(), ZoneId.systemDefault()))
    }
}
