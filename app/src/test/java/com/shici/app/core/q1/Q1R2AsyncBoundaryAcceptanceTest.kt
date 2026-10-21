package com.shici.app.core.q1

import com.shici.app.core.*
import com.shici.app.core.q1.host.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.nio.ByteBuffer

/** Verbatim R2 members; explicit deterministic Android/MLKit/in-memory repository adapters. */
class Q1R2AsyncBoundaryAcceptanceTest {
 @Before fun reset(){CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;SystemClock.now=1}
 private fun page()=Bitmap(480,640)
 private fun changed()=Bitmap(480,640,IntArray(480*640){0xff444444.toInt()})
 private fun dictionary():RawKajwebDictionary {
  val text="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\nE\tabandon\tv\tsynthetic only\nE\tability\tn\tsynthetic only\n"
  val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(text.toByteArray())};val b=out.toByteArray();val sha=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
  return RawKajwebDictionary.load(b.inputStream(),RawKajwebDictionary.Manifest(sha,2,0))
 }
 private fun fresh(b:Bitmap=page())=CaptureHost().apply{app.dictionary=Result.success(dictionary());start();deliver(b);windows.dispatchReady()}
 private fun stroke(h:CaptureHost,y:Float=200f){val v=h.liveView!!;v.onTouchEvent(MotionEvent(0,100f,y));v.onTouchEvent(MotionEvent(1,200f,y))}
 private fun recognizing(y:Float=200f)=fresh().apply{stroke(this,y);deliver(page())}
 private fun result(y:Int=200):OcrResult{val crop=HighlighterGeometry.selection(listOf(HighlighterStroke(listOf(HighlighterPoint(100f,y.toFloat()),HighlighterPoint(200f,y.toFloat())))),480,640,480,640,1f)!!.crop;return OcrResult(listOf(Block(listOf(Line(OcrRect(90-crop.left,y-10-crop.top,300-crop.left,y+10-crop.top),listOf(Element("abandon",OcrRect(110-crop.left,y-6-crop.top,190-crop.left,y+6-crop.top))))))))}
 private fun success(h:CaptureHost,y:Int=200){h.recognizer!!.tasks.last().succeed(result(y))}
 private fun finish(h:CaptureHost,y:Int=200){success(h,y);h.pump();h.pump()}
 private fun field(h:CaptureHost,n:String):Any?=CaptureHost::class.java.getDeclaredField(n).apply{isAccessible=true}.get(h)
 private fun emit(h:CaptureHost,b:Bitmap=changed(),configure:(Image)->Unit={}):Image{val i=Image(b,System.nanoTime());configure(i);h.reader!!.emit(i);assertTrue(i.closed);return i}
 private fun oldWorkCannotWrite(h:CaptureHost){h.app.worker.all();h.main.all();h.pump();assertEquals(0,h.app.repository.events.size)}
 private fun finishIfStarted(h:CaptureHost){if(h.recognizer!!.tasks.isNotEmpty())finish(h);h.pump();assertEquals(0,h.app.repository.events.size)}
 private fun queuedLift():CaptureHost=fresh().apply{stroke(this);queuePage(page());assertNotNull(requestedFrame.get());assertTrue(main.tasks.isNotEmpty())}
 @Test fun changedFrameAfterOfferBeforeMainDeliveryCannotBeDropped(){val h=queuedLift();emit(h);h.main.all();finishIfStarted(h)}
 @Test fun unreadableFrameAfterOfferBeforeMainDeliveryCannotBeDropped(){val h=queuedLift();emit(h,page()){it.planeThrow=true};h.main.all();finishIfStarted(h)}
 @Test fun missingPlaneAfterOfferBeforeMainDeliveryCannotBeDropped(){val h=queuedLift();emit(h,page()){it.planeMissing=true};h.main.all();finishIfStarted(h)}
 @Test fun zeroTimestampChangedFrameInOfferDeliveryWindowCannotBeDropped(){val h=queuedLift();val i=Image(changed(),0);h.reader!!.emit(i);assertTrue(i.closed);h.main.all();finishIfStarted(h)}
 @Test fun samePageFrameInOfferDeliveryWindowStillSavesControl(){val h=queuedLift();emit(h,page());h.main.all();finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun sourceOlderThanAcceptedLiftStillSavesControl(){val h=queuedLift();val t=h.requestedFrame.get()!!.acceptedTimestampNanos;h.reader!!.emit(Image(changed(),t-1));h.main.all();finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun changedFrameWhilePendingCardMountsCannotWrite(){val h=fresh();stroke(h);var injected=false;h.windows.beforeAdd={injected=true;h.windows.beforeAdd=null;emit(h)};h.deliver(page());assertTrue(injected);finishIfStarted(h)}
 @Test fun unreadableFrameWhilePendingCardMountsCannotWrite(){val h=fresh();stroke(h);h.windows.beforeAdd={h.windows.beforeAdd=null;emit(h,page()){it.planeThrow=true}};h.deliver(page());finishIfStarted(h)}
 @Test fun changedFrameFromOtherThreadWhilePendingCardMountsCannotWrite(){val h=fresh();stroke(h);h.windows.beforeAdd={h.windows.beforeAdd=null;val t=Thread{emit(h)};t.start();t.join()};h.deliver(page());finishIfStarted(h)}
 @Test fun ownPendingCardImageWhileMountingStillSavesControl(){val h=fresh();stroke(h);h.windows.beforeAdd={h.windows.beforeAdd=null;val b=page();for(y in 240..399)for(x in 40..439)b.pixels[y*480+x]=0xff222222.toInt();emit(h,b)};h.deliver(page());finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun noNewImageIsNotAReadFailureControl(){val h=recognizing();h.reader!!.listener!!(h.reader!!);finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun dimensionMismatchNewFrameCannotKeepOldPageAuthority(){val h=recognizing();val i=Image(Bitmap(479,640),System.nanoTime());h.reader!!.emit(i);assertTrue(i.closed);finish(h);assertEquals("new source geometry is uncertain",0,h.app.repository.events.size)}
 @Test fun invalidPixelGeometryAtActualRecordAdmissionRejects(){val h=recognizing();h.app.repository.beforeAdmission={val b=page();for(y in 194..206)for(x in 130..190)b.pixels[y*480+x]=0xff000000.toInt();emit(h,b){it.pixelStrideOverride=1}};finish(h);assertEquals("invalid RGBA layout cannot authorize write",0,h.app.repository.events.size)}
 @Test fun invalidRowGeometryAtActualRecordAdmissionRejects(){val h=recognizing();h.app.repository.beforeAdmission={val b=page();for(y in 194..206)for(x in 130..190)b.pixels[y*480+x]=0xff000000.toInt();emit(h,b){it.rowStrideOverride=4}};finish(h);assertEquals("overlapping rows cannot authorize write",0,h.app.repository.events.size)}
 @Test fun negativeTimestampChangedFrameInvalidates(){val h=recognizing();h.reader!!.emit(Image(changed(),-1));finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun invalidTimestampUnreadableFrameInvalidates(){val h=recognizing();h.reader!!.emit(Image(page(),0).apply{planeThrow=true});finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun exactLiftTimestampChangedFrameInvalidates(){val h=recognizing();val t=field(h,"pageGuardSourceNanos") as Long;h.reader!!.emit(Image(changed(),t));finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun ownCardEdgeOutsideOnePixelCannotBeMasked(){val h=recognizing();val box=(field(h,"pageOverlayBoxes") as List<PixelBox>).single();val b=page();b.pixels[(box.top-1)*480+110]=0xff000000.toInt();emit(h,b);finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun identityReleasedAfterSuccessfulSave(){val h=recognizing();finish(h);assertEquals(1,h.app.repository.events.size);for(n in listOf("live","pageGuard","pendingPageBaseline","pendingPageBody","pageGuardTimeout"))assertNull(n,field(h,n));assertEquals(0L,field(h,"pageGuardSourceNanos"));assertEquals(0L,field(h,"pendingPageSourceNanos"))}
 @Test fun identityReleasedAfterCancel(){val h=recognizing();h.close();for(n in listOf("live","pageGuard","pendingPageBaseline","pendingPageBody","pageGuardTimeout"))assertNull(n,field(h,n));finishIfStarted(h)}
 @Test fun identityReleasedAfterStop(){val h=recognizing();val task=h.recognizer!!.tasks.single();val r=result();h.stop();for(n in listOf("live","pageGuard","pendingPageBaseline","pendingPageBody","pageGuardTimeout"))assertNull(n,field(h,n));task.succeed(r);h.pump();assertTrue(h.app.repository.events.isEmpty())}
 @Test fun identityReleasedAfterResize(){val h=recognizing();h.resizeTo(640,480);for(n in listOf("live","pageGuard","pendingPageBaseline","pendingPageBody","pageGuardTimeout"))assertNull(n,field(h,n));finishIfStarted(h)}
 @Test fun observedPageChangeReleasesGuardAndStillClosesImage(){val h=recognizing();val i=emit(h);assertTrue(i.closed);h.main.all();assertNull(field(h,"pageGuard"));assertNull(field(h,"pageGuardTimeout"));finishIfStarted(h)}
 @Test fun acquireExceptionIsNotNoNewImageAtAdmission(){val h=recognizing();h.app.repository.beforeAdmission={h.reader!!.acquireFailure=true;h.reader!!.listener!!(h.reader!!)};finish(h);assertEquals("acquisition failure must not preserve old authority",0,h.app.repository.events.size)}
}
