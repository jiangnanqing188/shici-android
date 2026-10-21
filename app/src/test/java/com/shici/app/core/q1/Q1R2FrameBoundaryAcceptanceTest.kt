package com.shici.app.core.q1

import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Actual R2 production helper; all page pixels are synthetic, no Android framework/runtime. */
class Q1R2FrameBoundaryAcceptanceTest {
 private val w=24;private val h=20;private val body=PixelBox(0,0,w,h)
 private fun rows(p:IntArray=IntArray(w*h){-1},b:PixelBox=body)=LiveFrameFingerprint.sample(w,h,b){x,y,r->p.copyInto(r,0,y*w+x,y*w+x+r.size)}!!
 private fun plane(p:IntArray=IntArray(w*h){-1},rs:Int=w*4,ps:Int=4):ByteBuffer {
  val b=ByteBuffer.allocate(rs*h)
  for(y in 0 until h)for(x in 0 until w){val c=p[y*w+x];val o=y*rs+x*ps;b.put(o,(c ushr 16).toByte());b.put(o+1,(c ushr 8).toByte());b.put(o+2,c.toByte())}
  return b
 }
 @Test fun planeMatchesRowsForEverySingleGlyphRow(){for(y in 0 until h){val p=IntArray(w*h){-1};p[y*w+7]=0xff111111.toInt();assertFalse("row $y",rows().matchesPlane(w,h,body,w*4,4,plane(p),emptyList()))}}
 @Test fun planeDetectsEqualBrightnessColorSwap(){val a=IntArray(w*h){0xffff0000.toInt()};val b=IntArray(w*h){0xff00ff00.toInt()};assertFalse(rows(a).matchesPlane(w,h,body,w*4,4,plane(b),emptyList()))}
 @Test fun planeDetectsOnePixelHorizontalMove(){val a=IntArray(w*h){-1};val b=a.copyOf();a[7*w+7]=0xff111111.toInt();b[7*w+8]=0xff111111.toInt();assertFalse(rows(a).matchesPlane(w,h,body,w*4,4,plane(b),emptyList()))}
 @Test fun planeDetectsOnePixelVerticalMove(){val a=IntArray(w*h){-1};val b=a.copyOf();a[7*w+7]=0xff111111.toInt();b[8*w+7]=0xff111111.toInt();assertFalse(rows(a).matchesPlane(w,h,body,w*4,4,plane(b),emptyList()))}
 @Test fun masksUseExclusiveRightAndBottomEdges(){for(xy in listOf(12 to 7,7 to 12)){val p=IntArray(w*h){-1};p[xy.second*w+xy.first]=0xff000000.toInt();assertFalse(rows().matchesPlane(w,h,body,w*4,4,plane(p),listOf(PixelBox(5,5,12,12))))}}
 @Test fun masksCoverOnlyIncludedPixelControl(){val p=IntArray(w*h){-1};p[5*w+5]=0xff000000.toInt();assertTrue(rows().matchesPlane(w,h,body,w*4,4,plane(p),listOf(PixelBox(5,5,6,6))))}
 @Test fun offOriginBodyAndPaddedWidePixelsAreCorrect(){val p=IntArray(w*h){i->0xff000000.toInt() or (i*7919 and 0xffffff)};val b=PixelBox(3,4,22,19);val buffer=plane(p,rs=w*8+16,ps=8);buffer.position(5);assertTrue(rows(p,b).matchesPlane(w,h,b,w*8+16,8,buffer,emptyList()));assertEquals(5,buffer.position())}
 @Test fun slicedBufferOffsetIsRespected(){val actual=plane();val storage=ByteBuffer.allocate(actual.limit()+13);storage.position(13);storage.put(actual);storage.position(13);val slice=storage.slice();assertTrue(rows().matchesPlane(w,h,body,w*4,4,slice,emptyList()));assertEquals(13,storage.position());assertEquals(0,slice.position())}
 @Test fun readOnlyDirectBufferSupported(){val b=ByteBuffer.allocateDirect(w*h*4);b.put(plane());b.flip();assertTrue(rows().matchesPlane(w,h,body,w*4,4,b.asReadOnlyBuffer(),emptyList()))}
 @Test fun bufferLimitNotCapacityUsed(){val b=plane();b.limit(8);assertFalse(rows().matchesPlane(w,h,body,w*4,4,b,emptyList()))}
 @Test fun negativeStrideRejected(){assertFalse(rows().matchesPlane(w,h,body,-w*4,4,plane(),emptyList()))}
 @Test fun zeroPixelStrideRejected(){assertFalse(rows().matchesPlane(w,h,body,w*4,0,plane(),emptyList()))}
 @Test fun overlappingPixelChannelsCannotProveSameRgbaImage(){val b=ByteBuffer.allocate(w*h*4).apply{while(hasRemaining())put(255.toByte())};assertFalse("Q1-S2: malformed overlapping RGBA pixels must not authorize",rows().matchesPlane(w,h,body,w*4,1,b,emptyList()))}
 @Test fun overlappingRowsCannotProveSameRgbaImage(){val b=ByteBuffer.allocate(w*h*4).apply{while(hasRemaining())put(255.toByte())};assertFalse("Q1-S2: malformed overlapping RGBA rows must not authorize",rows().matchesPlane(w,h,body,4,4,b,emptyList()))}
 @Test fun samplerRejectsOverlappingPixelGeometry(){val b=ByteBuffer.allocate(w*h*4).apply{while(hasRemaining())put(255.toByte())};assertNull("invalid source geometry",LiveFrameFingerprint.sample(w,h,body,w*4,1,b))}
 @Test fun mismatchedShapeRejectedEvenWhenFullyMasked(){assertFalse(rows().matchesPlane(w+1,h,body,w*4,4,plane(),listOf(body)))}
 @Test fun oneLevelNoiseAcrossQuantizationBoundaryAccepted(){val a=IntArray(w*h){0xff7f7f7f.toInt()};val b=IntArray(w*h){0xff808080.toInt()};assertTrue(rows(a).matchesPlane(w,h,body,w*4,4,plane(b),emptyList()))}
 @Test fun visibleLowContrastGlyphStillDetected(){val p=IntArray(w*h){-1};for(x in 6..13)p[7*w+x]=0xffbbbbbb.toInt();assertFalse(rows().matchesPlane(w,h,body,w*4,4,plane(p),emptyList()))}
 @Test fun inputMutationCannotRewriteRetainedIdentity(){val p=IntArray(w*h){-1};val before=rows(p);p[7*w+7]=0xff000000.toInt();assertFalse(before.matches(rows(p)))}
 @Test fun aboveIdentityBudgetRejectedBeforeRead(){var reads=0;assertNull(LiveFrameFingerprint.sample(8192,1025,PixelBox(0,0,8192,1025)){_,_,_->reads++});assertEquals(0,reads)}
 @Test fun exactIdentityBudgetIsBoundedShortArray(){val f=LiveFrameFingerprint.sample(8192,1024,PixelBox(0,0,8192,1024)){_,_,r->r.fill(-1)}!!;val field=LiveFrameFingerprint::class.java.getDeclaredField("encoded").apply{isAccessible=true};val bytes=(field.get(f) as ShortArray).size.toLong()*2;assertEquals(16_777_216L,bytes);assertEquals(LiveFrameFingerprint.MAX_IDENTITY_BYTES,bytes)}
 @Test fun freshRequestRetainsActualAcceptedSourceNotLaterOffer(){val r=FreshFrameRequest(1,100,body);r.arm(5);assertFalse(r.offer(6,1,true));assertEquals(0L,r.acceptedTimestampNanos);assertTrue(r.offer(7,2,false));assertEquals(7L,r.acceptedTimestampNanos);assertFalse(r.offer(8,3,false));assertEquals(7L,r.acceptedTimestampNanos)}
}
