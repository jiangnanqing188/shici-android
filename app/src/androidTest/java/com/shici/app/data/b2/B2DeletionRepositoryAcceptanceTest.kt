package com.shici.app.data.b2

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shici.app.core.*
import com.shici.app.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Real LearningRepository + Android SQLite on a UUID-named isolated DB. Compile is NOT runtime pass. */
@RunWith(AndroidJUnit4::class)
class B2DeletionRepositoryAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repo: LearningRepository
    private lateinit var dbName: String
    private val target=DictionaryEntry("abandon","v","target")
    private val similar=DictionaryEntry("abandoned","adj","similar")
    private val other=DictionaryEntry("ability","n","other")
    private val day=LocalDate.of(2026,10,14)
    private val zone=ZoneId.of("Asia/Shanghai")
    private val start=day.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun event(id:String,e:DictionaryEntry=target,t:Long=start)=QueryEvent(id,e.key,e.headword,e.headword,t)
    private fun add(id:String,e:DictionaryEntry=target,t:Long=start,c:Long=repo.deletions.capture()) {
        assertTrue(repo.record(event(id,e,t),e,deletionCredential=c))
    }
    private fun count(table:String,key:String):Int=repo.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE entry_key=?",arrayOf(key)).use { c -> c.moveToFirst();c.getInt(0) }
    @Before fun setup() { dbName="b2-sol-${UUID.randomUUID()}.db";repo=LearningRepository(context,dbName) }
    @After fun cleanupOnlyOurUuidDatabase() { repo.close();context.deleteDatabase(dbName) }

    @Test fun atomicDeleteRemovesActiveRevokedEventsAndPersonalRow() {
        add("a1",t=start-100);add("a2");repo.revoke("a1");repo.updateWord(target.key,true,"deleted note")
        assertEquals(DeleteOutcome.DELETED,repo.deleteWord(target.key,repo.deletions.capture()))
        assertEquals(0,count("events",target.key));assertEquals(0,count("words",target.key))
        assertTrue(repo.words().isEmpty());assertEquals(LearningStats(0,0,0,0,0),repo.snapshot(day,zone).stats)
    }
    @Test fun revokedOnlyWordCanBeDeletedEvenThoughNotInWordsList() {
        add("r");repo.revoke("r");assertTrue(repo.words().isEmpty());assertEquals(1,count("words",target.key))
        assertEquals(DeleteOutcome.DELETED,repo.deleteWord(target.key,repo.deletions.capture()))
        assertEquals(0,count("events",target.key));assertEquals(0,count("words",target.key))
    }
    @Test fun exactKeyDeletePreservesSimilarWordAndOtherPersonalFields() {
        add("a");add("s",similar,start-50);add("b",other,start+50)
        repo.updateWord(similar.key,true,"similar note");repo.updateWord(other.key,true,"other note")
        val untouched=repo.words().filter { it.entry.key!=target.key };val events=repo.events().filter { it.entryKey!=target.key }
        repo.deleteWord(target.key,repo.deletions.capture())
        assertEquals(untouched,repo.words());assertEquals(events,repo.events());assertEquals(1,count("events",similar.key))
    }
    @Test fun repeatAndMissingDeleteDoNotPublishOrAdvanceGeneration() {
        add("a");val c=repo.deletions.capture();assertEquals(DeleteOutcome.DELETED,repo.deleteWord(target.key,c))
        val version=repo.changes.version;val epoch=repo.deletions.capture()
        assertEquals(DeleteOutcome.STALE,repo.deleteWord(target.key,c))
        assertEquals(DeleteOutcome.NOT_FOUND,repo.deleteWord(target.key,epoch))
        assertEquals(DeleteOutcome.NOT_FOUND,repo.deleteWord("missing",epoch))
        assertEquals(version,repo.changes.version);assertEquals(epoch,repo.deletions.capture())
    }
    @Test fun committedDeleteNotifiesOnceAfterTransactionWithFreshCredential() {
        add("a");val seen=mutableListOf<LearningSnapshot>()
        repo.changes.observe { assertFalse(repo.writableDatabase.inTransaction());seen+=repo.snapshot(day,zone) }
        val c=repo.deletions.capture();repo.deleteWord(target.key,c)
        assertEquals(1,seen.size);assertTrue(seen.single().words.isEmpty());assertEquals(2L,seen.single().version)
        assertEquals(c+1,seen.single().deletionCredential)
    }
    @Test fun failureOnWordsDeleteRollsBackPreviouslyDeletedEventsWithoutNotification() {
        add("a1");add("a2");repo.revoke("a1");repo.updateWord(target.key,true,"keep")
        val snapshot=repo.snapshot(day,zone);val events=repo.events();val version=repo.changes.version;val credential=repo.deletions.capture()
        val notifications=mutableListOf<Long>();repo.changes.observe { notifications+=it }
        repo.writableDatabase.execSQL("""CREATE TEMP TRIGGER b2_reject_word BEFORE DELETE ON words WHEN OLD.entry_key='abandon' BEGIN SELECT RAISE(ABORT,'b2 reject word deletion'); END""")
        val error=runCatching { repo.deleteWord(target.key,credential) }.exceptionOrNull()
        assertNotNull(error);assertEquals(snapshot,repo.snapshot(day,zone));assertEquals(events,repo.events())
        assertEquals(version,repo.changes.version);assertEquals(credential,repo.deletions.capture());assertTrue(notifications.isEmpty())
    }
    @Test fun failureOnEventsDeleteKeepsWordsEventsAndCredential() {
        add("a");val c=repo.deletions.capture();val v=repo.changes.version
        repo.writableDatabase.execSQL("""CREATE TEMP TRIGGER b2_reject_event BEFORE DELETE ON events BEGIN SELECT RAISE(ABORT,'b2 reject event deletion'); END""")
        assertNotNull(runCatching { repo.deleteWord(target.key,c) }.exceptionOrNull())
        assertEquals(1,count("events",target.key));assertEquals(1,count("words",target.key));assertEquals(c,repo.deletions.capture());assertEquals(v,repo.changes.version)
    }
    @Test fun failedDeletionDoesNotInvalidateInFlightSaveAndCanRetry() {
        add("a");val c=repo.deletions.capture()
        repo.writableDatabase.execSQL("""CREATE TEMP TRIGGER b2_failure BEFORE DELETE ON words BEGIN SELECT RAISE(ABORT,'failure'); END""")
        assertNotNull(runCatching { repo.deleteWord(target.key,c) }.exceptionOrNull())
        assertFalse(repo.deletions.isStale(target.key,c));add("late",c=c)
        repo.writableDatabase.execSQL("DROP TRIGGER b2_failure")
        assertEquals(DeleteOutcome.DELETED,repo.deleteWord(target.key,c));assertEquals(0,count("events",target.key))
    }
    @Test fun nestedTransactionDeleteIsRejectedWithoutChangingAnything() {
        add("a");val c=repo.deletions.capture();val v=repo.changes.version;val db=repo.writableDatabase
        db.beginTransaction()
        try { assertNotNull(runCatching { repo.deleteWord(target.key,c) }.exceptionOrNull()) } finally { db.endTransaction() }
        assertEquals(1,count("events",target.key));assertEquals(v,repo.changes.version);assertEquals(c,repo.deletions.capture())
    }
    @Test fun reopenDatabasePreservesDeletionAndUntouchedOtherWord() {
        add("a");add("b",other);repo.updateWord(other.key,true,"persistent")
        val before=repo.words().single { it.entry.key==other.key };repo.deleteWord(target.key,repo.deletions.capture())
        repo.close();repo=LearningRepository(context,dbName)
        assertEquals(0,count("events",target.key));assertEquals(0,count("words",target.key));assertEquals(before,repo.words().single())
        assertEquals(LearningStats(1,1,1,0,1),repo.snapshot(day,zone).stats)
    }
    @Test fun mixedDatesRevokedAndMasteryReaggregateFromRemainingRows() {
        add("a0",t=start-500);add("a1",t=start+1);add("b0",other,start-50);add("b1",other,start+2)
        add("br",other,start+3);repo.revoke("br");add("s",similar,start+5);repo.updateWord(other.key,true,"keep")
        repo.deleteWord(target.key,repo.deletions.capture())
        assertEquals(LearningStats(3,2,1,1,1),repo.snapshot(day,zone).stats)
        val b=repo.words().single { it.entry.key==other.key }
        assertEquals(2,b.queryCount);assertEquals(start-50,b.firstAt);assertEquals(start+2,b.lastAt);assertEquals("keep",b.note)
    }
    @Test fun stalePreDeletionQueryAndSaveRetriesCannotRecreateWord() {
        val c=repo.deletions.capture();add("a");repo.deleteWord(target.key,c)
        repeat(3) { assertFalse(repo.record(event("late"),target,deletionCredential=c)) }
        assertEquals(0,count("events",target.key));assertEquals(0,count("words",target.key));assertEquals(2L,repo.changes.version)
    }
    @Test fun newActiveQueryRecollectsFreshStateAndDates() {
        add("a",t=start-200);repo.updateWord(target.key,true,"old note");repo.deleteWord(target.key,repo.deletions.capture())
        add("new",t=start+90)
        val w=repo.words().single();assertEquals("",w.note);assertFalse(w.mastered);assertEquals(1,w.queryCount)
        assertEquals(start+90,w.firstAt);assertEquals(start+90,w.lastAt);assertEquals(listOf("new"),repo.events().map { it.id })
    }
    @Test fun oldDetailSaveRevokeAndDeleteCannotChangeReCollectedRow() {
        add("old");val old=repo.snapshot(day,zone).deletionCredential;repo.deleteWord(target.key,old);add("new",t=start+10)
        val before=repo.snapshot(day,zone);val events=repo.events()
        assertFalse(repo.updateWord(target.key,true,"stale note",old))
        assertEquals(RevokeOutcome.STALE,repo.revokeLatest(target.key,old));assertEquals(RevokeOutcome.STALE,repo.revokeEvent("new",target.key,old))
        assertEquals(DeleteOutcome.STALE,repo.deleteWord(target.key,old));assertEquals(before,repo.snapshot(day,zone));assertEquals(events,repo.events())
    }
    @Test fun deletionOfOtherWordLeavesPendingQueryAndOpenDetailsUsable() {
        add("a");add("b",other);val old=repo.snapshot(day,zone).deletionCredential
        repo.deleteWord(target.key,old);add("other-late",other,start+7,old)
        assertTrue(repo.updateWord(other.key,true,"edited",old));assertEquals("edited",repo.words().single().note)
        assertEquals(RevokeOutcome.REVOKED,repo.revokeLatest(other.key,old));assertEquals(1,repo.words().single().queryCount)
    }
    @Test fun continuousDeleteAndRecollectKeepsAllPriorCredentialsStale() {
        add("a");val c0=repo.deletions.capture();repo.deleteWord(target.key,c0);add("a2");val c1=repo.deletions.capture()
        repo.deleteWord(target.key,c1);add("a3");val c2=repo.deletions.capture()
        assertEquals(DeleteOutcome.STALE,repo.deleteWord(target.key,c0));assertEquals(DeleteOutcome.STALE,repo.deleteWord(target.key,c1))
        assertFalse(repo.record(event("late"),target,deletionCredential=c1));assertEquals("a3",repo.events().single().id)
        assertEquals(DeleteOutcome.DELETED,repo.deleteWord(target.key,c2))
    }
    @Test fun correctionCannotReplaceDeletedOriginalEventIntoOtherWord() {
        add("original");val c=repo.deletions.capture();repo.deleteWord(target.key,c)
        assertFalse(repo.record(event("correction",other),other,replaces="original",deletionCredential=c))
        assertTrue(repo.words().isEmpty());assertTrue(repo.events().isEmpty());assertEquals(2L,repo.changes.version)
    }
    @Test fun explicitRevokeUsesBothEventIdAndExactKeyAndNoOpDoesNotPublish() {
        add("a");add("b",other);val c=repo.deletions.capture();val v=repo.changes.version
        assertEquals(RevokeOutcome.NONE,repo.revokeEvent("b",target.key,c));assertEquals(RevokeOutcome.NONE,repo.revokeEvent("missing",other.key,c))
        assertEquals(v,repo.changes.version);assertFalse(repo.events().single { it.id=="b" }.revoked)
        assertEquals(RevokeOutcome.REVOKED,repo.revokeEvent("b",other.key,c));val new=repo.changes.version
        assertEquals(RevokeOutcome.NONE,repo.revokeEvent("b",other.key,c));assertEquals(RevokeOutcome.NONE,repo.revokeLatest(other.key,c));assertEquals(new,repo.changes.version)
    }
    @Test fun recordCancelledBeforeCommitRollsBackAndDoesNotPublish() {
        add("a");val before=repo.snapshot(day,zone);var calls=0
        assertFalse(repo.record(event("cancelled"),target,deletionCredential=before.deletionCredential,canCommit={ ++calls==1 }))
        assertEquals(before,repo.snapshot(day,zone));assertEquals(listOf("a"),repo.events().map { it.id })
    }
    @Test fun deleteSnapshotAndRecollectNeverMutateConfirmedExport() {
        add("a");add("b",other);repo.updateWord(target.key,true,"old note")
        val frozen=repo.exportSnapshot("",day);val original=frozen.words;val session=LearningExportSession();val request=session.begin("")!!
        assertTrue(session.freeze(request,frozen));session.choose(request)
        repo.deleteWord(target.key,repo.deletions.capture());assertEquals(listOf(other.key),repo.exportSnapshot("",day).words.map { it.entry.key })
        add("new",t=start+30);assertEquals(original,session.takeResult(request.id)!!.words)
        assertEquals("old note",frozen.words.single { it.entry.key==target.key }.note)
        assertEquals("",repo.words().single { it.entry.key==target.key }.note)
    }
    @Test fun alreadyExportedFileBytesAreUntouchedByRepositoryDeletion() {
        add("a");val path=java.io.File(context.cacheDir,"b2-sol-${UUID.randomUUID()}.pdf")
        // Sentinel PDF bytes verify no file modification, not PDF rendering or filesystem permission UI.
        val bytes="%PDF-1.4\nb2 pre-existing export sentinel\n%%EOF\n".toByteArray()
        try { path.writeBytes(bytes);repo.deleteWord(target.key,repo.deletions.capture());assertArrayEquals(bytes,path.readBytes()) } finally { path.delete() }
    }
    @Test fun emptyAfterDeletionHasEmptySearchAndExportSnapshot() {
        add("a");repo.deleteWord(target.key,repo.deletions.capture())
        assertTrue(repo.words("abandon").isEmpty());assertTrue(repo.exportSnapshot("abandon",day).words.isEmpty())
        assertEquals(LearningStats(0,0,0,0,0),repo.snapshot(day,zone).stats)
    }
}
