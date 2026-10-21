package com.shici.app.core.q1

import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Actual R3 Kotlin/JDK implementation, no replacement identity or request algorithm. */
class Q1R3FrameBoundaryAcceptanceTest {
 private val w=11;private val h=9;private val body=PixelBox(2,3,10,8)
 private fun sample()=LiveFrameFingerprint.sample(w,h,body){_,_,row->row.fill(-1)}!!
 private fun bytes(n:Int)=ByteBuffer.allocate(n).apply{while(hasRemaining())put(255.toByte())}
 @Test fun twoBytePixelsFailBothSamplingAndComparison(){val b=bytes(w*h*4);assertNull(LiveFrameFingerprint.sample(w,h,body,w*4,2,b));assertFalse(sample().matchesPlane(w,h,body,w*4,2,b,emptyList()))}
 @Test fun rowOverlapRejectedEvenForPartialBody(){val b=bytes(w*h*4);assertNull(LiveFrameFingerprint.sample(w,h,body,w*4-1,4,b));assertFalse(sample().matchesPlane(w,h,body,w*4-1,4,b,emptyList()))}
 @Test fun lastRequiredRgbByteHonorsLimitNotCapacity(){val rs=w*8+12;val end=(body.bottom-1)*rs+(body.right-1)*8+3;val b=bytes(rs*h);b.limit(end-1);assertNull(LiveFrameFingerprint.sample(w,h,body,rs,8,b));assertFalse(sample().matchesPlane(w,h,body,rs,8,b,emptyList()))}
 @Test fun exactBodyRgbLimitWithWidePixelsRemainsValid(){val rs=w*8+12;val end=(body.bottom-1)*rs+(body.right-1)*8+3;val b=bytes(rs*h);b.limit(end);b.position(6);assertNotNull(LiveFrameFingerprint.sample(w,h,body,rs,8,b));assertTrue(sample().matchesPlane(w,h,body,rs,8,b,emptyList()));assertEquals(6,b.position())}
 @Test fun invalidShapeCannotBeHiddenByFullMask(){val b=bytes(w*h*4);val invalid=PixelBox(2,-1,10,8);assertNull(LiveFrameFingerprint.sample(w,h,invalid,w*4,4,b));assertFalse(sample().matchesPlane(w,h,invalid,w*4,4,b,listOf(PixelBox(0,0,w,h))))}
 @Test fun widePlaneSliceWithOffsetAndReadOnlyDirectBuffer(){val rs=w*8+12;val source=bytes(rs*h);val direct=ByteBuffer.allocateDirect(rs*h+19);direct.position(19);direct.put(source.rewind() as ByteBuffer);direct.position(19);val slice=direct.slice().asReadOnlyBuffer();assertTrue(sample().matchesPlane(w,h,body,rs,8,slice,emptyList()));assertNotNull(LiveFrameFingerprint.sample(w,h,body,rs,8,slice));assertEquals(19,direct.position());assertEquals(0,slice.position())}
 @Test fun hugeRowStrideCannotWrapAddressToSmallBuffer(){val b=bytes(w*h*4);assertNull(LiveFrameFingerprint.sample(w,h,body,Int.MAX_VALUE,8,b));assertFalse(sample().matchesPlane(w,h,body,Int.MAX_VALUE,8,b,emptyList()))}
 @Test fun acceptedRequestStateSurvivesRearmUntilDelivery(){val r=FreshFrameRequest(1,100,body);r.arm(10);assertFalse(r.isAwaitingDelivery());assertTrue(r.offer(11,1,false));assertTrue(r.isAwaitingDelivery());r.arm(20);assertEquals(11L,r.acceptedTimestampNanos);assertFalse(r.canInspect(30,2));assertTrue(r.complete(3));assertFalse(r.isAwaitingDelivery());assertFalse(r.complete(4))}
 @Test fun cancelMakesAwaitingDeliveryFalseAndBlocksCompletion(){val r=FreshFrameRequest(1,100,body);r.arm(10);assertTrue(r.offer(11,1,false));r.cancel();assertFalse(r.isAwaitingDelivery());assertFalse(r.complete(2));assertFalse(r.offer(30,3,false))}
 @Test fun concurrentCancelClosesAcceptedRequestBeforeConsumer(){val r=FreshFrameRequest(1,100,body);r.arm(10);assertTrue(r.offer(11,1,false));val t=Thread{r.cancel()};t.start();t.join();assertFalse(r.isAwaitingDelivery());assertFalse(r.complete(2))}
 @Test fun blackOfferDoesNotOpenDeliveryState(){val r=FreshFrameRequest(1,100,body);r.arm(10);assertFalse(r.offer(11,1,true));assertFalse(r.isAwaitingDelivery());assertEquals(0L,r.acceptedTimestampNanos);assertTrue(r.offer(12,2,false));assertTrue(r.isAwaitingDelivery())}
}
