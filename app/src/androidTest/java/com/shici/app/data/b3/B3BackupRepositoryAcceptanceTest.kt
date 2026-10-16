package com.shici.app.data.b3
import com.shici.app.core.*
import com.shici.app.data.*
import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId
/** Requires Android SQLite; UUID-only synthetic DB. COMPILED ONLY, NOT RUN in local B3 acceptance. */
@RunWith(AndroidJUnit4::class)
class B3BackupRepositoryAcceptanceTest {
 private val context=InstrumentationRegistry.getInstrumentation().targetContext
 private lateinit var repo:LearningRepository
 private lateinit var name:String
 private val day=LocalDate.of(2026,10,16);private val zone=ZoneId.of("Asia/Shanghai");private val t=day.atStartOfDay(zone).toInstant().toEpochMilli()
 @Before fun setup(){name="b3-sol-${UUID.randomUUID()}.db";repo=LearningRepository(context,name)}
 @After fun cleanupOnlyOwnUuid(){repo.close();context.deleteDatabase(name)}
 private fun w(k:String)=BackupWord(k,k,"n","old😀","legacy","v0",true,"long".repeat(20000))
 private fun e(id:String,k:String="a",time:Long=t,revoked:Boolean=false)=BackupEvent(id,k,"raw","final",time,revoked)
 private fun payload()=LearningBackupData(listOf(w("a"),w("revoked"),w("orphan")),listOf(e("first",time=t-1),e("last",time=t+10),e("r",revoked=true),e("only-r","revoked",revoked=true)))
 private fun sorted(d:LearningBackupData)=d.copy(words=d.words.sortedBy{it.entryKey},events=d.events.sortedWith(compareBy<BackupEvent>{it.time}.thenBy{it.id}))
 private fun seed(){assertEquals(RestoreOutcome.RESTORED,repo.restoreBackup(payload(),repo.changes.version))}
 private fun assertFailureUnchanged(d:LearningBackupData){val before=repo.backupSnapshot();val g=repo.deletions.capture();var signals=0;val listener:(Long)->Unit={signals++};repo.changes.observe(listener);try{repo.restoreBackup(d,before.revision);fail("invalid restore committed")}catch(e:android.database.SQLException){}finally{repo.changes.remove(listener)};assertEquals(before,repo.backupSnapshot());assertEquals(g,repo.deletions.capture());assertEquals(0,signals)}
 @Test fun completeRawRoundTripIncludesRevokedOnlyAndNoEvents(){seed();assertEquals(sorted(payload()),repo.backupSnapshot().data);val bytes=LearningBackupCodec.encode(repo.backupSnapshot().data,"0.4.0",10,0);assertEquals(sorted(payload()),LearningBackupCodec.decode(bytes).data)}
 @Test fun overwriteNotMergeAndNewAggregates(){seed();val new=LearningBackupData(listOf(w("fresh").copy(mastered=false)),listOf(e("new","fresh")));repo.restoreBackup(new,repo.changes.version);assertEquals(new,repo.backupSnapshot().data);assertEquals(LearningStats(1,1,1,1,0),repo.snapshot(day,zone).stats)}
 @Test fun originalAggregatesRebuiltExcludeRevokedAndOrphan(){seed();val s=repo.snapshot(day,zone);assertEquals(LearningStats(2,1,0,0,1),s.stats);val a=s.words.single();assertEquals(2,a.queryCount);assertEquals(t-1,a.firstAt);assertEquals(t+10,a.lastAt);assertEquals("legacy",a.source);assertEquals("v0",a.version);assertTrue(a.note.length>65536)}
 @Test fun emptyClearsBothTablesAndCommitsOnce(){seed();val v=repo.changes.version;repo.restoreBackup(LearningBackupData(emptyList(),emptyList()),v);assertEquals(LearningBackupData(emptyList(),emptyList()),repo.backupSnapshot().data);assertEquals(v+1,repo.changes.version);assertEquals(LearningStats(0,0,0,0,0),repo.snapshot(day,zone).stats)}
 @Test fun duplicateWordRollsBackDeletesAndInserts(){seed();assertFailureUnchanged(payload().copy(words=payload().words+payload().words.first()))}
 @Test fun duplicateEventRollsBackDeletesAndInserts(){seed();assertFailureUnchanged(payload().copy(events=payload().events+payload().events.first()))}
 @Test fun foreignKeyFailureRollsBack(){seed();assertFailureUnchanged(payload().copy(events=listOf(e("bad","missing"))))}
 @Test fun triggerInsertFailureRollsBack(){seed();repo.writableDatabase.execSQL("CREATE TRIGGER b3_fail BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'b3 injected'); END");assertFailureUnchanged(payload())}
 @Test fun revisionDriftRejectsWithNoBarrier(){seed();val pre=repo.backupSnapshot();repo.changes.publishCommitted();val g=repo.deletions.capture();assertEquals(RestoreOutcome.REVISION_CHANGED,repo.restoreBackup(LearningBackupData(emptyList(),emptyList()),pre.revision));assertEquals(pre.data,repo.backupSnapshot().data);assertEquals(g,repo.deletions.capture())}
 @Test fun restoreCommitPublishesOnceAfterReadableDataAndBarrier(){val g=repo.deletions.capture();var calls=0;var inside=true;var stale=false;var observed:LearningBackupData?=null;repo.changes.observe{calls++;inside=repo.writableDatabase.inTransaction();stale=repo.deletions.isStale("missing-from-backup",g);observed=repo.backupSnapshot().data};seed();assertEquals(1,calls);assertFalse(inside);assertTrue(stale);assertEquals(sorted(payload()),observed)}
 @Test fun oldManualOcrRecordRevokeAndDetailsCannotAffectRestoredRows(){val g=repo.deletions.capture();seed();val entry=DictionaryEntry("a","n","new");assertFalse(repo.record(QueryEvent("late","a","a","a",t),entry,deletionCredential=g));assertEquals(RevokeOutcome.STALE,repo.revokeLatest("a",g));assertEquals(DeleteOutcome.STALE,repo.deleteWord("a",g));assertFalse(repo.updateWord("a",false,"bad",g));assertEquals(sorted(payload()),repo.backupSnapshot().data)}
 @Test fun freshQueriesWorkAndKeyDeletionRemainsIsolated(){seed();val g=repo.deletions.capture();assertTrue(repo.record(QueryEvent("new","fresh","fresh","fresh",t),DictionaryEntry("fresh","n","m"),deletionCredential=g));assertEquals(DeleteOutcome.DELETED,repo.deleteWord("a",g));assertTrue(repo.updateWord("fresh",false,"fresh-note",g));assertEquals("fresh-note",repo.words().first{it.entry.key=="fresh"}.note)}
 @Test fun nestedRestoreIsRejectedWithoutNotification(){val db=repo.writableDatabase;db.beginTransaction();try{try{repo.restoreBackup(payload(),0);fail()}catch(e:IllegalStateException){};assertEquals(0L,repo.changes.version);assertEquals(0L,repo.deletions.capture())}finally{db.endTransaction()}}
 @Test fun longExtremeTimesIdsAndLegalEmptyFieldsPreserved(){val d=LearningBackupData(listOf(w("a").copy(partOfSpeech="",meaning="",source="",version="",note="")),listOf(e("min",time=Long.MIN_VALUE,revoked=true),e("max",time=Long.MAX_VALUE)));repo.restoreBackup(d,0);assertEquals(sorted(d),repo.backupSnapshot().data)}
}
