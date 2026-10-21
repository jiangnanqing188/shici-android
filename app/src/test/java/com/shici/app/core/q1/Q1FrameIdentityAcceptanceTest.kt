package com.shici.app.core.q1
import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test

/** Runs actual production classes, without any Android class or pixel implementation substitution. */
class Q1FrameIdentityAcceptanceTest {
 private val w=960;private val h=1280;private val body=PixelBox(0,0,w,h)
 private fun sample(p:IntArray,b:PixelBox=body,ww:Int=w,hh:Int=h)=LiveFrameFingerprint.sample(ww,hh,b){x,y,row->p.copyInto(row,0,y*ww+x,y*ww+x+row.size)}!!
 @Test fun identicalFramesMatch(){val p=IntArray(w*h){-1};assertTrue(sample(p).matches(sample(p)))}
 @Test fun fullPageReplacementRejected(){assertFalse(sample(IntArray(w*h){-1}).matches(sample(IntArray(w*h){0xff444444.toInt()})))}
 @Test fun dimensionsRejectEvenWithSameSampleColors(){val a=sample(IntArray(w*h){-1});val b=sample(IntArray(h*w){-1},PixelBox(0,0,h,w),h,w);assertFalse(a.matches(b))}
 @Test fun invalidBodiesFailClosedAtSampling(){for(b in listOf(PixelBox(-1,0,5,5),PixelBox(0,0,w+1,h),PixelBox(0,0,0,5),PixelBox(0,5,5,2)))assertNull(LiveFrameFingerprint.sample(w,h,b){_,_,_->fail("must not read invalid body")})}
 @Test fun readerExceptionNotHiddenByPureFingerprint(){try{LiveFrameFingerprint.sample(w,h,body){_,_,_->throw IllegalStateException("bad read")};fail()}catch(e:IllegalStateException){assertEquals("bad read",e.message)}}
 @Test fun selectedPatchBetweenGridLinesMustInvalidate(){val a=IntArray(w*h){-1};val b=a.copyOf();for(y in 100..108)for(x in 101..119)b[y*w+x]=0xff000000.toInt();assertFalse("Q1-S1: entirely unsampled glyph patch",sample(a).matches(sample(b)))}
 @Test fun smallSelectedWordWithChangedSamplesMustInvalidate(){val a=IntArray(w*h){-1};val b=a.copyOf();for(y in 190..210)for(x in 200..360)b[y*w+x]=0xff000000.toInt();assertFalse("Q1-S1: word-sized 2% tolerated mismatch",sample(a).matches(sample(b)))}
 @Test fun fingerprintHasNoBitmapOrTextFields(){val fields=LiveFrameFingerprint::class.java.declaredFields;assertTrue(fields.none{it.type.name.contains("Bitmap")||it.type==String::class.java})}
 @Test fun sessionCarriesOriginalCredential(){val f=sample(IntArray(w*h){-1});val s=LiveSelectionSession(3,7,9,w,h,body,f);assertEquals(9L,s.deletionCredential);assertEquals(7L,s.entryToken);assertEquals(-1L,s.verifyToken);assertNull(s.selection)}
 @Test fun noReadBeforeCompositorArming(){val r=FreshFrameRequest(1,100,body);assertFalse(r.canInspect(Long.MAX_VALUE,1));r.arm(500);assertFalse(r.canInspect(499,1));assertTrue(r.canInspect(500,1))}
 @Test fun blackFrameDoesNotExtendDeadline(){val r=FreshFrameRequest(1,100,body);r.arm(5);assertFalse(r.offer(5,99,true));assertTrue(r.rejectedBlack);assertFalse(r.offer(10,100,false));assertFalse(r.complete(100))}
 @Test fun oneOfferOneCompleteOnly(){val r=FreshFrameRequest(1,100,body);r.arm(5);assertTrue(r.offer(6,2,false));assertFalse(r.offer(7,3,false));assertTrue(r.complete(4));assertFalse(r.complete(5))}
 @Test fun cancelledRequestRejectsQueuedComplete(){val r=FreshFrameRequest(1,100,body);r.arm(5);assertTrue(r.offer(6,2,false));r.cancel();assertFalse(r.complete(4))}
 @Test fun zeroWidthAndNegativeDimensionsFail(){assertNull(LiveFrameFingerprint.sample(0,10,PixelBox(0,0,1,1)){_,_,_->});assertNull(LiveFrameFingerprint.sample(-1,10,PixelBox(0,0,1,1)){_,_,_->})}
}
