package com.shici.app.core.b4
import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
/** Runs actual production codec. Byte edits below only construct malformed inputs; no replacement decoder. */
class B4LegacyCodecRegressionTest {
    private fun word(k:String="a")=BackupWord(k,k,"n\u0000形","历史释义😀","old-demo","v-old",true,"笔记\r\n")
    private fun data()=LearningBackupData(listOf(word(),word("revoked"),word("orphan-but-legal")),listOf(
        BackupEvent("i-1","a","原词😀","A",Long.MIN_VALUE,false), BackupEvent("i-2","a","","",Long.MAX_VALUE,true),
        BackupEvent("only-revoked","revoked","r","r",0,true)))
    private fun enc(d:LearningBackupData=data())=LearningBackupCodec.encode(d,"0.4.0",10,-123L)
    private fun seal(b:ByteArray)=b+MessageDigest.getInstance("SHA-256").digest(b)
    private fun edit(b:ByteArray=enc(),action:(ByteArray)->Unit):ByteArray {val body=b.copyOf(b.size-32);action(body);return seal(body)}
    private fun put(b:ByteArray,p:Int,n:Int){ByteBuffer.wrap(b,p,4).putInt(n)}
    private fun rejected(b:ByteArray){try{LearningBackupCodec.decode(b);fail("malformed input accepted")}catch(e:BackupFormatException){assertTrue(e.message!!.isNotEmpty())}}
    private val header get()=8+4+4+"com.shici.app".toByteArray().size+4+5+4+4+8
    private val wordStart get()=header+8
    @Test fun allRowsAllFieldsAndMetadataRoundTrip(){val d=data();val v=LearningBackupCodec.decode(enc(d));assertEquals(d,v.data);assertEquals("0.4.0",v.appVersionName);assertEquals(10,v.appVersionCode);assertEquals(3,v.schemaVersion);assertEquals(-123L,v.createdAt);assertEquals(3,v.wordCount);assertEquals(3,v.eventCount)}
    @Test fun emptyDatabaseLegal(){val d=LearningBackupData(emptyList(),emptyList());assertEquals(d,LearningBackupCodec.decode(enc(d)).data)}
    @Test fun legalEmptyFieldsPreserved(){val w=BackupWord("x","x","","","","",false,"");val d=LearningBackupData(listOf(w),listOf(BackupEvent("e","x","","",-1,false)));assertEquals(d,LearningBackupCodec.decode(enc(d)).data)}
    @Test fun unicodeLongNoteBeyondWriteUtfLimit(){val w=word().copy(note="😀漢\u0000e\u0301\n".repeat(18000));val d=LearningBackupData(listOf(w),emptyList());assertTrue(w.note.toByteArray().size>65536);assertEquals(d,LearningBackupCodec.decode(enc(d)).data)}
    @Test fun sourceAndVersionNeverReplacedByCurrentDictionary(){assertEquals("v-old",LearningBackupCodec.decode(enc()).data.words[0].version);assertEquals("old-demo",LearningBackupCodec.decode(enc()).data.words[0].source)}
    @Test fun rawRevokedAndNoEventWordsRetained(){val d=LearningBackupCodec.decode(enc()).data;assertEquals(3,d.words.size);assertEquals(2,d.events.count{it.revoked});assertFalse(d.events.any{it.entryKey=="orphan-but-legal"})}
    @Test fun checksumCoversBody(){val b=enc();b[50]=(b[50].toInt() xor 1).toByte();rejected(b)}
    @Test fun checksumDamageRejected(){val b=enc();b[b.lastIndex]=(b.last().toInt() xor 1).toByte();rejected(b)}
    @Test fun badMagicWithValidDigestRejected(){rejected(edit{it[0]=0})}
    @Test fun futureFormatRejected(){rejected(edit{put(it,8,4)})}
    @Test fun oldAndNegativeFormatRejected(){for(v in listOf(0,-1,Int.MIN_VALUE))rejected(edit{put(it,8,v)})}
    @Test fun wrongAppIdentityRejected(){rejected(edit{it[16]='X'.code.toByte()})}
    @Test fun futureSchemaRejected(){rejected(edit{put(it,header-12,4)})}
    @Test fun negativeSchemaRejected(){rejected(edit{put(it,header-12,-1)})}
    @Test fun negativeCountsRejected(){for(p in listOf(header,header+4))rejected(edit{put(it,p,-1)})}
    @Test(timeout=2000) fun hugeCountCannotPreallocateOrLoopUnbounded(){for(p in listOf(header,header+4))rejected(edit{put(it,p,Int.MAX_VALUE)})}
    @Test fun negativeLengthRejected(){rejected(edit{put(it,wordStart,-1)})}
    @Test(timeout=2000) fun maliciousHugeLengthRejectedBeforeAllocation(){rejected(edit{put(it,wordStart,Int.MAX_VALUE)})}
    @Test fun malformedUtf8Rejected(){rejected(edit{it[wordStart+4]=0x80.toByte()})}
    @Test fun everyTruncationRejected(){val b=enc();for(n in 0 until b.size)rejected(b.copyOf(n))}
    @Test fun resignedTruncationRejected(){val body=enc().let{it.copyOf(it.size-32)};for(n in 1 until body.size)rejected(seal(body.copyOf(n)))}
    @Test fun trailingEvenResignedRejected(){val b=enc();rejected(seal(b.copyOf(b.size-32)+byteArrayOf(0)))}
    @Test fun duplicateWordKeyRejected(){rejected(enc(LearningBackupData(listOf(word(),word()),emptyList())))}
    @Test fun duplicateEventIdRejected(){val d=data();rejected(enc(d.copy(events=d.events+ d.events.first())))}
    @Test fun orphanEventRejected(){rejected(enc(data().copy(events=listOf(BackupEvent("bad","missing","","",0,false)))))}
    @Test fun emptyKeyAndHeadwordRejected(){for(w in listOf(word().copy(entryKey=""),word().copy(headword="")))rejected(enc(LearningBackupData(listOf(w),emptyList())))}
    @Test fun mismatchedKeyRejected(){rejected(enc(LearningBackupData(listOf(word().copy(headword="b")),emptyList())))}
    @Test fun emptyEventIdRejected(){rejected(enc(data().copy(events=listOf(BackupEvent("","a","","",0,false)))))}
    @Test fun illegalWordBooleanRejected(){rejected(edit{b->var p=wordStart;repeat(6){p+=4+ByteBuffer.wrap(b,p,4).int};b[p]=2})}
    private fun eventBooleanOffset():Int { val b=enc();var p=wordStart;repeat(3){repeat(6){p+=4+ByteBuffer.wrap(b,p,4).int};p++;p+=4+ByteBuffer.wrap(b,p,4).int};repeat(4){p+=4+ByteBuffer.wrap(b,p,4).int};return p+8 }
    @Test fun illegalEventBooleanRejected(){rejected(edit{it[eventBooleanOffset()]=2})}
    @Test fun unpairedUtf16EncodeRejected(){try{enc(LearningBackupData(listOf(word().copy(note="\uD800")),emptyList()));fail("bad UTF16 accepted")}catch(e:BackupFormatException){assertTrue(e.message!!.contains("UTF-8"))}}
    @Test fun capPlusOneRejected(){rejected(ByteArray(LearningBackupCodec.MAX_FILE_BYTES.toInt()+1))}
    @Test fun exact64MiBBoundaryAccepted(){
        // One valid row's note is extended to put the entire file exactly on the cap; real decoder runs.
        val small=enc(LearningBackupData(listOf(word().copy(note="")),emptyList()));val size=LearningBackupCodec.MAX_FILE_BYTES.toInt();val noteLen=size-small.size
        val bytes=enc(LearningBackupData(listOf(word().copy(note="x".repeat(noteLen))),emptyList()))
        assertEquals(size,bytes.size)
        val decoded=LearningBackupCodec.decode(bytes);assertEquals(noteLen,decoded.data.words.single().note.length)
    }
    @Test fun encodeAboveCapNeverReturnsTruncatedPayload(){try{enc(LearningBackupData(listOf(word().copy(note="x".repeat(LearningBackupCodec.MAX_FILE_BYTES.toInt()))),emptyList()));fail("oversize encoded")}catch(e:BackupFormatException){assertTrue(e.message!!.contains("64MiB"))}}
}
