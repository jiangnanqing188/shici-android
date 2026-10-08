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
