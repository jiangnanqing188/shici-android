package com.shici.app.core

import kotlin.math.abs

/**
 * In-memory identity of one page body.
 *
 * The identity keeps one quantized colour per pixel in screen coordinates: RGB 5/6/5 in a
 * [ShortArray]. Keeping the pixels (rather than summed tiles or a sparse point grid) means that a
 * thin glyph row, a one-pixel horizontal/vertical move and an equal-brightness colour permutation
 * all remain observable. Two channel values are treated as equal when their quantized 5/6/5
 * buckets differ by at most [COLOR_BUCKET_TOLERANCE]; this absorbs ±1-level frame noise (white
 * 0xFF and 0xFE quantize identically) while real glyph contrast is far larger.
 *
 * Bounded resources:
 *  - at most [MAX_IDENTITY_PIXELS] pixels / [MAX_IDENTITY_BYTES] per identity; larger bodies fail
 *    closed at [sample] and are never retained;
 *  - at most two identities are alive during one lookup (entry + lift); the entry identity is
 *    dropped as soon as the lift frame is verified, and the guard keeps only the lift identity;
 *  - the guard's identity lives at most `PAGE_GUARD_TIMEOUT_MS` (60 s) and is released on save,
 *    cancel, dismissal, resize, stop or service destruction;
 *  - nothing is written to disk, uploaded, rendered or retained as a Bitmap/string.
 *
 * [matches] is intentionally strict: every non-excluded pixel must agree. A page that cannot be
 * proven to be the same page is treated as changed and the lookup is cancelled; it is better to
 * ask the user to select again than to OCR or save a word from a different page.
 */
