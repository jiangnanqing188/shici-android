package com.shici.app.core.u1

import com.shici.app.core.u1.host.ManualHost
import com.shici.app.core.q1.host.*
import com.shici.app.core.b4.host.LearningRepository
import com.shici.app.core.b4.host.Context as DbContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Owner-only additions. Real UI callbacks/repository, FIFO framework adapter, isolated SQLite :memory:. */
class U1S1OwnerIsolationTest {
 private class F:AutoCloseable {
  val r=LearningRepository(DbContext(),"s1-owner-isolated");val m=ManualHost()
  init{m.app.repository.real=r;m.app.dictionary=Result.success(U1TestAssets.dictionary())}
  fun pump(){m.app.worker.all();m.main.all()}
  fun query(k:String){m.query(k);pump();pump()}
  fun click(s:String){m.nodes().filterIsInstance<Button>().first{it.text==s}.click()}
  fun editor(k:String){query(k);click(if(k=="alpha")"补充/编辑词义"else"新增这个词");fill("old owner")}
  fun fill(s:String){m.nodes().filterIsInstance<EditText>().last().setText(s)}
  fun save(){click("保存到本机")}
  fun noSyntheticHistory(eventsBefore:Int){
   assertEquals("dictionary saves must not invent query history",eventsBefore,r.events().size)
   r.database.rawQuery("SELECT COUNT(*) FROM study_reviews",null).use{assertTrue(it.moveToFirst());assertEquals("dictionary save must not invent feedback",0,it.getInt(0))}
  }
  override fun close(){r.close()}
 }
 private fun reattach(v:View){v.isAttachedToWindow=true;v.attaches.forEach{it.onViewAttachedToWindow(v)};v.children.toList().forEach{reattach(it)}}
 private fun queuedOldAndNew(oldKey:String)=F().use{f->
  f.editor(oldKey);val old=f.m.activeDialog!!;val history=f.r.events().size;old.dismissQueue=f.m.main
  // Lookup result is queued first. Old save enters worker queue only after that lookup has returned.
  f.m.query("other");f.m.app.worker.all();f.save()
  f.m.main.post{f.click("新增这个词");f.fill("new owner");f.save()}
  f.m.main.all();assertNotSame(old,f.m.activeDialog);assertEquals(1,old.dismissDeliveries)
  reattach(old.root!!);old.root.all().filterIsInstance<Button>().first{it.text=="保存到本机"}.click()
  f.pump();assertNull("old queued lease must remain rejected after detach/dismiss/reattach",f.r.userEntry(oldKey))
  assertEquals("new owner",f.r.userEntry("other")?.meaning);assertEquals(1,f.r.userEntryCount());f.noSyntheticHistory(history)
 }
 @Test fun S1O01_missingOldQueuedLeaseRejectedWhileNewOwnerCommits(){queuedOldAndNew("novel")}
 @Test fun S1O02_matchedOldQueuedLeaseRejectedWhileNewOwnerCommits(){queuedOldAndNew("alpha")}
 @Test fun S1O03_oldDismissDuringNewWorkerBeforeAdmissionPreservesNewOwner()=F().use{f->
  f.editor("novel");val old=f.m.activeDialog!!;old.dismissQueue=f.m.main
  f.m.query("other");f.m.app.worker.all()
  f.m.main.post{f.click("新增这个词");f.fill("new owner");f.save()}
  f.m.main.one();f.m.main.one() // replacement, new editor action; old listener remains FIFO pending
  assertEquals(0,old.dismissDeliveries)
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val error=AtomicReference<Throwable?>()
  f.r.beforeWritable={f.r.beforeWritable=null;entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
  val worker=Thread{try{f.m.app.worker.all()}catch(e:Throwable){error.set(e)}};worker.start()
  try{assertTrue("new save must reach real pre-admission writableDatabase barrier",entered.await(5,TimeUnit.SECONDS));f.m.main.all();assertEquals(1,old.dismissDeliveries)}
  finally{release.countDown();worker.join(5000)}
  assertFalse(worker.isAlive);error.get()?.let{throw it};f.pump()
  assertNull(f.r.userEntry("novel"));assertEquals("new owner",f.r.userEntry("other")?.meaning);assertEquals(1,f.r.userEntryCount());f.noSyntheticHistory(0)
 }
 @Test fun S1O04_dismissedOwnerCannotOpenWritableEditorEvenIfOldViewReattached()=F().use{f->
  f.query("novel");val old=f.m.activeDialog!!;val oldAdd=old.root!!.all().filterIsInstance<Button>().first{it.text=="新增这个词"}
  f.query("other");assertEquals(1,old.dismissDeliveries);reattach(old.root);oldAdd.click()
  assertTrue(old.root.all().filterIsInstance<TextView>().any{it.text=="该窗口已关闭，请重新打开后再编辑"})
  assertFalse("closed owner must not register writable controls",old.root.all().filterIsInstance<Button>().any{it.text=="保存到本机"})
  f.click("新增这个词");f.fill("new owner");f.save();f.pump()
  assertNull(f.r.userEntry("novel"));assertEquals("new owner",f.r.userEntry("other")?.meaning);assertEquals(1,f.r.userEntryCount());f.noSyntheticHistory(0)
 }
 private fun activityEndsAll(destroy:Boolean)=F().use{f->
  f.editor("novel");val old=f.m.activeDialog!!;old.dismissQueue=f.m.main
  f.m.query("other");f.m.app.worker.all();f.save()
  f.m.main.post{f.click("新增这个词");f.fill("new owner");f.save()};f.m.main.all()
  val fresh=f.m.activeDialog!!;assertNotSame(old,fresh);assertEquals(1,old.dismissDeliveries)
  if(destroy)f.m.destroy()else f.m.stop()
  reattach(old.root!!);reattach(fresh.root!!)
  for(root in listOf(old.root,fresh.root))root!!.all().filterIsInstance<Button>().first{it.text=="保存到本机"}.click()
  f.pump();assertNull(f.r.userEntry("novel"));assertNull(f.r.userEntry("other"));assertEquals(0,f.r.userEntryCount());f.noSyntheticHistory(0)
 }
 @Test fun S1O05_activityStopStillInvalidatesNewAndOldLeases(){activityEndsAll(false)}
 @Test fun S1O06_activityDestroyStillInvalidatesNewAndOldLeases(){activityEndsAll(true)}
}
