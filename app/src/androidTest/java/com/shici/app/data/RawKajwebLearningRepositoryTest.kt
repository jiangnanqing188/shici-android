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

/** Isolated SQLite regressions; meanings below are self-authored snapshot fixtures. */
@RunWith(AndroidJUnit4::class)
class RawKajwebLearningRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: LearningRepository
    private lateinit var name: String
    private val oldEntry = DemoDictionary.entries.first()
    private val rawEntry = oldEntry.copy(partOfSpeech = "v", meaning = "原始快照测试夹具",
        source = RawKajwebDictionary.SOURCE, version = RawKajwebDictionary.VERSION)
    private fun event(id: String, time: Long = 100) = QueryEvent(id, oldEntry.key, "Abandon", "abandon", time)

    @Before fun setup() {
        name = "raw-kajweb-test-${UUID.randomUUID()}.db"
        repository = LearningRepository(context, name)
        repository.record(event("legacy"), oldEntry)
        repository.updateWord(oldEntry.key, true, "我的旧笔记")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(name) }

    @Test fun onlyNewSuccessfulQueryRefreshesSourceWhileKeepingNotesMasteryAndOldEvents() {
        val before = repository.events()
        assertEquals(DemoDictionary.SOURCE, repository.words().single().source)
        repository.record(event("raw", 200), rawEntry)
        val word = repository.words().single()
        assertEquals(rawEntry, word.entry)
        assertEquals(RawKajwebDictionary.SOURCE, word.source)
        assertEquals(RawKajwebDictionary.VERSION, word.version)
        assertEquals("我的旧笔记", word.note)
        assertTrue(word.mastered)
        assertEquals(2, word.queryCount)
        assertEquals(100L, word.firstAt)
        assertEquals(200L, word.lastAt)
        assertEquals(before.single(), repository.events().first { it.id == "legacy" })
        assertEquals(word, repository.exportSnapshot("").words.single())
    }
    @Test fun duplicateEventDoesNotRefreshOldOrNewDictionarySnapshotOrPublishChanges() {
        val old = repository.words().single()
        val initialVersion = repository.changes.version
        repository.record(event("legacy"), rawEntry)
        assertEquals(old, repository.words().single())
        assertEquals(initialVersion, repository.changes.version)
        repository.record(event("raw", 200), rawEntry)
        val raw = repository.words().single()
        val rawVersion = repository.changes.version
        repository.record(event("raw", 200), rawEntry.copy(meaning = "重复回调不得刷新"))
        repository.record(event("legacy"), oldEntry)
        assertEquals(raw, repository.words().single())
        assertEquals(rawVersion, repository.changes.version)
    }
    @Test fun revokedDuplicateCannotResurrectOrRefreshSource() {
        repository.record(event("second", 200), oldEntry)
        repository.revoke("second")
        val before = repository.words().single()
        val events = repository.events().sortedBy { it.id }
        val version = repository.changes.version
        repository.record(event("second", 200), rawEntry)
        assertEquals(before, repository.words().single())
        assertEquals(events, repository.events().sortedBy { it.id })
        assertEquals(version, repository.changes.version)
    }
    @Test fun cancelledRefreshAndCorrectionRollbackBothSnapshotAndEvents() {
        val before = repository.words().single()
        val events = repository.events()
        val version = repository.changes.version
        var calls = 0
        assertFalse(repository.record(event("cancelled", 200), rawEntry, "legacy") { ++calls == 1 })
        assertEquals(before, repository.words().single())
        assertEquals(events, repository.events())
        assertEquals(version, repository.changes.version)
    }
    @Test fun failingInsertDoesNotLeaveRefreshedSourceOrRevokeOriginal() {
        val before = repository.words().single()
        val events = repository.events()
        val version = repository.changes.version
        repository.writableDatabase.execSQL("""CREATE TEMP TRIGGER reject_raw BEFORE INSERT ON events
            WHEN NEW.id='failed' BEGIN SELECT RAISE(ABORT, 'test rollback'); END""")
        assertNotNull(runCatching { repository.record(event("failed", 200), rawEntry, "legacy") }.exceptionOrNull())
        assertEquals(before, repository.words().single())
        assertEquals(events, repository.events())
        assertEquals(version, repository.changes.version)
    }
    @Test fun successfulSameWordCorrectionCountsOnceAndRetainsPersonalFields() {
        repository.record(event("corrected", 200), rawEntry, "legacy")
        repository.record(event("corrected", 200), oldEntry, "legacy")
        val word = repository.words().single()
        assertEquals(rawEntry, word.entry)
        assertEquals(1, word.queryCount)
        assertEquals("我的旧笔记", word.note)
        assertTrue(word.mastered)
        assertTrue(repository.events().first { it.id == "legacy" }.revoked)
        assertEquals(1, repository.snapshot().stats.totalQueries)
    }
    @Test fun reopenDoesNotEagerlyRelabelUntouchedLegacyWords() {
        val other = DictionaryEntry("quantum", "n", "另一个原始快照夹具",
            RawKajwebDictionary.SOURCE, RawKajwebDictionary.VERSION)
        repository.record(QueryEvent("another", other.key, "quantum", "quantum", 200), other)
        repository.close()
        repository = LearningRepository(context, name)
        val old = repository.words().first { it.entry.key == oldEntry.key }
        assertEquals(oldEntry, old.entry)
        assertEquals(DemoDictionary.SOURCE, old.source)
        assertEquals(DemoDictionary.VERSION, old.version)
        assertEquals("我的旧笔记", old.note)
        assertTrue(old.mastered)
        val raw = repository.words().first { it.entry.key == other.key }
        assertEquals(other, raw.entry)
        assertTrue(LearningExportText.row(0, old).contains("自编演示数据"))
        assertTrue(LearningExportText.row(1, raw).contains("未逐条核对"))
    }
}
