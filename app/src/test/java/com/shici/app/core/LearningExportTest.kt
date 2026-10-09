package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LearningExportTest {
    private val word = LearnedWord(DemoDictionary.entries.first(), DemoDictionary.SOURCE,
        DemoDictionary.VERSION, "abandoned", 2, 100, 200, true, "已保存笔记")
    private fun snapshot(search: String = "笔记") = LearningExportSnapshot(listOf(word), search, LocalDate.of(2026, 10, 9))

    @Test fun confirmationAndFileWriteConsumeTheSameSnapshotOnce() {
        val session = LearningExportSession()
        val request = session.begin("笔记")!!
        assertTrue(session.busy)
        assertNull(session.begin("新搜索"))
        val frozen = snapshot()
        assertTrue(session.freeze(request, frozen))
        assertFalse(session.freeze(request, snapshot()))
        assertSame(frozen, session.choose(request))
        assertNull(session.choose(request))
        assertSame(frozen, session.takeResult(request.id))
        assertNull(session.takeResult(request.id))
        assertNull(session.begin(""))
        session.cancel(request.id)
        assertFalse(session.busy)
    }
    @Test fun cancelledLoadingCannotDisplayOrExportItsLateSnapshot() {
        val session = LearningExportSession()
        val old = session.begin("旧搜索")!!
        session.cancel(old.id)
        val current = session.begin("笔记")!!
        assertFalse(session.freeze(old, snapshot("旧搜索")))
        assertTrue(session.freeze(current, snapshot()))
        session.cancel(old.id)
        assertFalse(session.cancelPreparation(old.id))
        assertTrue(session.busy)
        assertNull(session.takeResult(old.id))
        assertNotNull(session.choose(current))
    }
    @Test fun confirmationCancellationAndStoppedPreparationLeaveNoPendingExport() {
        val session = LearningExportSession()
        val request = session.begin("笔记")!!
        session.freeze(request, snapshot())
        assertTrue(session.cancelPreparation())
        assertNull(session.choose(request))
        assertNull(session.takeResult(request.id))
        assertFalse(session.busy)
        val loading = session.begin("")!!
        assertTrue(session.cancelPreparation())
        assertFalse(session.freeze(loading, snapshot("")))
    }
    @Test fun pickerSurvivesStopButRecreationAndOldCallbacksCannotUseNewSnapshot() {
        val oldSession = LearningExportSession()
        val old = oldSession.begin("笔记")!!
        oldSession.freeze(old, snapshot())
        oldSession.choose(old)
        assertFalse(oldSession.cancelPreparation())
        assertEquals(old.id, oldSession.fileRequestCode)
        oldSession.clear()
        assertNull(oldSession.takeResult(old.id))
        val recreated = LearningExportSession()
        val current = recreated.begin("新搜索")!!
        recreated.freeze(current, snapshot("新搜索"))
        recreated.choose(current)
        assertNotEquals(old.id, current.id)
        assertNull(recreated.takeResult(old.id))
        assertEquals("新搜索", recreated.takeResult(current.id)!!.search)
    }
    @Test fun restoredAbandonedRequestCodeIsNeverReused() {
        LearningExportSession.reserveRequestCode(40000)
        val request = LearningExportSession().begin("")!!
        assertTrue(request.id > 40000)
    }
    @Test fun snapshotCopiesListAndMismatchedSearchIsRejected() {
        val source = mutableListOf(word)
        val frozen = LearningExportSnapshot(source, "笔记", LocalDate.now())
        source.clear()
        assertEquals(listOf(word), frozen.words)
        val session = LearningExportSession()
        val request = session.begin("另一个搜索")!!
        assertFalse(session.freeze(request, frozen))
        assertNull(session.choose(request))
    }
}
