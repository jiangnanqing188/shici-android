package com.shici.app.core

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/** One whole-page content translation, in screenshot pixels, relative to a fixed baseline frame. */
data class PageShift(val dx: Int, val dy: Int) {
    companion object {
        val NONE = PageShift(0, 0)
    }
}

/**
 * Identity of the one word proved under a candidate page shift.
 *
 * [bounds] must be expressed in the **baseline** frame: a callback that hits a word in the
 * candidate frame translates the word's bounds back by the same [PageShift]. Comparing text alone
 * is not enough because the same spelling can occur elsewhere on the page; the line index and the
 * baseline geometry make a false "same word" proof visible.
 */
data class StrokeWordIdentity(
    val text: String,
    val lineIndex: Int,
    val bounds: FloatBox
) {
    /** Moves candidate-frame bounds back into the fixed baseline frame. */
    fun normalizedToBaseline(shift: PageShift): StrokeWordIdentity = copy(
        bounds = FloatBox(
            bounds.left - shift.dx,
            bounds.top - shift.dy,
            bounds.right - shift.dx,
            bounds.bottom - shift.dy
        )
    )

    fun trimmed(): StrokeWordIdentity = copy(text = text.trim())

    /**
     * Same original word, in the same OCR line, at the same baseline position. Half a pixel of
     * tolerance absorbs integer-shift round-tripping; anything larger is a different occurrence or
     * a different word.
     */
    fun sameWordAs(other: StrokeWordIdentity): Boolean =
        text == other.text && lineIndex == other.lineIndex &&
            abs(bounds.left - other.bounds.left) <= 0.5f &&
            abs(bounds.top - other.bounds.top) <= 0.5f &&
            abs(bounds.right - other.bounds.right) <= 0.5f &&
            abs(bounds.bottom - other.bounds.bottom) <= 0.5f
}

/** Moves [this] box by a whole-pixel translation without clamping or clipping. */
fun PixelBox.shifted(dx: Int, dy: Int): PixelBox =
    PixelBox(left + dx, top + dy, right + dx, bottom + dy)

enum class FrameMatchKind {
    /** Strict zero-shift identity: every compared pixel equals the baseline. */
    SAME,

    /** A unique bounded whole-page translation was proven; [FrameMatch.dx]/[FrameMatch.dy] matter. */
    MOVED,

    /** The frames cannot both be the same rigidly translated page. */
    CHANGED,

    /** There is not enough trustworthy evidence either way; callers must reject, never assume same. */
    UNAVAILABLE
}

enum class FrameMatchReason {
    STRICT_ZERO_SHIFT,
    UNIQUE_BOUNDED_SHIFT,
    CONTENT_MISMATCH,
    NO_UNIQUE_SHIFT,
    AMBIGUOUS_SHIFT,
    INSUFFICIENT_ANCHORS,
    INSUFFICIENT_OVERLAP,
    PROTECTION_EXCLUDED,
    PROTECTION_OUTSIDE_BODY,
    PROTECTION_MISSING,
    INVALID_GEOMETRY,
    INVALID_DENSITY,
    PLANE_UNREADABLE,
    SEARCH_BUDGET_EXCEEDED,
    GUARD_INVALID
}

/**
 * Explicit result of one bounded page-identity comparison.
 *
 * [dx]/[dy] are the whole-page content translation **from the baseline frame to the candidate
 * frame** (baseline pixel `(x, y)` corresponds to candidate pixel `(x + dx, y + dy)`). They are
 * always present so a caller cannot reduce the result to a success boolean and forget to move the
 * stroke/selection/OCR crop. [baselineOverlap] is the baseline region that was actually compared;
 * [candidateOverlap] is the same region moved by `(dx, dy)`. [protection] is the selected-word
 * region in baseline coordinates; its candidate counterpart is [candidateProtection].
 *
 * A nonzero shift is relative to the original baseline only. Callers must keep using that same
 * baseline and must never rebase on a moved frame, otherwise a slow scroll would accumulate an
 * unbounded offset frame after frame.
 */
data class FrameMatch(
    val kind: FrameMatchKind,
    val dx: Int,
    val dy: Int,
    val baselineOverlap: PixelBox?,
    val candidateOverlap: PixelBox?,
    val protection: PixelBox?,
    val reason: FrameMatchReason,
    /** Hard bound (`<= 48 px`) used for this comparison, derived from display density. */
    val maxOffset: Int = 0,
    val sampledAnchors: Int = 0,
    val confirmingAnchors: Int = 0,
    val confirmingQuadrants: Int = 0,
    val probeSupport: Int = 0,
    val examinedCandidates: Int = 0,
    val comparedPixels: Long = 0L
) {
    val shift: PageShift get() = PageShift(dx, dy)
    val accepted: Boolean get() = kind == FrameMatchKind.SAME || kind == FrameMatchKind.MOVED
    val requiresCoordinateCorrection: Boolean get() =
        kind == FrameMatchKind.MOVED && (dx != 0 || dy != 0)
    val candidateProtection: PixelBox? get() = protection?.shifted(dx, dy)
}

/**
 * What lets a gesture drawn while the page was visible be reused at lift.
 *
 * A start/end whole-page offset alone does **not** describe the page position during the stroke.
 * Without one of these proofs, [SelectionMotion.admitStrokeShift] asks for a redraw instead of
 * blindly translating the ink.
 */
