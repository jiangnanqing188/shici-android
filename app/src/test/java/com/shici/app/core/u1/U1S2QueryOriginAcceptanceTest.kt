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

/** S2 only. Exact query/cards/listeners/editors -> FIFO -> verbatim repository -> isolated SQLite.
 * Fresh floating capture identity uses the actual beginLiveSelection body, never writes an epoch. */
class U1S2QueryOriginAcceptanceTest {
 private class F(val floating:Boolean):AutoCloseable {
  val r=LearningRepository(DbContext(),"s2-origin-isolated")
  val h=if(floating)CaptureHost()else null
  val m=if(!floating)ManualHost()else null
  val app get()=h?.app?:m!!.app
  init{CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;app.repository.real=r;app.dictionary=Result.success(U1TestAssets.dictionary())}
  fun nodes()=h?.nodes()?:m!!.nodes()
  fun main(){h?.main?.all()?:m!!.main.all()}
  fun pump(){app.worker.all();main()}
  fun query(k:String){h?.queryForTest(k)?:m!!.query(k)}
  fun show(k:String){query(k);pump();pump()}
  fun fresh(k:String){if(h!=null){h.start();assertEquals(r.deletions.capture(),h.deletionCredential);h.close()};show(k)}
  fun button(label:String)=nodes().filterIsInstance<Button>().first{it.text==label}
  fun edit(body:String){val b=nodes().filterIsInstance<Button>().first{it.text=="新增这个词"||it.text=="补充/编辑词义"};b.click();val fields=nodes().filterIsInstance<EditText>();assertEquals(2,fields.size);fields.last().setText(body)}
  fun save(){button("保存到本机").click();pump()}
  fun noWritableEditor(){assertFalse("old source must not create an editor",nodes().filterIsInstance<Button>().any{it.text=="保存到本机"&&it.isEnabled})}
  override fun close(){r.close()}
 }
 private fun replacement():LearningBackupData=LearningRepository(DbContext(),"s2-donor").use{r->
  val now=1792540800000L;val e=DictionaryEntry("sentinel","n.","restored source","raw-original","v0")
  assertTrue(r.record(QueryEvent("restored-query","sentinel","sentinel","sentinel",now),e))
  assertTrue(r.updateWord("sentinel",true,"restored private note"))
  val plan=StudyMembership(StudyBook.ALL,listOf("sentinel")){e}
  val card=r.studyView(plan,now,ZoneId.of("UTC")).card!!
  assertTrue(r.revealStudyCard(card.token));assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(card.token,FeedbackKind.KNOW,now,ZoneId.of("UTC")))
  assertTrue(r.saveUserEntry("privateword","n.","restored private meaning",now).saved)
  r.backupSnapshot().data.also{assertEquals(1,it.reviews.size);assertEquals(1,it.states.size)}
 }
 private fun restore(f:F){assertEquals(RestoreOutcome.RESTORED,f.r.restoreBackup(replacement(),f.r.changes.version))}
 private fun lateMissing(floating:Boolean)=F(floating).use{f->
  f.query("novel");f.app.worker.all() // result read in old world; delivery still queued
  restore(f);val expected=f.r.backupSnapshot();f.pump();f.pump()
  assertFalse(f.nodes().filterIsInstance<Button>().any{it.text=="新增这个词"&&it.isEnabled});f.noWritableEditor()
  assertEquals("whole nonempty restored world/revision unchanged",expected,f.r.backupSnapshot())
  f.fresh("novel");f.edit("fresh explicit missing");val before=f.r.backupSnapshot();f.save()
  val after=f.r.backupSnapshot();assertEquals("fresh explicit missing",f.r.userEntry("novel")!!.meaning)
  assertEquals(before.data.copy(userEntries=after.data.userEntries),after.data);assertEquals(before.revision+1,after.revision)
  f.button("查询并收录").click();f.pump();f.pump();assertEquals(before.data.events.size+1,f.r.events().size)
  assertEquals(1,f.r.events().count{it.entryKey=="novel"});assertTrue(f.r.personalMembers().contains("novel"))
 }
 @Test fun S2B01_manualLateMissingPreservesNonemptyRestoreAndFreshExplicitQueryWorks(){lateMissing(false)}
 @Test fun S2B02_floatingLateMissingPreservesNonemptyRestoreAndFreshExplicitQueryWorks(){lateMissing(true)}
 private fun shownMissing(floating:Boolean)=F(floating).use{f->
  f.show("novel");val add=f.button("新增这个词");val collect=f.button("查询并收录")
  restore(f);val expected=f.r.backupSnapshot()
  // The invalidation listener is queued, so the retained controls still execute their real checks.
  assertTrue(add.isEnabled);add.click();collect.click();f.pump();f.pump();f.noWritableEditor()
  assertFalse(add.isEnabled);assertFalse(collect.isEnabled)
  assertEquals("retained Missing controls must not change any restored data/revision",expected,f.r.backupSnapshot())
  assertTrue(f.nodes().any{it.text.contains("失效")})
 }
 @Test fun S2B03_manualDisplayedMissingRetainedControlsRejectBeforeListenerDrains(){shownMissing(false)}
 @Test fun S2B04_floatingDisplayedMissingRetainedControlsRejectBeforeListenerDrains(){shownMissing(true)}
 private fun lateMatch(floating:Boolean)=F(floating).use{f->
  f.query("alpha");f.app.worker.all();restore(f);val expected=f.r.backupSnapshot();f.pump();f.pump()
  assertFalse(f.nodes().filterIsInstance<Button>().any{it.text=="补充/编辑词义"&&it.isEnabled});f.noWritableEditor()
  assertEquals("old Match delivery must not record/repaint restored world",expected,f.r.backupSnapshot())
  f.fresh("alpha");assertEquals(1,f.r.events().count{it.entryKey=="alpha"});f.edit("fresh matched supplement")
  val before=f.r.backupSnapshot();f.save();val after=f.r.backupSnapshot()
  assertEquals("fresh matched supplement",f.r.userEntry("alpha")!!.meaning);assertEquals(before.data.copy(userEntries=after.data.userEntries),after.data)
  assertEquals(1,f.r.events().count{it.entryKey=="alpha"})
 }
 @Test fun S2B05_manualLateMatchCannotCollectAfterRestoreButFreshMatchCollectsOnce(){lateMatch(false)}
 @Test fun S2B06_floatingLateMatchCannotCollectAfterRestoreButFreshMatchCollectsOnce(){lateMatch(true)}
 private fun shownMatch(floating:Boolean)=F(floating).use{f->
  f.show("alpha");val oldEdit=f.button("补充/编辑词义");restore(f);val expected=f.r.backupSnapshot()
  oldEdit.click();f.pump();f.pump();f.noWritableEditor();assertEquals(expected,f.r.backupSnapshot())
  assertNull(f.r.userEntry("alpha"))
 }
 @Test fun S2B07_manualDisplayedMatchCannotOpenEditorWithFreshRestoreCredential(){shownMatch(false)}
 @Test fun S2B08_floatingDisplayedMatchCannotOpenEditorWithFreshRestoreCredential(){shownMatch(true)}
 private fun deleteSame(floating:Boolean)=F(floating).use{f->
  f.show("alpha");val oldEdit=f.button("补充/编辑词义")
  assertEquals(DeleteOutcome.DELETED,f.r.deleteWord("alpha",f.r.deletions.capture()));val expected=f.r.backupSnapshot()
  oldEdit.click();f.pump();f.pump();f.noWritableEditor();assertEquals(expected,f.r.backupSnapshot())
  f.fresh("alpha");f.edit("fresh after same-key deletion");f.save()
  assertEquals("fresh after same-key deletion",f.r.userEntry("alpha")!!.meaning);assertEquals(1,f.r.events().count{it.entryKey=="alpha"})
 }
 @Test fun S2B09_manualSameKeyDeletionRetiresOldMatchNotFreshQuery(){deleteSame(false)}
 @Test fun S2B10_floatingSameKeyDeletionRetiresOldMatchNotFreshQuery(){deleteSame(true)}
 private fun unrelated(floating:Boolean)=F(floating).use{f->
  val e=DictionaryEntry("sentinel","n.","untouched","raw-original","v0")
  assertTrue(f.r.record(QueryEvent("to-delete","sentinel","sentinel","sentinel",1),e))
  f.show("novel");assertEquals(DeleteOutcome.DELETED,f.r.deleteWord("sentinel",f.r.deletions.capture()));f.main()
  assertTrue(f.button("新增这个词").isEnabled);f.edit("not globally stale");val before=f.r.backupSnapshot();f.save();val after=f.r.backupSnapshot()
  assertEquals("not globally stale",f.r.userEntry("novel")!!.meaning);assertEquals(before.data.copy(userEntries=after.data.userEntries),after.data)
 }
 @Test fun S2B11_manualUnrelatedDeletionDoesNotGloballyRejectMissingSource(){unrelated(false)}
 @Test fun S2B12_floatingUnrelatedDeletionDoesNotGloballyRejectMissingSource(){unrelated(true)}
 private fun listenerOnly(floating:Boolean)=F(floating).use{f->
  f.show("novel");f.edit("old editor");val add=f.button("新增这个词");val collect=f.button("查询并收录");val save=f.button("保存到本机")
  restore(f);val expected=f.r.backupSnapshot();f.main()
  assertFalse("production data listener disables old add",add.isEnabled);assertFalse(collect.isEnabled)
  save.click();f.pump();assertEquals("listener closes existing editor lease, not just outer buttons",expected,f.r.backupSnapshot())
  f.fresh("other");f.edit("new owner after invalidation");f.save();assertEquals("new owner after invalidation",f.r.userEntry("other")!!.meaning);assertNull(f.r.userEntry("novel"))
 }
 @Test fun S2B13_manualProductionMissingListenerDisablesButtonsClosesLeaseNotFreshOwner(){listenerOnly(false)}
 @Test fun S2B14_floatingProductionMissingListenerDisablesButtonsClosesLeaseNotFreshOwner(){listenerOnly(true)}
}
