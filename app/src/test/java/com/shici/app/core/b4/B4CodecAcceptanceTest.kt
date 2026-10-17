package com.shici.app.core.b4
import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.time.*
class B4CodecAcceptanceTest {
 private val t=Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
 private val day=StudyRules.DAY_MILLIS
 private fun word(k:String="a")=BackupWord(k,k,"n","old meaning😀","legacy","v0",true,"note")
 private fun state()=BackupStudyState("a","ky",0,t+day,t,t,1)
 private fun review()=BackupStudyReview("r","a","know",-1,0,t+day,t,"2026-10-16","UTC",true,1)
 private fun session()=BackupStudySession("p","b","cet6",-1,true,true,"b","n","snapshot","legacy","v0","2026-10-16",t)
 private fun full()=LearningBackupData(listOf(word()),listOf(BackupEvent("q","a","raw","a",t,true)),BackupStudySettings("cet6",30),listOf(state()),listOf(review()),session())
 private fun decode(d:LearningBackupData)=LearningBackupCodec.decode(LearningBackupCodec.encode(d,"1.0.0",12,t))
 private fun reject(d:LearningBackupData){try{decode(d);fail("inconsistent v2 backup accepted")}catch(e:BackupFormatException){assertTrue(e.message!!.isNotBlank())}}
 private fun legacy():ByteArray {val bytes=ByteArrayOutputStream();val o=DataOutputStream(bytes);fun s(v:String){val b=v.toByteArray();o.writeInt(b.size);o.write(b)};o.writeBytes("SHICIBKP");o.writeInt(1);s("com.shici.app");s("0.4.1");o.writeInt(11);o.writeInt(1);o.writeLong(t);o.writeInt(1);o.writeInt(1);val w=word();listOf(w.entryKey,w.headword,w.partOfSpeech,w.meaning,w.source,w.version).forEach{ s(it) };o.writeBoolean(w.mastered);s(w.note);listOf("q","a","raw","a").forEach{ s(it) };o.writeLong(t);o.writeBoolean(true);val body=bytes.toByteArray();return body+MessageDigest.getInstance("SHA-256").digest(body)}
 @Test fun allV2FieldsRoundTrip(){val d=full();val v=decode(d);assertEquals(d,v.data);assertEquals(2,v.formatVersion);assertEquals(2,v.schemaVersion);assertFalse(v.legacyNoStudyData)}
 @Test fun genuineV1WireFixtureConverts(){val v=LearningBackupCodec.decode(legacy());assertTrue(v.legacyNoStudyData);assertEquals(1,v.formatVersion);assertEquals(1,v.schemaVersion);assertEquals(word(),v.data.words.single());assertTrue(v.data.events.single().revoked);assertEquals(BackupStudySettings(),v.data.settings);assertTrue(v.data.states.isEmpty());assertTrue(v.data.reviews.isEmpty());assertNull(v.data.session)}
 @Test fun v1ToV2ReencodeKeepsOldPersonalData(){val v1=LearningBackupCodec.decode(legacy());val v2=decode(v1.data);assertEquals(v1.data,v2.data);assertFalse(v2.legacyNoStudyData)}
 @Test fun invalidBookRejected(){reject(full().copy(settings=BackupStudySettings("cet4",20)))}
 @Test fun invalidGoalsRejected(){for(g in listOf(0,201,Int.MAX_VALUE))reject(full().copy(settings=BackupStudySettings("ky",g)))}
 @Test fun duplicateStateRejected(){reject(full().copy(states=listOf(state(),state())))}
 @Test fun orphanStateRejected(){reject(full().copy(states=listOf(state().copy(entryKey="none"))))}
 @Test fun invalidStateStageRejected(){for(s in listOf(-1,5))reject(full().copy(states=listOf(state().copy(stage=s))))}
 @Test fun duplicateFeedbackRejected(){reject(full().copy(reviews=listOf(review(),review())))}
 @Test fun feedbackOrphanRejected(){reject(full().copy(reviews=listOf(review().copy(entryKey="none"))))}
 @Test fun unknownFeedbackRejected(){reject(full().copy(reviews=listOf(review().copy(feedback="bogus"))))}
 @Test fun pendingTokenCollisionRejected(){reject(full().copy(session=session().copy(token="r")))}
 @Test fun pendingInvalidNewFlagRejected(){reject(full().copy(session=session().copy(newWord=false)))}
 @Test fun pendingEmptyTokenRejected(){reject(full().copy(session=session().copy(token="")))}
 @Test fun feedbackNewFlagMismatchRejected(){reject(full().copy(reviews=listOf(review().copy(newWord=false))))}
 @Test fun feedbackWithoutStateRejected(){reject(full().copy(states=emptyList()))}
 @Test fun stateWithoutAnyFeedbackRejected(){reject(full().copy(reviews=emptyList()))}
 @Test fun impossibleFeedbackTransitionRejected(){reject(full().copy(reviews=listOf(review().copy(stageAfter=4))))}
 @Test fun stateLatestFeedbackMismatchRejected(){reject(full().copy(states=listOf(state().copy(stage=4,dueAt=t+30*day))))}
 @Test fun duplicatedIntroductionInflatingQuotaRejected(){reject(full().copy(reviews=listOf(review(),review().copy(token="r2",reviewedAt=t+1))))}
 @Test fun invalidDayKeyRejected(){reject(full().copy(reviews=listOf(review().copy(dayKey="not-a-date"))))}
 @Test fun invalidZoneRejected(){reject(full().copy(reviews=listOf(review().copy(zoneId="Moon/Crater"))))}
 @Test fun dayTimeZoneDisagreementRejected(){reject(full().copy(reviews=listOf(review().copy(dayKey="1999-01-01"))))}
 @Test fun contradictoryDueRejected(){reject(full().copy(reviews=listOf(review().copy(dueAfter=Long.MIN_VALUE))))}
 @Test fun invertedStateTimesRejected(){reject(full().copy(states=listOf(state().copy(introducedAt=t+day,lastReviewAt=t-1))))}
 @Test fun existingPendingWithoutStateRejected(){reject(full().copy(session=session().copy(entryKey="c",headword="c",newWord=false,stageBefore=0)))}
 @Test fun newPendingForIntroducedWordRejected(){reject(full().copy(session=session().copy(entryKey="a",headword="a")))}
 @Test fun pendingHeadwordKeyMismatchRejected(){reject(full().copy(session=session().copy(headword="different")))}
 @Test fun pendingIllegalDayKeyRejected(){reject(full().copy(session=session().copy(dayKey="bad-day")))}
 @Test fun existingPendingStageMismatchRejected(){reject(full().copy(session=session().copy(entryKey="a",headword="a",stageBefore=4,newWord=false)))}
 @Test fun invalidStudyUtf16Rejected(){try{decode(full().copy(session=session().copy(meaning="\uD800")));fail("invalid UTF16 accepted")}catch(e:BackupFormatException){}}
}
