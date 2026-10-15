package com.shici.app.core.b2

import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Pure aggregation/export helpers, using explicitly supplied remaining rows, never a fake repository. */
class B2AggregationExportAcceptanceTest {
    private val day = LocalDate.of(2026, 10, 14)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun word(key: String, first: Long = start, last: Long = first, count: Int = 1, mastered: Boolean = false, note: String = "") =
        DictionaryEntry(key, "n", "meaning $key").let { LearnedWord(it, it.source, it.version, key, count, first, last, mastered, note) }
    private fun event(id: String, key: String, t: Long, revoked: Boolean = false) = QueryEvent(id, key, key, key, t, revoked)

    @Test fun remainingMixedDatesAndRevokedEventsHaveExactCounts() {
        val words = listOf(word("ability", start - 100, end - 1, 3, true, "keep"), word("abandoned", start + 1))
        val events = listOf(event("b0","ability",start - 100),event("b1","ability",start),event("b2","ability",end - 1),
            event("br","ability",start+2,true),event("c","abandoned",start+1))
        assertEquals(LearningStats(4,3,1,1,1), LearningRules.summarize(words,events,day,zone))
        assertEquals("keep", words[0].note); assertTrue(words[0].mastered)
    }
    @Test fun deletionDoesNotLeaveCompensationCountersInHelper() {
        val before = LearningRules.summarize(listOf(word("a"),word("b")),listOf(event("a","a",start),event("b","b",start)),day,zone)
        val after = LearningRules.summarize(listOf(word("b")),listOf(event("b","b",start)),day,zone)
        assertEquals(LearningStats(2,2,2,2,0),before); assertEquals(LearningStats(1,1,1,1,0),after)
    }
    @Test fun removingAllRemainingRowsHasZeroStats() {
        assertEquals(LearningStats(0,0,0,0,0),LearningRules.summarize(emptyList(),emptyList(),day,zone))
    }
    @Test fun revokedOnlyHistoryNeverCountsAsQuery() {
        assertEquals(LearningStats(0,0,0,0,0),LearningRules.summarize(emptyList(),listOf(event("r","left",start,true)),day,zone))
    }
    @Test fun newCollectionUsesNewDatesRatherThanDeletedHistory() {
        val fresh = word("left",start+123,start+123,1,false,"")
        assertEquals(LearningStats(1,1,1,1,0),LearningRules.summarize(listOf(fresh),listOf(event("new","left",start+123)),day,zone))
        assertFalse(fresh.mastered); assertEquals("",fresh.note)
    }
    @Test fun localDayBoundsRemainInclusiveExclusiveAfterDeletion() {
        val events = listOf(event("before","b",start-1),event("at","b",start),event("last","b",end-1),event("next","b",end))
        assertEquals(LearningStats(4,2,0,0,1),LearningRules.summarize(listOf(word("b",start-1,end,4,true)),events,day,zone))
    }
    @Test fun mixedSourceStateIsNotMutatedBySummarize() {
        val legacy = word("left",start-1,mastered=true,note="old note")
        val rawEntry = DictionaryEntry("quantum","n","raw",RawKajwebDictionary.SOURCE,RawKajwebDictionary.VERSION)
        val raw = LearnedWord(rawEntry,rawEntry.source,rawEntry.version,"quantum",2,start,start+5,false,"raw note")
        val input=listOf(legacy,raw);val copy=input.toList()
        LearningRules.summarize(input,listOf(event("old","left",start-1),event("n","quantum",start)),day,zone)
        assertEquals(copy,input)
    }
    @Test fun frozenExportMembershipAndPersonalFieldsStayOld() {
        val original=word("left",start-100,start,4,true,"old note");val other=word("leave",note="keep")
        val mutable=mutableListOf(original,other);val frozen=LearningExportSnapshot(mutable,"",day)
        mutable.removeAt(0); mutable[0]=other.copy(note="new note")
        assertEquals(listOf(original,other),frozen.words);assertTrue(LearningExportText.row(0,frozen.words[0]).contains("old note"))
    }
    @Test fun existingConfirmedSessionConsumesPreDeletionSnapshotOnce() {
        val session=LearningExportSession();val request=session.begin("")!!
        val frozen=LearningExportSnapshot(listOf(word("left",note="old"),word("leave")),"",day)
        assertTrue(session.freeze(request,frozen));assertSame(frozen,session.choose(request))
        val postDeletion=LearningExportSnapshot(listOf(word("leave")),"",day)
        assertFalse(session.freeze(request,postDeletion));assertSame(frozen,session.takeResult(request.id));assertNull(session.takeResult(request.id))
    }
    @Test fun newExportSessionUsesOnlyPostDeletionRows() {
        val session=LearningExportSession();val request=session.begin("")!!
        val fresh=LearningExportSnapshot(listOf(word("leave",note="keep")),"",day)
        assertTrue(session.freeze(request,fresh)); session.choose(request)
        val result=session.takeResult(request.id)!!
        assertEquals(listOf("leave"),result.words.map { it.entry.key });assertFalse(LearningExportText.row(0,result.words[0]).contains("meaning left"))
    }
    @Test fun cancelledPreparationCanStartNewPostDeletionSnapshot() {
        val session=LearningExportSession();val old=session.begin("")!!
        assertTrue(session.freeze(old,LearningExportSnapshot(listOf(word("left")),"",day)))
        assertTrue(session.cancelPreparation(old.id));val fresh=session.begin("")!!
        assertTrue(session.freeze(fresh,LearningExportSnapshot(emptyList(),"",day)));assertNull(session.choose(old));assertTrue(session.choose(fresh)!!.words.isEmpty())
    }
    @Test fun reCollectedSameKeyCannotChangeOldFrozenNoteAndMastery() {
        val old=word("left",start-100,mastered=true,note="deleted note");val frozen=LearningExportSnapshot(listOf(old),"",day)
        val fresh=word("left",start+42,note=""); val new=LearningExportSnapshot(listOf(fresh),"",day)
        assertTrue(frozen.words.single().mastered);assertEquals("deleted note",frozen.words.single().note)
        assertFalse(new.words.single().mastered);assertEquals("",new.words.single().note);assertNotEquals(frozen.words.single().firstAt,new.words.single().firstAt)
    }
    @Test fun oldRetryEventIdCannotMasqueradeAsNewQuery() {
        val old=event("old","left",start-100);val new=event("new","left",start+1)
        assertFalse(LearningRules.isDuplicate(null,new)); assertTrue(LearningRules.isDuplicate(old.copy(revoked=true),old))
        assertNotNull(runCatching { LearningRules.isDuplicate(old,old.copy(time=start+1)) }.exceptionOrNull())
    }
    @Test fun snapshotStoresGivenCredentialNotCurrentDeletionEpoch() {
        val gate=DeletionGate();val c=gate.capture();val old=LearningSnapshot(listOf(word("left")),LearningStats(1,1,1,1,0),1,c)
        gate.recordDeletion("left"); val fresh=LearningSnapshot(emptyList(),LearningStats(0,0,0,0,0),2,gate.capture())
        assertTrue(gate.isStale("left",old.deletionCredential));assertFalse(gate.isStale("left",fresh.deletionCredential))
    }
}
