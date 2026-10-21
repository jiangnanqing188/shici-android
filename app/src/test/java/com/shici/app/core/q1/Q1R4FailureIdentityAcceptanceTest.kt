package com.shici.app.core.q1

import com.shici.app.core.*
import com.shici.app.core.q1.host.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** R4 exact newReader -> offered frame -> main -> guard -> OCR/record chain.
 * The Bitmap pixel-read fault is explicit synthetic injection, not a native Android claim.
 * Queues remain FIFO: cancel/reopen happens before the old posted failure is delivered.
 */
class Q1R4FailureIdentityAcceptanceTest {
 @Before fun reset(){CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;SystemClock.now=1}
 private fun page()=Bitmap(480,640)
 private fun dictionary():RawKajwebDictionary {
  val text="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\nE\tabandon\tv\tsynthetic only\n"
  val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(text.toByteArray())};val b=out.toByteArray();val sha=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
  return RawKajwebDictionary.load(b.inputStream(),RawKajwebDictionary.Manifest(sha,1,0))
 }
 private fun host()=CaptureHost().apply{app.dictionary=Result.success(dictionary());start()}
 private fun fresh()=host().apply{deliver(page());windows.dispatchReady()}
 private fun stroke(h:CaptureHost){val v=h.liveView!!;v.onTouchEvent(MotionEvent(0,100f,200f));v.onTouchEvent(MotionEvent(1,200f,200f))}
 private fun verifying()=fresh().apply{stroke(this)}
 private fun queuedLift()=verifying().apply{queuePage(page());assertTrue(requestedFrame.get()!!.isAwaitingDelivery())}
 private fun field(h:CaptureHost,n:String):Any?=CaptureHost::class.java.getDeclaredField(n).apply{isAccessible=true}.get(h)
 private fun epoch(h:CaptureHost)=field(h,"lookupEpoch") as Long
 private fun result():OcrResult{val crop=HighlighterGeometry.selection(listOf(HighlighterStroke(listOf(HighlighterPoint(100f,200f),HighlighterPoint(200f,200f)))),480,640,480,640,1f)!!.crop;return OcrResult(listOf(Block(listOf(Line(OcrRect(90-crop.left,190-crop.top,300-crop.left,210-crop.top),listOf(Element("abandon",OcrRect(110-crop.left,194-crop.top,190-crop.left,206-crop.top))))))))}
 private fun finish(h:CaptureHost){h.recognizer!!.tasks.last().succeed(result());repeat(4){h.pump()};assertEquals(1,h.app.repository.events.size)}
 private fun sampleFailure(h:CaptureHost){
  val request=h.requestedFrame.get()!!;val b=page().apply{failReadAfter=1};val i=h.queuePage(b)
  assertTrue("FrameContent read succeeds, fingerprint read fails",b.pixelReads>=2)
  assertTrue(i.closed);assertTrue(b.recycled);assertNull(h.requestedFrame.get())
  assertFalse(h.gate.accepts(request.token));assertFalse(request.isAwaitingDelivery())
  assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.app.repository.events.isEmpty())
 }
 private fun fail(h:CaptureHost,kind:String){when(kind){
  "sample"->sampleFailure(h)
  "geometry"->{val i=h.queuePage(Bitmap(479,640));assertTrue(i.closed)}
  "acquire"->{val r=h.reader!!;r.acquireFailure=true;r.listener!!(r);r.acquireFailure=false}
  "gap"->{h.queuePage(Bitmap(480,640,IntArray(480*640){0xff444444.toInt()}))}
  else->error(kind)
 };assertNull(h.requestedFrame.get());assertTrue(h.app.repository.events.isEmpty())}
 private fun reopenAndFinish(h:CaptureHost){
  val before=epoch(h);h.cancel();assertTrue(epoch(h)>before);h.start();assertTrue(epoch(h)>before+1)
  val r=h.requestedFrame.get()!!;h.queuePage(page());h.main.all();h.windows.dispatchReady()
  assertTrue("old failure must not invalidate the new token",h.gate.accepts(r.token));assertNotNull(h.liveView)
  stroke(h);h.deliver(page());assertNotNull(field(h,"pageGuard"));finish(h)
 }
 @Test fun entrySamplingFailureImmediatelyClosesAuthority(){val h=host();sampleFailure(h);h.main.all();assertNull(h.liveView);assertTrue(h.nodes().any{it.text.contains("无法读取当前页面")})}
 @Test fun verifySamplingFailureImmediatelyClosesAuthority(){val h=verifying();sampleFailure(h);h.main.all();assertNull(h.live);assertNull(field(h,"pendingPageBaseline"));assertTrue(h.nodes().any{it.text.contains("无法读取抬笔")})}
 @Test fun queuedEntrySamplingFailureCannotCancelReopenedLookup(){val h=host();sampleFailure(h);reopenAndFinish(h)}
 @Test fun queuedVerifySamplingFailureCannotCancelReopenedLookup(){val h=verifying();sampleFailure(h);reopenAndFinish(h)}
 @Test fun queuedEntryAcquisitionFailureCannotCancelReopenedLookup(){val h=host();val r=h.queuePage(page());assertTrue(r.closed);fail(h,"acquire");reopenAndFinish(h)}
 @Test fun queuedEntryGeometryFailureCannotCancelReopenedLookup(){val h=host();fail(h,"geometry");reopenAndFinish(h)}
 private fun stopBeforeFailureUi(kind:String){
  val h=if(kind=="gap")queuedLift() else verifying();val request=h.requestedFrame.get()!!;val recognizer=h.recognizer!!;fail(h,kind)
  assertFalse(h.gate.accepts(request.token));h.stop();h.frames.all();h.main.all()
  assertTrue(h.stopped);assertNull(h.liveView);assertNull(h.panel);assertNull(h.bubble);assertTrue(h.windows.views.isEmpty())
  assertTrue(recognizer.closed);assertNull(h.recognizer);assertTrue(recognizer.tasks.isEmpty());assertTrue(h.app.repository.events.isEmpty())
 }
 @Test fun queuedAcquisitionFailureCannotRebuildUiAfterStop(){stopBeforeFailureUi("acquire")}
 @Test fun queuedGeometryFailureCannotRebuildUiAfterStop(){stopBeforeFailureUi("geometry")}
 @Test fun queuedSamplingFailureCannotRebuildUiAfterStop(){stopBeforeFailureUi("sample")}
 @Test fun queuedChangedGapFailureCannotRebuildUiAfterStop(){stopBeforeFailureUi("gap")}
 @Test fun cancelWithoutRestartDoesNotShowQueuedFailure(){val h=verifying();sampleFailure(h);val e=epoch(h);h.cancel();assertTrue(epoch(h)>e);val bubble=h.bubble;h.main.all();assertSame(bubble,h.bubble);assertNull(h.panel);assertNull(h.liveView);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun dismissWithoutRestartDoesNotShowQueuedFailure(){val h=verifying();sampleFailure(h);val e=epoch(h);h.close();assertTrue(epoch(h)>e);h.main.all();assertNull(h.panel);assertNull(h.liveView);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun generationChangedBeforeFailureUiCannotRebuildOldUi(){val h=host();sampleFailure(h);h.generationChange();h.main.all();assertNull(h.panel);assertNull(h.liveView);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun inactiveSessionBeforeFailureUiCannotRebuildOldUi(){val h=host();sampleFailure(h);CaptureStatus.state.phase=CapturePhase.STOPPING;h.main.all();assertNull(h.panel);assertNull(h.liveView);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun explicitReopenAfterCurrentGeometryFailureCanStillSucceed(){val h=host();fail(h,"geometry");h.main.all();assertTrue(h.nodes().any{it.text.contains("画面尺寸异常")});reopenAndFinish(h)}
 @Test fun nullImageRemainsNeutralForNewRequestAfterOldFailure(){val h=host();sampleFailure(h);h.cancel();h.start();val r=h.requestedFrame.get()!!;val reader=h.reader!!;reader.listener!!(reader);h.main.all();assertSame(r,h.requestedFrame.get());assertTrue(h.gate.accepts(r.token));h.deliver(page());h.windows.dispatchReady();stroke(h);h.deliver(page());finish(h)}
 // A real monitor on the production request freezes cancel() AFTER compareAndSet removed it.
 // This is a bounded scheduling barrier, not a second concurrent reader callback. The new entry
 // is only processed after the old capture callback exits, respecting the production frames Handler.
 private fun oldCaptureCancellationMustNotAdvanceNewToken(geometry:Boolean){
  val h=queuedLift();val old=h.requestedFrame.get()!!;val errors=AtomicReference<Throwable?>(null)
  lateinit var capture:Thread
  lateinit var newer:FreshFrameRequest
  synchronized(old){
   capture=Thread{try{
    if(geometry){val image=h.queuePage(Bitmap(479,640));assertTrue(image.closed)}
    else{val reader=h.reader!!;reader.acquireFailure=true;try{reader.listener!!(reader)}finally{reader.acquireFailure=false}}
   }catch(t:Throwable){errors.set(t)}}
   capture.start()
   val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
   while(h.requestedFrame.get()===old && capture.isAlive && System.nanoTime()<deadline)Thread.yield()
   assertNull("old request removed before production cancel() monitor barrier",h.requestedFrame.get())
   assertTrue(capture.isAlive)
   h.cancel();h.start();newer=h.requestedFrame.get()!!
   assertTrue("replacement token is current before old cancellation continues",h.gate.accepts(newer.token))
  }
  capture.join(5000);assertFalse(capture.isAlive);assertNull(errors.get())
  assertFalse(old.isAwaitingDelivery())
  assertTrue("old capture-thread gate.next must not invalidate the replacement token",h.gate.accepts(newer.token))
  h.deliver(page());h.windows.dispatchReady();assertNotNull(h.liveView);stroke(h);h.deliver(page());finish(h)
 }
 @Test fun oldCaptureAcquireCancellationCannotInvalidateReplacementToken(){oldCaptureCancellationMustNotAdvanceNewToken(false)}
 @Test fun oldCaptureGeometryCancellationCannotInvalidateReplacementToken(){oldCaptureCancellationMustNotAdvanceNewToken(true)}

}
