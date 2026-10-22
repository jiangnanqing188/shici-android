package com.shici.app.core.u1
import com.shici.app.core.*
import com.shici.app.core.b4.uihost.*
import com.shici.app.core.b4.host.LearningRepository
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class U1UnifiedStudyUiAcceptanceTest {
 private class F:AutoCloseable {
  val r=LearningRepository(com.shici.app.core.b4.host.Context(),"u1-study-ui");val ctx=Context();val app=ShiciApplication(r);val root=LinearLayout(ctx);val messages=mutableListOf<String>();val screen=StudyScreen(app,ctx,{}, {messages+=it})
  init{com.shici.app.core.b4.uihost.System.now=Instant.parse("2026-10-21T12:00:00Z").toEpochMilli();Handler.queue=Queue();app.dictionary=Result.success(U1TestAssets.dictionary());app.studyBooks=Result.success(U1TestAssets.books())}
  fun drain(){app.worker.all();Handler.queue.all()};fun render(){screen.render(root);drain()};fun click(s:String){root.all().first{it.textValue==s}.click();drain()};fun texts()=root.all().joinToString("\n"){it.textValue}
  fun collect(k:String){val e=app.resolveEntry(k)?:DictionaryEntry(k,"","historical $k","old","v0");r.record(QueryEvent("q-$k",k,k,k,com.shici.app.core.b4.uihost.System.now),e)}
  override fun close()=r.close()
 }
 @Test fun U1S01_actualScreenPersonalNewestBeforeBooksAndOneScope()=F().use{f->f.collect("alpha");f.r.saveUserEntry("novel","","only-user",1);assertTrue(f.r.personalMembers().contains("alpha"));f.render();assertEquals("alpha",f.r.backupSnapshot().data.session!!.entryKey);assertEquals("all",f.r.backupSnapshot().data.session!!.book);assertTrue(f.texts().contains("统一背诵"));assertFalse(f.root.all().any{it.textValue in listOf("考研","六级","个人生词")})}
 @Test fun U1S02_actualRevealUsesOriginalAndLatestSupplementWithoutExtraQuery()=F().use{f->f.collect("alpha");f.r.saveUserEntry("alpha","v.","最新自定义义",2);f.render();assertFalse(f.texts().contains("最新自定义义"));f.click("显示答案");f.render();assertTrue(f.texts().contains("原始义"));assertTrue(f.texts().contains("【用户补充】"));assertTrue(f.texts().contains("最新自定义义"));f.click("认识");assertEquals(1,f.r.events().size);assertEquals(1,f.r.backupSnapshot().data.reviews.size)}
 @Test fun U1S03_oldVisibleFeedbackAfterSupplementIsRejectedThenFreshCard()=F().use{f->f.collect("alpha");f.render();f.click("显示答案");f.render();val old=f.root.all().first{it.textValue=="认识"};val token=f.r.backupSnapshot().data.session!!.token;f.r.saveUserEntry("alpha","","newest",2);old.click();f.drain();assertTrue(f.r.backupSnapshot().data.reviews.isEmpty());f.render();assertNotEquals(token,f.r.backupSnapshot().data.session!!.token);f.click("显示答案");f.render();assertTrue(f.texts().contains("newest"))}
 @Test fun U1S04_orphanDueRenderedBeforePersonalNewWithHistoricalFallback()=F().use{f->val t=com.shici.app.core.b4.uihost.System.now;val p=com.shici.app.core.b4.host.StudyMembership(StudyBook.ALL,listOf("historical")){DictionaryEntry(it,"","old history","historical source","v0")};val c=f.r.studyView(p,t,ZoneId.systemDefault()).card!!;f.r.revealStudyCard(c.token);f.r.commitStudyFeedback(c.token,FeedbackKind.KNOW,t,ZoneId.systemDefault());f.collect("alpha");com.shici.app.core.b4.uihost.System.now+=StudyRules.DAY_MILLIS;f.render();assertEquals("historical",f.r.backupSnapshot().data.session!!.entryKey);f.click("显示答案");f.render();assertTrue(f.texts().contains("old history"));assertTrue(f.texts().contains("historical source"))}
}
