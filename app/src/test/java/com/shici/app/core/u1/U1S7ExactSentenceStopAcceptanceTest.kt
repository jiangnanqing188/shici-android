package com.shici.app.core.u1

import com.shici.app.core.*
import com.shici.app.core.u1.host.ManualHost
import com.shici.app.core.q1.host.*
import com.shici.app.core.b4.host.LearningRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Resolver is direct production Kotlin. UI bodies are the unchanged mapped production slices;
 * the existing FIFO/framework/SQLite adapters are not replaced or relaxed by this suite. */
class U1S7ExactSentenceStopAcceptanceTest {
 private fun dictionary(vararg keys: String): RawKajwebDictionary {
  val text="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\n"+
   keys.joinToString(""){"E\t$it\tn.\t原始:$it\n"}
  val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(text.toByteArray())}
  val bytes=out.toByteArray()
  return RawKajwebDictionary.load(bytes.inputStream(),RawKajwebDictionary.Manifest(U1TestAssets.hash(bytes),keys.size,0))
 }
 private fun user(key:String,meaning:String="个人义")=UserDictionaryEntry(key,"v.",meaning,1)
 private fun match(result:LookupResult,key:String):DictionaryEntry {
  assertTrue("expected exact/fallback Match for $key, got $result",result is LookupResult.Match)
  result as LookupResult.Match;assertEquals(key,result.form);assertEquals(1,result.entries.size)
  return result.entries.single().also{assertEquals(key,it.key)}
 }
 private fun merged(entry:DictionaryEntry,base:DictionaryEntry,personal:UserDictionaryEntry){
  assertEquals(base,PersonalDictionary.storedEntry(entry));assertEquals(base.source,entry.source)
  assertEquals(base.version,entry.version);assertEquals(base.meaning+"\n\n"+PersonalDictionary.MARKER+"\n"+personal.meaning,entry.meaning)
  assertEquals(entry,PersonalDictionary.merge(entry,personal))
 }
 @Test fun S7B01_originalAndUserFallbackNormalizeToRealKeyAndProbeBothLevels(){
  val d=dictionary("alpha");val seen=mutableListOf<String>()
  assertEquals(d.entry("alpha"),match(DictionaryResolver.lookup(d," (ALPHA.) "){seen+=it;null},"alpha"))
  assertEquals(listOf("alpha.","alpha"),seen);seen.clear()
  val u=user("novel");assertEquals(PersonalDictionary.userOnly(u),match(DictionaryResolver.lookup(d," (NOVEL.) "){seen+=it;if(it=="novel")u else null},"novel"))
  assertEquals(listOf("novel.","novel"),seen)
 }
 @Test fun S7B02_storedDottedUserExactWinsOverOriginalUndottedFallback(){
  val d=dictionary("alpha","novel");val u=user("novel.");assertTrue(PersonalDictionary.isValidStoredKey(u.entryKey))
  val seen=mutableListOf<String>();val e=match(DictionaryResolver.lookup(d,"NOVEL."){seen+=it;if(it==u.entryKey)u else null},"novel.")
  assertEquals(PersonalDictionary.userOnly(u),e);assertEquals(listOf("novel."),seen);assertNull(e.originalSnapshot)
 }
 @Test fun S7B03_originalDottedExactWinsOverUserUndottedAndStopsFallback(){
  val d=dictionary("alpha","alpha.");val seen=mutableListOf<String>()
  val e=match(DictionaryResolver.lookup(d,"alpha."){seen+=it;if(it=="alpha")user(it)else null},"alpha.")
  assertEquals(d.entry("alpha."),e);assertEquals(listOf("alpha."),seen)
 }
 @Test fun S7B04_dottedSharedExactMergesOnlySameKeyOnce(){
  val d=dictionary("alpha","alpha.");val u=user("alpha.");val seen=mutableListOf<String>()
  val e=match(DictionaryResolver.lookup(d,"alpha."){seen+=it;user(it,if(it=="alpha.")u.meaning else "错误退点义")},"alpha.")
  merged(e,d.entry("alpha.")!!,u);assertEquals(listOf("alpha."),seen)
 }
 @Test fun S7B05_onlyAfterFullMissFallbackMergesOriginalAndUserOnce(){
  val d=dictionary("alpha");val u=user("alpha");val seen=mutableListOf<String>()
  val e=match(DictionaryResolver.lookup(d,"alpha."){seen+=it;if(it=="alpha")u else null},"alpha")
  merged(e,d.entry("alpha")!!,u);assertEquals(listOf("alpha.","alpha"),seen)
  assertEquals(e,DictionaryResolver.resolve(d,e,"alpha"){if(it=="alpha")u else null})
 }
 @Test fun S7B06_missingKeepsOriginalNormalizedFormAndOnlyOneDrop(){
  val d=dictionary("alpha","u");val seen=mutableListOf<String>()
  for((raw,form,probes) in listOf(Triple(" (GHOST.) ","ghost.",listOf("ghost.","ghost")),Triple("GHOST","ghost",listOf("ghost")),Triple("u.s.","u.s.",listOf("u.s.","u.s")))){
   seen.clear();val r=DictionaryResolver.lookup(d,raw){seen+=it;null}
   assertTrue(r is LookupResult.Missing);assertEquals(form,(r as LookupResult.Missing).form);assertEquals(probes,seen)
  }
 }
 @Test fun S7B07_invalidNeverProbesEitherFallbackAndRepeatedStopsAreNotGuessed(){
  val d=dictionary("alpha");val seen=mutableListOf<String>()
  for(raw in listOf("", ".", "alpha..", "a1.", "alpha\u0000.", "alpha\uFFFD.")){
   assertEquals(raw,LookupResult.Invalid,DictionaryResolver.lookup(d,raw){seen+=it;user("alpha")})
  }
  assertTrue(seen.isEmpty())
 }
 @Test fun S7B08_noJoiningStemmingOrCreatingMultiwordAndExactPhraseStillAllowed(){
  val d=dictionary("alpha","two words");val seen=mutableListOf<String>()
  for(raw in listOf("al pha.","alphas.","alphas")){
   val r=DictionaryResolver.lookup(d,raw){seen+=it;null};assertTrue(r is LookupResult.Missing)
   assertEquals(RawKajwebDictionary.normalize(raw),(r as LookupResult.Missing).form)
  }
  assertFalse(seen.contains("alpha"));assertNull(PersonalDictionary.normalizeNewKey("two words"));assertNull(PersonalDictionary.normalizeNewKey("two words."))
  assertEquals("novel",PersonalDictionary.normalizeNewKey("NOVEL"))
  assertEquals(d.entry("two words"),match(DictionaryResolver.lookup(d,"two words."){null},"two words"))
 }
 @Test fun S7B09_originalInternalDottedExactAndDottedUserFallbackPreserveKeys(){
  val d=dictionary("alpha","u.s.");val seen=mutableListOf<String>()
  assertEquals(d.entry("u.s."),match(DictionaryResolver.lookup(d,"U.S."){seen+=it;null},"u.s."));assertEquals(listOf("u.s."),seen)
  val other=dictionary("alpha");val u=user("u.s");seen.clear()
  assertEquals(PersonalDictionary.userOnly(u),match(DictionaryResolver.lookup(other,"U.S."){seen+=it;if(it=="u.s")u else null},"u.s"))
  assertEquals(listOf("u.s.","u.s"),seen)
 }
 @Test fun S7B10_repeatedLookupUsesFreshUserAndNeverMutatesOriginalOrStacks(){
  val d=dictionary("alpha");val original=d.entry("alpha")!!;var u:UserDictionaryEntry?=user("alpha","旧义")
  val a=match(DictionaryResolver.lookup(d,"alpha."){if(it=="alpha")u else null},"alpha");merged(a,original,u!!)
  u=user("alpha","新义");val b=match(DictionaryResolver.lookup(d,"alpha."){if(it=="alpha")u else null},"alpha");merged(b,original,u!!)
  assertFalse(b.meaning.contains("旧义"));assertEquals(original,d.entry("alpha"));assertEquals(a.meaning,original.meaning+"\n\n"+PersonalDictionary.MARKER+"\n旧义")
  u=null;assertEquals(original,match(DictionaryResolver.lookup(d,"alpha."){if(it=="alpha")u else null},"alpha"))
 }
 private class F(val floating:Boolean):AutoCloseable {
  val r=LearningRepository(com.shici.app.core.b4.host.Context(),"u1-s7-isolated")
  val h=if(floating)CaptureHost()else null;val m=if(floating)null else ManualHost()
  val app get()=h?.app?:m!!.app
  init{CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;app.repository.real=r}
  fun pump(){app.worker.all();if(floating)h!!.main.all()else m!!.main.all()}
  fun query(raw:String){if(floating)h!!.queryForTest(raw)else m!!.query(raw);pump();pump()}
  fun text()=(if(floating)h!!.nodes()else m!!.nodes()).joinToString("\n"){it.text}
  override fun close(){r.close()}
 }
 private fun ui(floating:Boolean,dottedExact:Boolean)=F(floating).use{f->
  val d=dictionary("alpha","novel");f.app.dictionary=Result.success(d)
  val key=if(dottedExact)"novel."else "alpha";val raw=if(dottedExact)"novel."else "alpha."
  // Synthetic legal stored dotted key; do not modify new-word UI validation to create it.
  assertTrue(PersonalDictionary.isValidStoredKey(key));f.r.saveUserEntry(key,"v.","本机S7义",1)
  val before=f.r.backupSnapshot();assertTrue(before.data.events.isEmpty());assertTrue(f.r.personalMembers().isEmpty())
  f.query(raw);val b=f.r.backupSnapshot();assertEquals(listOf(key),f.r.personalMembers());assertEquals(1,b.data.words.size);assertEquals(1,b.data.events.size)
  val event=b.data.events.single();assertEquals(key,event.entryKey);assertEquals(key,event.finalForm);assertEquals(raw,event.rawText)
  val display=match(f.app.lookupWord(raw),key);assertTrue(f.text().contains("本机S7义"));assertEquals(before.data.userEntries,b.data.userEntries)
  if(dottedExact){assertEquals(PersonalDictionary.SOURCE,display.source);assertFalse(f.text().contains("原始:novel"));assertNull(f.r.storedEntry("novel"))}
  else {merged(display,d.entry("alpha")!!,user("alpha","本机S7义"));assertTrue(f.text().contains("原始:alpha"));assertEquals(d.entry("alpha"),f.r.storedEntry("alpha"))}
  f.pump();f.pump();assertEquals(1,f.r.events().size);assertEquals(listOf(key),f.r.personalMembers())
 }
 @Test fun S7B11_manualOriginalFallbackMergesOnceAndExplicitlyCollectsOneKey(){ui(false,false)}
 @Test fun S7B12_floatingOriginalFallbackMergesOnceAndExplicitlyCollectsOneKey(){ui(true,false)}
 @Test fun S7B13_manualDottedUserExactBeatsOriginalFallbackAndCollectsDottedKey(){ui(false,true)}
 @Test fun S7B14_floatingDottedUserExactBeatsOriginalFallbackAndCollectsDottedKey(){ui(true,true)}
}
