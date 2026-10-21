package com.shici.app.core.q1

import com.shici.app.core.*
import com.shici.app.core.q1.host.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Candidate's verbatim gesture and service members with explicit queued framework adapters.
 * Pixels and MLKit output are synthetic; repository is a guarded in-memory fake, not SQLite.
 */
class Q1LiveLookupAcceptanceTest {
 @Before fun reset(){CaptureStatus.generation=1;CaptureStatus.state.phase=CapturePhase.ACTIVE;SystemClock.now=1}
 private fun page()=Bitmap(480,640)
 private fun dictionary():RawKajwebDictionary {
  val text="SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\nE\tabandon\tv\tsynthetic only\nE\tability\tn\tsynthetic only\n"
  val out=ByteArrayOutputStream();GZIPOutputStream(out).use{it.write(text.toByteArray())};val bytes=out.toByteArray()
  val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
  return RawKajwebDictionary.load(bytes.inputStream(),RawKajwebDictionary.Manifest(hash,2,0))
 }
 private fun fresh():CaptureHost = CaptureHost().apply{app.dictionary=Result.success(dictionary());start();deliver(page());windows.dispatchReady()}
 private fun event(v:LiveSelectionView,a:Int,x:Float,y:Float,history:List<Pair<Float,Float>> = emptyList(),ids:List<Int> = listOf(0)){assertTrue(v.onTouchEvent(MotionEvent(a,x,y,ids,history)))}
 private fun stroke(h:CaptureHost,x1:Float=100f,y1:Float=200f,x2:Float=200f,y2:Float=200f){h.windows.dispatchReady();val v=h.liveView!!;event(v,0,x1,y1);event(v,1,x2,y2)}
 private fun ocr(h:CaptureHost,words:List<Pair<String,OcrRect>> = listOf("abandon" to OcrRect(110,194,190,206))):OcrResult {
  val area=h.recognizer!!.images.last();val original=HighlighterGeometry.selection(listOf(HighlighterStroke(listOf(HighlighterPoint(100f,200f),HighlighterPoint(200f,200f)))),480,640,480,640,1f)!!.crop
  return OcrResult(listOf(Block(listOf(Line(OcrRect(90-original.left,190-original.top,300-original.left,210-original.top),words.map{(t,r)->Element(t,OcrRect(r.left-original.left,r.top-original.top,r.right-original.left,r.bottom-original.top))})))) )
 }
 private fun recognizing():CaptureHost=fresh().also{stroke(it);it.deliver(page())}
 private fun complete(h:CaptureHost){h.recognizer!!.tasks.last().succeed(ocr(h));h.pump();h.pump()}
 @Test fun entryFrameRecycledAndOriginalPageNotStored(){val h=CaptureHost();h.start();val b=page();h.deliver(b);assertEquals(1,b.recycleCount);assertNotNull(h.liveView);assertNull(h.bubble);assertNull(h.panel)}
 @Test fun entryCanOnlyStartOneRequest(){val h=CaptureHost();h.start();val r=h.requestedFrame.get();h.start();assertSame(r,h.requestedFrame.get())}
 @Test fun unarmedViewSwallowsWithoutSubmission(){var n=0;val v=LiveSelectionView(Context(),480,640,{n++},{n++},{n++});event(v,0,100f,200f);event(v,1,200f,200f);assertEquals(0,n)}
 @Test fun actionUpSubmitsWithoutFinishButton(){val h=fresh();stroke(h);assertNull(h.liveView);assertNotNull(h.requestedFrame.get());assertEquals(1,h.perf.stages.count{it=="submit"});assertEquals(0,h.recognizer!!.tasks.size)}
 @Test fun shortTapKeepsModeAndDoesNotRequestVerify(){val h=fresh();stroke(h,100f,200f,102f,200f);assertNotNull(h.liveView);assertNull(h.requestedFrame.get());assertEquals(0,h.recognizer!!.tasks.size)}
 @Test fun exactMinimumStrokeAccepted(){val h=fresh();stroke(h,100f,200f,106f,200f);assertNotNull(h.requestedFrame.get())}
 @Test fun reverseStrokeAccepted(){val h=fresh();stroke(h,200f,200f,100f,200f);assertNotNull(h.requestedFrame.get())}
 @Test fun moveHistoryPreservesCurvedStroke(){val h=fresh();val v=h.liveView!!;event(v,0,100f,200f);event(v,2,140f,200f,listOf(110f to 180f,130f to 180f));event(v,1,160f,200f);assertEquals(5,h.live!!.selection!!.strokes.single().points.size)}
 @Test fun screenOffsetRestoredBeforeGeometry(){val h=fresh();val v=h.liveView!!;v.originX=12;v.originY=24;stroke(h);val first=h.live!!.selection!!.strokes.single().points.first();assertEquals(112f,first.x);assertEquals(224f,first.y)}
 @Test fun pointerDownInterruptDoesNotSubmit(){val h=fresh();val v=h.liveView!!;event(v,0,100f,200f);event(v,5,140f,200f,ids=listOf(0,1));event(v,1,200f,200f);assertNull(h.requestedFrame.get());assertNull(h.liveView)}
 @Test fun pointerUpInterruptDoesNotBridge(){val h=fresh();val v=h.liveView!!;event(v,0,100f,200f);event(v,6,140f,200f,ids=listOf(0,1));event(v,1,200f,200f);assertNull(h.live)}
 @Test fun cancelEventNeverSubmits(){val h=fresh();val v=h.liveView!!;event(v,0,100f,200f);event(v,3,200f,200f);assertNull(h.liveView);assertNull(h.requestedFrame.get())}
 @Test fun missingPointerNeverInventsStroke(){val h=fresh();val v=h.liveView!!;event(v,0,100f,200f);event(v,2,200f,200f,ids=listOf(7));event(v,1,220f,200f);assertNull(h.requestedFrame.get());assertNotNull(h.liveView)}
 @Test fun cancelChipCancels(){val h=fresh();val v=h.liveView!!;event(v,0,240f,580f);event(v,1,240f,580f);assertNull(h.liveView);assertNotNull(h.bubble)}
 @Test fun draggedCancelChipDoesNotSubmit(){val h=fresh();val v=h.liveView!!;event(v,0,240f,580f);event(v,1,100f,200f);assertNull(h.requestedFrame.get());assertNotNull(h.liveView)}
 @Test fun normalVerifyStartsExactlyOneCrop(){val h=recognizing();assertEquals(1,h.recognizer!!.tasks.size);assertNull(h.live);assertTrue(h.recognizer!!.images.single().width<480)}
 @Test fun verifyFrameRecycledButCropLivesUntilComplete(){val h=fresh();stroke(h);val b=page();h.deliver(b);assertEquals(1,b.recycleCount);val crop=h.recognizer!!.images.single();assertFalse(crop.recycled);h.recognizer!!.tasks.single().fail();assertEquals(1,crop.recycleCount)}
 @Test fun ocrFailureReleasesPixelsAndDoesNotSave(){val h=recognizing();h.recognizer!!.tasks.single().fail();h.pump();assertTrue(h.app.repository.events.isEmpty());assertTrue(h.nodes().any{it.text.contains("识别失败")})}
 @Test fun synchronousOcrFailureReleasesAndReports(){val h=fresh();h.recognizer!!.throwing=true;stroke(h);val b=page();h.deliver(b);assertTrue(b.recycled);assertTrue(h.nodes().any{it.text.contains("识别失败")})}
 @Test fun blankSelectionDoesNotLookupOrSave(){val h=recognizing();h.recognizer!!.tasks.single().succeed(OcrResult(emptyList()));h.pump();assertTrue(h.app.repository.events.isEmpty());assertTrue(h.nodes().any{it.text.contains("没有查询")})}
 @Test fun singleWordNormalizesAndSavesOnce(){val h=recognizing();complete(h);assertEquals(1,h.app.repository.events.size);assertEquals("abandon",h.app.repository.events.values.single().entryKey)}
 @Test fun duplicateSuccessCallbackDoesNotDoubleSave(){val h=recognizing();val task=h.recognizer!!.tasks.single();val result=ocr(h);task.succeed(result);task.succeed(result);h.pump();h.pump();assertEquals(1,h.app.repository.events.size)}
 @Test fun missingDictionaryWordDoesNotSave(){val h=recognizing();h.recognizer!!.tasks.single().succeed(ocr(h,listOf("unknownsynthetic" to OcrRect(110,194,190,206))));h.pump();assertTrue(h.app.repository.events.isEmpty());assertTrue(h.nodes().any{it.text.contains("未收录")})}
 @Test fun multiWordWaitsForExplicitChoice(){val h=recognizing();h.recognizer!!.tasks.single().succeed(ocr(h,listOf("abandon" to OcrRect(105,194,140,206),"ability" to OcrRect(150,194,195,206))));h.pump();assertTrue(h.app.repository.events.isEmpty());assertTrue(h.nodes().any{it.text=="选中原文"});h.click("查词：ability");h.pump();h.pump();assertEquals("ability",h.app.repository.events.values.single().entryKey)}
 @Test fun pendingCardNotFullScreenAndOutsideTouchesNotModal(){val h=recognizing();val p=h.windows.views[h.panel]!!;assertTrue(p.width in 1 until 480);assertTrue(p.height in 1 until 640);assertTrue(p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL!=0);assertTrue(h.windows.views.keys.none{it is LiveSelectionView})}
 @Test fun cancelBeforeVerifyRejectsLateFrame(){val h=fresh();stroke(h);val r=h.requestedFrame.get()!!;h.cancel();val b=page();h.deliverLate(r,b);assertTrue(b.recycled);assertTrue(h.recognizer!!.tasks.isEmpty())}
 @Test fun closeDuringOcrSuppressesAllResults(){val h=recognizing();val crop=h.recognizer!!.images.single();h.close();h.recognizer!!.tasks.single().succeed(ocr(h));h.pump();assertTrue(h.app.repository.events.isEmpty());assertTrue(crop.recycled);assertNull(h.panel)}
 @Test fun newLookupSuppressesOldOcr(){val h=recognizing();val t=h.recognizer!!.tasks.single();val r=ocr(h);h.start();t.succeed(r);h.pump();assertNotNull(h.requestedFrame.get());assertTrue(h.app.repository.events.isEmpty())}
 @Test fun stopSuppressesLateOcr(){val h=recognizing();val r=ocr(h);val task=h.recognizer!!.tasks.single();h.stop();task.succeed(r);h.pump();assertTrue(h.app.repository.events.isEmpty());assertNull(h.panel);assertNull(h.bubble)}
 @Test fun resizeInvalidatesOldVerify(){val h=fresh();stroke(h);val r=h.requestedFrame.get()!!;h.resizeTo(640,480);val b=page();h.deliverLate(r,b);assertTrue(b.recycled);assertTrue(h.recognizer!!.tasks.isEmpty())}
 @Test fun generationInvalidatesOldVerify(){val h=fresh();stroke(h);val r=h.requestedFrame.get()!!;h.generationChange();val b=page();h.deliverLate(r,b);assertTrue(b.recycled);assertTrue(h.recognizer!!.tasks.isEmpty())}
 @Test fun deleteBetweenEntryAndOcrPreventsLateSave(){val h=recognizing();h.delete("abandon");complete(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun restoreBetweenEntryAndOcrPreventsLateSave(){val h=recognizing();h.restore();complete(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun unrelatedDeleteDoesNotBlockCurrentWord(){val h=recognizing();h.delete("ability");complete(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun cancelBeforeDictionaryWorkerPreventsLookup(){val h=recognizing();h.recognizer!!.tasks.single().succeed(ocr(h));h.close();h.pump();assertTrue(h.app.repository.events.isEmpty())}
 @Test fun cancelAtRepositoryAdmissionPreventsSave(){val h=recognizing();h.app.repository.beforeAdmission={h.close()};complete(h);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun saveFailureHasRetryNotFakeSuccess(){val h=recognizing();h.app.repository.throwRecord=true;complete(h);assertTrue(h.nodes().any{it.text.startsWith("保存失败")});assertTrue(h.app.repository.events.isEmpty());h.app.repository.throwRecord=false;h.click("重试保存");h.pump();assertEquals(1,h.app.repository.events.size)}
 @Test fun deletedCardRetryCannotResurrect(){val h=recognizing();h.app.repository.throwRecord=true;complete(h);h.delete("abandon");h.app.repository.throwRecord=false;h.click("重试保存");h.pump();assertTrue(h.app.repository.events.isEmpty());assertTrue(h.nodes().any{it.text.startsWith("未收录")})}
 @Test fun undoRemovesOnlyCurrentEvent(){val h=recognizing();complete(h);h.click("撤销本次收录");h.pump();assertTrue(h.app.repository.events.isEmpty());assertTrue(h.nodes().any{it.text=="本次查询已撤销；之前的记录保留"})}
 @Test fun correctionOfDeletedCardCannotOpenQuery(){val h=recognizing();complete(h);h.delete("abandon");h.click("纠错");assertTrue(h.nodes().any{it.text.startsWith("未收录")});assertFalse(h.nodes().any{it.text=="确认正确词形"})}
 @Test fun entryTimeoutLeavesNoGestureOrWrite(){val h=CaptureHost();h.start();h.timeout();assertNull(h.liveView);assertNull(h.requestedFrame.get());assertTrue(h.app.repository.events.isEmpty())}
 @Test fun verifyTimeoutLeavesNoGestureOrWrite(){val h=fresh();stroke(h);h.timeout();assertNull(h.live);assertNull(h.requestedFrame.get());assertTrue(h.app.repository.events.isEmpty())}
 @Test fun blackFrameRejectedNotUsedForOcr(){val h=fresh();stroke(h);val b=Bitmap(480,640,IntArray(480*640){0xff000000.toInt()});h.deliver(b);assertTrue(b.recycled);assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.requestedFrame.get()!!.rejectedBlack)}
 @Test fun broadPageChangeCancelsBeforeOcr(){val h=fresh();stroke(h);val b=Bitmap(480,640,IntArray(480*640){0xff444444.toInt()});h.deliver(b);assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.nodes().any{it.text.contains("页面已变化")})}
 @Test fun selectedWordLocalChangeMustCancelBeforeOcr(){val h=fresh();stroke(h);val b=page();for(y in 194..206)for(x in 110..190)b.pixels[y*480+x]=0xff000000.toInt();h.deliver(b);assertEquals("Q1-S1: changed selected word must not reach OCR",0,h.recognizer!!.tasks.size)}
 @Test fun pageChangeAfterVerifyBeforeOcrMustNotRecordNewlyStaleSelection(){val h=recognizing();val changed=Bitmap(480,640,IntArray(480*640){0xff444444.toInt()});val image=h.displayPage(changed);assertTrue(image.closed);complete(h);assertEquals("Q1-S2: no source-page evidence after verify while OCR/worker is in flight",0,h.app.repository.events.size)}
 @Test fun imageAlwaysClosedOnAcceptedEntry(){val h=CaptureHost();h.start();val b=page();val i=h.displayPage(b);assertTrue(i.closed);assertEquals(1,b.recycleCount)}
 @Test fun imageClosedOnBlackReject(){val h=CaptureHost();h.start();val b=Bitmap(480,640,IntArray(480*640));val i=h.displayPage(b);assertTrue(i.closed);assertEquals(1,b.recycleCount);assertNotNull(h.requestedFrame.get())}
 @Test fun cancelBetweenFrameOfferAndMainDeliveryRecyclesStaleBitmap(){val h=fresh();stroke(h);val b=page();val i=h.queuePage(b);assertTrue(i.closed);assertFalse(b.recycled);h.cancel();h.main.all();assertEquals(1,b.recycleCount);assertTrue(h.recognizer!!.tasks.isEmpty())}
 @Test fun rejectedUiPostRecyclesWithoutLeaking(){val h=CaptureHost();h.start();h.main.rejectPost=true;val b=page();val i=h.queuePage(b);assertTrue(i.closed);assertEquals(1,b.recycleCount);assertNull(h.liveView)}
 @Test fun framePixelFailureClosesImageAndReleases(){val h=CaptureHost();h.start();val b=page().apply{throwRead=true};val i=h.displayPage(b);assertTrue(i.closed);assertEquals(1,b.recycleCount);assertNull(h.requestedFrame.get());assertTrue(h.nodes().any{it.text.contains("取词失败")})}
 // CONTRACT-R3-001: retain the historical case ID; current-source bad geometry now fails closed,
 // rather than keeping an obsolete request waiting. The R3 failure remains in the private report.
 @Test fun wrongImageDimensionsIgnoredAndClosed(){val h=CaptureHost();h.start();val b=Bitmap(640,480);val i=h.displayPage(b);assertTrue(i.closed);assertNull(h.liveView);assertNull(h.requestedFrame.get());assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.app.repository.events.isEmpty())}
 @Test fun timeoutWhileBitmapQueuedRecyclesAndDoesNotStartOcr(){val h=fresh();stroke(h);val b=page();h.queuePage(b);SystemClock.now=2000;h.main.all();assertEquals(1,b.recycleCount);assertTrue(h.recognizer!!.tasks.isEmpty());assertNull(h.requestedFrame.get())}
 @Test fun fullCropAliasIsCopiedBeforeSourceReleased(){val h=fresh();stroke(h,0f,0f,479f,639f);val b=page();h.deliver(b);val crop=h.recognizer!!.images.single();assertNotSame(b,crop);assertEquals(480,crop.width);assertEquals(640,crop.height);assertEquals(1,b.recycleCount);assertFalse(crop.recycled);h.recognizer!!.tasks.single().fail();assertEquals(1,crop.recycleCount)}
 @Test fun fullCropCopyFailureReleasesSourceAndDoesNotStartOcr(){val h=fresh();stroke(h,0f,0f,479f,639f);val b=page().apply{throwCopy=true};h.deliver(b);assertEquals(1,b.recycleCount);assertTrue(h.recognizer!!.tasks.isEmpty());assertTrue(h.nodes().any{it.text.contains("无法准备识别")})}
 @Test fun rereadAfterCancelUsesNewCredential(){val h=recognizing();h.delete("abandon");h.close();h.start();h.deliver(page());stroke(h);h.deliver(page());complete(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun oldLiveTimeoutDoesNotCancelReplacement(){val h=fresh();val timeout=h.liveTimeout!!;h.cancel();h.start();h.deliver(page());timeout.run();assertNotNull(h.liveView)}
 @Test fun entryLayerAddFailureDoesNotRetainSession(){val h=CaptureHost();h.start();h.windows.throwAdd=true;h.deliver(page());assertNull(h.liveView);assertNull(h.live);assertTrue(h.app.repository.events.isEmpty())}
 @Test fun fingerGeometryDoesNotHitUntraversedWordInsideCrop(){val h=recognizing();h.recognizer!!.tasks.single().succeed(ocr(h,listOf("abandon" to OcrRect(110,194,190,206),"ability" to OcrRect(110,230,190,242))));h.pump();h.pump();assertEquals("abandon",h.app.repository.events.values.single().entryKey)}
 @Test fun pageChangesWhileDictionaryQueuedMustInvalidate(){val h=recognizing();h.recognizer!!.tasks.single().succeed(ocr(h));assertFalse(h.app.worker.tasks.isEmpty());h.displayPage(Bitmap(480,640,IntArray(480*640){0xff444444.toInt()}));h.pump();h.pump();assertEquals("Q1-S2: source page changed while dictionary worker queued",0,h.app.repository.events.size)}
 @Test fun sourceChangedToNewWordMustNotSaveDifferentWordFromOldStroke(){val h=fresh();stroke(h);val b=page();for(y in 194..206)for(x in 110..190)b.pixels[y*480+x]=0xff000000.toInt();h.deliver(b);h.recognizer!!.tasks.singleOrNull()?.succeed(ocr(h,listOf("ability" to OcrRect(110,194,190,206))));h.pump();h.pump();assertEquals("Q1-S1: old stroke must not silently collect replacement word",0,h.app.repository.events.size)}

 @Test fun actualBubbleTouchClickStartsEntryRequest(){val h=CaptureHost().apply{app.dictionary=Result.success(dictionary())};h.tapEntry();assertNull(h.bubble);assertNotNull(h.requestedFrame.get());h.deliver(page());stroke(h);h.deliver(page());complete(h);assertEquals(1,h.app.repository.events.size)}
 @Test fun actualBubbleDragDoesNotStartLookup(){val h=CaptureHost();h.showEntry();val v=h.bubble!!;v.onTouchEvent(MotionEvent(0,20f,30f));v.onTouchEvent(MotionEvent(2,80f,90f));v.onTouchEvent(MotionEvent(1,80f,90f));assertNull(h.requestedFrame.get());assertNotNull(h.bubble)}
 @Test fun actualBubbleCancelDoesNotStartLookup(){val h=CaptureHost();h.showEntry();val v=h.bubble!!;v.onTouchEvent(MotionEvent(0,20f,30f));v.onTouchEvent(MotionEvent(3,20f,30f));v.onTouchEvent(MotionEvent(1,20f,30f));assertNull(h.requestedFrame.get())}
 @Test fun actualOnDestroyReleasesCaptureAndRecognizer(){val h=recognizing();val reader=h.reader!!;val display=h.display!!;val projection=h.projection!!;val recognizer=h.recognizer!!;val callback=recognizer.tasks.single();val r=ocr(h);h.stop();assertTrue(display.released);assertTrue(projection.stopped);assertTrue(recognizer.closed);assertNull(h.reader);assertNull(h.liveView);assertNull(h.panel);assertTrue(h.foregroundStopped);h.frames.all();h.main.all();assertTrue(reader.closed);assertTrue(h.framesThread.quit);callback.succeed(r);h.pump();assertTrue(h.app.repository.events.isEmpty());assertTrue(recognizer.images.single().recycled)}
 @Test fun observerInvalidatesVisibleDeletedCard(){val h=recognizing();complete(h);h.delete("abandon");h.main.all();assertTrue(h.nodes().any{it.text.startsWith("未收录")});assertFalse(h.nodes().filterIsInstance<Button>().first{it.text=="纠错"}.isEnabled)}

 @Test fun selectorReadyMetricMustWaitForActualWindowReadiness(){val h=CaptureHost();h.start();h.deliver(page());assertEquals(0,h.windows.readyCallbacks);assertEquals("Q1-S3: no attach/layout/draw/readiness callback has run",0,h.perf.stages.count{it=="ready"})}

}
