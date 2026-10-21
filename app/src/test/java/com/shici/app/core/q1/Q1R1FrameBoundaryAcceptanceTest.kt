package com.shici.app.core.q1

import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** R1 production Kotlin/JDK only: synthetic pixels, no Android substitution. */
class Q1R1FrameBoundaryAcceptanceTest {
 private val w=960;private val h=1280;private val body=PixelBox(0,0,w,h)
 private fun pixels()=IntArray(w*h){-1}
 private fun sample(p:IntArray,b:PixelBox=body)=LiveFrameFingerprint.sample(w,h,b){x,y,row->p.copyInto(row,0,y*w+x,y*w+x+row.size)}!!
 private fun line(p:IntArray,y:Int,x:Int=120,n:Int=40){for(i in x until x+n)p[y*w+i]=0xff000000.toInt()}
 @Test fun thinGlyphInNonTerminalTileRowMustInvalidate(){val a=pixels();val b=a.copyOf();line(b,100);assertFalse("Q1-S1: thin glyph on nonterminal tile row disappeared",sample(a).matches(sample(b)))}
 @Test fun thinGlyphInTerminalTileRowIsDetectedControl(){val a=pixels();val b=a.copyOf();line(b,101);assertFalse(sample(a).matches(sample(b)))}
 @Test fun sameQuadrantHorizontalGlyphMotionMustInvalidate(){val a=pixels();val b=pixels();for(x in 120..210 step 6){a[101*w+x]=0xff000000.toInt();b[101*w+x+1]=0xff000000.toInt()};assertFalse("Q1-S1: spatially different glyphs have equal quadrant sums",sample(a).matches(sample(b)))}
 @Test fun sameTileVerticalGlyphMotionMustInvalidate(){val a=pixels();val b=pixels();line(a,98);line(b,99);assertFalse("Q1-S1: both nonterminal rows lost",sample(a).matches(sample(b)))}
 @Test fun erasingThinGlyphMustInvalidate(){val a=pixels();line(a,100);assertFalse("Q1-S1: thin glyph erased without identity change",sample(a).matches(sample(pixels())))}
 @Test fun equalBrightnessDifferentColorsMustInvalidate(){val a=pixels();val b=pixels();for(y in 96..113)for(x in 120..199){a[y*w+x]=0xffff0000.toInt();b[y*w+x]=0xff00ff00.toInt()};assertFalse("Q1-S1: R/G permutation loses color identity",sample(a).matches(sample(b)))}
 @Test fun repeatedReplacementAcrossSeveralTilesIsDetectedControl(){val a=pixels();val b=a.copyOf();for(y in 95..112)line(b,y);assertFalse(sample(a).matches(sample(b)))}
 @Test fun everyRowContributesToIdentityNotMerelyRead(){val a=pixels();val b=a.copyOf();for(y in 96..100)line(b,y);assertFalse("Q1-S1: five rows of real contrast are read then overwritten",sample(a).matches(sample(b)))}
 @Test fun oneLevelRgbNoiseAcceptedControl(){val a=pixels();val b=IntArray(w*h){0xfffefefe.toInt()};assertTrue(sample(a).matches(sample(b)))}
 @Test fun changedOutsideExcludedCardMustInvalidate(){val a=pixels();val b=a.copyOf();for(y in 100..115)line(b,y);assertFalse(sample(a).matches(sample(b),listOf(PixelBox(200,400,700,800))))}
 @Test fun ownCardRegionCanChangeWithoutInvalidationControl(){val a=pixels();val b=a.copyOf();for(y in 402..797)for(x in 204..695)b[y*w+x]=0xff222222.toInt();assertTrue(sample(a).matches(sample(b),listOf(PixelBox(200,400,700,800))))}
 @Test fun exclusionMustNotHideVisiblePixelsInIntersectingTiles(){val a=pixels();val b=a.copyOf();for(x in 120..122)b[101*w+x]=0xff000000.toInt();assertFalse("Q1-S1: card rectangle excludes visible remainder of intersecting tile",sample(a).matches(sample(b),listOf(PixelBox(125,96,126,102))))}
 @Test fun excludedCardNeverAllowsMismatchedDimensions(){val a=sample(pixels());val b=LiveFrameFingerprint.sample(h,w,PixelBox(0,0,h,w)){_,_,row->row.fill(-1)}!!;assertFalse(a.matches(b,listOf(body)))}
 @Test fun bufferSamplerMatchesRowsWithPaddingAndPreservesPosition(){val ww=24;val hh=20;val stride=ww*4+12;val p=IntArray(ww*hh){i->if(i%9==0)0xff123456.toInt() else -1};val b=ByteBuffer.allocate(stride*hh);for(y in 0 until hh)for(x in 0 until ww){val c=p[y*ww+x];val o=y*stride+x*4;b.put(o,(c shr 16).toByte());b.put(o+1,(c shr 8).toByte());b.put(o+2,c.toByte());b.put(o+3,255.toByte())};b.position(7);val region=PixelBox(3,2,23,18);val a=LiveFrameFingerprint.sample(ww,hh,region){x,y,row->p.copyInto(row,0,y*ww+x,y*ww+x+row.size)}!!;val c=LiveFrameFingerprint.sample(ww,hh,region,stride,4,b)!!;assertTrue(a.matches(c));assertEquals(7,b.position())}
 @Test fun truncatedBufferRejectedWithoutOutOfBoundsRead(){assertNull(LiveFrameFingerprint.sample(24,20,PixelBox(0,0,24,20),96,4,ByteBuffer.allocate(10)))}
 @Test fun bufferWithMissingAlphaStillHasRgbOnlyContract(){val b=ByteBuffer.allocate(4*4*4-1);assertNotNull(LiveFrameFingerprint.sample(4,4,PixelBox(0,0,4,4),16,4,b))}
 @Test fun invalidBufferRegionRejected(){assertNull(LiveFrameFingerprint.sample(4,4,PixelBox(-1,0,4,4),16,4,ByteBuffer.allocate(64)))}
 @Test fun noRowsReadForNegativeHeight(){assertNull(LiveFrameFingerprint.sample(4,-1,PixelBox(0,0,4,4)){_,_,_->fail()})}
 @Test fun guardInvalidationIsOneWay(){val guard=LivePageGuard(3,body,sample(pixels()));assertTrue(guard.isValid);assertTrue(guard.invalidate());assertFalse(guard.invalidate());assertFalse(guard.matches(sample(pixels()),emptyList()))}
 @Test fun oldGuardInvalidationDoesNotMutateReplacement(){val f=sample(pixels());val old=LivePageGuard(1,body,f);val replacement=LivePageGuard(2,body,f);old.invalidate();assertTrue(replacement.matches(f,emptyList()))}
 @Test fun invalidationVisibleAcrossThreads(){val guard=LivePageGuard(1,body,sample(pixels()));val t=Thread{guard.invalidate()};t.start();t.join();assertFalse(guard.isValid)}
}