sealed interface StrokeShiftEvidence {
    /**
     * The legacy, explicitly named endpoints-only stillness path: the clean entry and lift frames
     * are strictly identical at zero shift with real page information, and no usable frame
     * observed while the finger was down disagreed. It is **not** a claim that every instant was
     * sampled; an unsampled transient move cannot be excluded from two endpoints alone, and the
     * README states that boundary. A nonzero observed shift, a changed/blank/unreadable frame or a
     * bad plane disqualifies this path; it must not be used to bypass observed movement.
     */
    data object StrictEndpointStillness : StrokeShiftEvidence

    /**
     * Every bounded comparison made from real compositor frames during the stroke proved the same
     * whole-page shift [observedShift], and the accepted lift frame proved that same shift. The
     * ink was therefore laid down on the page already at that screen offset: the stroke's own
     * coordinates are the lift frame's coordinates and must be used unchanged. This is the real
     * nonzero success path; it deliberately never adds the shift to the stroke a second time.
     */
    data class StrokeObservedShift(val observedShift: PageShift) : StrokeShiftEvidence

    /**
     * The caller proved that every page shift in [allowedShifts] hits the same non-blank word.
     * [allowedShifts] must enumerate every shift the page could have had while the stroke was drawn
     * — not only the start and end offset — and must be connected without gaps, stay inside the
     * match's bounded offset, and contain the observed shift. Two distant endpoints alone are
     * rejected; a caller that only has endpoints must say so via [StartEndOffsetOnly].
     *
     * This remains an optional conservative proof for a caller that truly owns the word identity;
     * the capture consumer does not need it, because it has real stroke-time shift observations.
     */
    data class SameWordAcrossAllowedShifts(
        val allowedShifts: List<PageShift>,
        val hitWord: (PageShift) -> StrokeWordIdentity?
    ) : StrokeShiftEvidence

    /**
     * The caller only knows the whole-page offset at the start and at the end of the stroke.
     * Those two samples say nothing about where the page was while the ink was laid down, so this
     * evidence is always refused ([StrokeShiftReason.PAGE_SHIFT_WITHOUT_TIME_MAPPING]); the user is
     * asked to draw again rather than have the stroke translated by an assumed uniform offset.
     */
    data object StartEndOffsetOnly : StrokeShiftEvidence
}

enum class StrokeShiftReason {
    NO_PAGE_SHIFT,
    STROKE_AT_LIFT_OFFSET,
    TIME_MAPPED_SHIFT,
    SAME_WORD_ACROSS_ALLOWED_SHIFTS,
    MATCH_NOT_ACCEPTED,
    PAGE_SHIFT_WITHOUT_TIME_MAPPING,
    PROTECTION_MISMATCH,
    ALLOWED_SHIFTS_OUT_OF_BOUNDS,
    ALLOWED_SHIFT_MISSING_OBSERVED,
    ALLOWED_SHIFTS_NOT_CONTIGUOUS,
    ALLOWED_SHIFTS_HIT_DIFFERENT_WORDS,
    SHIFT_PROOF_FAILED,
    SELECTION_OUTSIDE_PAGE
}

sealed interface StrokeShiftDecision {
    /** The page did not move; the stroke and its protection crop are still in entry coordinates. */
    data class UseEntryCoordinates(
        val selection: HighlighterSelection,
        val reason: StrokeShiftReason
    ) : StrokeShiftDecision

    /** The page moved and a proof was accepted; [selection] is already translated by [shift]. */
    data class Translate(
        val selection: HighlighterSelection,
        val shift: PageShift,
        val reason: StrokeShiftReason
    ) : StrokeShiftDecision

    /** The stroke cannot be reused safely; ask the user to draw again on the stable page. */
    data class Reselect(val reason: StrokeShiftReason) : StrokeShiftDecision
}

/**
 * Pure, Android-free bounded translation matching and gesture-shift admission.
 *
 * The matcher accepts at most a small whole-page translation (per display density, hard-capped at
 * [MAX_BOUNDED_SHIFT_PX]). It never compares only a percentage of the page and never accepts a
 * candidate merely because most pixels agree: a single changed glyph in the selected-word
 * protection region is a mismatch.
 *
 * Evidence rules:
 *  - zero displacement is checked first by the existing strict pixel comparison;
 *  - otherwise several dispersed high-contrast anchors must agree on exactly one integer shift
 *    (5x5 patch-confirmed in at least three screen quadrants);
 *  - the shifted overlap must be large enough and must fully contain the protection region, so a
 *    word that would fall into newly exposed edge pixels is rejected;
 *  - the winner is then verified pixel by pixel over the whole visible overlap with the original
 *    colour-bucket tolerance of [LiveFrameFingerprint]. There is no mismatch budget;
 *  - pure white/blank pages, repeated patterns with multiple equally strong shifts, too few
 *    anchors, ambiguous results, zoom/rotation/reflow and changes to the selected word all fail
 *    closed as [FrameMatchKind.UNAVAILABLE] or [FrameMatchKind.CHANGED].
 *
 * Hard resource budgets: at most [PROBE_ANCHORS] anchors scan offsets, at most
 * [MAX_CANDIDATES] candidates are ranked, and only a uniquely confirmed candidate reaches the
 * full-pixel pass. The search is never a `(2r+1)^2` scan over every page pixel and no OCR, network,
 * bitmap or second identity/fingerprint is allocated. Exclusions are screen-coordinate boxes
 * (this application's own windows), applied per pixel on both the baseline and candidate side; the
 * selected-word protection region may not intersect them.
 */
object SelectionMotion {
    /** Hard ceiling for any bounded shift, independent of display density. */
    const val MAX_BOUNDED_SHIFT_PX = 48

    /** Nominal small shift in dp; multiplied by density, then clamped to the hard ceiling. */
    const val BOUNDED_SHIFT_DP = 24f

