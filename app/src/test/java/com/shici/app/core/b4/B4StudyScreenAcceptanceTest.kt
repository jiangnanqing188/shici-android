package com.shici.app.core.b4
import com.shici.app.core.*
import com.shici.app.core.b4.uihost.*
import com.shici.app.core.b4.host.LearningRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class B4StudyScreenAcceptanceTest {
 private class Fixture:AutoCloseable {
  val repo=LearningRepository(com.shici.app.core.b4.host.Context(),"synthetic");val ctx=Context();val app=ShiciApplication(repo)
  var renders=0;val toasts=mutableListOf<String>();val owner=ActivityOwner(ctx,app,{renders++},{toasts+=it})
  val screen=owner.screenForTest();val root=LinearLayout(ctx)
  init {com.shici.app.core.b4.uihost.System.now=java.time.Instant.parse("2026-10-16T12:00:00Z").toEpochMilli();Handler.queue=Queue();repo.record(QueryEvent("q","alpha","alpha","alpha",1),DictionaryEntry("alpha","n","synthetic meaning","distinct-source","v-old"));repo.updateWord("alpha",true,"secret personal note");app.studyBooks=Result.success(com.shici.app.core.u1.U1TestAssets.books());repo.updateStudySettings(StudyBook.ALL,20)}
  fun render(){screen.render(root);drain()};fun drain(){app.worker.all();Handler.queue.all()}
  fun button(label:String)=root.all().first{it.textValue==label}
  fun texts()=root.all().map{it.textValue}.joinToString("\n")
  fun reveal(){button("显示答案").click();drain();render()}
  override fun close()=repo.close()
 }
 @Test fun actualUiHasThreeBooksAndNoFourth()=Fixture().use{f->f.render();assertTrue(f.texts().contains("统一背诵"));assertFalse(f.root.all().any{it.textValue in listOf("考研","六级","个人生词")});assertFalse(f.texts().contains("四级"))}
 @Test fun unrevealedDoesNotExposeFeedbackButtons()=Fixture().use{f->f.render();assertFalse(f.root.all().any{it.textValue=="认识"});assertFalse(f.texts().contains("synthetic meaning"))}
 @Test fun revealShowsActualMeaningAndFeedback()=Fixture().use{f->f.render();f.reveal();assertTrue(f.texts().contains("synthetic meaning"));assertNotNull(f.button("认识"));assertNotNull(f.button("模糊"));assertNotNull(f.button("忘记"))}
 @Test fun answerShowsSnapshotProvenanceAndPersonalNote()=Fixture().use{f->f.render();f.reveal();assertTrue("source/version omitted",f.texts().contains("distinct-source") && f.texts().contains("v-old"));}
 @Test fun uiDoubleClickCommitsOnce()=Fixture().use{f->f.render();f.reveal();val b=f.button("认识");b.click();b.click();f.drain();assertEquals(1,f.repo.backupSnapshot().data.reviews.size);assertEquals(1,f.repo.events().size)}
 @Test fun settingsUiWritesRealPersistentGoal()=Fixture().use{f->f.render();f.button("+5").click();f.drain();assertEquals(25,f.repo.studySettings().dailyNewGoal)}
 @Test fun deadActivityQueuedFeedbackMustNotCommit()=Fixture().use{f->f.render();f.reveal();f.button("认识").click();f.owner.onDestroy();f.drain();assertEquals("old destroyed UI queued feedback",0,f.repo.backupSnapshot().data.reviews.size)}
 @Test fun deadActivityQueuedSettingsMustNotWrite()=Fixture().use{f->f.render();f.button("+5").click();f.owner.onDestroy();f.drain();assertEquals(20,f.repo.studySettings().dailyNewGoal)}
 @Test fun deadActivityQueuedLoadMustNotCreatePending()=Fixture().use{f->f.screen.render(f.root);f.owner.onDestroy();f.drain();assertNull("old worker creates persistent session after destruction",f.repo.backupSnapshot().data.session)}
 @Test fun deadActivityPostedLoadDoesNotRender()=Fixture().use{f->f.screen.render(f.root);f.app.worker.all();f.owner.onDestroy();Handler.queue.all();assertEquals(0,f.ctx.deadWrites)}
 @Test fun staleOldButtonAfterRestoreDoesNotRebindCredential()=Fixture().use{f->f.render();f.reveal();val b=f.button("认识");val d=f.repo.backupSnapshot();f.repo.restoreBackup(d.data,d.revision);b.click();f.drain();assertEquals(0,f.repo.backupSnapshot().data.reviews.size);assertTrue(f.toasts.any{it.contains("失效")})}
 @Test fun restorePendingCanResumeThroughNewScreen()=Fixture().use{f->f.render();f.reveal();val old=f.repo.backupSnapshot();val token=old.data.session!!.token;f.repo.restoreBackup(old.data,old.revision);val second=StudyScreen(f.app,f.ctx,{},{});val root=LinearLayout(f.ctx);second.render(root);f.drain();root.all().first{it.textValue=="认识"}.click();f.drain();assertEquals(token,f.repo.backupSnapshot().data.reviews.single().token)}
 @Test fun indexLoadFailureDisplayedNotFullDictionaryFallback()=Fixture().use{f->f.app.studyBooks=Result.failure(IllegalStateException("test index unavailable"));f.render();assertTrue(f.texts().contains("背词暂时不可用"));assertNull(f.repo.backupSnapshot().data.session)}
 @Test fun actualVisibleOldCardAcrossMidnightRequiresRefresh()=Fixture().use{f->f.render();f.reveal();val b=f.button("认识");com.shici.app.core.b4.uihost.System.now+=StudyRules.DAY_MILLIS;b.click();f.drain();assertEquals("visible old button must not commit next day's card",0,f.repo.backupSnapshot().data.reviews.size)}
 @Test fun destroyedOldScreenCannotAnswerRotatedScreenPending()=Fixture().use{f->f.render();f.reveal();val b=f.button("认识");f.owner.onDestroy();val ctx2=Context();val screen2=StudyScreen(f.app,ctx2,{},{});val root2=LinearLayout(ctx2);screen2.render(root2);f.drain();val token=f.repo.backupSnapshot().data.session!!.token;b.click();f.drain();assertEquals(0,f.repo.backupSnapshot().data.reviews.size);assertEquals(token,f.repo.backupSnapshot().data.session!!.token)}
 @Test fun answerShowsPersonalNote()=Fixture().use{f->f.render();f.reveal();assertTrue("note omitted",f.texts().contains("secret personal note"))}
}
