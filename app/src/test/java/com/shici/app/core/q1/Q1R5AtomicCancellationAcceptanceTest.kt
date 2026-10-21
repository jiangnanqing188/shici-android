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

/** Real JVM capture/main threads, accurate R5 service methods, explicit synthetic frame/queue/MLKit.
 * A pixel/plane-read latch makes the real request-monitor barrier reachable AFTER canInspect or
 * isAwaitingDelivery. It does not alter production synchronization, gate, cancellation or IO order.
 * No second ImageReader callback is run until the first callback has exited.
 */
class Q1R5AtomicCancellationAcceptanceTest {
 @Before fun reset(){CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;SystemClock.now=1}
 private fun page()=Bitmap(480,640)
 private fun dictionary():RawKajwebDictionary {
  val text="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\nE\tabandon\tv\tsynthetic only\n"
  val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(text.toByteArray())};val b=out.toByteArray();val sha=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it.toInt() and 255)}
  return RawKajwebDictionary.load(b.inputStream(),RawKajwebDictionary.Manifest(sha,1,0))
 }
 private fun host()=CaptureHost().apply{app.dictionary=Result.success(dictionary());reader=CaptureHost::class.java.getDeclaredMethod("newReader",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}.invoke(this,480,640) as ImageReader;start()}
 private fun stroke(h:CaptureHost){val v=h.liveView!!;v.onTouchEvent(MotionEvent(0,100f,200f));v.onTouchEvent(MotionEvent(1,200f,200f))}
 private fun verifying()=host().apply{deliver(page());windows.dispatchReady();stroke(this)}
 private fun field(h:CaptureHost,n:String):Any?=CaptureHost::class.java.getDeclaredField(n).apply{isAccessible=true}.get(h)
 private fun result():OcrResult{val crop=HighlighterGeometry.selection(listOf(HighlighterStroke(listOf(HighlighterPoint(100f,200f),HighlighterPoint(200f,200f)))),480,640,480,640,1f)!!.crop;return OcrResult(listOf(Block(listOf(Line(OcrRect(90-crop.left,190-crop.top,300-crop.left,210-crop.top),listOf(Element("abandon",OcrRect(110-crop.left,194-crop.top,190-crop.left,206-crop.top))))))))}
 private fun finishReplacement(h:CaptureHost,newer:FreshFrameRequest){
  assertTrue("new token current after old cleanup",h.gate.accepts(newer.token));assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.app.repository.events.isEmpty())
  h.deliver(page());h.windows.dispatchReady();assertNotNull(h.liveView);stroke(h);h.deliver(page());assertNotNull(field(h,"pageGuard"));assertEquals(1,h.recognizer!!.tasks.size)
  h.recognizer!!.tasks.single().succeed(result());repeat(4){h.pump()};assertEquals(1,h.app.repository.events.size)
 }
 private fun boundedJoin(t:Thread){t.join(5000);assertFalse("thread finished within bound, no pageLock/request monitor inversion",t.isAlive)}
 private fun cancellation(kind:String,entry:Boolean=false,timeoutReplacement:Boolean=false){
  val gap=kind.startsWith("gap");val h=if(entry)host() else verifying()
  if(gap){h.queuePage(page());assertTrue(h.requestedFrame.get()!!.isAwaitingDelivery())}
  val old=h.requestedFrame.get()!!;val oldTimeout=h.frameTimeout!!;val reader=h.reader!!
  val atRead=CountDownLatch(1);val continueRead=CountDownLatch(1);val captureErrors=AtomicReference<Throwable?>(null);val uiErrors=AtomicReference<Throwable?>(null)
  val b=if(gap)Bitmap(480,640,IntArray(480*640){0xff444444.toInt()}) else page().apply{failReadAfter=1}
  val image=Image(b,System.nanoTime())
  val pause={atRead.countDown();check(continueRead.await(5,TimeUnit.SECONDS)){"read scheduling barrier timed out"}}
  if(gap)image.beforePlaneRead=pause else b.beforePixelRead={if(it==2)pause()}
  if(kind=="gap-plane-fault")image.planeThrow=true
  val capture=Thread({try{reader.emit(image)}catch(t:Throwable){captureErrors.set(t)}},"q1-r5-capture")
  capture.start();assertTrue("real reader reached pixel/plane inspection",atRead.await(5,TimeUnit.SECONDS))
  val newerRef=AtomicReference<FreshFrameRequest?>(null);val timeoutRef=AtomicReference<Runnable?>(null)
  try{
   synchronized(old){
    continueRead.countDown()
    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
    // request removal is visible before cancel; wait until the real callback is blocked on its
    // request monitor. At that point the new R5 gate/timeout/pending ownership step has completed.
    while((h.requestedFrame.get()===old || capture.state!=Thread.State.BLOCKED) && capture.isAlive && System.nanoTime()<deadline)Thread.yield()
    assertNull("old request detached",h.requestedFrame.get());assertEquals(Thread.State.BLOCKED,capture.state)
    assertFalse("old gate closed before blocking cleanup",h.gate.accepts(old.token))
    assertNull("old timeout detached before blocking cleanup",h.frameTimeout)
    assertNull(field(h,"pendingPageBaseline"));assertNull(field(h,"pendingPageBody"));assertEquals(0L,field(h,"pendingPageSourceNanos"))
    val ui=Thread({try{h.cancel();h.start();newerRef.set(h.requestedFrame.get());timeoutRef.set(h.frameTimeout)}catch(t:Throwable){uiErrors.set(t)}},"q1-r5-ui")
    ui.start();boundedJoin(ui);assertNull(uiErrors.get())
    val newer=newerRef.get()!!;assertTrue("main published without waiting on old monitor",h.gate.accepts(newer.token));assertNotSame(oldTimeout,timeoutRef.get());assertTrue(h.main.delayed.contains(timeoutRef.get()))
    assertTrue("capture still frozen at old cancel",capture.isAlive)
   }
  }finally{continueRead.countDown();capture.join(5000)}
  boundedJoin(capture);assertNull(captureErrors.get());assertTrue(image.closed);assertFalse(old.isAwaitingDelivery())
  if(!gap)assertTrue(b.recycled)
  val newer=newerRef.get()!!;val timeout=timeoutRef.get()!!
  assertSame("old callback did not clear new timeout",timeout,h.frameTimeout);assertTrue(h.main.delayed.contains(timeout));assertFalse(h.main.delayed.contains(oldTimeout))
  oldTimeout.run();assertSame("old runnable cannot expire replacement",newer,h.requestedFrame.get());assertSame(timeout,h.frameTimeout)
  h.main.all();assertSame("queued old notifications cannot kill replacement",newer,h.requestedFrame.get());assertTrue(h.gate.accepts(newer.token));assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.app.repository.events.isEmpty())
  if(timeoutReplacement){timeout.run();assertNull(h.requestedFrame.get());assertFalse(h.gate.accepts(newer.token));assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.app.repository.events.isEmpty())}
  else finishReplacement(h,newer)
 }
 @Test fun entryIdentityFailureCannotClearReplacementTimeoutOrToken(){cancellation("sample",entry=true)}
 @Test fun verifyIdentityFailureCannotClearReplacementTimeoutOrToken(){cancellation("sample")}
 @Test fun changedAwaitingGapCannotClearReplacementTimeoutOrToken(){cancellation("gap-changed")}
 @Test fun unreadableAwaitingGapCannotClearReplacementTimeoutOrToken(){cancellation("gap-plane-fault")}
 @Test fun entryIdentityFailureLeavesReplacementTimeoutFunctional(){cancellation("sample",entry=true,timeoutReplacement=true)}
 @Test fun verifyIdentityFailureLeavesReplacementTimeoutFunctional(){cancellation("sample",timeoutReplacement=true)}
 @Test fun changedAwaitingGapLeavesReplacementTimeoutFunctional(){cancellation("gap-changed",timeoutReplacement=true)}
 @Test fun unreadableAwaitingGapLeavesReplacementTimeoutFunctional(){cancellation("gap-plane-fault",timeoutReplacement=true)}
 // Pause in actual pixel/plane IO before cancellation, publish the replacement on the UI thread,
 // then resume the OLD callback. The old ownership check must lose without touching new state.
 private fun replacementWinsBeforeFailure(kind:String,entry:Boolean=false){
  val gap=kind.startsWith("gap");val h=if(entry)host() else verifying()
  if(gap)h.queuePage(page())
  val old=h.requestedFrame.get()!!;val arrived=CountDownLatch(1);val resume=CountDownLatch(1);val errors=AtomicReference<Throwable?>(null)
  val pause={arrived.countDown();check(resume.await(5,TimeUnit.SECONDS)){"IO barrier timed out"}}
  val b=if(gap)Bitmap(480,640,IntArray(480*640){0xff444444.toInt()}) else page().apply{failReadAfter=1;beforePixelRead={if(it==2)pause()}}
  val i=Image(b,System.nanoTime()).apply{if(gap)beforePlaneRead=pause;if(kind=="gap-plane-fault")planeThrow=true}
  val capture=Thread({try{h.reader!!.emit(i)}catch(t:Throwable){errors.set(t)}},"q1-r5-slow-io")
  val uiErrors=AtomicReference<Throwable?>(null);val newerRef=AtomicReference<FreshFrameRequest?>(null);val timeoutRef=AtomicReference<Runnable?>(null)
  capture.start()
  try{
   assertTrue(arrived.await(5,TimeUnit.SECONDS))
   val ui=Thread({try{h.cancel();h.start();newerRef.set(h.requestedFrame.get());timeoutRef.set(h.frameTimeout)}catch(t:Throwable){uiErrors.set(t)}},"q1-r5-ui-during-io")
   ui.start();boundedJoin(ui);assertNull(uiErrors.get());assertTrue("UI not blocked by pixel/plane IO",h.gate.accepts(newerRef.get()!!.token));assertTrue(capture.isAlive)
  }finally{resume.countDown();capture.join(5000)}
  boundedJoin(capture);assertNull(errors.get());assertTrue(i.closed);assertFalse(h.gate.accepts(old.token))
  val newer=newerRef.get()!!;assertSame(newer,h.requestedFrame.get());assertSame(timeoutRef.get(),h.frameTimeout);assertTrue(h.main.delayed.contains(timeoutRef.get()))
  assertNull(field(h,"pendingPageBaseline"));assertFalse(field(h,"pendingPageChanged") as Boolean)
  h.main.all();assertSame(newer,h.requestedFrame.get());finishReplacement(h,newer)
 }
 @Test fun replacementPublishedDuringEntryIdentityReadSurvivesOldFailure(){replacementWinsBeforeFailure("sample",entry=true)}
 @Test fun replacementPublishedDuringVerifyIdentityReadSurvivesOldFailure(){replacementWinsBeforeFailure("sample")}
 @Test fun replacementPublishedDuringChangedGapReadSurvivesOldFailure(){replacementWinsBeforeFailure("gap-changed")}
 @Test fun replacementPublishedDuringUnreadableGapReadSurvivesOldFailure(){replacementWinsBeforeFailure("gap-plane-fault")}

}
