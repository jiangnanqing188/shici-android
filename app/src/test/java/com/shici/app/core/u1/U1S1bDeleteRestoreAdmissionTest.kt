package com.shici.app.core.u1

import com.shici.app.core.*
import com.shici.app.core.u1.host.ManualHost
import com.shici.app.core.q1.host.*
import com.shici.app.core.b4.host.LearningRepository
import com.shici.app.core.b4.host.Context as DbContext
import com.shici.app.core.b4.host.DeleteOutcome
import com.shici.app.core.b4.host.RestoreOutcome
import com.shici.app.core.b4.host.StudyMembership
import com.shici.app.core.b4.host.StudyCommitOutcome
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** S1b only: actual UI save callback -> real worker -> verbatim Repository -> isolated SQLite.
 * The latch precedes writableDatabase admission; no production/helper replacement or forced outcome. */
class U1S1bDeleteRestoreAdmissionTest {
 private class F(val floating:Boolean):AutoCloseable {
  val r=LearningRepository(DbContext(),"s1b-isolated")
  val h=if(floating)CaptureHost()else null
  val m=if(!floating)ManualHost()else null
  val app get()=h?.app?:m!!.app
  init{CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;app.repository.real=r;app.dictionary=Result.success(U1TestAssets.dictionary())}
  fun nodes()=h?.nodes()?:m!!.nodes()
  fun main(){h?.main?.all()?:m!!.main.all()}
  fun pump(){app.worker.all();main()}
  fun query(key:String){h?.queryForTest(key)?:m!!.query(key);pump();pump()}
  fun click(label:String){nodes().filterIsInstance<Button>().first{it.text==label}.click()}
  fun open(key:String,body:String="obsolete queued meaning"){
   query(key)
   val buttons=nodes().filterIsInstance<Button>()
   buttons.first{it.text=="补充/编辑词义"||it.text=="新增这个词"}.click()
   val fields=nodes().filterIsInstance<EditText>();assertEquals(2,fields.size);fields.last().setText(body)
  }
  fun freshOpen(key:String,body:String){
   if(h!=null){
    // queryForTest supplies a manual form, NOT a new capture. The real capture-entry body must
    // capture the new deletion/restore epoch first. Cancel only its unused frame, then supply
    // the form through the existing lookup adapter; OCR/capture correctness is not under test.
    h.start();assertEquals("fresh capture must acquire current epoch",r.deletions.capture(),h.deletionCredential)
    h.close()
   }
   open(key,body)
  }
  fun save(){click("保存到本机")}
  fun text()=nodes().joinToString("\n"){it.text}
  override fun close(){r.close()}
 }
 // Nonempty replacement world, including a dictionary row for the OLD editor key, unrelated
 // personal history, private note/mastery and a genuine feedback record. All are setup, not counts.
 private fun replacement(key:String):LearningBackupData=LearningRepository(DbContext(),"s1b-donor").use{r->
  val now=1792540800000L;val e=DictionaryEntry("sentinel","n.","restored source","raw-original","v0")
  assertTrue(r.record(QueryEvent("restored-query","sentinel","sentinel","sentinel",now),e))
  assertTrue(r.updateWord("sentinel",true,"restored private note"))
  val plan=StudyMembership(StudyBook.ALL,listOf("sentinel")){e}
  val card=r.studyView(plan,now,ZoneId.of("UTC")).card!!
  assertTrue(r.revealStudyCard(card.token))
  assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(card.token,FeedbackKind.KNOW,now,ZoneId.of("UTC")))
  assertTrue(r.saveUserEntry(key,"n.","restored dictionary meaning",now).saved)
  r.backupSnapshot().data.also{assertEquals(1,it.reviews.size);assertEquals(1,it.states.size)}
 }
 private fun blocked(f:F,invalidate:()->Unit){
  f.save()
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val error=AtomicReference<Throwable?>()
  f.r.beforeWritable={f.r.beforeWritable=null;entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
  val worker=Thread{try{f.app.worker.all()}catch(t:Throwable){error.set(t)}}
  worker.start()
  try{
   assertTrue("UI callback's real worker must reach pre-admission writableDatabase",entered.await(10,TimeUnit.SECONDS))
   invalidate()
  }finally{release.countDown();worker.join(10000)}
  assertFalse("save worker must complete",worker.isAlive);error.get()?.let{throw it}
 }
 private fun deletion(floating:Boolean)=F(floating).use{f->
  assertTrue(f.r.saveUserEntry("alpha","n.","dictionary must survive learning deletion",1).saved)
  f.open("alpha")
  var expected:com.shici.app.core.b4.host.LearningBackupSnapshot?=null
  val workerSql=mutableListOf<String>()
  blocked(f){
   assertEquals(DeleteOutcome.DELETED,f.r.deleteWord("alpha",f.r.deletions.capture()))
   expected=f.r.backupSnapshot()
   // No other SQL runs until this worker joins; observe the actual storage boundary, not a mock.
   f.r.database.beforeSql={sql->workerSql+=sql}
  }
  f.r.database.beforeSql=null
  assertTrue("invalidated worker must not begin any transaction or issue any SQL: $workerSql",workerSql.isEmpty())
  f.main();assertEquals("old save cannot add history/feedback/revision or change dictionary",expected,f.r.backupSnapshot())
  assertEquals("dictionary must survive learning deletion",f.r.userEntry("alpha")!!.meaning)
  assertTrue(f.r.events().isEmpty());assertTrue(f.r.backupSnapshot().data.reviews.isEmpty())
  assertFalse(f.text().contains("已保存到本机用户词库"))
  // An explicit new query/editor AFTER deletion is a fresh owner, not the separate S2 stale callback.
  f.freshOpen("alpha","fresh owner after deletion");val history=f.r.backupSnapshot().data;f.save();f.pump()
  assertEquals("fresh owner after deletion",f.r.userEntry("alpha")!!.meaning)
  val after=f.r.backupSnapshot().data;assertEquals(history.copy(userEntries=after.userEntries),after)
 }
 @Test fun S1B01_manualRunningMatchedSaveDeletionBeforeAdmissionPreservesDictionary(){deletion(false)}
 @Test fun S1B02_floatingRunningMatchedSaveDeletionBeforeAdmissionPreservesDictionary(){deletion(true)}
 private fun restore(floating:Boolean,key:String)=F(floating).use{f->
  val restored=replacement(key);f.open(key)
  var expected:com.shici.app.core.b4.host.LearningBackupSnapshot?=null
  val workerSql=mutableListOf<String>()
  blocked(f){
   assertEquals(RestoreOutcome.RESTORED,f.r.restoreBackup(restored,f.r.changes.version))
   expected=f.r.backupSnapshot();assertEquals(restored,expected!!.data)
   f.r.database.beforeSql={sql->workerSql+=sql}
  }
  f.r.database.beforeSql=null
  assertTrue("must reject at admission, not merely conflict inside a transaction: $workerSql",workerSql.isEmpty())
  f.main();assertEquals("entire restored world and revision must survive old save",expected,f.r.backupSnapshot())
  assertEquals("restored dictionary meaning",f.r.userEntry(key)!!.meaning)
  assertFalse(f.text().contains("已保存到本机用户词库"))
  f.freshOpen(key,"fresh owner after restore");val history=f.r.backupSnapshot().data;f.save();f.pump()
  assertEquals("fresh owner after restore",f.r.userEntry(key)!!.meaning)
  val after=f.r.backupSnapshot().data;assertEquals(history.copy(userEntries=after.userEntries),after)
 }
 @Test fun S1B03_manualRunningMissingSaveRestoreKeepsNonemptyNewWorld(){restore(false,"novel")}
 @Test fun S1B04_floatingRunningMissingSaveRestoreKeepsNonemptyNewWorld(){restore(true,"novel")}
 @Test fun S1B05_manualRunningMatchedSaveRestoreKeepsNonemptyNewWorld(){restore(false,"alpha")}
 @Test fun S1B06_floatingRunningMatchedSaveRestoreKeepsNonemptyNewWorld(){restore(true,"alpha")}
 private fun admittedThenRestore(floating:Boolean)=F(floating).use{f->
  val restored=replacement("novel");f.open("novel","admitted meaning");val before=f.r.backupSnapshot()
  f.save();f.app.worker.all() // The real transaction commits; its main callback is still queued.
  val committed=f.r.backupSnapshot();assertEquals("admitted meaning",f.r.userEntry("novel")!!.meaning)
  assertEquals(before.revision+1,committed.revision)
  assertEquals(before.data.copy(userEntries=committed.data.userEntries),committed.data)
  assertEquals(RestoreOutcome.RESTORED,f.r.restoreBackup(restored,committed.revision))
  val expected=f.r.backupSnapshot();f.main()
  assertEquals(expected,f.r.backupSnapshot());assertEquals(restored,expected.data)
  assertTrue("committed save must be described truthfully after restore",f.text().contains("保存已提交，但该词随后被删除或数据已恢复"))
  assertFalse("no stale successful card repaint",f.text().contains("已保存到本机用户词库"))
  assertFalse("must not falsely claim admitted transaction was rejected",f.text().contains("操作已失效，未保存"))
 }
 @Test fun S1B07_manualAdmittedCommittedSaveThenRestoreSuppressesStaleSuccess(){admittedThenRestore(false)}
 @Test fun S1B08_floatingAdmittedCommittedSaveThenRestoreSuppressesStaleSuccess(){admittedThenRestore(true)}
}
