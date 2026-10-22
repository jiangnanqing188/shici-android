package com.shici.app.core.u1
import com.shici.app.core.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
object U1TestAssets {
 fun books():StudyBooks {val b=File(System.getProperty("b4.source"),"app/src/main/assets/"+StudyBooks.ASSET).readBytes();return StudyBooks.load(b.inputStream(),StudyBooks.Manifest(hash(b),mapOf("ky" to 4533,"cet6" to 2078)))}
 fun dictionary():RawKajwebDictionary {val s="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\nE\talpha\tn.\t原始义\nE\tabandon\tv.\t放弃\n";val o=ByteArrayOutputStream();GZIPOutputStream(o).use{it.write(s.toByteArray())};val b=o.toByteArray();return RawKajwebDictionary.load(b.inputStream(),RawKajwebDictionary.Manifest(hash(b),2,0))}
 fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
}
