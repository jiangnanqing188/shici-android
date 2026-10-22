package com.shici.app.core.b4
import com.shici.app.core.*
import com.shici.app.core.b4.host.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
/** Real production Repository/codec, independent in-memory SQLite engine. No user database. */
class B4G3SequenceQuotaRegressionTest {
 private val now=Instant.parse("2026-10-16T12:00:00Z").toEpochMilli();private val zone=ZoneOffset.UTC
 private fun plan(vararg keys:String,book:StudyBook=StudyBook.ALL)=StudyMembership(book,keys.toList()){DictionaryEntry(it,"n","meaning","synthetic","v0")}
 private fun <T> repo(f:(LearningRepository)->T):T=LearningRepository(Context(),"g3-sequence-quota").use(f)
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
  answer(r,view(r,"a",at=now+1),at=now+1);answer(r,view(r,"b"));val stale=view(r,"b",at=now+StudyRules.DAY_MILLIS);r.revealStudyCard(stale.card!!.token,stale.credential)
  assertEquals(DeleteOutcome.DELETED,r.deleteWord("a",stale.credential));val d=round(r.backupSnapshot().data);assertEquals(stale.card!!.token,d.session!!.token)
  assertEquals(listOf(2L),d.reviews.map{it.seq});assertEquals(listOf("b"),d.states.map{it.entryKey});assertEquals(RestoreOutcome.RESTORED,r.restoreBackup(d,r.changes.version))
  assertEquals(StudyCommitOutcome.STALE,r.commitStudyFeedback(stale.card!!.token,FeedbackKind.KNOW,now+StudyRules.DAY_MILLIS,zone,stale.credential))
  answer(r,view(r,"c"));val after=r.backupSnapshot().data;assertEquals(setOf(2L,3L),after.reviews.map{it.seq}.toSet());assertEquals(after,round(after))
 }
 @Test fun failedCommitRollsBackSequenceAndRetryRemainsIdempotent()=repo{r->
  answer(r,view(r,"a"));val v=view(r,"b");r.revealStudyCard(v.card!!.token,v.credential);val before=r.backupSnapshot()
  r.database.failAt="INSERT INTO study_reviews"
  try{r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential);fail("injected failure expected")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("injected:"))}
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
  r.updateStudySettings(StudyBook.ALL,2);answer(r,view(r,"a"));val old=view(r,"b");assertNotNull(old.card);r.updateStudySettings(StudyBook.ALL,1)
  assertNull(view(r,"a","b").card);val d=round(r.backupSnapshot().data);assertNull(d.session)
  repo{fresh->fresh.restoreBackup(d,fresh.changes.version);assertNull(view(fresh,"a","b").card);fresh.updateStudySettings(StudyBook.CET6,1)
   val other=fresh.studyView(plan("c",book=StudyBook.CET6),now,zone);assertNull(other.card);assertEquals(1,other.progress.introducedToday);assertEquals(0,other.progress.newRemaining)}
 }
 @Test fun dueReviewNotBlockedByLoweredGoalAndDiscardedNewCard()=repo{r->
  r.updateStudySettings(StudyBook.ALL,2);answer(r,view(r,"a"),FeedbackKind.FORGET);assertEquals("b",view(r,"a","b").card!!.entryKey);r.updateStudySettings(StudyBook.ALL,1)
  val due=view(r,"a","b",at=now+600000);assertEquals("a",due.card!!.entryKey);assertFalse(due.card!!.newWord);assertEquals(0,due.progress.newRemaining)
  answer(r,due,at=now+600000);assertEquals(1,r.backupSnapshot().data.reviews.count{it.newWord});assertEquals(2,r.backupSnapshot().data.reviews.size)
 }
 @Test fun nonexistentTailSequenceCannotMasqueradeAsCurrentState()=repo{r->
  answer(r,view(r,"a"));val d=r.backupSnapshot().data;assertEquals(d,round(d));reject(d.copy(states=d.states.map{it.copy(lastReviewSeq=it.lastReviewSeq+1)}))
 }
}
