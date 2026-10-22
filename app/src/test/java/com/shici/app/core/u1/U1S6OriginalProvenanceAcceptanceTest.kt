package com.shici.app.core.u1

import com.shici.app.core.*
import com.shici.app.core.b4.host.Context
import com.shici.app.core.b4.host.LearningRepository
import com.shici.app.core.b4.host.RestoreOutcome
import com.shici.app.core.b4.host.StudyMembership
import com.shici.app.core.b4.host.StudyCommitOutcome
import com.shici.app.core.q1.host.*
import com.shici.app.core.u1.host.ManualHost
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

/** S6: direct production dictionary/merge/codec; verbatim Repository/UI hosts with explicit adapters.
 * Synthetic legal marker-bearing originals, not a claim about the pinned real dictionary corpus. */
class U1S6OriginalProvenanceAcceptanceTest {
 private val time=1792627200000L
 private val zone=ZoneId.of("UTC")
 private val meaning="原义😀\n\n【用户补充】\n合法后半义\n\n【用户补充】\n重复原义  \n  "
 private val pos="n.；用户补充：v.；用户补充：adj.  "
 private val userBody="独立用户义\n\n【用户补充】\n合法用户正文\n例句 X\n例句 X"
 private fun raw()=DictionaryEntry("alpha",pos,meaning,"historical-original","v0")
 private fun user(body:String=userBody,p:String="adv.")=UserDictionaryEntry("alpha",p,body,time)
 private fun repo()=LearningRepository(Context(),"s6-isolated")
 private fun exact(expected:String,actual:String){assertEquals(expected,actual);assertArrayEquals(expected.toByteArray(Charsets.UTF_8),actual.toByteArray(Charsets.UTF_8))}
 private fun derived(base:DictionaryEntry,u:UserDictionaryEntry)=PersonalDictionary.merge(base,u)!!
 private fun checkDisplay(base:DictionaryEntry,u:UserDictionaryEntry,e:DictionaryEntry){
  exact(base.meaning+"\n\n【用户补充】\n"+u.meaning,e.meaning)
  exact(if(u.partOfSpeech.isBlank())base.partOfSpeech else if(base.partOfSpeech.isBlank())u.partOfSpeech else base.partOfSpeech+"；用户补充："+u.partOfSpeech,e.partOfSpeech)
  assertEquals(base.headword,e.headword);assertEquals(base.key,e.key);assertEquals(base.source,e.source);assertEquals(base.version,e.version)
  assertEquals(base,e.originalSnapshot);assertNull(e.originalSnapshot!!.originalSnapshot);assertEquals(base,PersonalDictionary.storedEntry(e))
 }
 @Test fun S6B01_defaultFiveAndThreeArgumentConstructorsAreRaw(){
  val a=raw();val b=DictionaryEntry("alpha",pos,meaning);assertNull(a.originalSnapshot);assertNull(b.originalSnapshot)
  assertSame(a,PersonalDictionary.storedEntry(a));assertSame(b,PersonalDictionary.storedEntry(b));exact(meaning,b.meaning);exact(pos,b.partOfSpeech)
 }
 @Test fun S6B02_rawWithoutUserReturnsSameMarkerBearingOriginal(){val a=raw();assertSame(a,PersonalDictionary.merge(a,null));assertSame(a,PersonalDictionary.storedEntry(a));exact(meaning,a.meaning);exact(pos,a.partOfSpeech)}
 @Test fun S6B03_multipleLiteralMarkersAndTrailingWhitespaceSurviveMerge(){val a=raw();val e=derived(a,user());checkDisplay(a,user(),e);exact(meaning,a.meaning);exact(pos,a.partOfSpeech)}
 @Test fun S6B04_sameSupplementHundredMergesNeverGrowSnapshotOrText(){val a=raw();val expected=derived(a,user());var e=expected;repeat(100){e=derived(e,user().copy());assertEquals(expected,e);assertEquals(expected.hashCode(),e.hashCode());assertSame(a,e.originalSnapshot)};checkDisplay(a,user(),e)}
 @Test fun S6B05_replacementDoesNotAccumulateEitherOldUserSection(){val a=raw();val first=derived(a,user("old-only-user"));val next=user("new-only-user\n\n【用户补充】\nnew tail","adj.");val e=derived(first,next);checkDisplay(a,next,e);assertFalse(e.meaning.contains("old-only-user"));assertEquals(a,PersonalDictionary.merge(e,null))}
 @Test fun S6B06_defaultCopyAndRepeatedCopiesRetainExplicitSource(){val a=raw();var e=derived(a,user());repeat(30){e=e.copy()};checkDisplay(a,user(),e);assertSame(a,e.originalSnapshot);assertEquals(a,PersonalDictionary.storedEntry(e.copy(meaning="discarded display",partOfSpeech="discarded display pos")))}
 @Test fun S6B07_nestedExplicitSnapshotsFlattenToUntouchedDeepestBase(){val a=raw();val first=derived(a,user("old"));val wrapped=first.copy(meaning="outer-only",originalSnapshot=first.copy(originalSnapshot=first));assertEquals(a,PersonalDictionary.storedEntry(wrapped));checkDisplay(a,user(),derived(wrapped,user()));assertSame(a,PersonalDictionary.merge(wrapped,null))}
 @Test fun S6B08_emptyBaseAndBlankUserPosKeepExistingDisplayRules(){
  val empty=raw().copy(meaning="",partOfSpeech="");val e=derived(empty,user("new","n."));exact("【用户补充】\nnew",e.meaning);exact("n.",e.partOfSpeech);assertEquals(empty,PersonalDictionary.storedEntry(e))
  val a=raw();checkDisplay(a,user(p=""),derived(a,user(p="")))
 }
 @Test fun S6B09_markerOnlyOriginalAndPosAreNotEvidenceOfDerivation(){
  val a=raw().copy(meaning="\n\n【用户补充】\n",partOfSpeech="；用户补充：");assertSame(a,PersonalDictionary.storedEntry(a));checkDisplay(a,user(),derived(a,user()))
 }
 @Test fun S6B10_userOnlyLiteralBodyIsNotPretendedBookOrigin(){
  val a=PersonalDictionary.userOnly(user());assertNull(a.originalSnapshot);assertSame(a,PersonalDictionary.storedEntry(a));exact(userBody,a.meaning);assertEquals(PersonalDictionary.SOURCE,a.source);assertEquals(PersonalDictionary.VERSION,a.version)
  assertEquals("用户补充 · 仅本机",PersonalDictionary.label(a));assertFalse(PersonalDictionary.provenance(a).contains("kajweb"));assertSame(a,PersonalDictionary.merge(a,null));val replacement=PersonalDictionary.merge(a,user("replacement"))!!;assertNull(replacement.originalSnapshot);exact("replacement",replacement.meaning)
 }
 @Test fun S6B11_rawAndDerivedCopiesPreserveStructuralEqualityAndKeyDedup(){
  val a=raw();val b=raw();assertEquals(a,b);assertEquals(a.hashCode(),b.hashCode());val x=derived(a,user());val y=derived(b,user());assertEquals(x,y);assertEquals(x.hashCode(),y.hashCode());assertEquals(1,setOf(x,y,x.copy()).size);assertEquals(listOf("alpha"),UnifiedStudyPool.merged(listOf(a.key,x.key),listOf(b.key),listOf(y.key)))
 }
 @Test fun S6B12_resolverWithoutPinnedSourceUsesExplicitBaseAndFreshUser(){
  val a=raw();val old=derived(a,user("old-user"));val fresh=user("fresh-user");val result=DictionaryResolver.resolve(null,old,"alpha"){fresh}!!;checkDisplay(a,fresh,result);assertFalse(result.meaning.contains("old-user"));assertSame(a,DictionaryResolver.resolve(null,result,"alpha"){null})
 }
 private fun codec(data:LearningBackupData)=LearningBackupCodec.decode(LearningBackupCodec.encode(data,"1.2.7",29,time))
 private fun plan(r:LearningRepository)=StudyMembership(StudyBook.ALL,listOf("alpha")){key->DictionaryResolver.resolve(null,r.storedEntry(key),key){r.userEntry(it)}}
 @Test fun S6B13_derivedRecordV3RestoreResolverStudyAndFeedbackHaveSingleOrigin(){repo().use{r->
  val a=raw();assertTrue(r.saveUserEntry("alpha","adv.",userBody,time).saved);val u=r.userEntry("alpha")!!;val e=derived(a,u)
  assertTrue(r.record(QueryEvent("s6-q","alpha","Alpha","alpha",time),e));assertEquals(a,r.storedEntry("alpha"));r.updateWord("alpha",true,"personal-note")
  val data=r.backupSnapshot().data;val w=data.words.single();exact(meaning,w.meaning);exact(pos,w.partOfSpeech);assertEquals(a.source,w.source);assertEquals(a.version,w.version);assertEquals(1,data.events.size);exact(userBody,data.userEntries.single().meaning)
  val decoded=codec(data);assertEquals(3,decoded.formatVersion);assertEquals(3,decoded.schemaVersion);assertEquals(data,decoded.data)
  repo().use{target->assertEquals(RestoreOutcome.RESTORED,target.restoreBackup(decoded.data,target.changes.version));assertEquals(data,target.backupSnapshot().data);val stored=target.storedEntry("alpha")!!;assertNull(stored.originalSnapshot);assertEquals(a,stored)
   val before=target.backupSnapshot();val resolved=DictionaryResolver.resolve(null,stored,"alpha"){target.userEntry(it)}!!;checkDisplay(a,u,resolved);assertEquals(before,target.backupSnapshot())
   val card=target.studyView(plan(target),time,zone).card!!;checkDisplay(a,u,card.entry);assertTrue(target.revealStudyCard(card.token));assertEquals(StudyCommitOutcome.COMMITTED,target.commitStudyFeedback(card.token,FeedbackKind.KNOW,time,zone))
   val studied=target.backupSnapshot().data;assertEquals(data.words,studied.words);assertEquals(data.events,studied.events);assertEquals(data.userEntries,studied.userEntries);assertEquals(1,studied.states.size);assertEquals(1,studied.reviews.size);assertEquals(studied,codec(studied).data)
  }
 }}
 @Test fun S6B14_markerHistoryAndSupplementReplacementNeverMutateLearningLedger(){repo().use{r->
  val a=raw();r.record(QueryEvent("history","alpha","alpha","alpha",time),a);r.updateWord("alpha",true,"note");val c=r.studyView(plan(r),time,zone).card!!;r.revealStudyCard(c.token);assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(c.token,FeedbackKind.KNOW,time,zone));val before=r.backupSnapshot().data
  assertTrue(r.saveUserEntry("alpha","adv.","old-user",time).saved);val old=r.userEntry("alpha")!!;val e=derived(a,old);assertTrue(r.saveUserEntry("alpha","v.",userBody,time,expectedUpdatedAt=old.updatedAt).saved)
  val fresh=r.userEntry("alpha")!!;checkDisplay(a,fresh,DictionaryResolver.resolve(null,e,"alpha"){r.userEntry(it)}!!);val after=r.backupSnapshot().data;assertEquals(before.copy(userEntries=after.userEntries),after);exact(meaning,after.words.single().meaning);assertEquals(after,codec(after).data)
 }}
 @Test fun S6B18_persistedPendingRereadMustNotTreatDerivedUserBodyAsRawOriginal(){repo().use{r->
  val a=raw();r.record(QueryEvent("pending-history","alpha","alpha","alpha",time),a);r.saveUserEntry("alpha","adv.",userBody,time);val u=r.userEntry("alpha")!!
  val first=r.studyView(plan(r),time,zone).card!!;checkDisplay(a,u,first.entry)
  val next=r.studyView(plan(r),time,zone).card!!;assertEquals(first.token,next.token);checkDisplay(a,u,next.entry)
  val third=r.studyView(plan(r),time,zone).card!!;assertEquals(next,third);exact(meaning,r.backupSnapshot().data.words.single().meaning)
 }}
 @Test fun S6B19_pendingV3RestoreMustNotBakeUserSegmentIntoHistoricalSnapshot(){repo().use{r->
  val a=raw();r.record(QueryEvent("backup-pending","alpha","alpha","alpha",time),a);r.saveUserEntry("alpha","adv.",userBody,time);val card=r.studyView(plan(r),time,zone).card!!
  val data=r.backupSnapshot().data;val session=data.session!!;exact(meaning,session.meaning);exact(pos,session.partOfSpeech);assertEquals(a.source,session.source);assertEquals(a.version,session.version)
  val decoded=codec(data);assertEquals(data,decoded.data)
  repo().use{target->assertEquals(RestoreOutcome.RESTORED,target.restoreBackup(decoded.data,target.changes.version));val u=target.userEntry("alpha")!!;val resumed=target.studyView(plan(target),time,zone).card!!;assertEquals(card.token,resumed.token);checkDisplay(a,u,resumed.entry);assertEquals(data.words,target.backupSnapshot().data.words);assertTrue(target.revealStudyCard(resumed.token));assertEquals(StudyCommitOutcome.COMMITTED,target.commitStudyFeedback(resumed.token,FeedbackKind.KNOW,time,zone));exact(meaning,target.backupSnapshot().data.words.single().meaning)}
 }}
 @Test fun S6B20_restoredPendingMustNotDuplicateOriginalOrIndependentUserBody(){repo().use{r->
  val a=raw();r.record(QueryEvent("restore-pending","alpha","alpha","alpha",time),a);r.saveUserEntry("alpha","adv.",userBody,time);val card=r.studyView(plan(r),time,zone).card!!;val data=codec(r.backupSnapshot().data).data
  repo().use{target->assertEquals(RestoreOutcome.RESTORED,target.restoreBackup(data,target.changes.version));val before=target.backupSnapshot();val restored=target.studyView(plan(target),time,zone).card!!;assertEquals(card.token,restored.token);assertEquals(before,target.backupSnapshot());checkDisplay(a,target.userEntry("alpha")!!,restored.entry)}
 }}
 @Test fun S6B21_firstBookStudyFeedbackMustNotBakeUserSegmentIntoNewWordRow(){repo().use{r->
  val a=raw();r.saveUserEntry("alpha","adv.",userBody,time);assertTrue(r.backupSnapshot().data.words.isEmpty());val p=StudyMembership(StudyBook.ALL,listOf("alpha")){key->DictionaryResolver.resolve(null,a,key){r.userEntry(it)}}
  val c=r.studyView(p,time,zone).card!!;checkDisplay(a,r.userEntry("alpha")!!,c.entry);assertTrue(r.revealStudyCard(c.token));assertEquals(StudyCommitOutcome.COMMITTED,r.commitStudyFeedback(c.token,FeedbackKind.KNOW,time,zone));assertTrue(r.events().isEmpty());assertEquals(1,r.backupSnapshot().data.reviews.size);val stored=r.storedEntry("alpha")!!;exact(meaning,stored.meaning);exact(pos,stored.partOfSpeech);assertEquals(a,stored)
 }}
 private fun dictionary():RawKajwebDictionary {
  fun escape(s:String)=s.replace("\\","\\\\").replace("\t","\\t").replace("\n","\\n")
  val text="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\nE\talpha\t${escape(pos)}\t${escape(meaning)}\n";val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(text.toByteArray())};val bytes=out.toByteArray();return RawKajwebDictionary.load(bytes.inputStream(),RawKajwebDictionary.Manifest(U1TestAssets.hash(bytes),1,0))
 }
 @Test fun S6B15_pinnedLookupAndResolverUseOneUntouchedMarkerBearingSource(){val d=dictionary();val a=d.entry("alpha")!!;val e=(DictionaryResolver.lookup(d,"Alpha"){user()} as LookupResult.Match).entries.single();checkDisplay(a,user(),e);assertEquals(e,DictionaryResolver.resolve(d,e,"alpha"){user()});assertEquals(PersonalDictionary.label(a),PersonalDictionary.label(e));assertEquals(PersonalDictionary.provenance(a),PersonalDictionary.provenance(e))}
 private fun ui(floating:Boolean){repo().use{r->
  val f=if(floating)CaptureHost() else null;val m=if(!floating)ManualHost() else null;val app=f?.app?:m!!.app;CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;app.repository.real=r;val d=dictionary();app.dictionary=Result.success(d)
  fun pump(){app.worker.all();if(floating)f!!.main.all() else m!!.main.all()}
  fun nodes()=if(floating)f!!.nodes() else m!!.nodes()
  if(floating)f!!.queryForTest("alpha") else m!!.query("alpha");pump();pump();assertTrue(nodes().any{it.text.contains(meaning)});assertTrue(nodes().any{it.text.contains(pos)})
  val before=r.backupSnapshot();nodes().filterIsInstance<Button>().single{it.text=="补充/编辑词义"}.click();val edits=nodes().filterIsInstance<EditText>();assertEquals(2,edits.size);edits.first().setText("adv.");edits.last().setText(userBody);nodes().filterIsInstance<Button>().single{it.text=="保存到本机"}.click();pump()
  val a=d.entry("alpha")!!;val u=r.userEntry("alpha")!!;val e=app.resolveEntry("alpha")!!;checkDisplay(a,u,e);assertTrue(nodes().any{it.text.contains(e.meaning)});assertEquals(before.data.copy(userEntries=r.backupSnapshot().data.userEntries),r.backupSnapshot().data);assertEquals(a,r.storedEntry("alpha"));assertEquals(1,r.events().size);exact(meaning,codec(r.backupSnapshot().data).data.words.single().meaning)
 }}
 @Test fun S6B16_manualQueryAndEditorDisplayLiteralOriginalAndUserOnce(){ui(false)}
 @Test fun S6B17_floatingQueryAndEditorDisplayLiteralOriginalAndUserOnce(){ui(true)}
}
