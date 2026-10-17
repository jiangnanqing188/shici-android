package com.shici.app.core.b4
import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest
import java.util.zip.*
class B4StudyBooksAcceptanceTest {
 private val base=File(System.getProperty("b4.base"));private val asset=File(System.getProperty("b4.source",File(base,"source-g3").path),"app/src/main/assets/dictionary/study-books.tsv.bin").readBytes()
 private val hash=MessageDigest.getInstance("SHA-256").digest(asset).joinToString(""){"%02x".format(it.toInt() and 255)}
 private fun manifest(h:String=hash)=StudyBooks.Manifest(h,mapOf("ky" to 4533,"cet6" to 2078))
 private fun load(bytes:ByteArray=asset,m:StudyBooks.Manifest=manifest())=StudyBooks.load(bytes.inputStream(),m)
 private fun reject(bytes:ByteArray=asset,m:StudyBooks.Manifest=manifest()){try{load(bytes,m);fail("invalid asset accepted")}catch(e:Exception){if(e is AssertionError)throw e}}
 private fun compressed(text:String):ByteArray{val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(text.toByteArray())};return out.toByteArray()}
 private fun rejectText(text:String){val b=compressed(text);val h=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)};reject(b,manifest(h))}
 private fun payload()=GZIPInputStream(asset.inputStream()).bufferedReader().readText()
 @Test fun actualLoaderFullOriginalKyMembers(){assertEquals(File(base,"sol-tools-g3/expected-ky.txt").readLines(),load().orderedMembers(StudyBook.KAOYAN))}
 @Test fun actualLoaderFullOriginalCet6Members(){assertEquals(File(base,"sol-tools-g3/expected-cet6.txt").readLines(),load().orderedMembers(StudyBook.CET6))}
 @Test fun personalIsRuntimeNotIndex(){assertNull(load().orderedMembers(StudyBook.PERSONAL));assertEquals(0,load().count(StudyBook.PERSONAL))}
 @Test fun badHashRejected(){reject(m=manifest("0".repeat(64)))}
 @Test fun incorrectSourceRejected(){reject(m=manifest().copy(source="reviewed"))}
 @Test fun incorrectVersionRejected(){reject(m=manifest().copy(version="future"))}
 @Test fun incorrectCountDeclarationRejected(){reject(m=manifest().copy(bookCounts=mapOf("ky" to 4532,"cet6" to 2078)))}
 @Test fun badGzipRejected(){reject(byteArrayOf(1,2,3))}
 @Test fun duplicateMemberRejected(){val p=payload();val first=p.lineSequence().first{it.startsWith("WORD")};rejectText(p.replaceFirst(first,first+"\n"+first))}
 @Test fun unknownBookRejected(){rejectText(payload().replace("BOOK\tky\t","BOOK\tcet4\t"))}
 @Test fun missingFixedBookRejected(){rejectText(payload().substringBefore("BOOK\tcet6\t"))}
 @Test fun malformedRowRejected(){rejectText(payload()+"INVALID\n")}
}
