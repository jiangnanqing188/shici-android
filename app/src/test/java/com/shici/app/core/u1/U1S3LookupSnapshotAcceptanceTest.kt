package com.shici.app.core.u1

import com.shici.app.core.*
import com.shici.app.core.u1.host.ManualHost
import com.shici.app.core.q1.host.*
import com.shici.app.core.b4.host.LearningRepository
import com.shici.app.core.b4.host.SQLiteDatabase
import com.shici.app.core.b4.host.Context as DbContext
import com.shici.app.core.b4.host.StudyMembership
import com.shici.app.core.b4.host.StudyCommitOutcome
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** S3 only: exact query/UI production slices, original FIFO, real repository/SQLite.
 * Existing SQLite adapter hooks stop after real SELECT rows are materialized, before returning
 * them to the production resolver. No fabricated LookupRead/result/revision and no SQL on main. */
class U1S3LookupSnapshotAcceptanceTest {
 private class F(val floating:Boolean):AutoCloseable {
  val db=SQLiteDatabase();val r=LearningRepository(DbContext(db),"s3-isolated")
  val h=if(floating)CaptureHost()else null;val m=if(!floating)ManualHost()else null
  val app get()=h?.app?:m!!.app
  val now=1792627200000L
  init {
   CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE
   app.repository.real=r;app.dictionary=Result.success(U1TestAssets.dictionary())
   val e=DictionaryEntry("sentinel","n.","historical source","raw-original","v0")
   assertTrue(r.record(QueryEvent("history","sentinel","sentinel","sentinel",now),e))
   assertTrue(r.updateWord("sentinel",true,"keep private note"))
   val plan=StudyMembership(StudyBook.ALL,listOf("sentinel")){e}
   val c=r.studyView(plan,now,ZoneId.of("UTC")).card!!
   assertTrue(r.revealStudyCard(c.token));assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(c.token,FeedbackKind.KNOW,now,ZoneId.of("UTC")))
   assertTrue(r.saveUserEntry("alpha","n.","old-supplement",now+10).saved)
  }
  fun nodes()=h?.nodes()?:m!!.nodes()
  fun text()=nodes().joinToString("\n"){it.text}
  fun query(k:String="alpha"){h?.queryForTest(k)?:m!!.query(k)}
  fun mainNoSql(){db.beforeSql={error("S3 prohibits main delivery DB IO: $it")};try{h?.main?.all()?:m!!.main.all()}finally{db.beforeSql=null}}
  fun drain(){repeat(3){app.worker.all();mainNoSql()}}
  fun commit(k:String="alpha",body:String="latest-user-meaning"){assertTrue(r.saveUserEntry(k,"n.",body,now+20).saved)}
  fun expires(expected:com.shici.app.core.b4.host.LearningBackupSnapshot){
   mainNoSql();drain()
   assertTrue("explicit visible invalidation, never toast alone",nodes().filterIsInstance<TextView>().any{it.text.contains("失效")})
   assertFalse("stale original/supplement must not be drawn",text().contains("old-supplement"))
   assertFalse("no writable stale card",nodes().filterIsInstance<Button>().any{it.isEnabled&&it.text in listOf("补充/编辑词义","新增这个词","查询并收录","保存到本机")})
   assertFalse(nodes().any{it is EditText})
   assertEquals("delivery/draining creates no event, review, write or revision",expected,r.backupSnapshot())
  }
  fun fresh(k:String="alpha") {
   val before=r.backupSnapshot();val n=r.events().size
   query(k);drain()
   assertTrue(text().contains("latest-user-meaning"));assertFalse(text().contains("old-supplement"));assertFalse(text().contains("查询已失效"))
   assertEquals(n+1,r.events().size);assertEquals(1,r.events().count{it.entryKey==k})
   val after=r.backupSnapshot();assertEquals(before.data.userEntries,after.data.userEntries)
   assertEquals(before.data.reviews,after.data.reviews);assertEquals(before.data.states,after.data.states)
   assertEquals(before.data.words.first{it.entryKey=="sentinel"},after.data.words.first{it.entryKey=="sentinel"})
   drain();assertEquals("one explicit query must collect once",after,r.backupSnapshot())
  }
  override fun close(){db.beforeSql=null;db.afterSql=null;r.close()}
 }
 private val userRead="SELECT entry_key, pos, meaning, updated_at FROM user_entries WHERE entry_key=?"
 private fun beforeWorker(floating:Boolean)=F(floating).use{f->
  f.query();f.commit();val before=f.r.backupSnapshot();f.drain()
  assertTrue("update committed before before-revision must resolve latest",f.text().contains("latest-user-meaning"))
  assertFalse(f.text().contains("查询已失效"));assertFalse(f.text().contains("old-supplement"))
  val after=f.r.backupSnapshot();assertEquals(before.data.userEntries,after.data.userEntries);assertEquals(before.data.reviews,after.data.reviews)
  assertEquals(1,f.r.events().count{it.entryKey=="alpha"});f.drain();assertEquals(after,f.r.backupSnapshot())
 }
 @Test fun S3B01_manualCommitBeforeWorkerReadResolvesLatestExactlyOnce(){beforeWorker(false)}
 @Test fun S3B02_floatingCommitBeforeWorkerReadResolvesLatestExactlyOnce(){beforeWorker(true)}
 private fun during(floating:Boolean,readNumber:Int,key:String="alpha")=F(floating).use{f->
  var reads=0;var fired=false
  f.db.afterSql={sql->if(sql==userRead&&++reads==readNumber){f.db.afterSql=null;f.commit(key);fired=true}}
  f.query(key);f.app.worker.all();f.db.afterSql=null
  assertTrue("real lookup must reach chosen SELECT boundary",fired)
  val expected=f.r.backupSnapshot();assertEquals("latest-user-meaning",f.r.userEntry(key)!!.meaning)
  assertEquals(0,f.r.events().count{it.entryKey==key});f.expires(expected);f.fresh(key)
 }
 @Test fun S3B03_manualOldResultNewUserRowCannotRelabelWithNewRevision(){during(false,1)}
 @Test fun S3B04_floatingOldResultNewUserRowCannotRelabelWithNewRevision(){during(true,1)}
 @Test fun S3B05_manualCommitAfterSecondUserReadCannotLaunderSnapshot(){during(false,2)}
 @Test fun S3B06_floatingCommitAfterSecondUserReadCannotLaunderSnapshot(){during(true,2)}
 private fun delivery(floating:Boolean)=F(floating).use{f->
  f.query();f.drain();assertTrue(f.text().contains("old-supplement"))
  assertTrue(f.nodes().filterIsInstance<Button>().any{it.text=="补充/编辑词义"&&it.isEnabled})
  f.query();f.app.worker.all();f.commit();val expected=f.r.backupSnapshot()
  f.expires(expected);assertEquals(1,f.r.events().count{it.entryKey=="alpha"})
  // The explicit new query adds precisely one event even with an existing historic alpha record.
  f.query();f.drain();assertTrue(f.text().contains("latest-user-meaning"));assertEquals(2,f.r.events().count{it.entryKey=="alpha"})
  val after=f.r.backupSnapshot();assertEquals(expected.data.userEntries,after.data.userEntries);assertEquals(expected.data.reviews,after.data.reviews);assertEquals(expected.data.states,after.data.states)
  f.drain();assertEquals(after,f.r.backupSnapshot())
 }
 @Test fun S3B07_manualLateDeliveryReplacesAlreadyVisibleWritableOldCard(){delivery(false)}
 @Test fun S3B08_floatingLateDeliveryReplacesAlreadyVisibleWritableOldCard(){delivery(true)}
 @Test fun S3B09_manualMissingReadThenCreationCannotOfferOldAddCard(){during(false,1,"novel")}
 @Test fun S3B10_floatingMissingReadThenCreationCannotOfferOldAddCard(){during(true,1,"novel")}
 private fun rollback(floating:Boolean)=F(floating).use{f->
  f.query();f.app.worker.all();val before=f.r.backupSnapshot()
  f.db.failAt="INSERT OR REPLACE INTO user_entries"
  val out=f.r.saveUserEntry("alpha","n.","failed-uncommitted-meaning",f.now+30)
  assertFalse(out.saved);assertEquals(before,f.r.backupSnapshot())
  f.drain();assertTrue("rolled back write must not counterfeit commit/revision",f.text().contains("old-supplement"));assertFalse(f.text().contains("失效"))
  val after=f.r.backupSnapshot();assertEquals(before.data.userEntries,after.data.userEntries);assertEquals(before.data.reviews,after.data.reviews);assertEquals(1,f.r.events().count{it.entryKey=="alpha"})
  f.drain();assertEquals(after,f.r.backupSnapshot())
 }
 @Test fun S3B11_manualRolledBackSaveDoesNotInvalidateStableReadOrDuplicateRecord(){rollback(false)}
 @Test fun S3B12_floatingRolledBackSaveDoesNotInvalidateStableReadOrDuplicateRecord(){rollback(true)}
 private fun noOp(floating:Boolean)=F(floating).use{f->
  f.query();f.app.worker.all();val before=f.r.backupSnapshot()
  val out=f.r.saveUserEntry("alpha","n.","old-supplement",f.now+40)
  assertTrue(out.saved);assertFalse(out.changed);assertEquals(before,f.r.backupSnapshot())
  f.drain();assertTrue(f.text().contains("old-supplement"));assertFalse(f.text().contains("失效"))
  val after=f.r.backupSnapshot();assertEquals(before.data.userEntries,after.data.userEntries);assertEquals(1,f.r.events().count{it.entryKey=="alpha"});f.drain();assertEquals(after,f.r.backupSnapshot())
 }
 @Test fun S3B13_manualNoOpSaveKeepsReadStableAndCollectsExactlyOnce(){noOp(false)}
 @Test fun S3B14_floatingNoOpSaveKeepsReadStableAndCollectsExactlyOnce(){noOp(true)}
}
