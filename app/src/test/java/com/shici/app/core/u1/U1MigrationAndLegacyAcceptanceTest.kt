package com.shici.app.core.u1
import com.shici.app.core.*
import com.shici.app.core.b4.host.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.time.*
import java.security.MessageDigest
/** Genuine v2 header/schema2, exact established v2 payload (no user count/table), no v3-labelled fake. */
class U1MigrationAndLegacyAcceptanceTest {
 private val now=Instant.parse("2026-10-21T12:00:00Z").toEpochMilli();private val zone=ZoneId.of("UTC")
 private fun repo()=LearningRepository(Context(),"u1-migration-isolated")
 private fun rows(db:SQLiteDatabase,t:String)=db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{it.rows.map{r->r.toList()}}
 private fun entry(k:String)=DictionaryEntry(k,"n","原义","historical","v-old")
 private fun plan(vararg k:String)=StudyMembership(StudyBook.ALL,k.toList()){entry(it)}
 private fun seed(r:LearningRepository):LearningBackupData {
  r.record(QueryEvent("revoked","alpha","old-raw","alpha",now-50),entry("alpha"));r.revoke("revoked");r.updateWord("alpha",true,"私有笔记");r.updateStudySettings(StudyBook.ALL,37)
  var c=r.studyView(plan("alpha"),now,zone).card!!;r.revealStudyCard(c.token);assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(c.token,FeedbackKind.KNOW,now,zone))
  c=r.studyView(plan("beta"),now,zone).card!!;r.revealStudyCard(c.token)
  return r.backupSnapshot().data
 }
 private fun v2(d:LearningBackupData):ByteArray {val b=ByteArrayOutputStream();val o=DataOutputStream(b);fun s(v:String){val a=v.toByteArray(Charsets.UTF_8);o.writeInt(a.size);o.write(a)}
  o.writeBytes("SHICIBKP");o.writeInt(2);s("com.shici.app");s("1.0.3");o.writeInt(15);o.writeInt(2);o.writeLong(now);o.writeInt(d.words.size);o.writeInt(d.events.size)
  d.words.forEach{w->listOf(w.entryKey,w.headword,w.partOfSpeech,w.meaning,w.source,w.version).forEach{s(it)};o.writeBoolean(w.mastered);s(w.note)}
  d.events.forEach{e->listOf(e.id,e.entryKey,e.rawText,e.finalForm).forEach{s(it)};o.writeLong(e.time);o.writeBoolean(e.revoked)}
  s(d.settings.selectedBook);o.writeInt(d.settings.dailyNewGoal);o.writeInt(d.states.size)
  d.states.forEach{x->s(x.entryKey);s(x.book);o.writeInt(x.stage);o.writeLong(x.dueAt);o.writeLong(x.introducedAt);o.writeLong(x.lastReviewAt);o.writeLong(x.lastReviewSeq)}
  o.writeInt(d.reviews.size);d.reviews.forEach{x->s(x.token);s(x.entryKey);s(x.feedback);o.writeInt(x.stageBefore);o.writeInt(x.stageAfter);o.writeLong(x.dueAfter);o.writeLong(x.reviewedAt);s(x.dayKey);s(x.zoneId);o.writeBoolean(x.newWord);o.writeLong(x.seq)}
  o.writeBoolean(d.session!=null);d.session?.let{x->s(x.token);s(x.entryKey);s(x.book);o.writeInt(x.stageBefore);o.writeBoolean(x.newWord);o.writeBoolean(x.answerRevealed);listOf(x.headword,x.partOfSpeech,x.meaning,x.source,x.version,x.dayKey).forEach{s(it)};o.writeLong(x.createdAt)}
  return b.toByteArray()+MessageDigest.getInstance("SHA-256").digest(b.toByteArray())
 }
 @Test fun U1M01_genuineV2RetainsAllDataAndCorrectPreviewFlags()=repo().use{r->val d=seed(r);val old=d.copy(settings=BackupStudySettings("cet6",37),states=d.states.map{it.copy(book="ky")},session=d.session!!.copy(book="personal"));val backup=LearningBackupCodec.decode(v2(old));assertEquals(2,backup.formatVersion);assertEquals(2,backup.schemaVersion);assertEquals(old,backup.data);assertFalse("v2 has study rows",backup.legacyNoStudyData);assertTrue(backup.noPersonalDictionary);assertEquals(1,backup.reviewCount);assertTrue(backup.data.events.single().revoked)}
 @Test fun U1M02_v2RestoreFullOverwriteMigratesScopeAndKeepsRevealedPending()=repo().use{r->val d=seed(r);val old=d.copy(settings=BackupStudySettings("cet6",37),session=d.session!!.copy(book="ky"));val decoded=LearningBackupCodec.decode(v2(old));r.saveUserEntry("novel","","new",now);val credential=r.deletions.capture();val before=r.changes.version;assertEquals(RestoreOutcome.RESTORED,r.restoreBackup(decoded.data,before));assertEquals(old.copy(settings=old.settings.copy(selectedBook="all"),session=old.session!!.copy(book="all")),r.backupSnapshot().data);assertTrue(r.deletions.isStale("alpha",credential));val v=r.studyView(plan("beta"),now,zone);assertEquals(old.session!!.token,v.card!!.token);assertTrue(v.card!!.answerRevealed);assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(v.card!!.token,FeedbackKind.KNOW,now,zone,v.credential));assertEquals(2,r.backupSnapshot().data.reviews.size);assertEquals(0,r.userEntryCount())}
 @Test fun U1M03_v2ToV3SqlUpgradePreservesEveryOldTableAndPendingPhase()=repo().use{r->
  seed(r);val source=r.writableDatabase;SQLiteDatabase().use{db->
   com.shici.app.core.u1.host.LegacyV2SchemaFactory().onCreate(db)
   val names=listOf("words","events","study_states","study_reviews","study_session","study_settings")
   for(name in names){db.delete(name,null,null);source.rawQuery("SELECT * FROM $name",null).use{c->for(row in c.rows){val values=ContentValues();c.names.forEachIndexed{i,k->values.put(k,row[i])};db.insertOrThrow(name,null,values)}}}
   db.execSQL("UPDATE study_settings SET selected_book='cet6'");db.execSQL("UPDATE study_session SET book='ky'")
   val stable=listOf("words","events","study_states","study_reviews");val before=stable.associateWith{rows(db,it)};val pending=rows(db,"study_session").single();val settings=rows(db,"study_settings").single()
   db.beginTransaction();try{r.onUpgrade(db,2,3);db.setTransactionSuccessful()}finally{db.endTransaction()}
   assertEquals(before,stable.associateWith{rows(db,it)});assertEquals(listOf(settings[0],"all",settings[2]),rows(db,"study_settings").single());val expected=pending.toMutableList();expected[3]="all";assertEquals(expected,rows(db,"study_session").single());assertTrue(rows(db,"user_entries").isEmpty())
  }
 }
 @Test fun U1M04_v2ToV3DdlFailureRollsBackWholeMigration()=repo().use{r->seed(r);val db=r.writableDatabase;db.execSQL("DROP TABLE user_entries");db.execSQL("UPDATE study_settings SET selected_book='cet6'");val before=rows(db,"study_settings");db.failAt="UPDATE study_settings";db.beginTransaction();try{try{r.onUpgrade(db,2,3);fail("expected injected migration failure")}catch(_:IllegalStateException){}}finally{db.endTransaction()};assertEquals(before,rows(db,"study_settings"));assertEquals(0,db.rawQuery("SELECT name FROM sqlite_master WHERE name='user_entries'",null).use{it.rows.size});assertEquals(1,rows(db,"study_reviews").size)}
 @Test fun U1M05_v2InvalidSeqStillRejectedWithValidChecksum()=repo().use{r->val d=seed(r);val bad=d.copy(states=d.states.map{it.copy(lastReviewSeq=999)});try{LearningBackupCodec.decode(v2(bad));fail("v2 bad seq accepted")}catch(_:BackupFormatException){}}
 @Test fun U1M06_v3RestoreInsertUserFailurePreservesAllOriginalData()=repo().use{r->val d=seed(r);r.saveUserEntry("local","","mine",now);val before=r.backupSnapshot();val cred=r.deletions.capture();r.database.failAt="INSERT INTO user_entries";try{r.restoreBackup(d.copy(userEntries=listOf(BackupUserEntry("remote","","theirs",now))),before.revision);fail("expected injected") }catch(_:IllegalStateException){};assertEquals(before,r.backupSnapshot());assertFalse(r.deletions.isStale("alpha",cred))}
}
