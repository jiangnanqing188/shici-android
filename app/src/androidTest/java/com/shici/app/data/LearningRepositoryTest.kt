package com.shici.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shici.app.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId

/** Requires an Android device/emulator; compiling this test is not a SQLite runtime check. */
@RunWith(AndroidJUnit4::class)
class LearningRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: LearningRepository
    private lateinit var name: String
    private val entry = DemoDictionary.entries.first()
    private fun event(id: String, time: Long = 100) = QueryEvent(id, entry.key, "Abandoned", "abandoned", time)

    @Before fun setup() {
        name = "test-${UUID.randomUUID()}.db"
        repository = LearningRepository(context, name)
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(name) }

    @Test fun committedChangesAreObservedOnlyAfterTransactionEnds() {
        val seen = mutableListOf<Pair<Long, LearningSnapshot>>()
        repository.changes.observe { version ->
            assertFalse(repository.writableDatabase.inTransaction())
            seen += version to repository.snapshot()
        }
        repository.record(event("one"), entry)
        repository.updateWord(entry.key, true, "已保存笔记")
        repository.record(event("two", 200), entry)
        repository.revoke("two")
        assertEquals(listOf(1L, 2L, 3L, 4L), seen.map { it.first })
        assertEquals(listOf(1, 1, 2, 1), seen.map { it.second.stats.totalQueries })
        assertEquals("已保存笔记", seen.last().second.words.single().note)
        assertTrue(seen.last().second.words.single().mastered)
    }
    @Test fun duplicateCallbacksAndNoOpUpdatesDoNotPublishOrChangeData() {
        val versions = mutableListOf<Long>()
        repository.changes.observe { versions += it }
        repository.record(event("one"), entry)
        repository.record(event("one"), entry)
        repository.updateWord(entry.key, false, "")
        repository.updateWord("missing", true, "ignored")
        repository.revoke("missing")
        assertEquals(listOf(1L), versions)
        repository.revoke("one")
        repository.revoke("one")
        repository.record(event("one"), entry)
        assertEquals(listOf(1L, 2L), versions)
        assertTrue(repository.words().isEmpty())
    }
    @Test fun correctionPublishesOneCommittedSnapshotAndKeepsOriginalNotes() {
        repository.record(event("original"), entry)
        repository.updateWord(entry.key, true, "原词笔记")
        val other = DemoDictionary.entries[1]
        val corrected = QueryEvent("replacement", other.key, "Abandoned", other.headword, 200)
        val seen = mutableListOf<LearningSnapshot>()
        repository.changes.observe { seen += repository.snapshot() }
        repository.record(corrected, other, "original")
        repository.record(corrected, other, "original")
        assertEquals(1, seen.size)
        assertEquals(other.key, seen.single().words.single().entry.key)
        assertEquals(1, seen.single().stats.totalQueries)
        repository.record(event("again", 300), entry)
        val restored = repository.words().first { it.entry.key == entry.key }
        assertEquals("原词笔记", restored.note)
        assertTrue(restored.mastered)
    }
    @Test fun cancelledOrThrowingCorrectionDoesNotPublishRolledBackChanges() {
        repository.record(event("original"), entry)
        val versions = mutableListOf<Long>()
        repository.changes.observe { versions += it }
        var calls = 0
        assertFalse(repository.record(event("cancelled", 200), entry, "original") { ++calls == 1 })
        repository.writableDatabase.execSQL("""CREATE TEMP TRIGGER reject_insert BEFORE INSERT ON events
            WHEN NEW.id='failed' BEGIN SELECT RAISE(ABORT, 'test rollback'); END""")
        assertNotNull(runCatching { repository.record(event("failed", 200), entry, "original") }.exceptionOrNull())
        assertNotNull(runCatching { repository.record(event("original", 999), entry) }.exceptionOrNull())
        assertTrue(versions.isEmpty())
        assertEquals(1L, repository.changes.version)
        assertEquals("original", repository.latestEventId(entry.key))
        assertEquals(1, repository.snapshot().stats.totalQueries)
    }
    @Test fun failedRevokeAndUpdateDoNotPublishOrLoseNotes() {
        repository.record(event("one"), entry)
        repository.updateWord(entry.key, true, "保留笔记")
        val versions = mutableListOf<Long>()
        repository.changes.observe { versions += it }
        repository.writableDatabase.execSQL("""CREATE TEMP TRIGGER reject_revoke BEFORE UPDATE ON events
            BEGIN SELECT RAISE(ABORT, 'test rollback'); END""")
        repository.writableDatabase.execSQL("""CREATE TEMP TRIGGER reject_edit BEFORE UPDATE ON words
            BEGIN SELECT RAISE(ABORT, 'test rollback'); END""")
        assertNotNull(runCatching { repository.revoke("one") }.exceptionOrNull())
        assertNotNull(runCatching { repository.updateWord(entry.key, false, "新笔记") }.exceptionOrNull())
        assertTrue(versions.isEmpty())
        assertEquals("保留笔记", repository.words().single().note)
        assertTrue(repository.words().single().mastered)
        assertEquals(1, repository.snapshot().stats.totalQueries)
    }
    @Test fun outerTransactionCannotCausePrematureCommitNotification() {
        val versions = mutableListOf<Long>()
        repository.changes.observe { versions += it }
        val db = repository.writableDatabase
        db.beginTransaction()
        try {
            assertTrue(runCatching { repository.record(event("nested"), entry) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { repository.revoke("nested") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { repository.updateWord(entry.key, false, "nested") }.exceptionOrNull() is IllegalStateException)
        } finally { db.endTransaction() }
        assertTrue(versions.isEmpty())
        assertTrue(repository.events().isEmpty())
    }
    @Test fun exportReadsCommittedDataAndFreezesSavedNotesCountsAndSearch() {
        repository.record(event("one"), entry)
        val staleUi = repository.words()
        repository.record(event("two", 200), entry)
        repository.updateWord(entry.key, true, "导出笔记")
        val date = LocalDate.of(2026, 10, 9)
        val frozen = repository.exportSnapshot("导出", date)
        assertEquals(1, staleUi.single().queryCount)
        assertEquals(2, frozen.words.single().queryCount)
        assertEquals("导出笔记", frozen.words.single().note)
        assertTrue(frozen.words.single().mastered)
        assertEquals("导出", frozen.search)
        assertEquals(date, frozen.date)
        repository.record(event("three", 300), entry)
        repository.updateWord(entry.key, false, "后来笔记")
        repository.revoke("two")
        assertEquals(2, frozen.words.single().queryCount)
        assertEquals("导出笔记", frozen.words.single().note)
        assertTrue(repository.exportSnapshot("导出", date).words.isEmpty())
        assertEquals("后来笔记", repository.exportSnapshot("后来", date).words.single().note)
    }
    @Test fun snapshotStatsAgreeWithWordsAfterRecordCorrectionAndRevoke() {
        val day = LocalDate.of(2026, 10, 9)
        val zone = ZoneId.of("Asia/Shanghai")
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        repository.record(event("one", start), entry)
        val other = DemoDictionary.entries[1]
        repository.record(QueryEvent("replacement", other.key, entry.headword, other.headword, start + 1), other, "one")
        val snapshot = repository.snapshot(day, zone)
        assertEquals(1, snapshot.words.sumOf { it.queryCount })
        assertEquals(LearningStats(1, 1, 1, 1, 0), snapshot.stats)
        assertEquals(repository.changes.version, snapshot.version)
        repository.revoke("replacement")
        assertEquals(LearningStats(0, 0, 0, 0, 0), repository.snapshot(day, zone).stats)
    }

    @Test fun retriesNeverDoubleCountAndRevokedRetryDoesNotResurrect() {
        repository.record(event("one"), entry)
        repository.record(event("one"), entry)
        assertEquals(1, repository.words().single().queryCount)
        repository.revoke("one")
        repository.record(event("one"), entry)
        assertTrue(repository.words().isEmpty())
    }
    @Test fun undoOnlyCurrentEventPreservesOlderMetadata() {
        repository.record(event("one"), entry)
        repository.updateWord(entry.key, true, "个人笔记")
        repository.record(event("two", 200), entry)
        assertEquals(2, repository.words().single().queryCount)
        repository.revoke("two")
        val word = repository.words().single()
        assertEquals(1, word.queryCount)
        assertEquals("个人笔记", word.note)
        assertTrue(word.mastered)
        assertEquals(100L, word.lastAt)
    }
    @Test fun correctionRevokesAndInsertsInOneTransaction() {
        repository.record(event("one"), entry)
        val other = DemoDictionary.entries[1]
        repository.record(QueryEvent("corrected", other.key, "Abandoned", "ability", 200), other, "one")
        assertEquals(other.key, repository.words().single().entry.key)
        assertEquals(1, repository.events().count { !it.revoked })
        repository.record(QueryEvent("corrected", other.key, "Abandoned", "ability", 200), other, "one")
        assertEquals(1, repository.words().single().queryCount)
    }
    @Test fun cancelImmediatelyBeforeCommitRollsBackEventAndWord() {
        var calls = 0
        assertFalse(repository.record(event("cancelled"), entry, canCommit = { ++calls == 1 }))
        assertTrue(repository.events().isEmpty())
        assertTrue(repository.words().isEmpty())
    }
    @Test fun cancelledCorrectionDoesNotRevokeOriginal() {
        repository.record(event("original"), entry)
        var calls = 0
        assertFalse(repository.record(event("cancelled", 200), entry, "original") { ++calls == 1 })
        assertEquals("original", repository.latestEventId(entry.key))
        assertEquals(1, repository.words().single().queryCount)
    }
    @Test fun searchAndReopenRetainActualRecords() {
        repository.record(event("one"), entry)
        repository.updateWord(entry.key, false, "恢复笔记")
        assertEquals(1, repository.words("恢复").size)
        assertTrue(repository.words("not in database").isEmpty())
        repository.close()
        repository = LearningRepository(context, name)
        assertEquals("恢复笔记", repository.words().single().note)
    }
    @Test fun reusedIdWithChangedPayloadDoesNotOverwriteExistingEvent() {
        repository.record(event("one"), entry)
        val error = runCatching { repository.record(event("one", 200), entry) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(100L, repository.words().single().lastAt)
        assertEquals(1, repository.words().single().queryCount)
    }
}
