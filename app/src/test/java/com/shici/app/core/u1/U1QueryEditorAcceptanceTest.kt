package com.shici.app.core.u1
import com.shici.app.core.*
import com.shici.app.core.u1.host.ManualHost
import com.shici.app.core.q1.host.*
import com.shici.app.core.b4.host.LearningRepository
import org.junit.Assert.*
import org.junit.Test
/** Actual query->card/Missing->editor->save callbacks. Only UI/framework/queues stand-ins;
 * app lookup/resolve actual body and all persistence runs exact production through actual SQLite. */
class U1QueryEditorAcceptanceTest {
 private class F(val floating:Boolean):AutoCloseable {
  val r=LearningRepository(com.shici.app.core.b4.host.Context(),"u1-ui-isolated");val h=if(floating)CaptureHost() else null;val m=if(!floating)ManualHost() else null
  val app get()=h?.app?:m!!.app
  init{CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;app.repository.real=r;app.dictionary=Result.success(U1TestAssets.dictionary())}
  fun query(k:String){if(floating)h!!.queryForTest(k) else m!!.query(k);pump();pump()}
  fun pump(){app.worker.all();if(floating)h!!.main.all() else m!!.main.all()}
  fun nodes()=if(floating)h!!.nodes() else m!!.nodes()
  fun click(s:String){nodes().first{it is Button && it.text==s}.click()}
  fun text()=nodes().joinToString("\n"){it.text}
  fun editor(k:String="alpha",body:String="新增义"){query(k);click(if(k=="alpha")"补充/编辑词义" else "新增这个词");val edits=nodes().filterIsInstance<EditText>();assertEquals(2,edits.size);edits.last().setText(body)}
  fun saveQueued(){click("保存到本机")}
  fun leave(){if(floating)h!!.close() else m!!.close()}
  fun stopOwner(){if(floating)h!!.stop() else m!!.stop()}
  override fun close(){r.close()}
 }
 private fun success(floating:Boolean)=F(floating).use{f->f.editor();val before=f.r.backupSnapshot();f.saveQueued();f.pump();assertEquals("新增义",f.r.userEntry("alpha")!!.meaning);assertEquals(before.data.events,f.r.events().map{BackupEvent(it.id,it.entryKey,it.rawText,it.finalForm,it.time,it.revoked)});assertEquals(1,f.r.events().size);assertTrue(f.text().contains("新增义"));assertTrue(f.text().contains("原始义"));val a=f.app.lookupWord("alpha") as LookupResult.Match;assertEquals(a.entries.single(),f.app.resolveEntry("alpha"));assertEquals(1,f.r.userEntryCount())}
 @Test fun U1UI01_manualHitSaveUpdatesDisplayWithoutExtraQuery(){success(false)}
 @Test fun U1UI02_floatingHitSaveUpdatesDisplayWithoutExtraQuery(){success(true)}
 private fun missing(floating:Boolean)=F(floating).use{f->f.editor("novel");assertTrue(f.r.events().isEmpty());f.saveQueued();f.pump();assertTrue(f.r.events().isEmpty());assertTrue(f.r.personalMembers().isEmpty());assertTrue(f.text().contains("尚未收录"));f.click("查询并收录");f.pump();f.pump();assertEquals(1,f.r.events().size);assertEquals(listOf("novel"),f.r.personalMembers());assertEquals(PersonalDictionary.SOURCE,f.r.storedEntry("novel")!!.source)}
 @Test fun U1UI03_manualMissingSaveThenExplicitCollect(){missing(false)}
 @Test fun U1UI04_floatingMissingSaveThenExplicitCollect(){missing(true)}
 private fun cancelled(floating:Boolean)=F(floating).use{f->f.editor("novel");f.leave();f.pump();assertNull(f.r.userEntry("novel"));assertTrue(f.r.events().isEmpty())}
 @Test fun U1UI05_manualCancelWithoutSaveDoesNotMutate(){cancelled(false)}
 @Test fun U1UI06_floatingCloseWithoutSaveDoesNotMutate(){cancelled(true)}
 private fun queuedClosed(floating:Boolean)=F(floating).use{f->f.editor("novel");f.saveQueued();f.leave();f.pump();assertNull("editor closed before true write admission must not persist",f.r.userEntry("novel"));assertTrue(f.r.events().isEmpty())}
 @Test fun U1UI07_manualQueuedSaveClosedBeforeWorkerRejects(){queuedClosed(false)}
 @Test fun U1UI08_floatingQueuedSaveClosedBeforeWorkerRejects(){queuedClosed(true)}
 private fun queuedStop(floating:Boolean)=F(floating).use{f->f.editor("novel");f.saveQueued();f.stopOwner();f.pump();assertNull("stop before actual save must reject stale editor",f.r.userEntry("novel"))}
 @Test fun U1UI09_manualQueuedSaveStopBeforeWorkerRejects(){queuedStop(false)}
 @Test fun U1UI10_floatingQueuedSaveStopBeforeWorkerRejects(){queuedStop(true)}
 private fun queuedRestore(floating:Boolean)=F(floating).use{f->f.editor("novel");f.saveQueued();val snap=f.r.backupSnapshot();f.r.restoreBackup(snap.data,snap.revision);f.pump();assertNull("restore invalidated editor before queued write",f.r.userEntry("novel"));assertFalse(f.text().contains("已保存到本机用户词库"))}
 @Test fun U1UI11_manualQueuedSaveRestoreBeforeWorkerRejects(){queuedRestore(false)}
 @Test fun U1UI12_floatingQueuedSaveRestoreBeforeWorkerRejects(){queuedRestore(true)}
 private fun queuedDelete(floating:Boolean)=F(floating).use{f->f.editor();f.saveQueued();f.r.deleteWord("alpha",f.r.deletions.capture());f.pump();assertNull("delete invalidated editor before queued write",f.r.userEntry("alpha"));assertTrue(f.r.events().isEmpty())}
 @Test fun U1UI13_manualQueuedSaveDeleteBeforeWorkerRejects(){queuedDelete(false)}
 @Test fun U1UI14_floatingQueuedSaveDeleteBeforeWorkerRejects(){queuedDelete(true)}
 private fun rollback(floating:Boolean)=F(floating).use{f->f.editor("novel");f.r.database.failAt="INSERT OR REPLACE INTO user_entries";f.saveQueued();f.pump();assertNull(f.r.userEntry("novel"));assertTrue(f.text().contains("保存失败"));assertFalse(f.text().contains("已保存到本机用户词库"));assertTrue(f.r.events().isEmpty())}
 @Test fun U1UI15_manualSaveSqlFailureNoFalseSuccess(){rollback(false)}
 @Test fun U1UI16_floatingSaveSqlFailureNoFalseSuccess(){rollback(true)}
 private fun empty(floating:Boolean)=F(floating).use{f->f.editor("novel"," ");f.saveQueued();f.pump();assertNull(f.r.userEntry("novel"));assertTrue(f.text().contains("不能为空"))}
 @Test fun U1UI17_manualEmptyMeaningNotSaved(){empty(false)}
 @Test fun U1UI18_floatingEmptyMeaningNotSaved(){empty(true)}
 private fun oldCallback(floating:Boolean)=F(floating).use{f->f.editor("novel");f.saveQueued();f.app.worker.all();f.leave();f.pump();assertEquals("新增义",f.r.userEntry("novel")!!.meaning);assertTrue("late saved callback must not rebuild closed UI",f.nodes().isEmpty()||f.nodes().none{it.text=="已保存到本机用户词库"})}
 @Test fun U1UI19_manualAlreadyCommittedThenCloseNoLateUi(){oldCallback(false)}
 @Test fun U1UI20_floatingAlreadyCommittedThenCloseNoLateUi(){oldCallback(true)}
 private fun error(floating:Boolean)=F(floating).use{f->f.app.dictionary=Result.failure(IllegalStateException("synthetic dictionary load error"));if(floating)f.h!!.queryForTest("novel")else f.m!!.query("novel");f.pump();assertTrue(f.r.events().isEmpty());assertTrue(f.nodes().none{it.text=="新增这个词"});assertTrue(if(floating)f.text().contains("不可用")else f.m!!.messages.any{it.contains("不可用")})}
 @Test fun U1UI21_manualOriginalLoadFailureExplicitNotMissing(){error(false)}
 @Test fun U1UI22_floatingOriginalLoadFailureExplicitNotMissing(){error(true)}
 private fun lateLookupAfterUserSave(floating:Boolean)=F(floating).use{f->
  if(floating)f.h!!.queryForTest("alpha")else f.m!!.query("alpha");f.app.worker.all()
  f.r.saveUserEntry("alpha","","最新用户义",System.currentTimeMillis());f.pump();f.pump()
  assertTrue("old lookup result must not hide current user supplement",f.text().contains("最新用户义")||f.text().contains("失效"))
 }
 @Test fun U1UI23_manualOldLookupAfterSupplementCannotHideNew(){lateLookupAfterUserSave(false)}
 @Test fun U1UI24_floatingOldLookupAfterSupplementCannotHideNew(){lateLookupAfterUserSave(true)}
 private fun staleMissingAfterRestore(floating:Boolean)=F(floating).use{f->
  if(floating)f.h!!.queryForTest("novel")else f.m!!.query("novel");f.app.worker.all()
  val before=f.r.backupSnapshot();f.r.restoreBackup(before.data,before.revision);f.pump()
  val add=f.nodes().firstOrNull{it is Button && it.text=="新增这个词"&&it.isEnabled}
  if(add!=null){add.click();f.nodes().filterIsInstance<EditText>().last().setText("old world text");f.saveQueued();f.pump()}
  assertNull("pre-restore Missing callback cannot recapture fresh credential and write",f.r.userEntry("novel"))
 }
 @Test fun U1UI25_manualPreRestoreMissingCallbackCannotRebindCredential(){staleMissingAfterRestore(false)}
 @Test fun U1UI26_floatingPreRestoreMissingCallbackCannotRebindCredential(){staleMissingAfterRestore(true)}
 private fun delayedOldCard(floating:Boolean)=F(floating).use{f->
  f.query("alpha");val edit=f.nodes().first{it is Button&&it.text=="补充/编辑词义"}
  f.r.saveUserEntry("alpha","","latest",System.currentTimeMillis());edit.click();val fields=f.nodes().filterIsInstance<EditText>()
  if(fields.isNotEmpty()){fields.last().setText("old editor");f.saveQueued();f.pump()};assertEquals("latest",f.r.userEntry("alpha")!!.meaning)
 }
 @Test fun U1UI27_manualOldCardEditorCannotOverwriteNewSupplement(){delayedOldCard(false)}
 @Test fun U1UI28_floatingOldCardEditorCannotOverwriteNewSupplement(){delayedOldCard(true)}
 private fun blockedSave(floating:Boolean,restore:Boolean)=F(floating).use{f->
  f.editor("novel");f.saveQueued();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val error=java.util.concurrent.atomic.AtomicReference<Throwable?>()
  f.r.beforeWritable={f.r.beforeWritable=null;entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}
  val thread=Thread{try{f.app.worker.all()}catch(t:Throwable){error.set(t)}};thread.start()
  try{assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));if(restore){val b=f.r.backupSnapshot();f.r.restoreBackup(b.data,b.revision)}else f.stopOwner()}finally{release.countDown();thread.join(5000)}
  assertFalse(thread.isAlive);error.get()?.let{throw it};f.pump();assertNull("worker already started but not admitted must reject invalidated editor",f.r.userEntry("novel"))
 }
 @Test fun U1UI29_manualRunningSaveStoppedOnOtherThreadBeforeAdmission(){blockedSave(false,false)}
 @Test fun U1UI30_floatingRunningSaveStoppedOnOtherThreadBeforeAdmission(){blockedSave(true,false)}
 @Test fun U1UI31_manualRunningSaveRestoreOnOtherThreadBeforeAdmission(){blockedSave(false,true)}
 @Test fun U1UI32_floatingRunningSaveRestoreOnOtherThreadBeforeAdmission(){blockedSave(true,true)}
 private fun userOnlyTrailingStop(floating:Boolean)=F(floating).use{f->
  f.r.saveUserEntry("novel","","local meaning",1);f.query("novel.");assertEquals("user-only word with sentence stop must collect same normalized headword",listOf("novel"),f.r.personalMembers());assertTrue(f.text().contains("local meaning"));assertEquals(1,f.r.events().size)
 }
 @Test fun U1UI33_manualUserOnlySentenceStopMatchesSavedWord(){userOnlyTrailingStop(false)}
 @Test fun U1UI34_floatingUserOnlySentenceStopMatchesSavedWord(){userOnlyTrailingStop(true)}
}
