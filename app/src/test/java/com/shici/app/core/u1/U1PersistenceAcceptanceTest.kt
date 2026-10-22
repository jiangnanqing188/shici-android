package com.shici.app.core.u1
import com.shici.app.core.*
import com.shici.app.core.b4.host.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
/** Separate Python SQLite processes reopen one fresh test-only file; not Android process/SQLiteOpenHelper. */
class U1PersistenceAcceptanceTest {
 @Test fun U1D01_newProcessReopenPreservesUnqueriedSupplementAndNoHistory(){
  val f=File.createTempFile("u1-isolated-",".sqlite");try{
   LearningRepository(Context(SQLiteDatabase(f.path)),"u1").use{r->assertTrue(r.saveUserEntry("novel","n.","persistent supplement",123).saved);assertTrue(r.events().isEmpty())}
   LearningRepository(Context(SQLiteDatabase(f.path),true),"u1").use{r->assertEquals("persistent supplement",r.userEntry("novel")!!.meaning);assertTrue(r.events().isEmpty());assertTrue(r.personalMembers().isEmpty());val a=DictionaryResolver.lookup(U1TestAssets.dictionary(),"NOVEL"){r.userEntry(it)} as LookupResult.Match;assertEquals(PersonalDictionary.SOURCE,a.entries.single().source);assertEquals(1,r.backupSnapshot().data.userEntries.size)}
  }finally{f.delete()}
 }
 @Test fun U1D02_restartResumesRevealedUnifiedCardWithLatestUserMeaning(){
  val f=File.createTempFile("u1-isolated-",".sqlite");val now=Instant.parse("2026-10-21T12:00:00Z").toEpochMilli();var token=""
  try{
   LearningRepository(Context(SQLiteDatabase(f.path)),"u1").use{r->r.saveUserEntry("novel","","user meaning",1);val e=PersonalDictionary.userOnly(r.userEntry("novel")!!);r.record(QueryEvent("q","novel","novel","novel",now),e);val p=StudyMembership(StudyBook.ALL,r.personalMembers()){e};val c=r.studyView(p,now,ZoneOffset.UTC).card!!;token=c.token;r.revealStudyCard(token)}
   LearningRepository(Context(SQLiteDatabase(f.path),true),"u1").use{r->val p=StudyMembership(StudyBook.ALL,r.personalMembers()){DictionaryResolver.resolve(null,r.storedEntry(it),it){k->r.userEntry(k)}};val c=r.studyView(p,now,ZoneOffset.UTC).card!!;assertEquals(token,c.token);assertTrue(c.answerRevealed);assertEquals("user meaning",c.entry.meaning);assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(token,FeedbackKind.KNOW,now,ZoneOffset.UTC));assertEquals(1,r.events().size);assertEquals(1,r.backupSnapshot().data.reviews.size)}
  }finally{f.delete()}
 }
}
