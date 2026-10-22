package com.shici.app.core.u1

import com.shici.app.core.*
import com.shici.app.core.b4.host.Context
import com.shici.app.core.b4.host.LearningRepository
import com.shici.app.core.b4.host.SQLiteDatabase
import com.shici.app.core.b4.host.StudyMembership
import com.shici.app.core.b4.host.StudyCommitOutcome
import com.shici.app.core.b4.host.RestoreOutcome
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** S6-R1 only. Production source/codec + full verbatim Repository on unchanged real SQLite adapter.
 * Synthetic databases; no Android SQLiteOpenHelper, device or user database. */
class U1S6R1PendingSourceAcceptanceTest {
 private val now=1792627200000L
 private val zone=ZoneId.of("UTC")
 private val base=DictionaryEntry("alpha","n.；用户补充：字面词性  ","原义😀\n\n【用户补充】\n原文尾义  \n  ","raw-original","v0")
 private val body="独立用户正文\n\n【用户补充】\n字面用户正文\n重复\n重复"
 private fun repo()=LearningRepository(Context(),"s6-r1-isolated")
 private fun seed(r:LearningRepository){assertTrue(r.saveUserEntry("alpha","adv.",body,now).saved)}
 private fun plan(r:LearningRepository)=StudyMembership(StudyBook.ALL,listOf("alpha")){key->DictionaryResolver.resolve(null,r.storedEntry(key)?:base,key){r.userEntry(it)}}
 private fun exact(a:String,b:String){assertEquals(a,b);assertArrayEquals(a.toByteArray(Charsets.UTF_8),b.toByteArray(Charsets.UTF_8))}
 private fun rawPending(d:LearningBackupData){val s=d.session!!;exact(base.meaning,s.meaning);exact(base.partOfSpeech,s.partOfSpeech);assertEquals(base.headword,s.headword);assertEquals(base.source,s.source);assertEquals(base.version,s.version);exact(body,d.userEntries.single().meaning)}
 private fun display(e:DictionaryEntry){exact(base.meaning+"\n\n【用户补充】\n"+body,e.meaning);exact(base.partOfSpeech+"；用户补充：adv.",e.partOfSpeech);assertEquals(base,PersonalDictionary.storedEntry(e));assertEquals(base,e.originalSnapshot)}
 private fun codec(d:LearningBackupData):LearningBackupData{val decoded=LearningBackupCodec.decode(LearningBackupCodec.encode(d,"1.2.8",30,now));assertEquals(3,decoded.formatVersion);assertEquals(3,decoded.schemaVersion);assertEquals(d,decoded.data);return decoded.data}

 @Test fun S6R1B01_fileBackedRestartKeepsRevealedTokenRawSourceAndPersonalFields(){
  val file=File(System.getProperty("b4.evidence"),"s6-r1-restart.sqlite");assertFalse(file.exists())
  val persisted=LearningRepository(Context(SQLiteDatabase(file.absolutePath)),"restart").use{r->
   seed(r);assertTrue(r.record(QueryEvent("original-query","alpha","Alpha","alpha",now),base));assertTrue(r.updateWord("alpha",true,"keep private note"))
   val c=r.studyView(plan(r),now,zone).card!!;display(c.entry);assertEquals(StudyRules.NEW_STAGE_BEFORE,c.stageBefore);assertTrue(c.newWord);assertFalse(c.answerRevealed);assertEquals("keep private note",c.note)
   assertTrue(r.revealStudyCard(c.token));r.backupSnapshot().data.also{rawPending(it);assertEquals(c.token,it.session!!.token);assertTrue(it.session!!.answerRevealed)}
  }
  LearningRepository(Context(SQLiteDatabase(file.absolutePath),true),"restart").use{r->
   assertEquals(persisted,r.backupSnapshot().data);val view=r.studyView(plan(r),now,zone);val c=view.card!!;display(c.entry);assertEquals(persisted.session!!.token,c.token);assertTrue(c.answerRevealed);assertEquals("keep private note",c.note);assertEquals(persisted,r.backupSnapshot().data);assertEquals(0,view.progress.introducedToday)
   repo().use{target->assertEquals(RestoreOutcome.RESTORED,target.restoreBackup(codec(persisted),target.changes.version));val resumed=target.studyView(plan(target),now,zone).card!!;assertEquals(c,resumed);assertEquals(persisted,target.backupSnapshot().data)}
  }
 }

