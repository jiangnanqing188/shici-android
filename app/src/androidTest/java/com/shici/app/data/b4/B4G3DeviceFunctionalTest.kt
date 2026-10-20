package com.shici.app.data.b4
import com.shici.app.core.*
import com.shici.app.data.*
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import java.time.*
/** Device-only regressions: real Android SQLiteOpenHelper; every database has an owned UUID. */
class B4G3DeviceFunctionalTest {
 private val now=Instant.parse("2026-10-16T12:00:00Z").toEpochMilli();private val zone=ZoneOffset.UTC
 private fun plan(vararg keys:String,book:StudyBook=StudyBook.KAOYAN)=StudyMembership(book,keys.toList()){DictionaryEntry(it,"n","meaning","synthetic","v0")}
 private fun <T> repo(f:(LearningRepository)->T):T {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val name="device-g3-${UUID.randomUUID()}.db"
  try{return LearningRepository(c,name).use(f)}finally{c.deleteDatabase(name)}
 }
 private fun view(r:LearningRepository,vararg keys:String,at:Long=now)=r.studyView(plan(*keys),at,zone)
 private fun answer(r:LearningRepository,v:StudyView,kind:FeedbackKind=FeedbackKind.KNOW,at:Long=now){assertTrue(r.revealStudyCard(v.card!!.token,v.credential));assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(v.card!!.token,kind,at,zone,v.credential))}
 private fun round(d:LearningBackupData)=LearningBackupCodec.decode(LearningBackupCodec.encode(d,"1.0.3",15,now)).data
 private fun reject(d:LearningBackupData){try{round(d);fail("invalid commit sequence accepted")}catch(e:BackupFormatException){assertTrue(e.message!!.isNotBlank())}}
 @Test fun sequenceFollowsCommitsAcrossWordsRollbackAndReversedWireList()=repo{r->
  answer(r,view(r,"a"),FeedbackKind.FORGET);answer(r,view(r,"b"),at=now-100)
  answer(r,view(r,"a",at=now+600000),at=now-200)
  val d=r.backupSnapshot().data;val ordered=d.reviews.sortedBy{it.seq}
  assertEquals(listOf(1L,2L,3L),ordered.map{it.seq});assertEquals(listOf("a","b","a"),ordered.map{it.entryKey});assertEquals(listOf(now,now-100,now-200),ordered.map{it.reviewedAt})
  val shuffled=d.copy(reviews=ordered.reversed());assertEquals(shuffled,round(shuffled));assertEquals(3L,d.states.single{it.entryKey=="a"}.lastReviewSeq)
 }
 @Test fun identicalStageDueAndTimeStillCannotSubstituteEarlierSequence()=repo{r->
  answer(r,view(r,"a"),FeedbackKind.FORGET);val first=r.backupSnapshot().data.states.single()
  answer(r,view(r,"a",at=now+600000),FeedbackKind.FORGET,now)
  val latest=r.backupSnapshot().data;val last=latest.states.single()
  assertEquals(first.copy(lastReviewSeq=last.lastReviewSeq),last);assertEquals(latest,round(latest));reject(latest.copy(states=listOf(first)))
 }
 @Test fun duplicateSequenceAcrossDifferentWordsRejectedWithValidChecksum()=repo{r->
  answer(r,view(r,"a"));answer(r,view(r,"b"));val d=r.backupSnapshot().data
  assertEquals(d,round(d));val seq=d.reviews.first().seq;reject(d.copy(reviews=d.reviews.map{it.copy(seq=seq)},states=d.states.map{it.copy(lastReviewSeq=seq)}))
 }
 @Test fun swappedCommitOrderCannotUseWallClockOrListAsExcuse()=repo{r->
  answer(r,view(r,"a"));answer(r,view(r,"a",at=now+StudyRules.DAY_MILLIS),at=now+StudyRules.DAY_MILLIS)
  val d=r.backupSnapshot().data;val reviews=d.reviews.sortedBy{it.seq};assertEquals(d,round(d))
  reject(d.copy(reviews=listOf(reviews[0].copy(seq=2),reviews[1].copy(seq=1)),states=d.states.map{it.copy(lastReviewSeq=2)}))
 }
 @Test fun restoredSnapshotKeepsSequenceAndNextCommitContinuesAboveRestoredMaximum()=repo{r->
  answer(r,view(r,"a"),FeedbackKind.FORGET);answer(r,view(r,"b"));val d=round(r.backupSnapshot().data)
  repo{fresh->assertEquals(RestoreOutcome.RESTORED,fresh.restoreBackup(d,fresh.changes.version));assertEquals(d,fresh.backupSnapshot().data)
   answer(fresh,view(fresh,"a",at=now+600000),at=now-1);val after=fresh.backupSnapshot().data
   assertEquals(setOf(1L,2L,3L),after.reviews.map{it.seq}.toSet());assertEquals(3L,after.states.single{it.entryKey=="a"}.lastReviewSeq);assertEquals(after,round(after))}
 }
 @Test fun deleteAndRestoreDoNotRenumberSurvivingHistoryOrReviveOldCard()=repo{r->
  answer(r,view(r,"a"));answer(r,view(r,"b"));val stale=view(r,"b",at=now+StudyRules.DAY_MILLIS);r.revealStudyCard(stale.card!!.token,stale.credential)
  assertEquals(DeleteOutcome.DELETED,r.deleteWord("a",stale.credential));val d=round(r.backupSnapshot().data);assertEquals(stale.card!!.token,d.session!!.token)
  assertEquals(listOf(2L),d.reviews.map{it.seq});assertEquals(listOf("b"),d.states.map{it.entryKey});assertEquals(RestoreOutcome.RESTORED,r.restoreBackup(d,r.changes.version))
  assertEquals(StudyCommitOutcome.STALE,r.commitStudyFeedback(stale.card!!.token,FeedbackKind.KNOW,now+StudyRules.DAY_MILLIS,zone,stale.credential))
  answer(r,view(r,"c"));val after=r.backupSnapshot().data;assertEquals(setOf(2L,3L),after.reviews.map{it.seq}.toSet());assertEquals(after,round(after))
 }
 @Test fun failedCommitRollsBackSequenceAndRetryRemainsIdempotent()=repo{r->
  answer(r,view(r,"a"));val v=view(r,"b");r.revealStudyCard(v.card!!.token,v.credential);val before=r.backupSnapshot()
  r.writableDatabase.execSQL("CREATE TEMP TRIGGER device_fail BEFORE INSERT ON study_reviews BEGIN SELECT RAISE(ABORT, 'device injected'); END")
  try{r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential);fail("injected failure expected")}catch(e:android.database.SQLException){assertTrue(e.message!!.contains("injected"))}
  r.writableDatabase.execSQL("DROP TRIGGER device_fail")
  assertEquals(before,r.backupSnapshot());assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential));val after=r.backupSnapshot()
  assertEquals(setOf(1L,2L),after.data.reviews.map{it.seq}.toSet());assertEquals(StudyCommitOutcome.IDEMPOTENT,r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential));assertEquals(after,r.backupSnapshot())
 }
 @Test fun exhaustedSequenceRefusesWithoutOverflowOrPartialPublication()=repo{r->
  answer(r,view(r,"a"));val d=r.backupSnapshot().data;val max=d.copy(states=d.states.map{it.copy(lastReviewSeq=Long.MAX_VALUE)},reviews=d.reviews.map{it.copy(seq=Long.MAX_VALUE)})
  assertEquals(max,round(max));r.restoreBackup(max,r.changes.version);val v=view(r,"b");r.revealStudyCard(v.card!!.token,v.credential);val before=r.backupSnapshot()
  try{r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential);fail("sequence overflow must refuse")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("序号已用尽"))}
  assertEquals(before,r.backupSnapshot())
 }
 @Test fun loweredGoalClearsPersistedNewCardAndRemainsSharedAfterSnapshotReload()=repo{r->
  r.updateStudySettings(StudyBook.KAOYAN,2);answer(r,view(r,"a"));val old=view(r,"b");assertNotNull(old.card);r.updateStudySettings(StudyBook.KAOYAN,1)
  assertNull(view(r,"a","b").card);val d=round(r.backupSnapshot().data);assertNull(d.session)
  repo{fresh->fresh.restoreBackup(d,fresh.changes.version);assertNull(view(fresh,"a","b").card);fresh.updateStudySettings(StudyBook.CET6,1)
   val other=fresh.studyView(plan("c",book=StudyBook.CET6),now,zone);assertNull(other.card);assertEquals(1,other.progress.introducedToday);assertEquals(0,other.progress.newRemaining)}
 }
 @Test fun dueReviewNotBlockedByLoweredGoalAndDiscardedNewCard()=repo{r->
  r.updateStudySettings(StudyBook.KAOYAN,2);answer(r,view(r,"a"),FeedbackKind.FORGET);assertEquals("b",view(r,"a","b").card!!.entryKey);r.updateStudySettings(StudyBook.KAOYAN,1)
  val due=view(r,"a","b",at=now+600000);assertEquals("a",due.card!!.entryKey);assertFalse(due.card!!.newWord);assertEquals(0,due.progress.newRemaining)
  answer(r,due,at=now+600000);assertEquals(1,r.backupSnapshot().data.reviews.count{it.newWord});assertEquals(2,r.backupSnapshot().data.reviews.size)
 }
 @Test fun nonexistentTailSequenceCannotMasqueradeAsCurrentState()=repo{r->
  answer(r,view(r,"a"));val d=r.backupSnapshot().data;assertEquals(d,round(d));reject(d.copy(states=d.states.map{it.copy(lastReviewSeq=it.lastReviewSeq+1)}))
 }

 @Test fun frameworkV1MigrationPreservesRawRowsAndInitializesOnlyStudy() {
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val name="device-v1-${UUID.randomUUID()}.db"
  try {
   c.openOrCreateDatabase(name,0,null).use { db->
    db.execSQL("CREATE TABLE words (entry_key TEXT PRIMARY KEY, headword TEXT NOT NULL, pos TEXT NOT NULL, meaning TEXT NOT NULL, source TEXT NOT NULL, version TEXT NOT NULL, mastered INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '')")
    db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, entry_key TEXT NOT NULL REFERENCES words(entry_key), raw_text TEXT NOT NULL, final_form TEXT NOT NULL, time INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0)")
    db.execSQL("CREATE INDEX events_word_time ON events(entry_key,time)")
    db.execSQL("INSERT INTO words VALUES ('a','a','n','old meaning','legacy','v0',1,'note😀')")
    db.execSQL("INSERT INTO events VALUES ('revoked','a','raw','a',123,1)")
    db.version=1
   }
   LearningRepository(c,name).use { r->
    assertEquals(2,r.readableDatabase.version)
    val d=r.backupSnapshot().data
    assertEquals(BackupWord("a","a","n","old meaning","legacy","v0",true,"note😀"),d.words.single())
    assertEquals(BackupEvent("revoked","a","raw","a",123,true),d.events.single())
    assertTrue(d.states.isEmpty());assertTrue(d.reviews.isEmpty());assertNull(d.session)
    assertEquals(StudySettings(),r.studySettings())
   }
  } finally { c.deleteDatabase(name) }
 }
 @Test fun reopenedFrameworkHelperPersistsRevealedCardAndSequence() {
  val c=InstrumentationRegistry.getInstrumentation().targetContext;val name="device-reopen-${UUID.randomUUID()}.db"
  try {
   var token=""
   LearningRepository(c,name).use{r->val v=view(r,"a");token=v.card!!.token;assertTrue(r.revealStudyCard(token,v.credential))}
   LearningRepository(c,name).use{r->val v=view(r,"a");assertEquals(token,v.card!!.token);assertTrue(v.card!!.answerRevealed);assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(token,FeedbackKind.KNOW,now,zone,v.credential))}
   LearningRepository(c,name).use{r->assertEquals(1L,r.backupSnapshot().data.reviews.single().seq);assertNull(view(r,"a").card)}
  }finally{c.deleteDatabase(name)}
 }
 @Test fun destroyedBeforeAdmissionCannotDealSettingsRevealOrFeedback()=repo{r->
  val gate=StudyAdmission();gate.invalidate();val before=r.backupSnapshot()
  assertNull(r.studyView(plan("a"),now,zone,gate).card)
  assertFalse(r.updateStudySettings(StudyBook.CET6,50,gate));assertEquals(before,r.backupSnapshot())
  val v=view(r,"a");val pending=r.backupSnapshot()
  assertFalse(r.revealStudyCard(v.card!!.token,v.credential,gate));assertEquals(pending,r.backupSnapshot())
  r.revealStudyCard(v.card!!.token,v.credential);val revealed=r.backupSnapshot()
  assertEquals(StudyCommitOutcome.REJECTED,r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential,gate));assertEquals(revealed,r.backupSnapshot())
 }
 @Test fun invalidationDuringSnapshotReadRejectsPendingAndAdmittedWriteCompletesAtomically()=repo{r->
  val gate=StudyAdmission();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
  try {
   val f=executor.submit<StudyView>{r.studyView(StudyMembership(StudyBook.KAOYAN,listOf("a")){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));DictionaryEntry(it,"n","m")},now,zone,gate)}
   assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));gate.invalidate();release.countDown();assertNull(f.get(5,java.util.concurrent.TimeUnit.SECONDS).card);assertNull(r.backupSnapshot().data.session)
   val admitted=StudyAdmission { true };val v=view(r,"a");r.revealStudyCard(v.card!!.token,v.credential)
   var calls=0;r.changes.observe{calls++;admitted.invalidate();assertFalse(r.writableDatabase.inTransaction());assertEquals(1,r.backupSnapshot().data.reviews.size)}
   assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential,admitted));assertEquals(1,calls);assertEquals(1,r.backupSnapshot().data.states.size)
  } finally {release.countDown();executor.shutdownNow()}
 }
 private fun legacy():ByteArray {val bytes=java.io.ByteArrayOutputStream();val o=java.io.DataOutputStream(bytes);fun s(v:String){val b=v.toByteArray();o.writeInt(b.size);o.write(b)};o.writeBytes("SHICIBKP");o.writeInt(1);s("com.shici.app");s("0.4.1");o.writeInt(11);o.writeInt(1);o.writeLong(now);o.writeInt(1);o.writeInt(1);val w=BackupWord("a","a","n","old meaning😀","legacy","v0",true,"note");listOf(w.entryKey,w.headword,w.partOfSpeech,w.meaning,w.source,w.version).forEach{ s(it) };o.writeBoolean(w.mastered);s(w.note);listOf("q","a","raw","a").forEach{ s(it) };o.writeLong(now);o.writeBoolean(true);val body=bytes.toByteArray();return body+java.security.MessageDigest.getInstance("SHA-256").digest(body)}
 @Test fun actualAndroidReadsGenuineV1WireAndRestoresWithoutInventingStudy()=repo{r->
  answer(r,view(r,"b"));val decoded=LearningBackupCodec.decode(legacy());assertTrue(decoded.legacyNoStudyData);assertEquals(1,decoded.schemaVersion)
  assertEquals(RestoreOutcome.RESTORED,r.restoreBackup(decoded.data,r.changes.version));assertEquals(decoded.data,r.backupSnapshot().data)
  assertTrue(r.backupSnapshot().data.events.single().revoked);assertEquals("note",r.backupSnapshot().data.words.single().note);assertEquals(StudySettings(),r.studySettings());assertTrue(r.backupSnapshot().data.states.isEmpty());assertTrue(r.backupSnapshot().data.reviews.isEmpty())
 }
}