class LiveFrameFingerprint private constructor(
    private val width: Int,
    private val height: Int,
    private val body: PixelBox,
    private val encoded: ShortArray
) {
    /** Strict whole-body comparison used between the clean entry frame and the clean lift frame. */
    fun matches(other: LiveFrameFingerprint): Boolean = matches(other, emptyList())

    /**
     * Compares every pixel outside [excluded]. Exclusions are used only for this application's own
     * visible card/entry windows: masking is per pixel, so a rectangle that only touches a region
     * cannot hide the still-visible pixels around it.
     */
    fun matches(other: LiveFrameFingerprint, excluded: List<PixelBox>): Boolean {
        if (!sameShape(other.width, other.height, other.body)) return false
        if (encoded.size != other.encoded.size) return false
        val rowWidth = body.width
        for (row in 0 until body.height) {
            val screenY = body.top + row
            val base = row * rowWidth
            for (column in 0 until rowWidth) {
                if (isExcluded(body.left + column, screenY, excluded)) continue
                if (!closeEnough(encoded[base + column], other.encoded[base + column].toInt())) return false
            }
        }
        return true
    }

    /**
     * Compares this identity against a fresh RGBA_8888 plane without allocating a second identity.
     * Returns false when the region is invalid/too short, a read fails, or any visible pixel
     * differs; the caller treats every false result as "cannot prove same page" and cancels.
     * The buffer position is never advanced.
     */
    fun matchesPlane(width: Int, height: Int, body: PixelBox, rowStride: Int, pixelStride: Int,
                     buffer: java.nio.ByteBuffer, excluded: List<PixelBox>): Boolean {
        if (!sameShape(width, height, body)) return false
        if (!usablePlaneGeometry(width, height, body, rowStride, pixelStride, buffer.limit())) return false
        val rowWidth = body.width
        for (y in body.top until body.bottom) {
            var offset = y * rowStride + body.left * pixelStride
            val base = (y - body.top) * rowWidth
            for (column in 0 until rowWidth) {
                val red = buffer.get(offset).toInt() and 255
                val green = buffer.get(offset + 1).toInt() and 255
                val blue = buffer.get(offset + 2).toInt() and 255
                offset += pixelStride
                if (isExcluded(body.left + column, y, excluded)) continue
                if (!closeEnough(encoded[base + column], encode(red, green, blue))) return false
            }
        }
        return true
    }

    /**
     * Match against this fixed baseline across the captured screen. The selected protection is
     * checked exhaustively within the original colour tolerance; only surrounding context is
     * sampled with a small unrelated-animation allowance. A nonzero shift must be unique among
     * the bounded candidate set and preserve the selected region. Results never rebase the entry.
     * The original matches()/matchesPlane() APIs above retain their strict historical semantics.
     */
    fun matchBounded(
        other: LiveFrameFingerprint,
        protection: PixelBox,
        density: Float,
        excluded: List<PixelBox> = emptyList(),
        allowTranslation: Boolean = true
    ): FrameMatch {
        val maxOffset = SelectionMotion.maxOffsetPx(density, body)
        SelectionMotion.validate(body, protection, excluded, density, maxOffset)?.let { return it }
        if (!sameShape(other.width, other.height, other.body)) {
            return SelectionMotion.rejected(FrameMatchKind.UNAVAILABLE,
                FrameMatchReason.INVALID_GEOMETRY, protection, maxOffset)
        }
        return try {
            SelectionMotion.search(body, encoded,
                SelectionMotion.FingerprintPixels(other.encoded), protection, excluded, maxOffset,
                allowTranslation = allowTranslation)
        } catch (_: RuntimeException) {
            SelectionMotion.rejected(FrameMatchKind.UNAVAILABLE,
                FrameMatchReason.INVALID_GEOMETRY, protection, maxOffset)
        }
    }

    /**
     * Plane version of [matchBounded]: reads an RGBA_8888 image plane directly (row/pixel strides
     * honoured), without sampling or retaining a second fingerprint. The buffer is only read via
     * absolute offsets, so its position is never advanced.
     */
    fun matchBoundedPlane(
        width: Int, height: Int, body: PixelBox, rowStride: Int, pixelStride: Int,
        buffer: java.nio.ByteBuffer, protection: PixelBox, density: Float,
        excluded: List<PixelBox> = emptyList(), allowTranslation: Boolean = true,
        stillObserving: () -> Boolean = { true }
    ): FrameMatch {
        val maxOffset = SelectionMotion.maxOffsetPx(density, body)
        SelectionMotion.validate(body, protection, excluded, density, maxOffset)?.let { return it }
        if (!sameShape(width, height, body) ||
            !usablePlaneGeometry(width, height, body, rowStride, pixelStride, buffer.limit())) {
            return SelectionMotion.rejected(FrameMatchKind.UNAVAILABLE,
                FrameMatchReason.INVALID_GEOMETRY, protection, maxOffset)
        }
        return try {
            SelectionMotion.search(body, encoded,
                SelectionMotion.PlanePixels(rowStride, pixelStride, buffer),
                protection, excluded, maxOffset, allowTranslation, stillObserving)
        } catch (_: RuntimeException) {
            SelectionMotion.rejected(FrameMatchKind.UNAVAILABLE,
                FrameMatchReason.PLANE_UNREADABLE, protection, maxOffset)
        }
    }

    /**
     * Stroke-time, shift-only probe against this fixed baseline.
     *
     * It returns the real bounded shift the visible page had in this compositor frame, or an
     * unaccepted result. There is no selected word yet while the finger is down, so the probe
     * carries no protection box: this application's ink/chips are excluded per pixel and the rest
     * of the visible page must prove a unique whole-page translation. No fingerprint, bitmap or
     * text is retained; the caller only keeps the resulting shift as timing/coordinate evidence.
     * A blank page, too few anchors, an ambiguous shift or any unreadable plane is returned as
     * unaccepted and must be treated as doubt, never as zero movement. Once the owner ends,
     * [stillObserving] can stop the expensive shift search without accepting a partial match.
     */
    fun probeBoundedPlane(
        width: Int, height: Int, body: PixelBox, rowStride: Int, pixelStride: Int,
        buffer: java.nio.ByteBuffer, density: Float, excluded: List<PixelBox> = emptyList(),
        stillObserving: () -> Boolean = { true }
    ): FrameMatch {
        val maxOffset = SelectionMotion.maxOffsetPx(density, body)
        if (!density.isFinite() || density <= 0f) {
            return SelectionMotion.rejected(FrameMatchKind.UNAVAILABLE,
                FrameMatchReason.INVALID_DENSITY, null, maxOffset)
        }
        if (!sameShape(width, height, body) ||
            !usablePlaneGeometry(width, height, body, rowStride, pixelStride, buffer.limit())) {
            return SelectionMotion.rejected(FrameMatchKind.UNAVAILABLE,
                FrameMatchReason.INVALID_GEOMETRY, null, maxOffset)
        }
        return try {
            SelectionMotion.search(body, encoded,
                SelectionMotion.PlanePixels(rowStride, pixelStride, buffer),
                null, excluded, maxOffset, stillObserving = stillObserving)
        } catch (_: RuntimeException) {
            SelectionMotion.rejected(FrameMatchKind.UNAVAILABLE,
                FrameMatchReason.PLANE_UNREADABLE, null, maxOffset)
        }
    }

    /**
     * Whether this retained baseline carries enough page information for a SAME result to mean
     * anything. A pure white/black or featureless page must not be trusted as identity, even when
     * every compared pixel agrees. [excluded] are this application's own visible windows; a clean
     * entry/lift baseline passes the default empty list, because masking our current card or ink
     * from a baseline that predates it could hide the only page text and make a real page blank.
     */
    fun hasIdentityInformation(excluded: List<PixelBox> = emptyList()): Boolean =
        SelectionMotion.hasIdentityInformation(body, excluded, encoded)

    private fun sameShape(otherWidth: Int, otherHeight: Int, otherBody: PixelBox): Boolean =
        width == otherWidth && height == otherHeight && body == otherBody

    companion object {
        // 2 bytes per pixel. 8 Mi pixels (16 MiB) covers a 3000x2120 tablet page with headroom;
        // the capture layer already caps raw images at 16 Mi pixels, but anything above this
        // identity budget is rejected rather than retained.
        private const val MAX_IDENTITY_PIXELS = 8_388_608L
        const val MAX_IDENTITY_BYTES = MAX_IDENTITY_PIXELS * 2
        // One 5/6/5 bucket is 8 levels in red/blue and 4 in green. Allowing a one-bucket
        // difference absorbs ±1-level noise without missing real glyph contrast.
        private const val COLOR_BUCKET_TOLERANCE = 1

        /**
         * Samples the entire [body] once, row by row. No row buffer or bitmap is retained.
         * Invalid bodies and bodies above the identity budget fail closed before any row is read;
         * exceptions from [readRow] propagate.
         */
        fun sample(width: Int, height: Int, body: PixelBox,
                   readRow: (x: Int, y: Int, pixels: IntArray) -> Unit): LiveFrameFingerprint? =
            sampleRows(width, height, body) { y, row -> readRow(body.left, y, row) }

        /**
         * Samples straight from an RGBA_8888 image plane buffer, avoiding a full-screen bitmap
         * allocation for every observed ambient frame. Bytes are interpreted as R, G, B per pixel;
         * row padding ([rowStride]) and multi-byte pixels ([pixelStride]) are honoured. The caller
         * keeps ownership of the image and closes it after this call.
         */
        fun sample(width: Int, height: Int, body: PixelBox, rowStride: Int, pixelStride: Int,
                   buffer: java.nio.ByteBuffer): LiveFrameFingerprint? {
            if (!usablePlaneGeometry(width, height, body, rowStride, pixelStride, buffer.limit())) return null
            return sampleRows(width, height, body) { y, row ->
                var offset = y * rowStride + body.left * pixelStride
                for (i in row.indices) {
                    val red = buffer.get(offset).toInt() and 255
                    val green = buffer.get(offset + 1).toInt() and 255
                    val blue = buffer.get(offset + 2).toInt() and 255
                    row[i] = (red shl 16) or (green shl 8) or blue
                    offset += pixelStride
                }
            }
        }

        /**
         * Validates a real RGBA_8888 plane layout before any byte is read. A positive stride is
         * not enough: pixelStride 1 overlaps the three sampled channels of adjacent pixels, and a
         * rowStride shorter than one full row overlaps rows. Both would silently re-index a
         * changed glyph to a white prefix and wrongly prove "same page". Valid row padding,
         * pixelStride 4/8, non-zero body offsets, buffer slices/direct/read-only buffers and a
         * final pixel that supplies only RGB (no alpha byte) remain accepted.
         */
        internal fun usablePlaneGeometry(width: Int, height: Int, body: PixelBox,
                                        rowStride: Int, pixelStride: Int, limit: Int): Boolean {
            if (width <= 0 || height <= 0 || body.left < 0 || body.top < 0 ||
                body.right > width || body.bottom > height || body.width <= 0 || body.height <= 0) return false
            if (pixelStride < 3) return false
            if (rowStride.toLong() < width.toLong() * pixelStride) return false
            val required = (body.bottom - 1).toLong() * rowStride +
                (body.right - 1).toLong() * pixelStride + 3
            return required <= limit
        }

        private fun sampleRows(width: Int, height: Int, body: PixelBox,
                               readRow: (y: Int, pixels: IntArray) -> Unit): LiveFrameFingerprint? {
            if (width <= 0 || height <= 0 || body.left < 0 || body.top < 0 ||
                body.right > width || body.bottom > height || body.width <= 0 || body.height <= 0) return null
            val pixelCount = body.width.toLong() * body.height.toLong()
            if (pixelCount > MAX_IDENTITY_PIXELS) return null
            val encoded = ShortArray(pixelCount.toInt())
            val rowPixels = IntArray(body.width)
            var out = 0
            for (y in body.top until body.bottom) {
                readRow(y, rowPixels)
                for (x in 0 until body.width) {
                    val pixel = rowPixels[x]
                    encoded[out++] = encode((pixel ushr 16) and 255,
                        (pixel ushr 8) and 255, pixel and 255).toShort()
                }
            }
            return LiveFrameFingerprint(width, height, body, encoded)
        }

        internal fun encode(red: Int, green: Int, blue: Int): Int =
            ((red shr 3) shl 11) or ((green shr 2) shl 5) or (blue shr 3)

        /** Same 5/6/5 bucket encoding used by the strict comparisons, exposed to bounded core. */
        internal fun encodeColor(red: Int, green: Int, blue: Int): Int = encode(red, green, blue)

        /**
         * The original per-channel bucket tolerance: two encoded 5/6/5 colours agree when every
         * channel differs by at most [COLOR_BUCKET_TOLERANCE]. Both arguments are bucket values
         * (0..0xFFFF); strict comparison and bounded translation verification share this rule.
         */
        internal fun bucketsMatch(a: Int, b: Int): Boolean {
            val left = a and 0xFFFF
            val right = b and 0xFFFF
            return abs(((left ushr 11) and 0x1F) - ((right ushr 11) and 0x1F)) <= COLOR_BUCKET_TOLERANCE &&
                abs(((left ushr 5) and 0x3F) - ((right ushr 5) and 0x3F)) <= COLOR_BUCKET_TOLERANCE &&
                abs((left and 0x1F) - (right and 0x1F)) <= COLOR_BUCKET_TOLERANCE
        }

        private fun closeEnough(a: Short, b: Int): Boolean = bucketsMatch(a.toInt(), b)

        internal fun isExcluded(x: Int, y: Int, excluded: List<PixelBox>): Boolean {
            for (box in excluded) {
                if (x >= box.left && x < box.right && y >= box.top && y < box.bottom) return true
            }
            return false
        }
    }
}

