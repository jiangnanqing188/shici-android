package com.shici.app.core.b4
import com.shici.app.core.*
import com.shici.app.core.b4.uihost.*
import com.shici.app.core.b4.host.LearningRepository
import org.junit.Assert.*
import org.junit.Test
/** Hooks are only in the explicit SQL adapter, all UI/Repository production slices remain verbatim.
 * Callback destroys owner precisely while worker's real read/admission is in flight, before BEGIN.
 * This is a deterministic interleaving, not an Android runtime lifecycle test. */
class B4G2LifecycleRegressionTest {
 private class Fixture:AutoCloseable {
  val repo=LearningRepository(com.shici.app.core.b4.host.Context(),"g2-lifecycle");val ctx=Context();val app=ShiciApplication(repo);val owner=ActivityOwner(ctx,app)
  val screen=owner.screenForTest();val root=LinearLayout(ctx)
  init {com.shici.app.core.b4.uihost.System.now=java.time.Instant.parse("2026-10-16T12:00:00Z").toEpochMilli();Handler.queue=Queue();repo.record(QueryEvent("q","alpha","alpha","alpha",1),DictionaryEntry("alpha","n","meaning","source","version"));app.studyBooks=Result.success(com.shici.app.core.u1.U1TestAssets.books());repo.updateStudySettings(StudyBook.ALL,20)}
  fun drain(){app.worker.all();Handler.queue.all()};fun render(){screen.render(root);drain()}
  fun click(s:String){root.all().first{it.textValue==s}.click()}
  fun reveal(){click("显示答案");drain();render()}
  fun destroyWhen(sqlPredicate:(String)->Boolean){repo.database.beforeSql={sql->if(sqlPredicate(sql)){repo.database.beforeSql=null;owner.onDestroy()}}}
  override fun close()=repo.close()
 }
 @Test fun destroyDuringPlanForPersonalMembersReadMustNotDealPersistentCard()=Fixture().use{f->
  f.screen.render(f.root);f.destroyWhen{it.startsWith("SELECT w.*")};f.drain()
  assertTrue(f.ctx.isDestroyed);assertEquals(0,f.ctx.deadWrites)
  assertNull("destroyed during planFor still persisted pending card",f.repo.backupSnapshot().data.session)
 }
 @Test fun destroyDuringSettingsReadMustNotPersistQueuedGoal()=Fixture().use{f->
  f.render();f.click("+5");f.repo.beforeWritable={f.repo.beforeWritable=null;f.owner.onDestroy()};f.drain()
  assertTrue(f.ctx.isDestroyed);assertEquals("worker started but not admitted settings write",20,f.repo.studySettings().dailyNewGoal)
 }
 @Test fun destroyAfterCanCommitWhileAdmissionReadMustNotCommitFeedback()=Fixture().use{f->
  f.render();f.reveal();f.click("认识");f.destroyWhen{it.startsWith("SELECT selected_book")};f.drain()
  assertTrue(f.ctx.isDestroyed);assertEquals("destroy before feedback BEGIN must reject",0,f.repo.backupSnapshot().data.reviews.size)
 }
 @Test fun destroyAfterRealTransactionAdmissionMayFinishAtomically()=Fixture().use{f->
  f.render();f.reveal();f.click("认识")
  f.repo.database.afterSql={sql->if(sql=="BEGIN"){f.repo.database.afterSql=null;f.owner.onDestroy()}}
  f.drain();assertTrue(f.ctx.isDestroyed);assertEquals(1,f.repo.backupSnapshot().data.reviews.size);assertEquals(0,f.ctx.deadWrites)
 }
}