    private const val GRID_COLUMNS = 6
    private const val GRID_ROWS = 6
    private const val LATTICE = 5
    private const val MAX_ANCHORS = 12
    private const val MIN_ANCHORS = 6
    private const val PROBE_ANCHORS = 6
    private const val SIGNATURE_RADIUS = 1
    private const val SIGNATURE_POINTS = 5
    private const val PATCH_RADIUS = 2
    private const val HIGH_CONTRAST_BUCKETS = 3
    private const val MIN_CONFIRMING_ANCHORS = 4
    private const val MIN_CONFIRMING_QUADRANTS = 3
    private const val MIN_PROBE_VOTES = 2
    private const val MAX_CANDIDATES = 4
    private const val MIN_OVERLAP_PERCENT = 60L
    private const val MIN_COMPARED_PERCENT = 20L
    private const val MAX_SEARCH_COMPARISONS = 320_000L
    private const val MAX_ALLOWED_SHIFT_PROOFS = 9_409

    private val NEIGHBOURS = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1)
    private val SIGNATURE = intArrayOf(0, 0, -1, 0, 1, 0, 0, -1, 0, 1)
    private val LATTICE_POINTS: List<Pair<Int, Int>> = run {
        val points = ArrayList<Pair<Int, Int>>(LATTICE * LATTICE)
        for (y in 0 until LATTICE) for (x in 0 until LATTICE) points += x to y
        points.sortWith(compareBy<Pair<Int, Int>>(
            { abs(it.first - LATTICE / 2) + abs(it.second - LATTICE / 2) },
            { it.first },
            { it.second }
        ))
        points
    }

    /**
     * Bounded offset for [density], in pixels. Invalid densities yield 0 (no shift search);
     * values are clamped to [MAX_BOUNDED_SHIFT_PX].
     */
    fun maxOffsetPx(density: Float): Int {
        if (!density.isFinite() || density <= 0f) return 0
        return (BOUNDED_SHIFT_DP * density).roundToInt().coerceIn(0, MAX_BOUNDED_SHIFT_PX)
    }

    /** The complete finite set of shifts the matcher may consider, or null for an invalid density. */
    fun boundedShifts(density: Float): List<PageShift>? {
        if (!density.isFinite() || density <= 0f) return null
        val radius = maxOffsetPx(density)
        val shifts = ArrayList<PageShift>((2 * radius + 1) * (2 * radius + 1))
        for (dy in -radius..radius) for (dx in -radius..radius) shifts += PageShift(dx, dy)
        return shifts
    }

    /**
     * Pure translation of the crop and every stroke. Returns null rather than clamping when the
     * translated crop would leave the screen: a partially invented crop is not a safe OCR input.
     */
    fun translate(selection: HighlighterSelection, shift: PageShift,
                  screenWidth: Int, screenHeight: Int): HighlighterSelection? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val crop = selection.crop
        val moved = PixelBox(crop.left + shift.dx, crop.top + shift.dy,
            crop.right + shift.dx, crop.bottom + shift.dy)
        if (moved.left < 0 || moved.top < 0 ||
            moved.right > screenWidth || moved.bottom > screenHeight ||
            moved.width <= 0 || moved.height <= 0) return null
        val strokes = selection.strokes.map { stroke ->
            HighlighterStroke(stroke.points.map { point ->
                HighlighterPoint(point.x + shift.dx, point.y + shift.dy)
            })
        }
        return HighlighterSelection(strokes, moved, selection.brushRadius,
            selection.inkBounds?.shifted(shift.dx, shift.dy))
    }

    /**
     * Decides whether a stroke drawn on the visible page can be used at lift.
     *
     * [protection] is the necessary painted-ink box in the **stroke/lift** frame. The accepted
     * match must prove the same box in its candidate frame; that is how a protection box translated
     * into entry coordinates for the matcher cannot silently refer to some other page region.
     *
     * Evidence is evaluated for every accepted match, including a zero-displacement SAME one:
     * strict entry/lift equality alone says nothing about where the page was while the ink was laid
     * down. [StrokeShiftEvidence.StartEndOffsetOnly] is therefore refused. When stroke-time frames
     * proved the same offset as the lift frame, the stroke already is in lift coordinates and is
     * reused unchanged; the matcher's [FrameMatch.dx] is never added implicitly.
     */
    fun admitStrokeShift(
        match: FrameMatch,
        selection: HighlighterSelection,
        protection: PixelBox,
        screenWidth: Int,
        screenHeight: Int,
        evidence: StrokeShiftEvidence
    ): StrokeShiftDecision {
        if (!match.accepted) return StrokeShiftDecision.Reselect(StrokeShiftReason.MATCH_NOT_ACCEPTED)
        if (match.candidateProtection != protection) {
            return StrokeShiftDecision.Reselect(StrokeShiftReason.PROTECTION_MISMATCH)
        }
        return when (evidence) {
            StrokeShiftEvidence.StartEndOffsetOnly ->
                StrokeShiftDecision.Reselect(StrokeShiftReason.PAGE_SHIFT_WITHOUT_TIME_MAPPING)

            StrokeShiftEvidence.StrictEndpointStillness -> {
                if (match.dx != 0 || match.dy != 0) {
                    StrokeShiftDecision.Reselect(StrokeShiftReason.PAGE_SHIFT_WITHOUT_TIME_MAPPING)
                } else {
                    StrokeShiftDecision.UseEntryCoordinates(selection, StrokeShiftReason.NO_PAGE_SHIFT)
                }
            }

            is StrokeShiftEvidence.StrokeObservedShift -> {
                val observed = evidence.observedShift
                if (observed.dx != match.dx || observed.dy != match.dy) {
                    StrokeShiftDecision.Reselect(StrokeShiftReason.PAGE_SHIFT_WITHOUT_TIME_MAPPING)
                } else if (match.dx == 0 && match.dy == 0) {
                    StrokeShiftDecision.UseEntryCoordinates(selection, StrokeShiftReason.NO_PAGE_SHIFT)
                } else {
                    // Every observed stroke instant and the lift frame are at the same offset, so
                    // the ink's screen coordinates are already the lift frame's coordinates.
                    // Translating by the whole-page shift here would move the stroke twice.
                    StrokeShiftDecision.UseEntryCoordinates(
                        selection, StrokeShiftReason.STROKE_AT_LIFT_OFFSET)
                }
            }

            is StrokeShiftEvidence.SameWordAcrossAllowedShifts -> {
                val allowed = evidence.allowedShifts
                if (allowed.isEmpty() || allowed.size > MAX_ALLOWED_SHIFT_PROOFS) {
                    return StrokeShiftDecision.Reselect(StrokeShiftReason.ALLOWED_SHIFTS_OUT_OF_BOUNDS)
                }
                if (match.maxOffset <= 0 ||
                    allowed.any { abs(it.dx) > match.maxOffset || abs(it.dy) > match.maxOffset }) {
                    return StrokeShiftDecision.Reselect(StrokeShiftReason.ALLOWED_SHIFTS_OUT_OF_BOUNDS)
                }
                if (allowed.none { it.dx == match.dx && it.dy == match.dy }) {
                    return StrokeShiftDecision.Reselect(
                        StrokeShiftReason.ALLOWED_SHIFT_MISSING_OBSERVED)
                }
                // Endpoints with a gap in between do not describe a continuous trajectory, so a
                // "same word at start and end" claim cannot stand in for the whole stroke.
                if (!contiguous(allowed)) {
                    return StrokeShiftDecision.Reselect(
                        StrokeShiftReason.ALLOWED_SHIFTS_NOT_CONTIGUOUS)
                }
                var reference: StrokeWordIdentity? = null
                try {
                    for (shift in allowed) {
                        val identity = evidence.hitWord(shift)
                            ?.normalizedToBaseline(shift)
                            ?.trimmed()
                        if (identity == null || identity.text.isEmpty()) {
                            return StrokeShiftDecision.Reselect(
                                StrokeShiftReason.ALLOWED_SHIFTS_HIT_DIFFERENT_WORDS)
                        }
                        val previous = reference
                        if (previous == null) {
                            reference = identity
                        } else if (!previous.sameWordAs(identity)) {
                            // Same spelling elsewhere on the page is a different occurrence.
                            return StrokeShiftDecision.Reselect(
                                StrokeShiftReason.ALLOWED_SHIFTS_HIT_DIFFERENT_WORDS)
                        }
                    }
                } catch (_: RuntimeException) {
                    return StrokeShiftDecision.Reselect(StrokeShiftReason.SHIFT_PROOF_FAILED)
                }
                if (match.dx == 0 && match.dy == 0) {
                    StrokeShiftDecision.UseEntryCoordinates(
                        selection, StrokeShiftReason.SAME_WORD_ACROSS_ALLOWED_SHIFTS)
                } else {
                    val translated = translate(selection, match.shift, screenWidth, screenHeight)
                        ?: return StrokeShiftDecision.Reselect(
                            StrokeShiftReason.SELECTION_OUTSIDE_PAGE)
                    StrokeShiftDecision.Translate(
                        translated, match.shift,
                        StrokeShiftReason.SAME_WORD_ACROSS_ALLOWED_SHIFTS)
                }
            }
        }
    }

    /**
     * True when [shifts] contains at least two distinct shifts and every shift is reachable from
     * any other by unit steps inside the set (8-connected). A page that is rigidly shifted by one
     * whole-pixel translation per step therefore forms a continuous cover; two distant endpoints no
     * longer qualify, because sampling only the start and the end cannot prove the middle.
     */
    private fun contiguous(shifts: List<PageShift>): Boolean {
        val allowed = HashSet<PageShift>(shifts.size * 2)
        allowed.addAll(shifts)
        if (allowed.size < 2) return false
        val start = allowed.first()
        val seen = HashSet<PageShift>(allowed.size * 2)
        val queue = ArrayDeque<PageShift>()
        seen.add(start)
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (oy in -1..1) {
                for (ox in -1..1) {
                    if (ox == 0 && oy == 0) continue
                    val next = PageShift(current.dx + ox, current.dy + oy)
                    if (next in allowed && seen.add(next)) queue.addLast(next)
                }
            }
        }
        return seen.size == allowed.size
    }

    /**
     * Validates the density, page body and protection region before any pixel comparison.
     * Returns a failing [FrameMatch] or null when the protection region is usable.
     */
    internal fun validate(
        body: PixelBox,
        protection: PixelBox,
        excluded: List<PixelBox>,
        density: Float,
        maxOffset: Int
    ): FrameMatch? {
        if (body.width <= 0 || body.height <= 0) {
            return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.INVALID_GEOMETRY,
                protection, maxOffset)
        }
        if (!density.isFinite() || density <= 0f) {
            return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.INVALID_DENSITY,
                protection, maxOffset)
        }
        if (protection.width <= 0 || protection.height <= 0 ||
            !inside(body, protection.left, protection.top) ||
            !inside(body, protection.right - 1, protection.bottom - 1)) {
            return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.PROTECTION_OUTSIDE_BODY,
                protection, maxOffset)
        }
        if (intersects(protection, excluded)) {
            return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.PROTECTION_EXCLUDED,
                protection, maxOffset)
        }
        return null
    }

    /** Builds a non-accepted result with explicit reason/counters. */
    internal fun rejected(
        kind: FrameMatchKind,
        reason: FrameMatchReason,
        protection: PixelBox?,
        maxOffset: Int,
        sampledAnchors: Int = 0,
        confirmingAnchors: Int = 0,
        confirmingQuadrants: Int = 0,
        probeSupport: Int = 0,
        examinedCandidates: Int = 0,
        comparedPixels: Long = 0L
    ): FrameMatch = FrameMatch(
        kind = kind, dx = 0, dy = 0,
        baselineOverlap = null, candidateOverlap = null,
        protection = protection, reason = reason, maxOffset = maxOffset,
        sampledAnchors = sampledAnchors,
        confirmingAnchors = confirmingAnchors,
        confirmingQuadrants = confirmingQuadrants,
        probeSupport = probeSupport,
        examinedCandidates = examinedCandidates,
        comparedPixels = comparedPixels
    )

    /** Random-access candidate pixels; no full candidate identity is retained. */
    internal interface CandidatePixels {
        fun colorAt(x: Int, y: Int, body: PixelBox): Int
        fun verify(
            shift: PageShift,
            overlap: PixelBox,
            body: PixelBox,
            baselineBuckets: ShortArray,
            excluded: List<PixelBox>
        ): Verification
    }

    internal class Verification(val comparedPixels: Long, val mismatch: Boolean)

    internal class FingerprintPixels(private val buckets: ShortArray) : CandidatePixels {
        override fun colorAt(x: Int, y: Int, body: PixelBox): Int =
            buckets[(y - body.top) * body.width + (x - body.left)].toInt() and 0xFFFF

        override fun verify(
            shift: PageShift,
            overlap: PixelBox,
            body: PixelBox,
            baselineBuckets: ShortArray,
            excluded: List<PixelBox>
        ): Verification {
            val width = body.width
            var compared = 0L
            var row = 0
            while (row < overlap.height) {
                val baselineY = overlap.top + row
                val candidateY = baselineY + shift.dy
                var column = 0
                while (column < overlap.width) {
                    val baselineX = overlap.left + column
                    val candidateX = baselineX + shift.dx
                    if (!excludedAt(baselineX, baselineY, excluded) &&
                        !excludedAt(candidateX, candidateY, excluded)) {
                        compared++
                        val baselineIndex = (baselineY - body.top) * width + (baselineX - body.left)
                        val candidateIndex = (candidateY - body.top) * width + (candidateX - body.left)
                        if (!LiveFrameFingerprint.bucketsMatch(
                                baselineBuckets[baselineIndex].toInt(),
                                buckets[candidateIndex].toInt())) {
                            return Verification(compared, true)
                        }
                    }
                    column++
                }
                row++
            }
            return Verification(compared, false)
        }
    }

    internal class PlanePixels(
        private val rowStride: Int,
        private val pixelStride: Int,
        private val buffer: ByteBuffer
    ) : CandidatePixels {
        override fun colorAt(x: Int, y: Int, body: PixelBox): Int {
            val offset = y * rowStride + x * pixelStride
            return LiveFrameFingerprint.encodeColor(
                buffer.get(offset).toInt() and 255,
                buffer.get(offset + 1).toInt() and 255,
                buffer.get(offset + 2).toInt() and 255
            )
        }

        override fun verify(
            shift: PageShift,
            overlap: PixelBox,
            body: PixelBox,
            baselineBuckets: ShortArray,
            excluded: List<PixelBox>
        ): Verification {
            var compared = 0L
            var row = 0
            while (row < overlap.height) {
                val baselineY = overlap.top + row
                val candidateY = baselineY + shift.dy
                var column = 0
                while (column < overlap.width) {
                    val baselineX = overlap.left + column
                    val candidateX = baselineX + shift.dx
                    if (!excludedAt(baselineX, baselineY, excluded) &&
                        !excludedAt(candidateX, candidateY, excluded)) {
                        compared++
                        val offset = candidateY * rowStride + candidateX * pixelStride
                        val candidateColor = LiveFrameFingerprint.encodeColor(
                            buffer.get(offset).toInt() and 255,
                            buffer.get(offset + 1).toInt() and 255,
                            buffer.get(offset + 2).toInt() and 255
                        )
                        if (!LiveFrameFingerprint.bucketsMatch(
                                bucketAt(baselineBuckets, body, baselineX, baselineY),
                                candidateColor)) {
                            return Verification(compared, true)
                        }
                    }
                    column++
                }
                row++
            }
            return Verification(compared, false)
        }
    }

    /**
     * Bounded shift search. The candidate's zero-shift strict comparison has already failed and
     * the protection region has already been validated by [validate].
     */
    internal fun search(
        body: PixelBox,
        baselineBuckets: ShortArray,
        candidate: CandidatePixels,
        protection: PixelBox?,
        excluded: List<PixelBox>,
        maxOffset: Int
    ): FrameMatch {
        val anchors = selectAnchors(body, baselineBuckets, excluded)
        val quadrantCount = anchors.mapTo(HashSet<Int>(4)) { it.quadrant }.size
        if (anchors.size < MIN_ANCHORS || quadrantCount < MIN_CONFIRMING_QUADRANTS) {
            return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.INSUFFICIENT_ANCHORS,
                protection, maxOffset, sampledAnchors = anchors.size)
        }
        if (maxOffset <= 0) {
            return rejected(FrameMatchKind.CHANGED, FrameMatchReason.CONTENT_MISMATCH,
                protection, maxOffset, sampledAnchors = anchors.size)
        }
        val span = 2 * maxOffset + 1
        val probes = anchors.subList(0, minOf(PROBE_ANCHORS, anchors.size))
        val comparisons = probes.size.toLong() * span * span * SIGNATURE_POINTS
        if (comparisons > MAX_SEARCH_COMPARISONS) {
            return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.SEARCH_BUDGET_EXCEEDED,
                protection, maxOffset, sampledAnchors = anchors.size)
        }
        val bodyArea = body.width.toLong() * body.height.toLong()
        val minOverlap = (bodyArea * MIN_OVERLAP_PERCENT + 99) / 100
        val admissible = BooleanArray(span * span)
        val votes = IntArray(span * span)
        var dx = -maxOffset
        while (dx <= maxOffset) {
            var dy = -maxOffset
            while (dy <= maxOffset) {
                if (shiftAdmissible(body, protection, excluded, dx, dy, minOverlap)) {
                    admissible[(dx + maxOffset) * span + (dy + maxOffset)] = true
                }
                dy++
            }
            dx++
        }
        for (anchor in probes) {
            var dy = -maxOffset
            while (dy <= maxOffset) {
                var dxProbe = -maxOffset
                while (dxProbe <= maxOffset) {
                    val index = (dxProbe + maxOffset) * span + (dy + maxOffset)
                    if (admissible[index] && signatureMatches(
                            baselineBuckets, body, anchor, candidate, dxProbe, dy, excluded)) {
                        votes[index]++
                    }
                    dxProbe++
                }
                dy++
            }
        }
        val top = topCandidates(votes, span, maxOffset)
        if (top.isEmpty()) {
            return rejected(FrameMatchKind.CHANGED, FrameMatchReason.NO_UNIQUE_SHIFT,
                protection, maxOffset, sampledAnchors = anchors.size)
        }
        val strong = ArrayList<Confirmation>(top.size)
        for (ranked in top) {
            var confirming = 0
            val quadrants = HashSet<Int>(4)
            for (anchor in anchors) {
                if (patchMatches(baselineBuckets, body, anchor, candidate, ranked.shift, excluded)) {
                    confirming++
                    quadrants += anchor.quadrant
                }
            }
            if (confirming >= MIN_CONFIRMING_ANCHORS &&
                quadrants.size >= MIN_CONFIRMING_QUADRANTS) {
                strong += Confirmation(ranked.shift, confirming, quadrants.size, ranked.votes)
            }
        }
        if (strong.size > 1) {
            return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.AMBIGUOUS_SHIFT,
                protection, maxOffset, sampledAnchors = anchors.size,
                examinedCandidates = top.size)
        }
        if (strong.isEmpty()) {
            return rejected(FrameMatchKind.CHANGED, FrameMatchReason.CONTENT_MISMATCH,
                protection, maxOffset, sampledAnchors = anchors.size,
                examinedCandidates = top.size)
        }
        val winner = strong.single()
        val overlap = shiftedOverlap(body, winner.shift.dx, winner.shift.dy)
            ?: return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.INSUFFICIENT_OVERLAP,
                protection, maxOffset, sampledAnchors = anchors.size,
                confirmingAnchors = winner.anchors, confirmingQuadrants = winner.quadrants,
                probeSupport = winner.votes, examinedCandidates = top.size)
        val verification = candidate.verify(winner.shift, overlap, body, baselineBuckets, excluded)
        if (verification.mismatch) {
            return rejected(FrameMatchKind.CHANGED, FrameMatchReason.CONTENT_MISMATCH,
                protection, maxOffset, sampledAnchors = anchors.size,
                confirmingAnchors = winner.anchors, confirmingQuadrants = winner.quadrants,
                probeSupport = winner.votes, examinedCandidates = top.size,
                comparedPixels = verification.comparedPixels)
        }
        val minCompared = (bodyArea * MIN_COMPARED_PERCENT + 99) / 100
        if (verification.comparedPixels < minCompared) {
            return rejected(FrameMatchKind.UNAVAILABLE, FrameMatchReason.INSUFFICIENT_OVERLAP,
                protection, maxOffset, sampledAnchors = anchors.size,
                confirmingAnchors = winner.anchors, confirmingQuadrants = winner.quadrants,
                probeSupport = winner.votes, examinedCandidates = top.size,
                comparedPixels = verification.comparedPixels)
        }
        return FrameMatch(
            kind = FrameMatchKind.MOVED,
            dx = winner.shift.dx,
            dy = winner.shift.dy,
            baselineOverlap = overlap,
            candidateOverlap = overlap.shifted(winner.shift.dx, winner.shift.dy),
            protection = protection,
            reason = FrameMatchReason.UNIQUE_BOUNDED_SHIFT,
            maxOffset = maxOffset,
            sampledAnchors = anchors.size,
            confirmingAnchors = winner.anchors,
            confirmingQuadrants = winner.quadrants,
            probeSupport = winner.votes,
            examinedCandidates = top.size,
            comparedPixels = verification.comparedPixels
        )
    }

    private class Anchor(val x: Int, val y: Int, val contrast: Int, val quadrant: Int)

    private class RankedShift(val shift: PageShift, val votes: Int, val distance: Int)

    private class Confirmation(
        val shift: PageShift,
        val anchors: Int,
        val quadrants: Int,
        val votes: Int
    )

    private val RANKING = compareByDescending<RankedShift> { it.votes }
        .thenBy { it.distance }
        .thenBy { it.shift.dx }
        .thenBy { it.shift.dy }

    private fun selectAnchors(
        body: PixelBox,
        buckets: ShortArray,
        excluded: List<PixelBox>
    ): List<Anchor> {
        val byQuadrant = Array(4) { ArrayList<Anchor>() }
        for (row in 0 until GRID_ROWS) {
            val cellTop = body.top + ((body.height.toLong() * row) / GRID_ROWS).toInt()
            val cellBottom = body.top + ((body.height.toLong() * (row + 1)) / GRID_ROWS).toInt()
            if (cellBottom <= cellTop) continue
            for (column in 0 until GRID_COLUMNS) {
                val cellLeft = body.left + ((body.width.toLong() * column) / GRID_COLUMNS).toInt()
                val cellRight =
                    body.left + ((body.width.toLong() * (column + 1)) / GRID_COLUMNS).toInt()
                if (cellRight <= cellLeft) continue
                var chosen: Anchor? = null
                for (point in LATTICE_POINTS) {
                    val x = cellLeft +
                        ((cellRight - cellLeft) * (2 * point.first + 1)) / (2 * LATTICE)
                    val y = cellTop +
                        ((cellBottom - cellTop) * (2 * point.second + 1)) / (2 * LATTICE)
                    if (!patchVisible(x, y, SIGNATURE_RADIUS, body, excluded)) continue
                    val contrast = contrastAt(buckets, body, x, y, excluded)
                    if (contrast < HIGH_CONTRAST_BUCKETS) continue
                    val candidate = Anchor(x, y, contrast, quadrantOf(x, y, body))
                    val current = chosen
                    if (current == null || candidate.contrast > current.contrast) chosen = candidate
                }
                chosen?.let { byQuadrant[it.quadrant].add(it) }
            }
        }
        val sorted = byQuadrant.map { list -> list.sortedByDescending { it.contrast } }
        return roundRobin(sorted)
    }

    private fun roundRobin(sorted: List<List<Anchor>>): List<Anchor> {
        val selected = ArrayList<Anchor>(MAX_ANCHORS)
        val next = IntArray(sorted.size)
        var added = true
        while (selected.size < MAX_ANCHORS && added) {
            added = false
            for (quadrant in sorted.indices) {
                val anchor = sorted[quadrant].getOrNull(next[quadrant]) ?: continue
                next[quadrant]++
                selected += anchor
                added = true
                if (selected.size >= MAX_ANCHORS) break
            }
        }
        return selected
    }

    private fun contrastAt(
        buckets: ShortArray,
        body: PixelBox,
        x: Int,
        y: Int,
        excluded: List<PixelBox>
    ): Int {
        val base = bucketAt(buckets, body, x, y)
        var best = 0
        var compared = 0
        var index = 0
        while (index < NEIGHBOURS.size) {
            val nx = x + NEIGHBOURS[index]
            val ny = y + NEIGHBOURS[index + 1]
            index += 2
            if (!inside(body, nx, ny) || excludedAt(nx, ny, excluded)) continue
            compared++
            val difference = bucketContrast(base, bucketAt(buckets, body, nx, ny))
            if (difference > best) best = difference
        }
        return if (compared >= 2) best else 0
    }

    private fun bucketContrast(a: Int, b: Int): Int {
        val red = abs(((a ushr 11) and 0x1F) - ((b ushr 11) and 0x1F))
        val green = abs(((a ushr 5) and 0x3F) - ((b ushr 5) and 0x3F))
        val blue = abs((a and 0x1F) - (b and 0x1F))
        return maxOf(red, green, blue)
    }

    private fun signatureMatches(
        baselineBuckets: ShortArray,
        body: PixelBox,
        anchor: Anchor,
        candidate: CandidatePixels,
        dx: Int,
        dy: Int,
        excluded: List<PixelBox>
    ): Boolean {
        var index = 0
        while (index < SIGNATURE.size) {
            val bx = anchor.x + SIGNATURE[index]
            val by = anchor.y + SIGNATURE[index + 1]
            index += 2
            val cx = bx + dx
            val cy = by + dy
            if (!inside(body, bx, by) || !inside(body, cx, cy)) return false
            if (excludedAt(bx, by, excluded) || excludedAt(cx, cy, excluded)) return false
            if (!LiveFrameFingerprint.bucketsMatch(
                    bucketAt(baselineBuckets, body, bx, by), candidate.colorAt(cx, cy, body))) {
                return false
            }
        }
        return true
    }

    private fun patchMatches(
        baselineBuckets: ShortArray,
        body: PixelBox,
        anchor: Anchor,
        candidate: CandidatePixels,
        shift: PageShift,
        excluded: List<PixelBox>
    ): Boolean {
        var py = -PATCH_RADIUS
        while (py <= PATCH_RADIUS) {
            var px = -PATCH_RADIUS
            while (px <= PATCH_RADIUS) {
                val bx = anchor.x + px
                val by = anchor.y + py
                val cx = bx + shift.dx
                val cy = by + shift.dy
                if (!inside(body, bx, by) || !inside(body, cx, cy)) return false
                if (excludedAt(bx, by, excluded) || excludedAt(cx, cy, excluded)) return false
                if (!LiveFrameFingerprint.bucketsMatch(
                        bucketAt(baselineBuckets, body, bx, by), candidate.colorAt(cx, cy, body))) {
                    return false
                }
                px++
            }
            py++
        }
        return true
    }

    private fun topCandidates(votes: IntArray, span: Int, maxOffset: Int): List<RankedShift> {
        val ranked = ArrayList<RankedShift>(MAX_CANDIDATES + 1)
        for (index in votes.indices) {
            val count = votes[index]
            if (count < MIN_PROBE_VOTES) continue
            val dx = index / span - maxOffset
            val dy = index % span - maxOffset
            val candidate = RankedShift(PageShift(dx, dy), count, abs(dx) + abs(dy))
            var inserted = false
            for (position in ranked.indices) {
                if (RANKING.compare(candidate, ranked[position]) < 0) {
                    ranked.add(position, candidate)
                    inserted = true
                    break
                }
            }
            if (!inserted) ranked.add(candidate)
            if (ranked.size > MAX_CANDIDATES) ranked.removeAt(ranked.size - 1)
        }
        return ranked
    }

    private fun shiftAdmissible(
        body: PixelBox,
        protection: PixelBox?,
        excluded: List<PixelBox>,
        dx: Int,
        dy: Int,
        minOverlap: Long
    ): Boolean {
        val overlap = shiftedOverlap(body, dx, dy) ?: return false
        if (overlap.width.toLong() * overlap.height.toLong() < minOverlap) return false
        // A stroke-time probe has no selected word yet (the ink itself is excluded); it only
        // proves the whole-page shift. When a protection box is supplied, its baseline and
        // candidate positions must both stay inside the compared overlap.
        if (protection == null) return true
        if (!contains(overlap, protection)) return false
        return !intersects(protection.shifted(dx, dy), excluded)
    }

    private fun shiftedOverlap(body: PixelBox, dx: Int, dy: Int): PixelBox? {
        val left = maxOf(body.left, body.left - dx)
        val top = maxOf(body.top, body.top - dy)
        val right = minOf(body.right, body.right - dx)
        val bottom = minOf(body.bottom, body.bottom - dy)
        return if (right > left && bottom > top) PixelBox(left, top, right, bottom) else null
    }

    private fun contains(outer: PixelBox, inner: PixelBox): Boolean =
        inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom

    private fun intersects(box: PixelBox, excluded: List<PixelBox>): Boolean {
        for (other in excluded) {
            if (box.left < other.right && other.left < box.right &&
                box.top < other.bottom && other.top < box.bottom) return true
        }
        return false
    }

    private fun inside(body: PixelBox, x: Int, y: Int): Boolean =
        x >= body.left && x < body.right && y >= body.top && y < body.bottom

    private fun bucketAt(buckets: ShortArray, body: PixelBox, x: Int, y: Int): Int =
        buckets[(y - body.top) * body.width + (x - body.left)].toInt() and 0xFFFF

    private fun excludedAt(x: Int, y: Int, excluded: List<PixelBox>): Boolean =
        excluded.isNotEmpty() && LiveFrameFingerprint.isExcluded(x, y, excluded)

    private fun patchVisible(
        x: Int,
        y: Int,
        radius: Int,
        body: PixelBox,
        excluded: List<PixelBox>
    ): Boolean {
        if (x - radius < body.left || x + radius >= body.right ||
            y - radius < body.top || y + radius >= body.bottom) return false
        if (excluded.isEmpty()) return true
        var py = y - radius
        while (py <= y + radius) {
            var px = x - radius
            while (px <= x + radius) {
                if (LiveFrameFingerprint.isExcluded(px, py, excluded)) return false
                px++
            }
            py++
        }
        return true
    }

    /**
     * Whether the retained baseline actually contains page identity information.
     *
     * A strict zero-shift comparison of a pure white/black (or otherwise featureless) page proves
     * only that two blank frames look alike; it must not be reported as the same page. This bounded
     * grid scan (at most ~51k samples) requires at least two quantized colours and several
     * high-contrast edges, with the application's own window rectangles excluded per pixel. It is
     * used only before accepting SAME; the old strict comparison APIs are unchanged.
     */
    internal fun hasIdentityInformation(
        body: PixelBox,
        excluded: List<PixelBox>,
        encoded: ShortArray
    ): Boolean {
        if (body.width <= 0 || body.height <= 0 || encoded.size < body.width * body.height) {
            return false
        }
        val step = maxOf(1, minOf(body.width, body.height) / 160)
        val colours = HashSet<Int>(64)
        var edges = 0
        var y = body.top
        while (y < body.bottom) {
            var x = body.left
            while (x < body.right) {
                if (!excludedAt(x, y, excluded)) {
                    val base = (y - body.top) * body.width + (x - body.left)
                    val colour = encoded[base].toInt() and 0xFFFF
                    colours += colour
                    val rightX = x + step
                    if (rightX < body.right && !excludedAt(rightX, y, excluded)) {
                        val right = encoded[base + step].toInt() and 0xFFFF
                        if (bucketDistance(colour, right) >= HIGH_CONTRAST_BUCKETS) edges += 1
                    }
                    val downY = y + step
                    if (downY < body.bottom && !excludedAt(x, downY, excluded)) {
                        val down = encoded[base + step * body.width].toInt() and 0xFFFF
                        if (bucketDistance(colour, down) >= HIGH_CONTRAST_BUCKETS) edges += 1
                    }
                }
                x += step
            }
            y += step
        }
        return colours.size >= 2 && edges >= 8
    }

    /** Largest per-channel difference between two 5/6/5 buckets. */
    internal fun bucketDistance(first: Int, second: Int): Int {
        val left = first and 0xFFFF
        val right = second and 0xFFFF
        return maxOf(
            abs(((left ushr 11) and 0x1F) - ((right ushr 11) and 0x1F)),
            abs(((left ushr 5) and 0x3F) - ((right ushr 5) and 0x3F)),
            abs((left and 0x1F) - (right and 0x1F))
        )
    }

    private fun quadrantOf(x: Int, y: Int, body: PixelBox): Int {
        val right = (x - body.left).toLong() * 2 >= body.width.toLong()
        val bottom = (y - body.top).toLong() * 2 >= body.height.toLong()
        return (if (right) 1 else 0) + (if (bottom) 2 else 0)
    }
}