/**
 * Page identity for one query that has already passed the clean lift-frame check.
 *
 * The guard keeps the lift-frame identity (never pixels of a Bitmap) and is invalidated by an
 * observed page change, dismissal, save completion or its bounded lifetime. It is not persisted
 * and carries no OCR text.
 */
class LivePageGuard(
    override val generation: Long,
    override val body: PixelBox,
    val baseline: LiveFrameFingerprint
) : LookupPageGuard {
    @Volatile private var valid = true

    override val isValid: Boolean get() = valid

    /** Returns true only for the first invalidation, so old callbacks cannot win twice. */
    @Synchronized override fun invalidate(): Boolean {
        if (!valid) return false
        valid = false
        return true
    }

    /** The original strict comparison, kept byte-for-byte in meaning for existing callers. */
    fun matches(frame: LiveFrameFingerprint, excluded: List<PixelBox>): Boolean =
        valid && baseline.matches(frame, excluded)

    /**
     * Bounded comparison against this guard's fixed baseline. Invalidation wins over every result;
     * a moved frame is never adopted as a new baseline here.
     */
    fun matchBounded(
        frame: LiveFrameFingerprint,
        protection: PixelBox,
        density: Float,
        excluded: List<PixelBox> = emptyList()
    ): FrameMatch = if (valid) {
        baseline.matchBounded(frame, protection, density, excluded)
    } else {
        SelectionMotion.rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.GUARD_INVALID,
            protection, SelectionMotion.maxOffsetPx(density, body))
    }
}

