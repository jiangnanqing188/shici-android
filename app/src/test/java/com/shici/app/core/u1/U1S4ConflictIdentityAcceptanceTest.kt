package com.shici.app.core.u1

import com.shici.app.core.*
import com.shici.app.core.b4.host.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** S4: every identity assertion crosses the public save/storage boundary and real isolated SQLite.
 * No private helper reflection, no wall-clock equality requirement, no SQL/FIFO adapter changes. */
class U1S4ConflictIdentityAcceptanceTest {
 private val time=1792627200000L
 private val zone=ZoneId.of("UTC")
 private fun repo()=LearningRepository(Context(),"s4-isolated")
 private fun entry(k:String)=DictionaryEntry(k,"n.","original $k","raw-original","v0")
 private fun plan(r:LearningRepository,k:String)=StudyMembership(StudyBook.ALL,listOf(k)){DictionaryResolver.resolve(null,r.storedEntry(it)?:entry(it),it){key->r.userEntry(key)}}
 private fun pending(r:LearningRepository,k:String)=r.studyView(plan(r,k),time,zone).card!!
 private fun restore(r:LearningRepository,vararg rows:BackupUserEntry){
  val data=LearningBackupData(emptyList(),emptyList(),userEntries=rows.sortedBy{it.entryKey})
  val decoded=LearningBackupCodec.decode(LearningBackupCodec.encode(data,"1.2.5",27,time))
  assertEquals("legal imported identities must not be normalized by codec",data,decoded.data)
  assertEquals(RestoreOutcome.RESTORED,r.restoreBackup(decoded.data,r.changes.version))
  assertEquals(data,r.backupSnapshot().data)
 }
 private fun version(r:LearningRepository,k:String)=r.userEntry(k)!!.updatedAt
 private fun advance(r:LearningRepository,k:String,text:String,now:Long,old:Long):Long {
  val a=r.saveUserEntry(k,"n.",text,now,expectedUpdatedAt=old)
  assertTrue("fresh current identity must save: $a",a.saved&&a.changed)
  val id=version(r,k);assertEquals(a.entry,r.userEntry(k));assertNotEquals("0 reserved for missing row",0L,id)
  assertTrue("real mutation must advance $old -> $id",id>old);assertEquals(text,r.userEntry(k)!!.meaning)
  return id
 }
 private fun noWrites(r:LearningRepository,block:()->Unit){
  val before=r.backupSnapshot();val notes=mutableListOf<Long>();r.changes.observe{notes+=it}
  val begin=r.database.calls.size;block();val sql=r.database.calls.drop(begin)
  assertFalse("must not issue content writes: $sql",sql.any{it.startsWith("INSERT")||it.startsWith("DELETE")||it.startsWith("UPDATE")})
  assertEquals(before,r.backupSnapshot());assertTrue("no public change notification",notes.isEmpty())
 }
 @Test fun S4B01_zeroClockCreationCannotAuthorizeSecondMissingEditor()=repo().use{r->
  val first=r.saveUserEntry("alpha","n.","one",0,expectedUpdatedAt=0);assertTrue(first.saved&&first.changed)
  val id=version(r,"alpha");assertNotEquals(0L,id)
  noWrites(r){assertFalse(r.saveUserEntry("alpha","n.","two",0,expectedUpdatedAt=0).saved)}
  advance(r,"alpha","two",0,id);Unit
 }
 @Test fun S4B02_negativeAndMinClockNewRowsAreNonzeroAndIndependent()=repo().use{r->
  for((k,t) in listOf("alpha" to -1L,"beta" to Long.MIN_VALUE)){
   val a=r.saveUserEntry(k,"n.","one",t,expectedUpdatedAt=0,newHeadword=true);assertTrue(a.saved&&a.changed);assertNotEquals(0L,version(r,k))
   val id=version(r,k);advance(r,k,"two",t,id)
   noWrites(r){assertFalse(r.saveUserEntry(k,"n.","stale",t,expectedUpdatedAt=id).saved)}
  }
 }
 @Test fun S4B03_sameMillisecondContentABACannotReuseOldIdentity()=repo().use{r->
  r.saveUserEntry("alpha","n.","A",time);val a=version(r,"alpha")
  val b=advance(r,"alpha","B",time,a);val c=advance(r,"alpha","A",time,b)
  assertNotEquals(a,c);noWrites(r){assertFalse(r.saveUserEntry("alpha","n.","late",time,expectedUpdatedAt=a).saved)}
  advance(r,"alpha","current",time,c);Unit
 }
 @Test fun S4B04_clockRollbackAndForwardJumpNeverReactivateOldEditor()=repo().use{r->
  r.saveUserEntry("alpha","n.","A",100);val a=version(r,"alpha")
  val b=advance(r,"alpha","B",-100,a);val c=advance(r,"alpha","A",100,b)
  val d=advance(r,"alpha","forward",10000,c);val e=advance(r,"alpha","back",Long.MIN_VALUE,d)
  assertEquals(5,setOf(a,b,c,d,e).size)
  noWrites(r){for(old in listOf(a,b,c,d))assertFalse(r.saveUserEntry("alpha","n.","late",old,expectedUpdatedAt=old).saved)}
 }
 @Test fun S4B05_eachKeyHasIndependentConflictIdentityNotGlobalAllocation()=repo().use{r->
  r.saveUserEntry("alpha","n.","A",100);r.saveUserEntry("beta","n.","B",100)
  assertEquals(version(r,"alpha"),version(r,"beta"));val b=version(r,"beta")
  advance(r,"alpha","A2",100,version(r,"alpha"));advance(r,"alpha","future",100000,version(r,"alpha"))
  assertEquals(b,version(r,"beta"));val next=advance(r,"beta","B2",100,b)
  assertEquals("other key's future identity must not consume this key's next identity",b+1,next)
 }
 @Test fun S4B06_importedNegativeIdentitiesAdvanceAcrossZeroWithoutCollision()=repo().use{r->
  restore(r,BackupUserEntry("alpha","n.","A",-2))
  val b=advance(r,"alpha","B",Long.MIN_VALUE,-2);assertEquals(-1L,b)
  val c=advance(r,"alpha","A",Long.MIN_VALUE,b);assertEquals(1L,c)
  noWrites(r){for(old in listOf(-2L,-1L,0L))assertFalse(r.saveUserEntry("alpha","n.","late",Long.MIN_VALUE,expectedUpdatedAt=old).saved)}
 }
 @Test fun S4B07_importedLongMinIdentityAdvancesWithoutOverflow()=repo().use{r->
  restore(r,BackupUserEntry("alpha","n.","A",Long.MIN_VALUE))
  val b=advance(r,"alpha","B",Long.MIN_VALUE,Long.MIN_VALUE);assertEquals(Long.MIN_VALUE+1,b)
  advance(r,"alpha","C",Long.MIN_VALUE+2,b)
  noWrites(r){assertFalse(r.saveUserEntry("alpha","n.","late",Long.MAX_VALUE,expectedUpdatedAt=Long.MIN_VALUE).saved)}
 }
 @Test fun S4B08_importedZeroNoopPreservesLegacyRowThenRealChangeRetiresZero()=repo().use{r->
  restore(r,BackupUserEntry("alpha","n.","A",0));pending(r,"alpha")
  noWrites(r){val a=r.saveUserEntry("alpha","n.","A",Long.MIN_VALUE,expectedUpdatedAt=0);assertTrue(a.saved);assertFalse(a.changed);assertEquals(0L,a.entry!!.updatedAt)}
  advance(r,"alpha","B",0,0)
  noWrites(r){assertFalse(r.saveUserEntry("alpha","n.","late",1,expectedUpdatedAt=0).saved)}
 }
 @Test fun S4B09_longMaxMinusOneCanCommitLastIdentityThenExhaustionRejects()=repo().use{r->
  restore(r,BackupUserEntry("alpha","n.","A",Long.MAX_VALUE-1))
  val b=advance(r,"alpha","B",Long.MIN_VALUE,Long.MAX_VALUE-1);assertEquals(Long.MAX_VALUE,b);pending(r,"alpha")
  noWrites(r){val a=r.saveUserEntry("alpha","n.","C",Long.MIN_VALUE,expectedUpdatedAt=b);assertFalse(a.saved);assertFalse(a.changed);assertEquals(r.userEntry("alpha"),a.entry)}
  noWrites(r){assertFalse("unconditional public save must not bypass exhaustion",r.saveUserEntry("alpha","n.","C",Long.MAX_VALUE).saved)}
 }
 @Test fun S4B10_longMaxSameContentStillSucceedsWithoutClearingPendingOrPublishing()=repo().use{r->
  restore(r,BackupUserEntry("alpha","n.","A",Long.MAX_VALUE));pending(r,"alpha")
  noWrites(r){for(t in listOf(Long.MIN_VALUE,0L,Long.MAX_VALUE)){
   val a=r.saveUserEntry("alpha","n.","A",t,expectedUpdatedAt=Long.MAX_VALUE);assertTrue(a.saved);assertFalse(a.changed);assertEquals(Long.MAX_VALUE,a.entry!!.updatedAt)
  }}
 }
 @Test fun S4B11_staleExpectationMustConflictEvenWhenContentEqualsCurrentRow()=repo().use{r->
  r.saveUserEntry("alpha","n.","A",time);val a=version(r,"alpha");val b=advance(r,"alpha","B",time,a);pending(r,"alpha")
  noWrites(r){val result=r.saveUserEntry("alpha","n.","B",time,expectedUpdatedAt=a);assertFalse(result.saved);assertFalse(result.changed);assertEquals(b,result.entry!!.updatedAt)}
 }
 @Test fun S4B12_sameContentNoopPreservesIdentityPendingAndRevisionAcrossClockExtremes()=repo().use{r->
  r.saveUserEntry("alpha","n.","A",time);val a=version(r,"alpha");pending(r,"alpha")
  noWrites(r){for(t in listOf(Long.MIN_VALUE,0L,time,Long.MAX_VALUE)){
   val result=r.saveUserEntry("alpha","n.","A",t,expectedUpdatedAt=a);assertTrue(result.saved);assertFalse(result.changed);assertEquals(a,result.entry!!.updatedAt)
  }}
 }
 @Test fun S4B13_partOfSpeechOnlyMutationAlsoRetiresOldIdentity()=repo().use{r->
  r.saveUserEntry("alpha","n.","A",time);val a=version(r,"alpha")
  val b=r.saveUserEntry("alpha","v.","A",time,expectedUpdatedAt=a);assertTrue(b.saved&&b.changed);assertTrue(b.entry!!.updatedAt>a)
  noWrites(r){assertFalse(r.saveUserEntry("alpha","n.","A",time,expectedUpdatedAt=a).saved)}
 }
 private fun failedWrite(stage:String)=repo().use{r->
  r.saveUserEntry("alpha","n.","A",time);val a=version(r,"alpha");pending(r,"alpha")
  val before=r.backupSnapshot();val notes=mutableListOf<Long>();r.changes.observe{notes+=it}
  // Real SQLite trigger aborts inside INSERT or DELETE; no alteration to transport failAt/FIFO.
  val trigger=if(stage=="INSERT")"CREATE TEMP TRIGGER fail_s4 BEFORE INSERT ON user_entries BEGIN SELECT RAISE(ABORT,'S4 real SQL abort'); END" else "CREATE TEMP TRIGGER fail_s4 BEFORE DELETE ON study_session BEGIN SELECT RAISE(ABORT,'S4 real SQL abort'); END"
  r.database.execSQL(trigger)
  val failed=r.saveUserEntry("alpha","n.","B",time,expectedUpdatedAt=a)
  assertFalse(failed.saved);assertFalse(failed.changed);assertEquals(before,r.backupSnapshot());assertTrue(notes.isEmpty());assertFalse(r.database.inTransaction())
  r.database.execSQL("DROP TRIGGER fail_s4")
  assertEquals("rolled-back candidate must not consume a public identity",a+1,advance(r,"alpha","B",time,a))
  assertEquals(listOf(before.revision+1),notes)
  val after=r.backupSnapshot();assertEquals(before.data.copy(userEntries=after.data.userEntries,session=null),after.data)
  noWrites(r){assertFalse(r.saveUserEntry("alpha","n.","late",time,expectedUpdatedAt=a).saved)}
 }
 @Test fun S4B14_realSQLiteInsertAbortRollsBackAndRetryDoesNotConsumeIdentity(){failedWrite("INSERT")}
 @Test fun S4B15_realSQLitePendingDeleteAbortRollsBackInsertedIdentityAndRetry(){failedWrite("DELETE")}
 @Test fun S4B16_admissionBeforeAndAfterLinearizationStillHasOriginalMeaning()=repo().use{r->
  r.saveUserEntry("alpha","n.","A",time);val a=version(r,"alpha");pending(r,"alpha");val permit=StudyAdmission();permit.invalidate()
  noWrites(r){
   val begin=r.database.calls.size
   assertFalse(r.saveUserEntry("alpha","n.","B",time,expectedUpdatedAt=a,admission=permit).saved)
   assertTrue("invalidated save must execute no SQL at all",r.database.calls.drop(begin).isEmpty())
  }
  val admitted=StudyAdmission();val before=r.backupSnapshot();val notices=mutableListOf<Long>();r.changes.observe{notices+=it}
  r.database.afterSql={if(it=="BEGIN")admitted.invalidate()}
  val result=try{r.saveUserEntry("alpha","n.","B",time,expectedUpdatedAt=a,admission=admitted)}finally{r.database.afterSql=null}
  assertTrue("once admitted before BEGIN a later invalidation cannot cancel atomic commit",result.saved&&result.changed)
  assertEquals(a+1,version(r,"alpha"));assertEquals(listOf(before.revision+1),notices)
 }
 @Test fun S4B17_backupRoundTripAndRestorePreserveAllLegalLegacyTimeValuesAndSchema()=repo().use{r->
  val rows=listOf(Long.MIN_VALUE,-1L,0L,1L,Long.MAX_VALUE).mapIndexed{i,t->BackupUserEntry(listOf("alpha","beta","gamma","delta","epsilon")[i],"n.","legacy $i",t)}
  val schemaBefore=r.writableDatabase.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name",null).use{c->val x=mutableListOf<String>();while(c.moveToNext())x+=c.getString(0)+c.getString(1);x}
  restore(r,*rows.toTypedArray());assertEquals(rows.sortedBy{it.entryKey},r.backupSnapshot().data.userEntries)
  val schemaAfter=r.database.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name",null).use{c->val x=mutableListOf<String>();while(c.moveToNext())x+=c.getString(0)+c.getString(1);x}
  assertEquals(schemaBefore,schemaAfter)
 }
 @Test fun S4B18_trueMutationDoesNotCreateEventsOrConsumeFeedbackSequenceOrOtherPending()=repo().use{r->
  val c=pending(r,"sentinel");assertTrue(r.revealStudyCard(c.token));assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(c.token,FeedbackKind.KNOW,time,zone))
  val other=pending(r,"beta");r.saveUserEntry("alpha","n.","A",time);val before=r.backupSnapshot();val a=version(r,"alpha");val notes=mutableListOf<Long>();r.changes.observe{notes+=it}
  advance(r,"alpha","B",time,a);val after=r.backupSnapshot()
  assertEquals(before.data.copy(userEntries=after.data.userEntries),after.data);assertEquals(listOf(before.revision+1),notes);assertTrue(after.data.events.isEmpty());assertEquals(other.token,after.data.session!!.token)
  assertTrue(r.revealStudyCard(other.token));assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(other.token,FeedbackKind.KNOW,time,zone))
  assertEquals(listOf(1L,2L),r.backupSnapshot().data.reviews.map{it.seq})
 }
}
