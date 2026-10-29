package com.shici.app.core

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters

/** Regression cases against the frozen full-screen selection implementation, not Android mocks. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FullscreenSelectionTest {
    private val white = 0xFFFFFF
    private fun mix(i: Int): Int {
        var x = i + 0x234567
        x = (x xor (x ushr 16)) * 0x45d9f3b
        x = (x xor (x ushr 16)) * 0x45d9f3b
        return (x xor (x ushr 16)) and 0xFFFFFF
    }
    private fun dense(w: Int, h: Int) = IntArray(w*h) { mix(it) }
    private fun fp(p: IntArray, w: Int, h: Int, body: PixelBox = PixelBox(0,0,w,h)) =
        requireNotNull(LiveFrameFingerprint.sample(w,h,body) { x,y,row ->
            System.arraycopy(p,y*w+x,row,0,row.size)
        })
    private fun move(p: IntArray,w: Int,h: Int,dx: Int,dy: Int): IntArray =
        IntArray(w*h) { i ->
            val x=i%w-dx; val y=i/w-dy
            if(x in 0 until w && y in 0 until h) p[y*w+x] else white
        }
    private fun plane(p: IntArray,w: Int,h: Int,ps: Int=4,pad: Int=0): ByteBuffer {
        val rs=w*ps+pad; val b=ByteBuffer.allocate(rs*h)
        for(y in 0 until h) for(x in 0 until w) {
            val c=p[y*w+x]; val o=y*rs+x*ps
            b.put(o,(c ushr 16).toByte()); b.put(o+1,(c ushr 8).toByte()); b.put(o+2,c.toByte())
            if(ps>3) b.put(o+3,255.toByte())
        }
        return b
    }
    private fun log(label: String,m: FrameMatch) { println("OBS $label $m") }
    private fun moved(label:String,m:FrameMatch,dx:Int,dy:Int) {
        log(label,m); assertEquals(label,FrameMatchKind.MOVED,m.kind)
        assertEquals(label,PageShift(dx,dy),m.shift)
    }
    private val letters = mapOf(
        'C' to listOf("01110","10001","10000","10000","10000","10001","01110"),
        'R' to listOf("11110","10001","10001","11110","10100","10010","10001"),
        'A' to listOf("01110","10001","10001","11111","10001","10001","10001"),
        'W' to listOf("10001","10001","10001","10101","10101","11011","10001"),
        'L' to listOf("10000","10000","10000","10000","10000","10000","11111"),
        'E' to listOf("11111","10000","10000","11110","10000","10000","11111"),
        'D' to listOf("11110","10001","10001","10001","10001","10001","11110"),
        'O' to listOf("01110","10001","10001","10001","10001","10001","01110"),
        'U' to listOf("10001","10001","10001","10001","10001","10001","01110"),
        'S' to listOf("01111","10000","10000","01110","00001","00001","11110")
    )
    private fun word(a:IntArray,w:Int,h:Int,text:String,left:Int,top:Int) {
        for((i,c) in text.withIndex()) for((y,row) in letters.getValue(c).withIndex())
            for((x,bit) in row.withIndex()) {
                val xx=left+i*6+x;val yy=top+y
                if(bit=='1' && xx in 0 until w && yy in 0 until h) a[yy*w+xx]=0
            }
    }
    private fun glyphs(w:Int,h:Int):IntArray {
        val a=IntArray(w*h){white};val vocabulary=listOf("CRAWL","READ","WORDS","CLOUD")
        for(r in 0..12) for(c in 0..12) word(a,w,h,vocabulary[mix(r*13+c)%4],20+c*35,20+r*45)
        return a
    }

    @Test fun f01_fullscreenBoundsAndAllocationLimit() {
        for((w,h) in listOf(480 to 640,2120 to 3000,3000 to 2120)) {
            val b=PixelBox(0,0,w,h)
            for(d in listOf(0.75f,1f,3.5f)) assertEquals(maxOf(w,h)-1,SelectionMotion.maxOffsetPx(d,b))
        }
        assertEquals(0,SelectionMotion.maxOffsetPx(Float.NaN,PixelBox(0,0,480,640)))
        var reads=0
        assertNull(LiveFrameFingerprint.sample(4096,4096,PixelBox(0,0,4096,4096)){_,_,_->reads++})
        assertEquals(0,reads)
        println("OBS bounds and oversized-before-read controls complete")
    }
    @Test fun f02_largeBothAxesAndDirections() {
        val w=480; val h=640; val a=dense(w,h); val b=fp(a,w,h); val p=PixelBox(220,240,240,260)
        val errors=mutableListOf<String>()
        for(s in listOf(PageShift(96,0),PageShift(-112,0),PageShift(0,180),PageShift(0,-160))) {
            val m=b.matchBounded(fp(move(a,w,h,s.dx,s.dy),w,h),p,1f); log("axis-$s",m)
            if(m.kind!=FrameMatchKind.MOVED || m.shift!=s) errors += "$s -> ${m.kind}/${m.shift}/${m.reason}"
        }
        assertTrue(errors.joinToString(),errors.isEmpty())
    }
    @Test fun f03_largeDiagonalVisibleSelectedWord() {
        val w=480; val h=640; val a=dense(w,h); val p=PixelBox(40,60,60,80)
        // A unique rigid translation leaves 25% of the original page and the whole word visible.
        val m=fp(a,w,h).matchBounded(fp(move(a,w,h,240,320),w,h),p,1f)
        moved("half-screen-diagonal",m,240,320)
    }
    @Test fun f04_paddedRgbaFingerprintSameSemantics() {
        val w=480; val h=640; val body=PixelBox(8,12,472,628); val a=dense(w,h)
        val q=move(a,w,h,72,84); val base=fp(a,w,h,body); val p=PixelBox(140,200,160,220)
        val expected=base.matchBounded(fp(q,w,h,body),p,1f)
        val buf=plane(q,w,h,8,32).asReadOnlyBuffer();buf.position(7)
        val actual=base.matchBoundedPlane(w,h,body,w*8+32,8,buf,p,1f)
        log("plane",actual); assertEquals(expected,actual); assertEquals(7,buf.position())
        moved("fingerprint",expected,72,84)
        assertFalse(base.matchBoundedPlane(w,h,body,w*8+32,8,ByteBuffer.allocate(5),p,1f).accepted)
    }
    @Test fun f05_stationarySmallHeaderAnimationAccepted() {
        val w=480;val h=640;val a=dense(w,h);val q=a.copyOf()
        for(y in 8..12) for(x in 10..99) q[y*w+x]=q[y*w+x] xor 0xFFFFFF
        val b=fp(a,w,h);val next=fp(q,w,h);assertFalse(b.matches(next))
        val m=b.matchBounded(next,PixelBox(220,240,240,260),1f);log("header-animation",m)
        assertEquals(FrameMatchKind.SAME,m.kind);assertEquals(FrameMatchReason.STABLE_CONTENT_WITH_ANIMATION,m.reason)
        assertEquals(PageShift.NONE,m.shift)
    }
    @Test fun f06_singleProtectedPixelChangeRejectedAtZeroAndShift() {
        val w=480;val h=640;val a=dense(w,h);val p=PixelBox(140,200,160,220);val base=fp(a,w,h)
        val errors=mutableListOf<String>()
        for(s in listOf(PageShift.NONE,PageShift(80,96))) {
            val q=move(a,w,h,s.dx,s.dy);val i=(210+s.dy)*w+150+s.dx;q[i]=q[i] xor 0xFFFFFF
            val m=base.matchBounded(fp(q,w,h),p,1f);log("protected-change-$s",m)
            if(m.accepted) errors += "$s accepted $m"
        }
        assertTrue(errors.joinToString(),errors.isEmpty())
    }
    @Test fun f07_protectionMaskAndBlankFramesFailClosed() {
        val w=240;val h=320;val a=dense(w,h);val base=fp(a,w,h);val p=PixelBox(80,100,100,120)
        val mask=base.matchBounded(fp(a,w,h),p,1f,listOf(PixelBox(85,105,86,106)))
        log("protection-exclusion",mask);assertEquals(FrameMatchReason.PROTECTION_EXCLUDED,mask.reason)
        for(c in listOf(0,white)) {
            val blank=fp(IntArray(w*h){c},w,h)
            assertFalse(blank.matchBounded(blank,p,1f).accepted)
            assertFalse(base.matchBounded(blank,p,1f).accepted)
            println("OBS blank=$c rejected against itself and against informative baseline")
        }
    }
    @Test fun f08_sparseActualMotionMustNotBecomeZeroInStrokeProbe() {
        val w=800;val h=1000
        val normal=IntArray(w*h){white}
        for((i,xy) in listOf(100 to 100,300 to 200,500 to 500,600 to 700).withIndex())
            word(normal,w,h,listOf("CRAWL","READ","WORDS","CLOUD")[i],xy.first,xy.second)
        // Real thin lowercase "ill" at a legitimate subpixel/grid alignment: all stems are
        // between the four-pixel context lattice, whose white samples still see ink neighbours.
        val thin=IntArray(w*h){white}
        for((x,y) in listOf(101 to 100,301 to 200,501 to 500,601 to 700)) {
            thin[y*w+x]=0
            for(yy in y+2..y+6) thin[yy*w+x]=0
            for(xx in listOf(x+4,x+8)) for(yy in y..y+6) thin[yy*w+xx]=0
        }
        val errors=mutableListOf<String>()
        for((label,a) in listOf("four-readable-words" to normal,"thin-ill-grid-alignment" to thin)) {
            val q=move(a,w,h,96,128);val base=fp(a,w,h)
            assertTrue("$label carries actual identity",base.hasIdentityInformation())
            assertFalse("$label actual translated pixels differ",base.matches(fp(q,w,h)))
            println("METRIC $label black_pixels=${a.count{it!=white}} different_pixels=${a.indices.count{a[it]!=q[it]}} total=${a.size}")
            val m=base.probeBoundedPlane(w,h,PixelBox(0,0,w,h),w*4,4,plane(q,w,h),1f)
            log("$label-true-96-128-stroke-probe",m)
            if(m.kind==FrameMatchKind.SAME) errors += "$label real nonzero motion recorded as zero: $m"
        }
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun f09_sparseUniqueGlyphPageLargeShiftDetected() {
        val w=480;val h=640;val a=glyphs(w,h);val p=PixelBox(90,110,120,118)
        val q=move(a,w,h,80,96);val base=fp(a,w,h)
        assertTrue(base.hasIdentityInformation());assertFalse(base.matches(fp(q,w,h)))
        val m=base.matchBounded(fp(q,w,h),p,1f);moved("unique-glyphs",m,80,96)
    }
    @Test fun f10_periodicAmbiguousTranslationRejected() {
        val w=240;val h=320
        val a=IntArray(w*h){ i -> mix((i/w%16)*16+(i%w%16)) }
        val q=IntArray(w*h){i -> mix(((i/w+5)%16)*16+((i%w+7)%16))}
        val m=fp(a,w,h).matchBounded(fp(q,w,h),PixelBox(90,120,110,140),1f)
        log("periodic-ambiguous",m);assertFalse(m.accepted)
        assertTrue(m.reason in setOf(FrameMatchReason.AMBIGUOUS_SHIFT,FrameMatchReason.NO_UNIQUE_SHIFT))
    }
    @Test(timeout=30000) fun f11_tabletSizedDenseShiftAndMeasuredCost() {
        val w=2120;val h=3000;val a=dense(w,h);val q=move(a,w,h,96,128);val base=fp(a,w,h)
        val next=fp(q,w,h);val t=System.nanoTime()
        val m=base.matchBounded(next,PixelBox(600,900,630,930),1f)
        val ms=(System.nanoTime()-t)/1_000_000.0
        println("METRIC tablet=${w}x$h match_ms=$ms identity_bytes=${w.toLong()*h*2}")
        moved("tablet-dense",m,96,128)
        // This bounds a host regression run only; no claim about Android wall time is made.
    }
    @Test fun f12_strokeTimingGuardDeadlineAndOriginalCredentials() {
        val w=480;val h=640;val body=PixelBox(0,0,w,h);val a=dense(w,h);val base=fp(a,w,h)
        val deletes=DeletionGate();val original=deletes.capture()
        val s=LiveSelectionSession(8,9,original,w,h,body,base)
        val ink=PixelBox(180,240,210,260)
        s.selection=HighlighterSelection(listOf(HighlighterStroke(listOf(HighlighterPoint(185f,250f),HighlighterPoint(205f,250f)))),PixelBox(140,200,240,300),4f,ink)
        s.beginStrokeMotion();assertFalse(s.beginMotionObservation(0));assertTrue(s.beginMotionObservation(100_000_000))
        assertFalse(s.beginMotionObservation(110_000_000));s.noteMotionObservation(PageShift(80,96))
        for(i in 1..7) assertTrue(s.beginMotionObservation(100_000_000+i*24_000_000L))
        assertFalse(s.beginMotionObservation(400_000_000))
        val m=base.matchBounded(fp(move(a,w,h,80,96),w,h),ink.shifted(-80,-96),1f)
        moved("timing-large-shift",m,80,96)
        val selected=s.selection!!
        assertTrue(s.decideStrokeShift(m,s.strokeMotionEvidence(m.shift)) is StrokeShiftDecision.UseEntryCoordinates)
        assertSame(selected,(s.decideStrokeShift(m,s.strokeMotionEvidence(m.shift)) as StrokeShiftDecision.UseEntryCoordinates).selection)
        s.noteMotionUnavailable();assertTrue(s.decideStrokeShift(m,s.strokeMotionEvidence(m.shift)) is StrokeShiftDecision.Reselect)
        s.endStrokeMotion();assertFalse(s.beginMotionObservation(500_000_000))
        assertTrue(SelectionMotion.admitStrokeShift(m,selected,ink,w,h,StrokeShiftEvidence.StartEndOffsetOnly) is StrokeShiftDecision.Reselect)
        val guard=LivePageGuard(8,body,base);guard.invalidate();assertEquals(FrameMatchReason.GUARD_INVALID,guard.matchBounded(base,ink,1f).reason)
        val req=FreshFrameRequest(9,1500,body);req.arm(100);assertFalse(req.offer(99,1000,false));assertFalse(req.offer(101,1000,true))
        assertTrue(req.offer(102,1499,false));assertFalse(req.complete(1500))
        deletes.recordDeletion("test-key");assertEquals(original,s.deletionCredential);assertTrue(deletes.isStale("test-key",s.deletionCredential))
        val gate=QueryGate();val token=gate.next();gate.next();assertFalse(gate.accepts(token))
        println("OBS complete timing/cap/stale-guard/deadline/original-credential/no-double-shift controls")
    }
}
