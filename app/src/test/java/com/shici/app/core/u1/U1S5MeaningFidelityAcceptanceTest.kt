package com.shici.app.core.u1

import com.shici.app.core.*
import com.shici.app.core.b4.host.Context
import com.shici.app.core.b4.host.LearningRepository
import com.shici.app.core.b4.host.RestoreOutcome
import com.shici.app.core.b4.host.StudyMembership
import com.shici.app.core.b4.host.StudyCommitOutcome
import com.shici.app.core.q1.host.*
import com.shici.app.core.u1.host.ManualHost
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.ZoneId

/** S5 only: production PersonalDictionary/codec and full verbatim Repository over real SQLite.
 * UI callbacks are production slices with explicit View/queue adapters, not Android UI runtime. */
class U1S5MeaningFidelityAcceptanceTest {
 private val time=1792627200000L
 private val zone=ZoneId.of("UTC")
 private val body="首义😀\n\n  例句 X  \n    \n义项乙\n  例句 X  \n末义"
 private fun repo()=LearningRepository(Context(),"s5-isolated")
 private fun exact(expected:String,actual:String){assertEquals(expected,actual);assertArrayEquals(expected.toByteArray(Charsets.UTF_8),actual.toByteArray(Charsets.UTF_8))}
 private fun codec(d:LearningBackupData)=LearningBackupCodec.decode(LearningBackupCodec.encode(d,"1.2.6",28,time))
 private fun reject(d:LearningBackupData){try{codec(d);fail("invalid v3 accepted")}catch(_:BackupFormatException){}}
 private fun noMutation(r:LearningRepository,block:()->Unit){
  val before=r.backupSnapshot();val notes=mutableListOf<Long>();r.changes.observe{notes+=it}
  val begin=r.database.calls.size;block();val sql=r.database.calls.drop(begin)
  assertFalse("unexpected content writes: $sql",sql.any{it.startsWith("INSERT")||it.startsWith("DELETE")||it.startsWith("UPDATE")})
  assertEquals(before,r.backupSnapshot());assertTrue("no change notification",notes.isEmpty())
 }
 private fun pending(r:LearningRepository,k:String)=r.studyView(StudyMembership(StudyBook.ALL,listOf(k)){key->DictionaryResolver.resolve(null,r.storedEntry(key)?:DictionaryEntry(key,"n.","original","raw","v"),key){r.userEntry(it)}},time,zone).card!!
 @Test fun S5B01_mixedCRWholeTrimInternalWhitespaceRoundTripsSQLiteCodecRestore(){
  val raw=" \r\n"+body.replace("首义😀\n","首义😀\r\n").replace("义项乙\n","义项乙\r")+"\r\n  "
  exact(body,PersonalDictionary.sanitizeMeaning(raw));assertTrue(PersonalDictionary.isValidStoredMeaning(body))
  repo().use{r->
   val saved=r.saveUserEntry("novel","n.",raw,time);assertTrue(saved.saved&&saved.changed);exact(body,r.userEntry("novel")!!.meaning)
   val data=r.backupSnapshot().data;val decoded=codec(data);assertEquals(3,decoded.formatVersion);assertEquals(3,decoded.schemaVersion);assertEquals(data,decoded.data);exact(body,decoded.data.userEntries.single().meaning)
   repo().use{target->target.saveUserEntry("sentinel","","not merged",time);assertEquals(RestoreOutcome.RESTORED,target.restoreBackup(decoded.data,target.changes.version));assertEquals(data,target.backupSnapshot().data);exact(body,target.userEntry("novel")!!.meaning);assertNull(target.userEntry("sentinel"))}
  }
 }
 @Test fun S5B02_onlyInteriorBlankRepeatIndentAndTrailingSpacesAreRealMutations(){repo().use{r->
  r.saveUserEntry("alpha","n.","first\nX\nlast",time);var old=r.userEntry("alpha")!!.updatedAt
  val variants=listOf("first\n\nX\nlast","first\n\nX\nX\nlast","first\n\n  X\nX\nlast","first\n\n  X  \nX\nlast")
  val notes=mutableListOf<Long>();r.changes.observe{notes+=it}
  for(text in variants){
   val before=r.backupSnapshot();val changed=r.saveUserEntry("alpha","n.",text,time,expectedUpdatedAt=old);assertTrue(changed.saved&&changed.changed)
   val current=r.userEntry("alpha")!!;exact(text,current.meaning);assertEquals(old+1,current.updatedAt);val after=r.backupSnapshot();assertEquals(before.data.copy(userEntries=after.data.userEntries),after.data);assertEquals(before.revision+1,after.revision)
   noMutation(r){assertFalse("old content editor must stay stale",r.saveUserEntry("alpha","n.","old body",time,expectedUpdatedAt=old).saved)}
   old=current.updatedAt
  }
  assertEquals(variants.size,notes.size);assertTrue(r.events().isEmpty());assertTrue(r.backupSnapshot().data.reviews.isEmpty())
 }}
 @Test fun S5B03_sameCanonicalBodyIsNoOpWithoutErasingLinesOrPending(){repo().use{r->
  r.saveUserEntry("alpha","n.",body,time);pending(r,"alpha");val entry=r.userEntry("alpha")!!
  noMutation(r){val saved=r.saveUserEntry("alpha"," n. "," \r\n"+body.replace("\n","\r\n")+"\r\n ",Long.MIN_VALUE,expectedUpdatedAt=entry.updatedAt);assertTrue(saved.saved);assertFalse(saved.changed);assertEquals(entry,saved.entry)}
  exact(body,r.userEntry("alpha")!!.meaning)
 }}
 @Test fun S5B04_exactMaxLengthWithRepeatedLinesSurvivesAndPlusOneRejected(){repo().use{r->
  val text="A\n".repeat(8191)+"ZZ";assertEquals(16384,text.length)
  assertTrue(r.saveUserEntry("alpha","",text,time).saved);exact(text,r.userEntry("alpha")!!.meaning);exact(text,codec(r.backupSnapshot().data).data.userEntries.single().meaning)
  noMutation(r){assertFalse(r.saveUserEntry("alpha","",text+"Z",time).saved)}
  reject(LearningBackupData(emptyList(),emptyList(),userEntries=listOf(BackupUserEntry("alpha","",text+"Z",time))))
 }}
 @Test fun S5B05_emptyControlsAndReplacementStillRejectBeforeWrites(){repo().use{r->
  r.saveUserEntry("alpha","n.",body,time)
  for(bad in listOf(""," \r\n ","x\u0000y","x\ty","x\u007fy","x\u0085y","x\uFFFDy")){
   noMutation(r){assertFalse(r.saveUserEntry("alpha","n.",bad,time).saved)}
   assertFalse(PersonalDictionary.isValidStoredMeaning(bad));reject(LearningBackupData(emptyList(),emptyList(),userEntries=listOf(BackupUserEntry("alpha","n.",bad,time))))
  }
 }}
 @Test fun S5B06_codecRejectsNoncanonicalOuterWhitespaceAndCRRatherThanSilentlyRewriting(){
  for(text in listOf(" "+body,body+" ",body.replace("\n","\r\n"),body.replace("\n","\r"))){assertFalse(PersonalDictionary.isValidStoredMeaning(text));reject(LearningBackupData(emptyList(),emptyList(),userEntries=listOf(BackupUserEntry("alpha","",text,time))))}
 }
 @Test fun S5B07_malformedMeaningUtf8WithValidChecksumStillRejected(){
  val data=LearningBackupData(emptyList(),emptyList(),userEntries=listOf(BackupUserEntry("alpha","","unique_utf8_body",time)))
  val encoded=LearningBackupCodec.encode(data,"1.2.6",28,time);val payload=encoded.copyOf(encoded.size-32);val needle="unique_utf8_body".toByteArray()
  val offsets=(0..payload.size-needle.size).filter{i->needle.indices.all{payload[i+it]==needle[it]}};assertEquals(1,offsets.size);val at=offsets.single();assertEquals(needle.size,ByteBuffer.wrap(payload,at-4,4).int)
  payload[at]=0x80.toByte();val broken=payload+MessageDigest.getInstance("SHA-256").digest(payload)
  try{LearningBackupCodec.decode(broken);fail("invalid UTF8 with valid digest accepted")}catch(e:BackupFormatException){assertTrue(e.message!!.contains("UTF-8"))}
 }
 private fun rollback(stage:String){repo().use{r->
  r.saveUserEntry("alpha","n.",body,time);pending(r,"alpha");val before=r.backupSnapshot();val old=r.userEntry("alpha")!!.updatedAt;val notes=mutableListOf<Long>();r.changes.observe{notes+=it}
  val trigger=if(stage=="INSERT")"CREATE TEMP TRIGGER fail_s5 BEFORE INSERT ON user_entries BEGIN SELECT RAISE(ABORT,'S5 insert abort'); END" else "CREATE TEMP TRIGGER fail_s5 BEFORE DELETE ON study_session BEGIN SELECT RAISE(ABORT,'S5 delete abort'); END"
  r.database.execSQL(trigger);val result=r.saveUserEntry("alpha","n.",body.replace("\n\n","\n\n\n"),time,expectedUpdatedAt=old)
  assertFalse(result.saved);assertFalse(result.changed);assertEquals(before,r.backupSnapshot());assertTrue(notes.isEmpty());assertFalse(r.database.inTransaction());exact(body,r.userEntry("alpha")!!.meaning)
  r.database.execSQL("DROP TRIGGER fail_s5");val retry=r.saveUserEntry("alpha","n.",body+"\nnew",time,expectedUpdatedAt=old);assertTrue(retry.saved&&retry.changed);assertEquals(old+1,retry.entry!!.updatedAt);exact(body+"\nnew",r.userEntry("alpha")!!.meaning);assertEquals(listOf(before.revision+1),notes)
 }}
 @Test fun S5B08_realSQLiteInsertAbortPreservesOldBodyAndIdentity(){rollback("INSERT")}
 @Test fun S5B09_realSQLiteDeleteAbortRollsBackAlreadyWrittenNewBody(){rollback("DELETE")}
 @Test fun S5B10_userOnlyQueryStudyUpdateAndBackupKeepBodyAndRealLedger(){repo().use{r->
  r.saveUserEntry("novel","n.",body,time);val e=PersonalDictionary.userOnly(r.userEntry("novel")!!);assertTrue(r.record(QueryEvent("s5-query","novel","Novel","novel",time),e));exact(body,r.storedEntry("novel")!!.meaning)
  val card=pending(r,"novel");exact(body,card.entry.meaning);assertTrue(r.revealStudyCard(card.token));assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(card.token,FeedbackKind.KNOW,time,zone))
  val before=r.backupSnapshot();val old=r.userEntry("novel")!!.updatedAt;val next=body+"\n\n  例句 X  \nend";val saved=r.saveUserEntry("novel","n.",next,time,expectedUpdatedAt=old);assertTrue(saved.saved&&saved.changed)
  exact(next,DictionaryResolver.resolve(null,r.storedEntry("novel"),"novel"){r.userEntry(it)}!!.meaning);val after=r.backupSnapshot();assertEquals(before.data.copy(userEntries=after.data.userEntries),after.data);assertEquals(1,after.data.events.size);assertEquals(1,after.data.reviews.size);assertEquals(after.data,codec(after.data).data)
 }}
 private fun editor(floating:Boolean,missing:Boolean){
  repo().use{r->
   val h=if(floating)CaptureHost() else null;val m=if(!floating)ManualHost() else null;val app=h?.app?:m!!.app
   CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;app.repository.real=r;app.dictionary=Result.success(U1TestAssets.dictionary())
   fun pump(){app.worker.all();if(floating)h!!.main.all() else m!!.main.all()}
   fun nodes()=if(floating)h!!.nodes() else m!!.nodes()
   fun click(s:String){nodes().filterIsInstance<Button>().single{it.text==s}.click()}
   val key=if(missing)"novel" else "alpha";if(floating)h!!.queryForTest(key) else m!!.query(key);pump();pump()
   click(if(missing)"新增这个词" else "补充/编辑词义");val edits=nodes().filterIsInstance<EditText>();assertEquals(2,edits.size);edits.last().setText(body)
   val before=r.backupSnapshot();val notes=mutableListOf<Long>();r.changes.observe{notes+=it};click("保存到本机");pump()
   exact(body,r.userEntry(key)!!.meaning);assertTrue("exact interior body must reach display node",nodes().any{it.text.contains(body)});assertEquals(before.data.events,r.backupSnapshot().data.events);assertEquals(before.data.words,r.backupSnapshot().data.words);assertEquals(before.data.reviews,r.backupSnapshot().data.reviews);assertEquals(listOf(before.revision+1),notes)
   assertEquals(if(missing)0 else 1,r.events().size);exact(body,app.resolveEntry(key)!!.meaning.let{if(missing)it else it.substringAfter("\n\n【用户补充】\n")})
  }
 }
 @Test fun S5B11_manualHitEditorSavesAndDisplaysInteriorBody(){editor(false,false)}
 @Test fun S5B12_floatingHitEditorSavesAndDisplaysInteriorBody(){editor(true,false)}
 @Test fun S5B13_manualMissingEditorSavesAndDisplaysWithoutQueryHistory(){editor(false,true)}
 @Test fun S5B14_floatingMissingEditorSavesAndDisplaysWithoutQueryHistory(){editor(true,true)}
}
