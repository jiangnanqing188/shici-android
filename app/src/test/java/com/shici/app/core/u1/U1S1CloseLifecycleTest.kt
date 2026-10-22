package com.shici.app.core.u1

import com.shici.app.core.*
import com.shici.app.core.u1.host.ManualHost
import com.shici.app.core.q1.host.*
import com.shici.app.core.b4.host.LearningRepository
import com.shici.app.core.b4.host.Context as DbContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** S1a only. UI callbacks exact; framework queues/views explicit adapters; isolated real SQLite engine. */
class U1S1CloseLifecycleTest {
 private class F(val floating:Boolean):AutoCloseable {
  val r=LearningRepository(DbContext(),"s1-close-isolated")
  val h=if(floating)CaptureHost()else null;val m=if(!floating)ManualHost()else null
  val app get()=h?.app?:m!!.app
  init{CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;app.repository.real=r;app.dictionary=Result.success(U1TestAssets.dictionary())}
  fun nodes()=h?.nodes()?:m!!.nodes()
  fun pump(){app.worker.all();h?.main?.all()?:m!!.main.all()}
  fun query(k:String){h?.queryForTest(k)?:m!!.query(k);pump();pump()}
  fun click(s:String){nodes().filterIsInstance<Button>().first{it.text==s}.click()}
  fun open(k:String="novel",body:String="first"){query(k);click(if(k=="alpha")"补充/编辑词义"else"新增这个词");fill(body)}
  fun fill(body:String){nodes().filterIsInstance<EditText>().last().setText(body)}
  fun save(){click("保存到本机")}
  fun closePage(){h?.close()?:m!!.close()}
  fun destroy(){h?.stop()?:m!!.destroy()}
  fun root()=nodes().first{it.attaches.isNotEmpty()}
  override fun close(){r.close()}
 }
 private fun blocked(floating:Boolean,admitted:Boolean)=F(floating).use{f->
  f.open();f.save();val entered=CountDownLatch(1);val release=CountDownLatch(1);val error=AtomicReference<Throwable?>()
  fun waitAtBarrier(){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
  if(admitted)f.r.database.beforeSql={sql->if(sql=="BEGIN"){f.r.database.beforeSql=null;waitAtBarrier()}}
  else f.r.beforeWritable={f.r.beforeWritable=null;waitAtBarrier()}
  val t=Thread{try{f.app.worker.all()}catch(e:Throwable){error.set(e)}};t.start()
  try{assertTrue("save worker must reach actual repository barrier",entered.await(5,TimeUnit.SECONDS));f.closePage()}
  finally{release.countDown();t.join(5000)}
  assertFalse(t.isAlive);error.get()?.let{throw it};f.pump()
  if(admitted){assertEquals("first",f.r.userEntry("novel")?.meaning);assertEquals(1,f.r.userEntryCount())}
  else{assertNull("explicit close before admission must block even running save",f.r.userEntry("novel"));assertEquals(0,f.r.userEntryCount())}
  assertTrue(f.r.events().isEmpty());if(f.floating)assertTrue(f.nodes().isEmpty())else assertFalse(f.m!!.activeDialog!!.isShowing)
 }
 @Test fun S1C01_manualRunningWorkerExplicitCloseBeforeAdmission(){blocked(false,false)}
 @Test fun S1C02_floatingRunningWorkerExplicitCloseBeforeAdmission(){blocked(true,false)}
 @Test fun S1C03_manualAlreadyAdmittedTransactionSurvivesClose(){blocked(false,true)}
 @Test fun S1C04_floatingAlreadyAdmittedTransactionSurvivesClose(){blocked(true,true)}
 private fun destroyed(floating:Boolean)=F(floating).use{f->f.open();f.save();f.destroy();f.pump();assertNull(f.r.userEntry("novel"));assertTrue(f.r.events().isEmpty())}
 @Test fun S1C05_manualDestroyBeforeQueuedSaveRejects(){destroyed(false)}
 @Test fun S1C06_floatingDestroyBeforeQueuedSaveRejects(){destroyed(true)}
 private fun reattach(v:View){v.isAttachedToWindow=true;v.attaches.forEach{it.onViewAttachedToWindow(v)};v.children.toList().forEach{reattach(it)}}
 private fun reattached(floating:Boolean)=F(floating).use{f->f.open();val root=f.root();root.detach();reattach(root);f.save();f.pump();assertNull("detached lease is permanently dead even with alive=true",f.r.userEntry("novel"));assertTrue(f.r.events().isEmpty())}
 @Test fun S1C07_manualDetachedEditorReattachCannotRevive(){reattached(false)}
 @Test fun S1C08_floatingDetachedEditorReattachCannotRevive(){reattached(true)}
 private fun oldDetach(floating:Boolean)=F(floating).use{f->
  f.open();val old=f.root();f.save();f.closePage();f.open("other","new owner");old.detach();reattach(old)
  old.all().filterIsInstance<Button>().first{it.text=="保存到本机"}.click();f.save();f.pump()
  assertNull(f.r.userEntry("novel"));assertEquals("new owner",f.r.userEntry("other")?.meaning);assertEquals(1,f.r.userEntryCount());assertTrue(f.r.events().isEmpty())
 }
 @Test fun S1C09_manualOldQueuedSaveAndDetachCannotCancelNewOwner(){oldDetach(false)}
 @Test fun S1C10_floatingOldQueuedSaveAndDetachCannotCancelNewOwner(){oldDetach(true)}
 private fun reedit(floating:Boolean)=F(floating).use{f->
  f.open();val old=f.root();f.save();f.pump();assertEquals("first",f.r.userEntry("novel")?.meaning)
  f.click("重新编辑词义");f.fill("second");old.detach();f.save();f.pump()
  assertEquals("second",f.r.userEntry("novel")?.meaning);assertEquals(1,f.r.userEntryCount());assertTrue(f.r.events().isEmpty())
 }
 @Test fun S1C11_manualSamePageReeditSurvivesOldViewDetach(){reedit(false)}
 @Test fun S1C12_floatingSamePageReeditSurvivesOldViewDetach(){reedit(true)}
 private fun delayedDismiss(oldKey:String)=F(false).use{f->
  f.open(oldKey);val old=f.m!!.activeDialog!!;old.dismissQueue=f.m.main
  // FIFO main queue: replacement-result callback, already-queued UI action, old dismiss listener.
  // No scheduler hold or reordering: production dismiss posts its listener at the existing tail.
  f.m.query("other");f.app.worker.all()
  f.m.main.post{f.click("新增这个词");f.fill("new owner");f.save()}
  f.m.main.all();assertNotSame(old,f.m.activeDialog);assertEquals(1,old.dismissDeliveries);f.pump()
  assertEquals("old dialog dismissal must not invalidate fresh editor belonging to replacement dialog","new owner",f.r.userEntry("other")?.meaning)
  assertNull(f.r.userEntry(oldKey));assertEquals(1,f.r.userEntryCount())
 }
 @Test fun S1C13_manualDelayedMissingDialogDismissCannotCloseNewOwner(){delayedDismiss("novel")}
 @Test fun S1C14_manualDelayedMatchedDialogDismissCannotCloseNewOwner(){delayedDismiss("alpha")}
 private fun cancelledThenNew(floating:Boolean)=F(floating).use{f->
  f.open();f.closePage();f.open("other","new owner");f.save();f.pump();assertNull(f.r.userEntry("novel"));assertEquals("new owner",f.r.userEntry("other")?.meaning);assertTrue(f.r.events().isEmpty())
 }
 @Test fun S1C15_manualNewDialogAfterCancelRemainsWritable(){cancelledThenNew(false)}
 @Test fun S1C16_floatingNewPanelAfterCancelRemainsWritable(){cancelledThenNew(true)}
}
