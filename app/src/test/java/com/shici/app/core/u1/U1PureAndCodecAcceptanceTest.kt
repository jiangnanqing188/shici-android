package com.shici.app.core.u1
import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
class U1PureAndCodecAcceptanceTest {
 private fun user(s:String)=UserDictionaryEntry("alpha","v.",s,100)
 private fun base()=DictionaryEntry("alpha","n.","原始义","source","v")
 private fun data(vararg u:BackupUserEntry)=LearningBackupData(emptyList(),emptyList(),userEntries=u.toList())
 private fun decode(d:LearningBackupData)=LearningBackupCodec.decode(LearningBackupCodec.encode(d,"1.2.0",22,100))
 private fun reject(d:LearningBackupData){try{decode(d);fail("invalid v3 accepted")}catch(_:BackupFormatException){}}
 private fun dictionary():RawKajwebDictionary {val s="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\nE\talpha\tn.\t原始义\nE\tu.s.\tn.\t美国\n";val o=ByteArrayOutputStream();GZIPOutputStream(o).use{it.write(s.toByteArray())};val b=o.toByteArray();return RawKajwebDictionary.load(b.inputStream(),RawKajwebDictionary.Manifest(MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)},2,0))}
 @Test fun U1P01_personalFirstStableInterleaveExactDedup(){val result=UnifiedStudyPool.merged(listOf("p","both","p"),listOf("a","both","c","d"),listOf("b","a","e"));assertEquals(listOf("p","both","a","b","c","e","d"),result);assertEquals(result,UnifiedStudyPool.merged(listOf("p","both","p"),listOf("a","both","c","d"),listOf("b","a","e")))}
 @Test fun U1P02_emptyAndUnevenStreams(){assertEquals(emptyList<String>(),UnifiedStudyPool.merged(emptyList(),emptyList(),emptyList()));assertEquals(listOf("a","b","c"),UnifiedStudyPool.merged(emptyList(),emptyList(),listOf("a","b","c")))}
 @Test fun U1P03_keyNormalizationAndSingleWord(){assertEquals("alpha",PersonalDictionary.normalizeNewKey(" (Alpha!) "));assertNull(PersonalDictionary.normalizeNewKey("two words"));assertNull(PersonalDictionary.normalizeNewKey("a1"));assertNull(PersonalDictionary.normalizeNewKey("x\u0000"))}
 @Test fun U1P04_mergeOnceKeepsOriginalAndLabelsUser(){val a=PersonalDictionary.merge(base(),user("新义"))!!;assertTrue(a.meaning.contains("原始义"));assertTrue(a.meaning.contains(PersonalDictionary.MARKER));assertEquals(base().source,a.source);assertTrue(a.meaning.contains("【用户补充】"));assertEquals(a,PersonalDictionary.merge(a,user("新义")));assertEquals("原始义",PersonalDictionary.storedEntry(a).meaning)}
 @Test fun U1P05_legalOriginalMarkerMustNotTruncate(){val original=base().copy(meaning="原义\n\n【用户补充】\n原始词源合法后半义");val merged=PersonalDictionary.merge(original,user("个人义"))!!;assertTrue("原始正文包含marker不能被截断",merged.meaning.contains(original.meaning));assertEquals(original,PersonalDictionary.storedEntry(merged))}
 @Test fun U1P06_legalOriginalPosMarkerMustNotTruncate(){val original=base().copy(partOfSpeech="n.；用户补充：v.");val merged=PersonalDictionary.merge(original,user("个人义"))!!;assertEquals(original,PersonalDictionary.storedEntry(merged))}
 @Test fun U1P07_lookupAndStudyResolveAgreeAndNoAccumulation(){val d=dictionary();val u=user("新义");val a=(DictionaryResolver.lookup(d,"Alpha"){if(it=="alpha")u else null} as LookupResult.Match).entries.single();val b=DictionaryResolver.resolve(d,a,"alpha"){u}!!;assertEquals(a,b);assertEquals(1,b.meaning.split(PersonalDictionary.MARKER).size-1)}
 @Test fun U1P08_existingTrailingStopLookupAndUserOnlyNormalizedQuery(){val d=dictionary();assertEquals("u.s.",(DictionaryResolver.lookup(d,"U.S."){null} as LookupResult.Match).form);val u=UserDictionaryEntry("novel","","自建",1);assertEquals("novel",(DictionaryResolver.lookup(d,"NOVEL"){if(it=="novel")u else null} as LookupResult.Match).form)}
 @Test fun U1P09_userOnlySentenceStopMustBehaveLikeOriginal(){val d=dictionary();val u=UserDictionaryEntry("novel","","自建",1);assertTrue("OCR句末点自建词应与原词一致",DictionaryResolver.lookup(d,"novel."){if(it=="novel")u else null} is LookupResult.Match)}
 @Test fun U1P10_backupV3NoEventsSupplementRoundTrip(){val d=data(BackupUserEntry("novel","","new",1));val b=decode(d);assertEquals(d,b.data);assertEquals(3,b.schemaVersion);assertEquals(3,b.formatVersion);assertFalse(b.legacyNoStudyData);assertFalse(b.noPersonalDictionary)}
 @Test fun U1P11_backupRejectDuplicateUserKeys(){val u=BackupUserEntry("alpha","","m",1);reject(data(u,u.copy(meaning="different")))}
 @Test fun U1P12_backupRejectInvalidKeysPosAndMeaning(){for(u in listOf(BackupUserEntry("Alpha","","m",1),BackupUserEntry("","","m",1),BackupUserEntry("alpha","p".repeat(65),"m",1),BackupUserEntry("alpha",""," ",1),BackupUserEntry("alpha","","x\u0000",1),BackupUserEntry("alpha","","x".repeat(16385),1))){reject(data(u))}}
 @Test fun U1P13_backupPreservesLegalRepeatedBodyAndNewlines(){val m="甲\n例\n乙\n例";val d=data(BackupUserEntry("alpha","",m,1));assertEquals(d,decode(d).data)}
 @Test fun U1P14_backupChecksumFutureAndUtf8StillStrict(){val bytes=LearningBackupCodec.encode(data(),"1.2.0",22,1);for(i in listOf(8,bytes.lastIndex)){val broken=bytes.copyOf();broken[i]=(broken[i].toInt() xor 0x7f).toByte();try{LearningBackupCodec.decode(broken);fail("corrupt/future accepted")}catch(_:BackupFormatException){}};reject(data(BackupUserEntry("alpha","","\uD800",1)))}
 @Test fun U1P15_missingOriginalFallbackExplicitUserSource(){val u=user("only");val a=DictionaryResolver.resolve(null,null,"alpha"){u}!!;assertEquals(PersonalDictionary.SOURCE,a.source);assertFalse(PersonalDictionary.provenance(a).contains("kajweb"))}
}
