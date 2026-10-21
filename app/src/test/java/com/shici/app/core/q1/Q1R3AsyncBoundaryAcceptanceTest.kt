package com.shici.app.core.q1

import com.shici.app.core.*
import com.shici.app.core.q1.host.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exact R3 service members; explicit Image/queue/MLKit/repository adapters, never Android runtime. */
class Q1R3AsyncBoundaryAcceptanceTest {
 @Before fun reset(){CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;SystemClock.now=1}
 private fun page()=Bitmap(480,640)
 private fun changed()=Bitmap(480,640,IntArray(480*640){0xff444444.toInt()})
 private fun dictionary():RawKajwebDictionary {
  val text="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\nE\tabandon\tv\tsynthetic only\nE\tability\tn\tsynthetic only\n"
  val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(text.toByteArray())};val b=out.toByteArray();val sha=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
  return RawKajwebDictionary.load(b.inputStream(),RawKajwebDictionary.Manifest(sha,2,0))
 }
 private fun fresh()=CaptureHost().apply{app.dictionary=Result.success(dictionary());start();deliver(page());windows.dispatchReady()}
 private fun stroke(h:CaptureHost){val v=h.liveView!!;v.onTouchEvent(MotionEvent(0,100f,200f));v.onTouchEvent(MotionEvent(1,200f,200f))}
 private fun recognizing()=fresh().apply{stroke(this);deliver(page())}
 private fun result():OcrResult{val crop=HighlighterGeometry.selection(listOf(HighlighterStroke(listOf(HighlighterPoint(100f,200f),HighlighterPoint(200f,200f)))),480,640,480,640,1f)!!.crop;return OcrResult(listOf(Block(listOf(Line(OcrRect(90-crop.left,190-crop.top,300-crop.left,210-crop.top),listOf(Element("abandon",OcrRect(110-crop.left,194-crop.top,190-crop.left,206-crop.top))))))))}
 private fun finish(h:CaptureHost){if(h.recognizer!!.tasks.isNotEmpty())h.recognizer!!.tasks.last().succeed(result());repeat(4){h.pump()}}
 private fun failure(h:CaptureHost,geometry:Boolean){val r=h.reader!!;if(geometry){val i=Image(Bitmap(479,640),System.nanoTime());r.emit(i);assertTrue(i.closed)}else{r.acquireFailure=true;r.listener!!(r);r.acquireFailure=false}}
 private fun queuedLift()=fresh().apply{stroke(this);queuePage(page());assertTrue(requestedFrame.get()!!.isAwaitingDelivery())}
 private fun field(h:CaptureHost,n:String):Any?=CaptureHost::class.java.getDeclaredField(n).apply{isAccessible=true}.get(h)
 private fun replaceReader(h:CaptureHost):ImageReader {val m=CaptureHost::class.java.getDeclaredMethod("newReader",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true};return (m.invoke(h,480,640) as ImageReader).also{h.reader=it}}
 private fun restart(h:CaptureHost){h.cancel();h.start();val newer=h.requestedFrame.get()!!;assertTrue(h.gate.accepts(newer.token));h.queuePage(page());h.main.all();h.windows.dispatchReady();assertTrue("old callback invalidated replacement token ${newer.token}",h.gate.accepts(newer.token));assertNotNull("new entry must remain usable",h.liveView);stroke(h);h.deliver(page());finish(h)}
 @Test fun acquireDuringAcceptedOfferInvalidatesBeforeMain(){val h=queuedLift();failure(h,false);assertNull(h.requestedFrame.get());h.main.all();finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun geometryDuringAcceptedOfferInvalidatesBeforeMain(){val h=queuedLift();failure(h,true);assertNull(h.requestedFrame.get());h.main.all();finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun acquireWhilePendingCardMountsRejectsBeforeOcr(){val h=fresh();stroke(h);h.windows.beforeAdd={h.windows.beforeAdd=null;failure(h,false)};h.deliver(page());finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun geometryWhilePendingCardMountsRejectsBeforeOcr(){val h=fresh();stroke(h);h.windows.beforeAdd={h.windows.beforeAdd=null;failure(h,true)};h.deliver(page());finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun acquireOtherThreadAtActualRecordAdmissionClosesGate(){val h=recognizing();h.app.repository.beforeAdmission={val t=Thread{failure(h,false)};t.start();t.join()};finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun geometryOtherThreadAtActualRecordAdmissionClosesGate(){val h=recognizing();h.app.repository.beforeAdmission={val t=Thread{failure(h,true)};t.start();t.join()};finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun nullAfterOfferKeepsSuccessfulQuery(){val h=queuedLift();h.reader!!.listener!!(h.reader!!);h.main.all();finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun nullAtActualRecordAdmissionKeepsSuccessfulQuery(){val h=recognizing();h.app.repository.beforeAdmission={h.reader!!.listener!!(h.reader!!)};finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun staleReaderAcquireFailureCannotKillCurrentGuard(){val h=recognizing();val old=h.reader!!;replaceReader(h);old.acquireFailure=true;old.listener!!(old);finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun staleReaderGeometryCannotKillCurrentGuard(){val h=recognizing();val old=h.reader!!;replaceReader(h);val i=Image(Bitmap(479,640),System.nanoTime());old.emit(i);assertTrue(i.closed);finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun staleGenerationAcquireCallbackCannotChangeNewService(){val old=recognizing();CaptureStatus.generation++;val current=CaptureHost().apply{sessionGeneration=CaptureStatus.generation;app.dictionary=Result.success(dictionary());start();deliver(page());windows.dispatchReady()};stroke(current);current.deliver(page());failure(old,false);finish(old);finish(current);assertTrue(old.app.repository.events.isEmpty());assertEquals(1,current.app.repository.events.size)}
 @Test fun staleGenerationGeometryCallbackCannotChangeNewService(){val old=recognizing();CaptureStatus.generation++;val current=CaptureHost().apply{sessionGeneration=CaptureStatus.generation;app.dictionary=Result.success(dictionary());start();deliver(page());windows.dispatchReady()};stroke(current);current.deliver(page());failure(old,true);finish(old);finish(current);assertTrue(old.app.repository.events.isEmpty());assertEquals(1,current.app.repository.events.size)}
 @Test fun acquiredFailureAfterAtomicCommitDoesNotUndoEvent(){val h=recognizing();h.recognizer!!.tasks.last().succeed(result());h.pump();assertEquals(1,h.app.worker.tasks.size);h.app.worker.one();assertEquals(1,h.app.repository.events.size);failure(h,false);h.main.all();assertEquals(1,h.app.repository.events.size)}
 @Test fun geometryFailureAfterAtomicCommitDoesNotUndoEvent(){val h=recognizing();h.recognizer!!.tasks.last().succeed(result());h.pump();assertEquals(1,h.app.worker.tasks.size);h.app.worker.one();assertEquals(1,h.app.repository.events.size);failure(h,true);h.main.all();assertEquals(1,h.app.repository.events.size)}
 @Test fun queuedOldGuardAcquireUiCallbackCannotKillRestart(){val h=recognizing();failure(h,false);restart(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun queuedOldGuardGeometryUiCallbackCannotKillRestart(){val h=recognizing();failure(h,true);restart(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun queuedOldRequestAcquireUiCallbackCannotKillRestart(){val h=queuedLift();failure(h,false);restart(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun queuedOldRequestGeometryUiCallbackCannotKillRestart(){val h=queuedLift();failure(h,true);restart(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun queuedChangedGapUiCallbackCannotKillRestart(){val h=queuedLift();h.reader!!.emit(Image(changed(),System.nanoTime()));restart(h);assertEquals(1,h.app.repository.events.size)}
 private fun interleavedMount(unreadable:Boolean,changedFrame:Boolean){val h=queuedLift();val b=if(changedFrame)changed() else page();val i=Image(b,System.nanoTime());var fired=false;i.beforePlaneRead={i.beforePlaneRead=null;fired=true;h.main.all();if(unreadable)i.planeThrow=true};h.reader!!.emit(i);assertTrue(fired);assertTrue(i.closed);finish(h);assertEquals(if(unreadable||changedFrame)0 else 1,h.app.repository.events.size)}
 @Test fun changedObservationStartedBeforeMountFinishesAgainstNewGuard(){interleavedMount(false,true)}
 @Test fun unreadableObservationStartedBeforeMountFinishesAgainstNewGuard(){interleavedMount(true,false)}
 @Test fun sameObservationStartedBeforeMountStillAllowsSuccess(){interleavedMount(false,false)}
 private fun oldSourceCrossingReplacement(noTimestamp:Boolean){
  val h=recognizing();val old=h.reader!!;val i=Image(changed(),if(noTimestamp)0 else System.nanoTime())
  val sourceChecked=CountDownLatch(1);val proceed=CountDownLatch(1);val failure=AtomicReference<Throwable?>(null)
  i.beforeWidthRead={i.beforeWidthRead=null;sourceChecked.countDown();check(proceed.await(5,TimeUnit.SECONDS))}
  val t=Thread{try{old.emit(i)}catch(e:Throwable){failure.set(e)}};t.start()
  try{
   assertTrue("capture thread reached image.width after source identity check",sourceChecked.await(5,TimeUnit.SECONDS))
   // Both readers dispatch to the same production `frames` Handler: main can replace/start,
   // but the new reader cannot process an entry/lift while this old callback is still blocked.
   h.close();replaceReader(h);h.start()
   assertNotNull(h.requestedFrame.get());assertNull(h.liveView)
  }finally{proceed.countDown();t.join(5000)}
  assertFalse(t.isAlive);assertNull(failure.get());assertTrue(i.closed)
  h.deliver(page());h.windows.dispatchReady();stroke(h);h.deliver(page())
  assertEquals("old OCR plus current OCR both exist",2,h.recognizer!!.tasks.size);finish(h)
  assertEquals("late old reader frame must not invalidate replacement query",1,h.app.repository.events.size)
 }
 @Test fun callbackAlreadyPastSourceCheckCannotKillReplacementQuery(){oldSourceCrossingReplacement(true)}
 @Test fun oldTimestampAfterSourceReplacementIsNeutralControl(){oldSourceCrossingReplacement(false)}
 @Test fun currentGeometryFailureBeforeFirstEntryCancelsWithoutOcrOrWrites(){val h=CaptureHost();h.start();h.queuePage(page());failure(h,true);h.main.all();assertNull(h.requestedFrame.get());assertNull(h.liveView);assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.app.repository.events.isEmpty())}

 @Test fun failedOfferReleasesAcceptedIdentityAndBitmap(){val h=queuedLift();val r=h.requestedFrame.get()!!;failure(h,false);h.main.all();for(n in listOf("pendingPageBaseline","pendingPageBody","pageGuard","live"))assertNull(n,field(h,n));assertFalse(r.isAwaitingDelivery());assertTrue(h.recognizer!!.tasks.isEmpty())}
}
