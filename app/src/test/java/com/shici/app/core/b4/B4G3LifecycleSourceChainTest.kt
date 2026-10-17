package com.shici.app.core.b4
import com.shici.app.core.*
import com.shici.app.core.b4.uihost.*
import com.shici.app.core.b4.host.LearningRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
/** Actual MainActivity lazy/property/onDestroy + whole StudyScreen + whole Repository slices.
 * Worker and destroy run on distinct JVM threads; queues/widgets/Android framework remain explicit fakes. */
class B4G3LifecycleSourceChainTest {
 private class Fixture:AutoCloseable {
  val repo=LearningRepository(com.shici.app.core.b4.host.Context(),"g3-lifecycle");val ctx=Context();val app=ShiciApplication(repo);var renders=0;val messages=mutableListOf<String>()
  val owner=ActivityOwner(ctx,app,{renders++},{messages+=it});val screen get()=owner.screenForTest();val root=LinearLayout(ctx)
  init {com.shici.app.core.b4.uihost.System.now=java.time.Instant.parse("2026-10-16T12:00:00Z").toEpochMilli();Handler.queue=Queue();repo.record(QueryEvent("q","alpha","alpha","alpha",1),DictionaryEntry("alpha","n","meaning","source","v0"));repo.updateStudySettings(StudyBook.PERSONAL,20)}
  fun drain(){app.worker.all();Handler.queue.all()};fun render(){screen.render(root);drain()};fun click(label:String){root.all().first{it.textValue==label}.click()}
  fun revealed(){render();click("显示答案");drain();render()}
  fun destroyBlockedWorker(predicate:(String)->Boolean,after:Boolean=false){
   val entered=CountDownLatch(1);val release=CountDownLatch(1);val error=AtomicReference<Throwable?>()
   val hook:(String)->Unit={sql->if(predicate(sql)){repo.database.beforeSql=null;repo.database.afterSql=null;entered.countDown();check(release.await(5,TimeUnit.SECONDS)){"test timeout releasing blocked SQL"}}}
   if(after)repo.database.afterSql=hook else repo.database.beforeSql=hook
   val worker=Thread{try{app.worker.all()}catch(t:Throwable){error.set(t)}};worker.start()
   try{assertTrue("real worker did not reach selected production SQL",entered.await(5,TimeUnit.SECONDS));owner.onDestroy();assertTrue(ctx.isDestroyed);assertTrue("destroy must not wait on long read",worker.isAlive)}finally{release.countDown();worker.join(5000)}
   assertFalse("worker did not finish",worker.isAlive);error.get()?.let{throw it};Handler.queue.all();assertEquals(0,ctx.deadWrites);assertEquals(0,renders);assertTrue(messages.isEmpty())
  }
  override fun close()=repo.close()
 }
 @Test fun destroyingUnopenedActivityDoesNotInstantiateLazyStudyScreen()=Fixture().use{f->assertFalse(f.owner.screenInitializedForTest());f.owner.onDestroy();assertTrue(f.ctx.isDestroyed);assertFalse(f.owner.screenInitializedForTest());assertTrue(f.app.worker.tasks.isEmpty());assertNull(f.repo.backupSnapshot().data.session)}
 @Test fun blockedPersonalReadDestroyedOnOtherThreadCannotDealPending()=Fixture().use{f->f.screen.render(f.root);f.destroyBlockedWorker({it.startsWith("SELECT w.*")});assertNull(f.repo.backupSnapshot().data.session)}
 @Test fun blockedSettingsReadDestroyedOnOtherThreadCannotChangeGoal()=Fixture().use{f->f.render();f.click("+5");val before=f.repo.backupSnapshot();f.destroyBlockedWorker({it.startsWith("SELECT selected_book")});assertEquals(before,f.repo.backupSnapshot());assertEquals(20,f.repo.studySettings().dailyNewGoal)}
 @Test fun blockedBookSwitchReadDestroyedOnOtherThreadCannotChangeSelection()=Fixture().use{f->f.render();f.click("六级");val before=f.repo.backupSnapshot();f.destroyBlockedWorker({it.startsWith("SELECT selected_book")});assertEquals(before,f.repo.backupSnapshot());assertEquals(StudyBook.PERSONAL,f.repo.studySettings().selectedBook)}
 @Test fun blockedRevealReadDestroyedOnOtherThreadCannotWriteRevealPhase()=Fixture().use{f->f.render();f.click("显示答案");val before=f.repo.backupSnapshot();f.destroyBlockedWorker({it.contains("FROM study_session s LEFT JOIN")});assertEquals(before,f.repo.backupSnapshot());assertFalse(f.repo.backupSnapshot().data.session!!.answerRevealed)}
 @Test fun blockedFeedbackAdmissionReadDestroyedOnOtherThreadCannotCommit()=Fixture().use{f->f.revealed();f.renders=0;f.click("认识");val before=f.repo.backupSnapshot();f.destroyBlockedWorker({it.startsWith("SELECT selected_book")});assertEquals(before,f.repo.backupSnapshot());assertTrue(f.repo.backupSnapshot().data.reviews.isEmpty())}
 @Test fun admittedFeedbackMayFinishAtomicallyAfterCrossThreadDestroyWithoutUiWrites()=Fixture().use{f->f.revealed();f.renders=0;f.click("认识");val before=f.repo.changes.version;f.destroyBlockedWorker({it=="BEGIN"},true);val d=f.repo.backupSnapshot().data;assertEquals(1,d.reviews.size);assertEquals(1,d.states.size);assertNull(d.session);assertEquals(before+1,f.repo.changes.version);assertEquals(d.reviews.single().seq,d.states.single().lastReviewSeq)}
}