/**
 * One non-persistent live lookup attempt. The deletion credential is captured when the entry is
 * tapped and carried through the entry frame, the gesture, the lift verification frame and OCR.
 */
class LiveSelectionSession(
    val generation: Long,
    val entryToken: Long,
    val deletionCredential: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val body: PixelBox,
    val entryFingerprint: LiveFrameFingerprint
) {
    var verifyToken: Long = -1L
    var selection: HighlighterSelection? = null

    // Bounded stroke-time page observations, fed by the capture thread while the finger is down.
    // They retain no pixels, fingerprint or text: only the accepted whole-page shift, a bounded
    // attempt count and a conservative "could not observe" flag survive until the lift decision.
    private val motionLock = Any()
    private var motionStarted = false
    private var motionActive = false
    private var motionDoubt = false
    private var motionAttempts = 0
    private var motionObservations = 0
    private var motionShift: PageShift? = null
    private var motionShiftStable = true
    private var motionStartedNanos = 0L
    private var motionLastAttemptNanos = 0L
    private var motionProbePending = false

    /** Starts a fresh evidence window at ACTION_DOWN; earlier taps/frames are forgotten. */
    fun beginStrokeMotion(startedNanos: Long = 0L) {
        synchronized(motionLock) {
            motionStarted = true
            motionActive = true
            motionDoubt = false
            motionAttempts = 0
            motionObservations = 0
            motionShift = null
            motionShiftStable = true
            motionStartedNanos = startedNanos
            motionLastAttemptNanos = 0L
            motionProbePending = false
        }
    }

    /**
     * Closes the observation window at lift, before the overlay (and its ink) is removed. A frame
     * whose probe was already in progress can finish only its reserved observation. A pending
     * result is not the legacy no-sampled-frame case; until it finishes the stroke is unproven.
     */
    fun endStrokeMotion() {
        synchronized(motionLock) {
            motionActive = false
        }
    }

    fun isStrokeMotionActive(): Boolean = synchronized(motionLock) { motionActive }

    /** No lift match can recover an observed bad frame or disagreement between shifts. */
    fun hasUnprovenStrokeMotion(): Boolean = synchronized(motionLock) {
        motionDoubt || !motionShiftStable || motionProbePending
    }

    /**
     * Reserves one bounded stroke-time comparison for a compositor frame at [timestampNanos].
     *
     * The per-stroke hard budget and minimum time spacing keep the probe from scanning every
     * frame. An unsampled frame carries no evidence; a known unavailable plane is still doubt.
     * The capture thread must supply the compositor timestamp, not its receipt time.
     */
    fun beginMotionObservation(timestampNanos: Long, planeAvailable: Boolean = true): Boolean = synchronized(motionLock) {
        if (!motionActive || motionDoubt || !motionShiftStable || timestampNanos <= 0L ||
            timestampNanos < motionStartedNanos) return@synchronized false
        // A known unreadable plane remains doubt even if regular comparisons hit their cap.
        if (!planeAvailable) {
            motionDoubt = true
            return@synchronized false
        }
        if (motionAttempts >= MAX_STROKE_MOTION_ATTEMPTS) return@synchronized false
        if (motionLastAttemptNanos > 0L &&
            timestampNanos - motionLastAttemptNanos < MIN_STROKE_OBSERVATION_INTERVAL_NANOS) {
            return@synchronized false
        }
        motionAttempts += 1
        motionLastAttemptNanos = timestampNanos
        motionProbePending = true
        true
    }

    /**
     * One frame captured while the stroke was active proved [shift] relative to the fixed entry
     * baseline. A different accepted shift later in the same stroke makes a single uniform
     * screen-coordinate reuse unprovable. The capture thread supplies the reserved compositor
     * timestamp; an old probe cannot finish into a subsequent gesture on the same layer.
     */
    fun noteMotionObservation(shift: PageShift?, timestampNanos: Long? = null) {
        synchronized(motionLock) {
            if (timestampNanos == null) {
                if (!motionActive) return
            } else if (!motionProbePending || timestampNanos != motionLastAttemptNanos) return
            motionProbePending = false
            if (shift == null) {
                motionDoubt = true
                return
            }
            motionObservations += 1
            val previous = motionShift
            if (previous == null) {
                motionShift = shift
            } else if (previous != shift) {
                motionShiftStable = false
            }
        }
    }

    /** A frame during the stroke was too blank/ambiguous to prove the page; no optimistic default. */
    fun noteMotionUnavailable() = noteMotionObservation(null)

    /** The one shift every usable stroke-time frame agreed on, or null when none was proven. */
    fun observedStrokeShift(): PageShift? = synchronized(motionLock) {
        val observed = motionShift
        if (!motionStarted || motionDoubt || motionProbePending || !motionShiftStable ||
            motionObservations < 1 || observed == null) null else observed
    }

    /**
     * Evidence for reusing the stroke at lift.
     *
     * - all usable stroke-time observations and the lift match agree on one shift → that real
     *   observation, including a nonzero shift drawn on the already-moved page;
     * - no usable observation, no doubt and the lift frame is strictly unmoved → the explicitly
     *   named legacy endpoints-only stillness path;
     * - anything else (disagreeing shifts, bad frame, observed movement but zero net lift, no
     *   observation with a moved lift) → [StrokeShiftEvidence.StartEndOffsetOnly], which is refused.
     */
    fun strokeMotionEvidence(finalShift: PageShift): StrokeShiftEvidence = synchronized(motionLock) {
        if (!motionStarted || motionDoubt || motionProbePending || !motionShiftStable) {
            return@synchronized StrokeShiftEvidence.StartEndOffsetOnly
        }
        val observed = motionShift
        when {
            observed != null && motionObservations >= 1 ->
                if (observed == finalShift) StrokeShiftEvidence.StrokeObservedShift(observed)
                else StrokeShiftEvidence.StartEndOffsetOnly
            finalShift == PageShift.NONE -> StrokeShiftEvidence.StrictEndpointStillness
            else -> StrokeShiftEvidence.StartEndOffsetOnly
        }
    }

    /**
     * Bounded entry/lift protection. The necessary painted-ink box is in the stroke's own screen
     * coordinates. When stroke-time frames proved a nonzero offset, the same box is translated
     * back into entry coordinates for the fixed baseline; if that entry position was not fully
     * visible/readable, no match is attempted. With no proven offset, the box is clamped to the
     * captured page body (system bars are not page pixels).
     */
    private fun strokeProtection(): StrokeProtection? {
        val selected = selection ?: return null
        val raw = selected.inkBounds?.takeIf { it.width > 0 && it.height > 0 } ?: return null
        val observed = observedStrokeShift()
        if (observed != null && (observed.dx != 0 || observed.dy != 0)) {
            val baseline = raw.shifted(-observed.dx, -observed.dy)
            if (!insideBody(baseline) || !insideBody(raw)) return null
            return StrokeProtection(baseline, raw)
        }
        val clamped = clampToBody(raw) ?: return null
        return StrokeProtection(clamped, clamped)
    }

    private fun insideBody(box: PixelBox): Boolean =
        box.left >= body.left && box.top >= body.top &&
            box.right <= body.right && box.bottom <= body.bottom

    private fun clampToBody(box: PixelBox): PixelBox? {
        val left = maxOf(box.left, body.left)
        val top = maxOf(box.top, body.top)
        val right = minOf(box.right, body.right)
        val bottom = minOf(box.bottom, body.bottom)
        return if (right > left && bottom > top) PixelBox(left, top, right, bottom) else null
    }

    private class StrokeProtection(val baseline: PixelBox, val candidate: PixelBox)

    /**
     * The necessary selected-ink box in the accepted lift frame's coordinates, for the pending and
     * guard comparisons that follow. Null when it cannot be placed safely.
     */
    fun liftProtection(): PixelBox? = strokeProtection()?.candidate

    /** The entry identity that bounded matching must keep using; never replace it with a lift frame. */
    val boundedBaseline: LiveFrameFingerprint get() = entryFingerprint

    /**
     * Bounded entry/lift comparison for the stroke's necessary ink region. Returns
     * [FrameMatchReason.PROTECTION_MISSING] before a stroke exists; the entry baseline is never
     * updated from the returned shift.
     */
    fun matchLift(
        frame: LiveFrameFingerprint,
        density: Float,
        excluded: List<PixelBox> = emptyList(),
        allowTranslation: Boolean = true
    ): FrameMatch {
        val protection = strokeProtection() ?: return SelectionMotion.rejected(
            FrameMatchKind.UNAVAILABLE, FrameMatchReason.PROTECTION_MISSING, null,
            SelectionMotion.maxOffsetPx(density, body))
        return entryFingerprint.matchBounded(frame, protection.baseline, density, excluded, allowTranslation)
    }

    /** Plane version of [matchLift] for a fresh RGBA_8888 capture frame. */
    fun matchLiftPlane(
        width: Int, height: Int, rowStride: Int, pixelStride: Int,
        buffer: java.nio.ByteBuffer, density: Float,
        excluded: List<PixelBox> = emptyList()
    ): FrameMatch {
        val protection = strokeProtection() ?: return SelectionMotion.rejected(
            FrameMatchKind.UNAVAILABLE, FrameMatchReason.PROTECTION_MISSING, null,
            SelectionMotion.maxOffsetPx(density, body))
        return entryFingerprint.matchBoundedPlane(width, height, body, rowStride, pixelStride,
            buffer, protection.baseline, density, excluded)
    }

    /** Applies [SelectionMotion.admitStrokeShift] to this session's selection and screen size. */
    fun decideStrokeShift(
        match: FrameMatch,
        evidence: StrokeShiftEvidence
    ): StrokeShiftDecision {
        val selected = selection
            ?: return StrokeShiftDecision.Reselect(StrokeShiftReason.MATCH_NOT_ACCEPTED)
        val protection = strokeProtection()?.candidate
            ?: return StrokeShiftDecision.Reselect(StrokeShiftReason.PROTECTION_MISMATCH)
        return SelectionMotion.admitStrokeShift(
            match, selected, protection, screenWidth, screenHeight, evidence)
    }

    private companion object {
        /** Hard per-stroke cap on bounded page comparisons (not on captured frames). */
        const val MAX_STROKE_MOTION_ATTEMPTS = 8
        /** At most one bounded comparison per this interval, in compositor nanoseconds. */
        const val MIN_STROKE_OBSERVATION_INTERVAL_NANOS = 24_000_000L
    }
}
