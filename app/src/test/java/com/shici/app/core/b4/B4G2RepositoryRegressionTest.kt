package com.shici.app.core.b4
import com.shici.app.core.*
import com.shici.app.core.b4.host.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
/** Real verbatim G2 Repository plus SQLite RPC, not business-logic simulation. */
class B4G2RepositoryRegressionTest {
 private val now=Instant.parse("2026-10-16T12:00:00Z").toEpochMilli();private val zone=ZoneOffset.UTC
 private fun entry(k:String)=DictionaryEntry(k,"n","old meaning","old source","old version")
 private fun plan(vararg keys:String)=StudyMembership(StudyBook.ALL,keys.toList()){entry(it)}
 private fun <T> repo(f:(LearningRepository)->T):T=LearningRepository(Context(),"g2-synthetic").use(f)
 private fun view(r:LearningRepository,at:Long=now)=r.studyView(plan("a"),at,zone)
 private fun answer(r:LearningRepository,v:StudyView,kind:FeedbackKind,at:Long){r.revealStudyCard(v.card!!.token,v.credential);assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(v.card!!.token,kind,at,zone,v.credential))}
 private fun roundTrip(data:LearningBackupData):LearningBackupData=LearningBackupCodec.decode(LearningBackupCodec.encode(data,"1.0.2",14,now)).data
 private fun reject(data:LearningBackupData){try{roundTrip(data);fail("older real state accepted despite later committed feedback")}catch(e:BackupFormatException){assertTrue(e.message!!.isNotBlank())}}
 @Test fun legalClockRollbackRepositoryFeedbackMustRoundTrip()=repo{r->
  answer(r,view(r),FeedbackKind.FORGET,now)
  val pending=view(r,now+600000)
  answer(r,pending,FeedbackKind.KNOW,now-1)
  val data=r.backupSnapshot().data
  assertEquals(2,data.reviews.size);assertEquals(1,data.reviews.count{it.newWord})
  assertTrue(data.states.single().introducedAt>data.states.single().lastReviewAt)
  println("legal committed clock rollback: "+data)
  assertEquals(data,roundTrip(data))
 }
 @Test fun olderRealStateCannotReplaceLatestCommitWithValidChecksum()=repo{r->
  answer(r,view(r),FeedbackKind.KNOW,now);val old=r.backupSnapshot().data.states
  answer(r,view(r,now+StudyRules.DAY_MILLIS),FeedbackKind.KNOW,now+StudyRules.DAY_MILLIS)
  val latest=r.backupSnapshot().data;assertEquals(latest,roundTrip(latest));assertEquals(1,latest.states.single().stage)
  val forged=latest.copy(states=old);assertEquals(2,forged.reviews.size)
  println("older-state substitution: "+forged);reject(forged)
 }
 @Test fun sameMillisecondRealCommitsCannotRollbackToEarlierRealState()=repo{r->
  answer(r,view(r),FeedbackKind.FORGET,now);val old=r.backupSnapshot().data.states
  answer(r,view(r,now+600000),FeedbackKind.KNOW,now)
  val latest=r.backupSnapshot().data;assertEquals(setOf(now),latest.reviews.map{it.reviewedAt}.toSet())
  assertEquals(latest,roundTrip(latest));println("same-ms real commit sequence: "+latest);reject(latest.copy(states=old))
 }
 @Test fun lawfulSameMillisecondHistoryRoundTripsWithoutWallClockSortAssumption()=repo{r->
  answer(r,view(r),FeedbackKind.FORGET,now);answer(r,view(r,now+600000),FeedbackKind.KNOW,now)
  val d=r.backupSnapshot().data;assertEquals(2,d.reviews.size);assertEquals(1,d.reviews.count{it.newWord});assertEquals(d,roundTrip(d))
 }
 @Test fun introducedRelearningMinusOneAndFuzzyRoundTrip()=repo{r->
  answer(r,view(r),FeedbackKind.FORGET,now);val first=r.backupSnapshot().data
  assertEquals(-1,first.states.single().stage);assertEquals(first,roundTrip(first))
  answer(r,view(r,now+600000),FeedbackKind.FUZZY,now+600000);val second=r.backupSnapshot().data
  assertEquals(-1,second.states.single().stage);assertEquals(1,second.reviews.count{it.newWord});assertEquals(second,roundTrip(second))
 }
 @Test fun revokedPersonalMemberRejectsVisibleOldFeedbackWithoutReplan()=repo{r->
  r.record(QueryEvent("q","a","a","a",now),entry("a"));r.updateStudySettings(StudyBook.PERSONAL,20)
  val v=r.studyView(StudyMembership(StudyBook.PERSONAL,r.personalMembers()){null},now,zone)
  r.revealStudyCard(v.card!!.token,v.credential);r.revoke("q");val before=r.backupSnapshot()
  assertEquals(StudyCommitOutcome.REJECTED,r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential));assertEquals(before,r.backupSnapshot())
 }
 @Test fun loweredGoalPlanningMustNotKeepOverQuotaNewCard()=repo{r->
  r.updateStudySettings(StudyBook.ALL,2);answer(r,view(r),FeedbackKind.KNOW,now)
  val pending=r.studyView(plan("a","b"),now,zone);assertEquals("b",pending.card!!.entryKey)
  r.updateStudySettings(StudyBook.ALL,1);val refreshed=r.studyView(plan("a","b"),now,zone)
  assertEquals(0,refreshed.progress.newRemaining);assertNull("planning must not retain an over-quota new card",refreshed.card)
 }
 @Test fun pendingSnapshotStaysOldButPersonalNoteReadsLive()=repo{r->
  r.record(QueryEvent("q","a","a","a",now),entry("a"));r.updateWord("a",true,"first note")
  val first=view(r);r.updateWord("a",true,"new note")
  r.record(QueryEvent("q2","a","a","a",now+1),DictionaryEntry("a","v","updated meaning","updated source","updated version"))
  val current=view(r);assertEquals(first.card!!.entry,current.card!!.entry);assertEquals("new note",current.card!!.note)
  assertEquals("old source",current.card!!.entry.source);assertEquals("updated source",r.backupSnapshot().data.words.single().source)
 }
 @Test fun queryEventLongExtremesRetainOriginalSemantics()=repo{r->
  r.record(QueryEvent("min","a","a","a",Long.MIN_VALUE),entry("a"));r.record(QueryEvent("max","a","a","a",Long.MAX_VALUE),entry("a"))
  val d=r.backupSnapshot().data;assertEquals(setOf(Long.MIN_VALUE,Long.MAX_VALUE),d.events.map{it.time}.toSet());assertEquals(d,roundTrip(d))
 }
}
