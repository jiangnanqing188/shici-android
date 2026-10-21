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

/** Verbatim R1 members; explicit deterministic Android/MLKit/in-memory repository adapters. */
class Q1R1AsyncBoundaryAcceptanceTest {
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
 @Test fun observedChangeBeforeOcrRejectsWithoutMainDrain(){val h=recognizing();emit(h);assertFalse(h.main.tasks.isEmpty());success(h);oldWorkCannotWrite(h)}
 @Test fun observedChangeBeforeDictionaryRejectsWithoutMainDrain(){val h=recognizing();success(h);emit(h);h.app.worker.all();assertTrue(h.app.repository.events.isEmpty());h.main.all();h.pump();assertTrue(h.app.repository.events.isEmpty())}
 @Test fun observedChangeBeforeSaveRejectsWithoutMainDrain(){val h=recognizing();success(h);h.app.worker.one();h.main.all();assertFalse(h.app.worker.tasks.isEmpty());emit(h);h.app.worker.all();assertTrue(h.app.repository.events.isEmpty());h.main.all();assertTrue(h.app.repository.events.isEmpty())}
 @Test fun observedChangeAtActualRecordAdmissionRejects(){val h=recognizing();h.app.repository.beforeAdmission={emit(h)};finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun observedChangeOnOtherThreadAtRecordAdmissionRejects(){val h=recognizing();h.app.repository.beforeAdmission={val t=Thread{emit(h)};t.start();t.join()};finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun normalSamePageAtRecordAdmissionStillSaves(){val h=recognizing();h.app.repository.beforeAdmission={emit(h,page())};finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun visibleOwnCardChangesDoNotKillSamePageLookup(){val h=recognizing();val b=page();for(y in 240..399)for(x in 40..439)b.pixels[y*480+x]=0xff222222.toInt();emit(h,b);finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun fullPageMoveStillRejectedWhenOwnCardAlsoChanges(){val h=recognizing();emit(h);success(h);oldWorkCannotWrite(h)}
 @Test fun localChangeOutsideCardCancelsBeforeOcr(){val h=recognizing();val b=page();for(y in 194..206)for(x in 110..190)b.pixels[y*480+x]=0xff000000.toInt();emit(h,b);success(h);oldWorkCannotWrite(h)}
 @Test fun selectedVisibleWordInsideCardMarginCannotBeAssumedUnchanged(){val h=recognizing(220f);val b=page();for(y in 230..409)for(x in 20..459)b.pixels[y*480+x]=0xff222222.toInt();for(y in 214..226)for(x in 110..190)b.pixels[y*480+x]=0xff000000.toInt();emit(h,b);finish(h,220);assertEquals("Q1-S2: visible selected-word replacement outside actual card, inside 16dp exclusion margin",0,h.app.repository.events.size)}
 @Test fun thinSelectedGlyphChangeAfterLiftMustRejectOldOcr(){val h=recognizing();val b=page();for(x in 110..190)b.pixels[200*480+x]=0xff000000.toInt();emit(h,b);finish(h);assertEquals("Q1-S1: ambient thin glyph change bypassed source identity",0,h.app.repository.events.size)}
 @Test fun movedEqualSumGlyphsCannotReuseOldGesture(){val a=page();val b=page();for(x in 100..196 step 4){a.pixels[199*480+x]=0xff000000.toInt();b.pixels[199*480+x+1]=0xff000000.toInt()};val h=fresh(a);stroke(h);h.deliver(b);if(h.recognizer!!.tasks.isNotEmpty())finish(h);assertEquals("Q1-S1: moved strokes must not become a new word query",0,h.app.repository.events.size)}
 @Test fun unreadableAmbientPlaneCannotAuthorizeOldOcr(){val h=recognizing();emit(h,configure={it.planeThrow=true});finish(h);assertEquals("Q1-S2: read exception leaves old page authorization live",0,h.app.repository.events.size)}
 @Test fun missingAmbientPlaneCannotAuthorizeOldOcr(){val h=recognizing();emit(h,configure={it.planeMissing=true});finish(h);assertEquals("Q1-S2: missing plane leaves old page authorization live",0,h.app.repository.events.size)}
 @Test fun truncatedAmbientPlaneCannotAuthorizeOldOcr(){val h=recognizing();emit(h,configure={it.bufferOverride=ByteBuffer.allocate(8)});finish(h);assertEquals("Q1-S2: invalid buffer leaves old page authorization live",0,h.app.repository.events.size)}
 @Test fun unreadableFrameAtRecordAdmissionMustReject(){val h=recognizing();h.app.repository.beforeAdmission={emit(h,configure={it.planeThrow=true})};finish(h);assertEquals("Q1-S2: unreadable observed frame at actual write admission",0,h.app.repository.events.size)}
 @Test fun genuinePostLiftFrameQueuedBeforeGuardMountMustBeChecked(){val h=fresh();stroke(h);h.queuePage(page());val afterLiftNanos=System.nanoTime();h.main.all();assertNotNull(field(h,"pageGuard"));val i=Image(changed(),afterLiftNanos);h.reader!!.emit(i);assertTrue(i.closed);finish(h);assertEquals("Q1-S2: actual newer-than-lift frame dropped as older than guard creation",0,h.app.repository.events.size)}
 @Test fun trulyOldPreLiftAmbientFrameIsIgnoredControl(){val h=recognizing();h.reader!!.emit(Image(changed(),1));finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun zeroTimestampFreshChangedFrameIsNotSilentlyIgnored(){val h=recognizing();h.reader!!.emit(Image(changed(),0));success(h);oldWorkCannotWrite(h)}
 @Test fun cancelAtWriteAdmissionPreventsWrite(){val h=recognizing();h.app.repository.beforeAdmission={h.close()};finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun newSelectionAtWriteAdmissionPreventsWrite(){val h=recognizing();h.app.repository.beforeAdmission={h.start()};finish(h);assertTrue(h.app.repository.events.isEmpty());assertNotNull(h.requestedFrame.get())}
 @Test fun generationChangeAtWriteAdmissionPreventsWrite(){val h=recognizing();h.app.repository.beforeAdmission={h.generationChange()};finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun stopAtWriteAdmissionPreventsWrite(){val h=recognizing();h.app.repository.beforeAdmission={h.stop()};finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun timeoutBeforeOcrCannotWrite(){val h=recognizing();val timeout=field(h,"pageGuardTimeout") as Runnable;timeout.run();finish(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun oldTimeoutCannotInvalidateNewSelection(){val h=recognizing();val timeout=field(h,"pageGuardTimeout") as Runnable;h.start();h.deliver(page());h.windows.dispatchReady();stroke(h);h.deliver(page());timeout.run();finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun queuedOldInvalidationCannotCancelNewEntry(){val h=recognizing();emit(h);h.start();h.main.all();assertNotNull(h.requestedFrame.get());h.deliver(page());h.windows.dispatchReady();stroke(h);h.deliver(page());finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun changedPageAfterSaveDoesNotPretendRollback(){val h=recognizing();finish(h);assertEquals(1,h.app.repository.events.size);assertNull(field(h,"pageGuard"));emit(h);h.main.all();assertEquals(1,h.app.repository.events.size);assertTrue(h.nodes().any{it.text.startsWith("已收录")})}
 @Test fun changedPageAfterFailedSaveDisablesRetry(){val h=recognizing();h.app.repository.throwRecord=true;finish(h);emit(h);h.main.all();h.app.repository.throwRecord=false;h.click("重试保存");h.pump();assertTrue(h.app.repository.events.isEmpty())}
 @Test fun imageClosedAndNoAmbientBitmapRetained(){val h=recognizing();val b=page();val i=emit(h,b);assertTrue(i.closed);assertFalse(b.recycled);assertEquals(0,b.recycleCount);finish(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun oldLayerReadyAfterCancelCannotReport(){val h=CaptureHost();h.start();h.deliver(page());val old=h.liveView!!;h.cancel();old.isAttachedToWindow=true;old.onAttachedToWindow();old.onLayout(true,0,0,480,640);assertEquals(0,h.perf.stages.count{it=="ready"})}
 @Test fun oldLayerReadyCannotPolluteReplacementSession(){val h=CaptureHost();h.start();h.deliver(page());val old=h.liveView!!;h.cancel();h.start();h.deliver(page());old.isAttachedToWindow=true;old.onAttachedToWindow();assertEquals(0,h.perf.stages.count{it=="ready"});h.windows.dispatchReady();assertEquals(1,h.perf.stages.count{it=="ready"})}
 @Test fun readinessRequiresNonzeroSizeAndVisibility(){val h=CaptureHost();h.start();h.deliver(page());val v=h.liveView!!;v.width=0;v.height=0;v.isAttachedToWindow=true;v.onAttachedToWindow();assertEquals(0,h.perf.stages.count{it=="ready"});v.width=480;v.height=640;v.visibility=View.GONE;v.onLayout(true,0,0,480,640);assertEquals(0,h.perf.stages.count{it=="ready"});v.visibility=View.VISIBLE;v.onWindowVisibilityChanged(View.VISIBLE);assertEquals(1,h.perf.stages.count{it=="ready"})}
 @Test fun actualLayoutReadinessOnlyReportsOnce(){val h=fresh();h.windows.dispatchReady();h.liveView!!.onWindowVisibilityChanged(View.VISIBLE);assertEquals(1,h.perf.stages.count{it=="ready"})}
 @Test fun upBeforeActualReadyCannotStartQuery(){val h=CaptureHost();h.start();h.deliver(page());val v=h.liveView!!;v.onTouchEvent(MotionEvent(0,100f,200f));v.onTouchEvent(MotionEvent(1,200f,200f));assertNull(h.requestedFrame.get());assertEquals(0,h.perf.stages.count{it=="submit"})}
 @Test fun submitMetricStartsAtUpNotDownOrMove(){val h=fresh();val v=h.liveView!!;v.onTouchEvent(MotionEvent(0,100f,200f));v.onTouchEvent(MotionEvent(2,150f,200f));assertEquals(0,h.perf.stages.count{it=="submit"});v.onTouchEvent(MotionEvent(1,200f,200f));assertEquals(1,h.perf.stages.count{it=="submit"})}
 @Test fun readinessAfterStopDoesNotReport(){val h=CaptureHost();h.start();h.deliver(page());val old=h.liveView!!;h.stop();old.isAttachedToWindow=true;old.onAttachedToWindow();assertEquals(0,h.perf.stages.count{it=="ready"})}
 @Test fun samePageCenterSelectionStillSavesControl(){val h=recognizing(300f);finish(h,300);assertEquals(1,h.app.repository.events.size)}
 @Test fun ownCardAndMarginAdjacentUnchangedSelectionStillSaveControl(){val h=recognizing(220f);val b=page();for(y in 230..409)for(x in 20..459)b.pixels[y*480+x]=0xff222222.toInt();emit(h,b);finish(h,220);assertEquals(1,h.app.repository.events.size)}

}