 @Test fun S6R1B02_dueStageSecondTokenRestoreFeedbackPreservesSeqQuotaAndOriginalFields(){repo().use{r->
  seed(r);r.record(QueryEvent("history","alpha","alpha","alpha",now),base);r.updateWord("alpha",true,"note stays");assertTrue(r.updateStudySettings(StudyBook.ALL,1))
  val first=r.studyView(plan(r),now,zone).card!!;assertTrue(r.revealStudyCard(first.token));assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(first.token,FeedbackKind.KNOW,now,zone))
  val committed=r.backupSnapshot().data;assertEquals(1L,committed.reviews.single().seq);assertEquals(0,committed.states.single().stage);assertEquals(1,r.studyView(plan(r),now,zone).progress.introducedToday);assertEquals(0,r.studyView(plan(r),now,zone).progress.newRemaining);assertNull(r.studyView(plan(r),now,zone).card)
  val due=committed.states.single().dueAt;val next=r.studyView(plan(r),due,zone).card!!;assertNotEquals(first.token,next.token);assertFalse(next.newWord);assertEquals(0,next.stageBefore);display(next.entry);assertTrue(r.revealStudyCard(next.token));val pending=codec(r.backupSnapshot().data);rawPending(pending)
  repo().use{target->assertEquals(RestoreOutcome.RESTORED,target.restoreBackup(pending,target.changes.version));val c=target.studyView(plan(target),due,zone).card!!;assertTrue(c.answerRevealed);assertEquals(next.token,c.token);assertEquals(next.dayKey,c.dayKey);assertEquals(next.stageBefore,c.stageBefore);display(c.entry);assertEquals(pending,target.backupSnapshot().data)
   assertEquals(StudyCommitOutcome.IDEMPOTENT,target.commitStudyFeedback(first.token,FeedbackKind.KNOW,due,zone));assertEquals(pending,target.backupSnapshot().data)
   assertEquals(StudyCommitOutcome.COMMITTED,target.commitStudyFeedback(c.token,FeedbackKind.FUZZY,due,zone));val after=target.backupSnapshot().data;assertEquals(listOf(1L,2L),after.reviews.map{it.seq}.sorted());assertEquals(2L,after.states.single().lastReviewSeq);assertEquals(0,after.states.single().stage);assertEquals(committed.words,after.words);assertEquals(committed.events,after.events);assertEquals(committed.userEntries,after.userEntries);assertEquals(committed.settings,after.settings);assertEquals(1,target.studyView(plan(target),due,zone).progress.totalIntroduced);assertNull(after.session)
  }
 }}

 @Test fun S6R1B03_userOnlyPendingRoundTripRevealAndFirstFeedbackRetainUserSource(){repo().use{r->
  seed(r);val p=StudyMembership(StudyBook.ALL,listOf("alpha")){key->PersonalDictionary.merge(null,r.userEntry(key))};val c=r.studyView(p,now,zone).card!!;val expected=PersonalDictionary.userOnly(r.userEntry("alpha")!!);assertEquals(expected,c.entry);assertNull(c.entry.originalSnapshot);val d=codec(r.backupSnapshot().data);assertTrue(d.words.isEmpty());exact(body,d.session!!.meaning);assertEquals(PersonalDictionary.SOURCE,d.session!!.source);assertEquals(PersonalDictionary.VERSION,d.session!!.version)
  repo().use{target->assertEquals(RestoreOutcome.RESTORED,target.restoreBackup(d,target.changes.version));val tp=StudyMembership(StudyBook.ALL,listOf("alpha")){key->PersonalDictionary.merge(null,target.userEntry(key))};val resumed=target.studyView(tp,now,zone).card!!;assertEquals(c,resumed);assertEquals(expected,resumed.entry);assertTrue(target.revealStudyCard(resumed.token));assertEquals(StudyCommitOutcome.COMMITTED,target.commitStudyFeedback(resumed.token,FeedbackKind.KNOW,now,zone));assertEquals(expected,target.storedEntry("alpha"));val after=target.backupSnapshot().data;exact(body,after.words.single().meaning);exact(body,after.userEntries.single().meaning);assertTrue(after.events.isEmpty());assertEquals(1L,after.reviews.single().seq);assertEquals(1,after.states.size)}
 }}

 @Test fun S6R1B04_invalidationDuringRealSnapshotResolveBeforeAdmissionWritesNothing(){repo().use{r->
  seed(r);val before=r.backupSnapshot();val admission=StudyAdmission();var resolved=false
  val p=StudyMembership(StudyBook.ALL,listOf("alpha")){resolved=true;admission.invalidate();PersonalDictionary.merge(base,r.userEntry("alpha"))}
  val c=r.studyView(p,now,zone,admission);assertTrue(resolved);assertFalse(admission.isActive);assertNull(c.card);assertEquals(before,r.backupSnapshot());assertFalse(r.writableDatabase.calls.any{it.startsWith("INSERT INTO study_session")})
 }}

 @Test fun S6R1B05_invalidationAfterActualAdmissionAllowsAtomicRawPendingOnly(){repo().use{r->
  seed(r);val before=r.backupSnapshot();val admission=StudyAdmission();var intercepted=false
  r.writableDatabase.beforeSql={sql->if(sql.startsWith("INSERT INTO study_session")){intercepted=true;r.writableDatabase.beforeSql=null;admission.invalidate()}}
  val c=r.studyView(plan(r),now,zone,admission).card!!;assertTrue(intercepted);assertFalse(admission.isActive);display(c.entry);val after=r.backupSnapshot();rawPending(after.data);assertEquals(c.token,after.data.session!!.token);assertEquals(before.data.copy(session=after.data.session),after.data);assertEquals(before.revision,after.revision);assertFalse(r.writableDatabase.inTransaction());assertTrue(r.writableDatabase.calls.contains("COMMIT"))
 }}

 @Test fun S6R1B06_actualSqlDealAndFeedbackRollbackKeepRawPendingLedgerAndRetrySeq(){repo().use{r->
  seed(r);val before=r.backupSnapshot();var inserted=false
  r.writableDatabase.afterSql={sql->if(sql.startsWith("INSERT INTO study_session")){r.writableDatabase.afterSql=null;inserted=true;error("S6R1 injected after actual pending insert")}}
  try{r.studyView(plan(r),now,zone);fail("must expose SQL transaction failure")}catch(e:IllegalStateException){assertEquals("S6R1 injected after actual pending insert",e.message)}
  assertTrue(inserted);assertEquals(before,r.backupSnapshot());assertTrue(r.writableDatabase.calls.contains("ROLLBACK"));assertFalse(r.writableDatabase.inTransaction())
  val c=r.studyView(plan(r),now,zone).card!!;display(c.entry);rawPending(r.backupSnapshot().data);assertTrue(r.revealStudyCard(c.token));val pending=r.backupSnapshot();var feedbackInserted=false
  r.writableDatabase.afterSql={sql->if(sql.startsWith("INSERT INTO study_reviews")){r.writableDatabase.afterSql=null;feedbackInserted=true;error("S6R1 injected after actual feedback insert")}}
  try{r.commitStudyFeedback(c.token,FeedbackKind.KNOW,now,zone);fail("must expose feedback rollback")}catch(e:IllegalStateException){assertEquals("S6R1 injected after actual feedback insert",e.message)}
  assertTrue(feedbackInserted);assertEquals(pending,r.backupSnapshot());assertFalse(r.writableDatabase.inTransaction());rawPending(r.backupSnapshot().data);assertTrue(r.backupSnapshot().data.words.isEmpty());assertTrue(r.backupSnapshot().data.reviews.isEmpty())
  assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(c.token,FeedbackKind.KNOW,now,zone));val done=r.backupSnapshot();assertEquals(1L,done.data.reviews.single().seq);assertEquals(1L,done.data.states.single().lastReviewSeq);assertEquals(base,r.storedEntry("alpha"));assertEquals(1,r.studyView(plan(r),now,zone).progress.introducedToday);assertTrue(done.data.events.isEmpty());assertEquals(before.data.userEntries,done.data.userEntries);val once=r.backupSnapshot();assertEquals(StudyCommitOutcome.IDEMPOTENT,r.commitStudyFeedback(c.token,FeedbackKind.KNOW,now,zone));assertEquals(once,r.backupSnapshot())
 }}
}
