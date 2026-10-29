package com.shici.app.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * How [SelectionRecovery.resolve] obtained its result.
 *
 * The strict [HighlighterGeometry.hitWords] path stays the only source of [EXACT]; recovery never
 * changes its result, order or the later single-word/sentence routing. [RECOVERED] is one uniquely
 * plausible word near the actually painted segments; [AMBIGUOUS] carries a few original OCR words
 * that the user must choose between; [NONE] has neither an exact hit nor a candidate.
 */
enum class SelectionResolutionMode { EXACT, RECOVERED, AMBIGUOUS, NONE }

/**
 * One explicit selection decision for a committed highlighter gesture.
 *
 * [selected] is non-empty only for [SelectionResolutionMode.EXACT] (the unchanged `hitWords`
 * result, in the same reading order) and [SelectionResolutionMode.RECOVERED] (exactly one word).
 * [candidates] is non-empty only for [SelectionResolutionMode.AMBIGUOUS]; the caller must show
 * those original OCR words for an explicit choice and must not concatenate them or silently take
 * the first/nearest one.
 */
data class SelectionResolution(
    val mode: SelectionResolutionMode,
    val selected: List<HighlighterWord>,
    val candidates: List<HighlighterWord> = emptyList()
)

/**
 * Bounded fallback selection for a committed highlighter gesture.
 *
 * This is a pure geometry decision over the same screenshot-pixel coordinates as
 * [HighlighterGeometry.hitWords]: [HighlighterSelection.strokes], [HighlighterWord.bounds] and
 * [HighlighterSelection.brushRadius] all share one density/scale basis. It starts no lookup or
 * record, reads no dictionary, normalizes no text and never rewrites an OCR word: every result is
 * an original [HighlighterWord] instance from the caller's list.
 *
 * Any exact hit that contains an English letter wins and is returned untouched, so attached
 * punctuation, mixed-script and phrase-shaped OCR elements still follow the old exact path. Only
 * when no exact hit contains an English letter does the fallback look beside the real stroke
 * segments (never the whole-stroke bounding box). A segment must be assigned to the word's own OCR
 * line, must span the word horizontally and must be within a hard, brush/word-height-normalized
 * distance. A clearly adjacent word that the ink did not come near is therefore not eligible.
 * Taps/too-short strokes produce no [HighlighterSelection] at [HighlighterGeometry.selection], and
 * the live gesture owner rejects cancelled/multi-pointer lifts before calling here. A stroke with a
 * non-finite point is ignored here as well, and no off-screen point is clamped into a new edge
 * stroke.
 */
object SelectionRecovery {
    /** At most this many original words are offered for an explicit user choice. */
    private const val MAX_CANDIDATES = 3

    // The recovered distance is the minimum of independent scale-aware limits, so no single
    // large dimension can turn recovery into a nearest-word or whole-line search.
    private const val MAX_DISTANCE_BRUSH_FACTOR = 1.5f
    private const val MAX_DISTANCE_WORD_HEIGHT_FRACTION = 0.65f
    private const val MAX_DISTANCE_LINE_HEIGHT_FRACTION = 0.6f
    private const val NO_BRUSH_WORD_HEIGHT_FRACTION = 0.4f
    // Absolute screenshot-pixel cap. The scale-relative limits alone grow without bound on a
    // poster-sized page or a very large OCR line, which would turn recovery into a nearest-word
    // search; this finite cap keeps every accepted recovery beside the actually painted segment.
    private const val MAX_DISTANCE_PX = 48f
    // Segments whose line gap is within this fraction of the smallest line height are treated as
    // an exact line tie, which surfaces both lines as AMBIGUOUS instead of silently choosing one.
    private const val NEAR_TIE_LINE_FRACTION = 0.1f

    /** Punctuation at an OCR element's edge does not stop it from naming an English word. */
    private const val BOUNDARY_PUNCTUATION = " \"'“”‘’()[]{}<>,;:!?«»"

    /**
     * Same Latin-token grammar as [SentenceWords]: internal straight/curly apostrophes, hyphens and
     * dots stay inside one token and a single sentence-final stop is allowed. The original OCR
     * text is never trimmed or rewritten; this only decides candidacy.
     */
    private val englishWord =
        Regex("[\\p{IsLatin}]+(?:['’‘.\\-\\u2010\\u2011][\\p{IsLatin}]+)*\\.?")

    fun resolve(selection: HighlighterSelection, words: List<HighlighterWord>): SelectionResolution {
        val exact = HighlighterGeometry.hitWords(selection, words)
        // Any exact hit containing an English letter is final: recovery must not duplicate or
        // re-rank it, and the old pipeline keeps deciding punctuation/mixed/phrase shapes.
        if (exact.any { hasAsciiLatinLetter(it.text) }) {
            return SelectionResolution(SelectionResolutionMode.EXACT, exact)
        }
        val recovered = nearCandidates(selection, words)
        return when {
            recovered.size == 1 ->
                SelectionResolution(SelectionResolutionMode.RECOVERED, listOf(recovered.single().word))
            recovered.size > 1 ->
                SelectionResolution(SelectionResolutionMode.AMBIGUOUS, emptyList(), limited(recovered))
            // Exact punctuation/non-English hits stay visible through the old path when no
            // bounded English candidate exists; recovery never turns them into a query by itself.
            exact.isNotEmpty() -> SelectionResolution(SelectionResolutionMode.EXACT, exact)
            else -> SelectionResolution(SelectionResolutionMode.NONE, emptyList())
        }
    }

    private data class Candidate(
        val word: HighlighterWord,
        val distance: Float,
        val lineTop: Float,
        val left: Float
    )

    private data class InkSegment(
        val a: HighlighterPoint,
        val b: HighlighterPoint,
        val lineIndexes: Set<Int>
    )

    private fun nearCandidates(
        selection: HighlighterSelection,
        words: List<HighlighterWord>
    ): List<Candidate> {
        val bands = lineBands(words)
        if (bands.isEmpty()) return emptyList()
        val ink = inkSegments(selection, bands)
        if (ink.isEmpty()) return emptyList()
        val found = LinkedHashMap<HighlighterWord, Candidate>()
        for (word in words) {
            if (!isEnglishWord(word.text)) continue
            val inner = innerBox(word.bounds) ?: continue
            val band = bands[word.lineIndex] ?: continue
            val allowed = allowedDistance(
                inner.bottom - inner.top, band.bottom - band.top, selection.brushRadius)
            if (!allowed.isFinite() || allowed <= 0f) continue
            var best = Float.POSITIVE_INFINITY
            for (segment in ink) {
                // Same-line only: a stroke clearly inside a neighbouring line never reaches this
                // word even when the nominal brush radius would overlap it.
                if (word.lineIndex !in segment.lineIndexes) continue
                if (!spansHorizontally(segment.a, segment.b, inner)) continue
                val distance = segmentDistance(segment.a, segment.b, inner)
                if (distance <= allowed && distance < best) best = distance
            }
            if (!best.isFinite()) continue
            val candidate = Candidate(word, best, band.top, word.bounds.left)
            val previous = found[word]
            if (previous == null || best < previous.distance) found[word] = candidate
        }
        return found.values.sortedWith(compareBy<Candidate>(
            { it.distance }, { it.lineTop }, { it.left }, { it.word.text }))
    }

    /**
     * Keeps the result small while preserving one nearest candidate per line, so a gesture between
     * two lines offers both instead of dropping one behind a crowd on the first line.
     */
    private fun limited(ranked: List<Candidate>): List<HighlighterWord> {
        if (ranked.size <= MAX_CANDIDATES) return ranked.map { it.word }
        val chosen = ArrayList<Candidate>(MAX_CANDIDATES)
        val lines = HashSet<Int>()
        for (candidate in ranked) {
            if (chosen.size >= MAX_CANDIDATES) break
            if (lines.add(candidate.word.lineIndex)) chosen += candidate
        }
        for (candidate in ranked) {
            if (chosen.size >= MAX_CANDIDATES) break
            if (!chosen.contains(candidate)) chosen += candidate
        }
        return chosen.sortedWith(compareBy<Candidate>(
            { it.distance }, { it.lineTop }, { it.left }, { it.word.text }))
            .map { it.word }
    }

    /**
     * Boundary punctuation is tolerated exactly like the existing headword trim; a punctuation-only
     * token, a digit-bearing token, a multi-word phrase or a non-Latin token is not an English
     * fallback candidate. At least one ASCII Latin letter is required, matching the query gate's
     * "English" test without calling or changing dictionary normalization.
     */
    private fun isEnglishWord(text: String): Boolean {
        val core = text.trim { it.isWhitespace() || it in BOUNDARY_PUNCTUATION }
        if (core.isEmpty() || !englishWord.matches(core)) return false
        return hasAsciiLatinLetter(core)
    }

    /** Read-only test, not normalization: an exact result with any English letter stays exact. */
    private fun hasAsciiLatinLetter(text: String): Boolean =
        text.any { it in 'A'..'Z' || it in 'a'..'z' }

    private fun allowedDistance(wordHeight: Float, lineHeight: Float, brushRadius: Float): Float {
        val wordLimit = wordHeight * MAX_DISTANCE_WORD_HEIGHT_FRACTION
        val lineLimit = if (lineHeight.isFinite() && lineHeight > 0f) {
            lineHeight * MAX_DISTANCE_LINE_HEIGHT_FRACTION
        } else {
            wordLimit
        }
        val brushLimit = if (brushRadius.isFinite() && brushRadius > 0f) {
            brushRadius * MAX_DISTANCE_BRUSH_FACTOR
        } else {
            wordHeight * NO_BRUSH_WORD_HEIGHT_FRACTION
        }
        return minOf(wordLimit, lineLimit, brushLimit, MAX_DISTANCE_PX)
    }

    /** One merged vertical band per OCR line index; invalid individual line boxes are ignored. */
    private fun lineBands(words: List<HighlighterWord>): Map<Int, FloatBox> {
        val merged = LinkedHashMap<Int, FloatBox>()
        for (word in words) {
            if (word.text.isBlank()) continue
            val box = effectiveLineBox(word)
            if (!usable(box)) continue
            val previous = merged[word.lineIndex]
            merged[word.lineIndex] = if (previous == null) box else FloatBox(
                min(previous.left, box.left), min(previous.top, box.top),
                max(previous.right, box.right), max(previous.bottom, box.bottom))
        }
        return merged
    }

    private fun effectiveLineBox(word: HighlighterWord): FloatBox =
        if (usable(word.lineBounds)) word.lineBounds else word.bounds

    private fun usable(box: FloatBox): Boolean =
        box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
            box.right > box.left && box.bottom > box.top

    private fun innerBox(bounds: FloatBox): FloatBox? {
        if (!usable(bounds)) return null
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val inset = min(width, height) * HighlighterTuning.WORD_INSET_FRACTION
        val inner = FloatBox(
            bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        return if (inner.right > inner.left && inner.bottom > inner.top) inner else null
    }

    /**
     * Every adjacent pair of recorded points, including intermediate drag history and both
     * directions of a back-and-forth stroke. Only finite strokes with at least two points and a
     * non-zero segment are used; no point is clamped or invented.
     */
    private fun inkSegments(
        selection: HighlighterSelection,
        bands: Map<Int, FloatBox>
    ): List<InkSegment> {
        val result = ArrayList<InkSegment>()
        for (stroke in selection.strokes) {
            if (stroke.points.size < 2) continue
            if (stroke.points.any { !it.x.isFinite() || !it.y.isFinite() }) continue
            for ((a, b) in stroke.points.zipWithNext()) {
                if (a.x == b.x && a.y == b.y) continue
                val lines = lineIndexesFor(a, b, bands)
                if (lines.isNotEmpty()) result += InkSegment(a, b, lines)
            }
        }
        return result
    }

    /**
     * Attaches a segment to the line bands it actually occupies, plus exact/near ties. A segment
     * spanning two lines (or falling between them) therefore yields two candidates and an explicit
     * choice instead of one silently chosen line.
     */
    private fun lineIndexesFor(
        a: HighlighterPoint,
        b: HighlighterPoint,
        bands: Map<Int, FloatBox>
    ): Set<Int> {
        if (bands.isEmpty()) return emptySet()
        var minGap = Float.POSITIVE_INFINITY
        var minHeight = Float.POSITIVE_INFINITY
        for (box in bands.values) {
            minGap = min(minGap, verticalGap(a, b, box))
            minHeight = min(minHeight, box.bottom - box.top)
        }
        if (!minGap.isFinite() || !minHeight.isFinite()) return emptySet()
        val limit = minGap + minHeight * NEAR_TIE_LINE_FRACTION
        val result = HashSet<Int>()
        for ((index, box) in bands) {
            if (verticalGap(a, b, box) <= limit) result += index
        }
        return result
    }

    private fun verticalGap(a: HighlighterPoint, b: HighlighterPoint, box: FloatBox): Float {
        val top = min(a.y, b.y)
        val bottom = max(a.y, b.y)
        return when {
            bottom < box.top -> box.top - bottom
            top > box.bottom -> top - box.bottom
            else -> 0f
        }
    }

    private fun spansHorizontally(a: HighlighterPoint, b: HighlighterPoint, box: FloatBox): Boolean {
        // An endpoint merely touching the word does not span it. A vertical/tiny segment has zero
        // real horizontal coverage even when one of its endpoints lies inside the word, so only the
        // length of the segment∩word interval counts.
        val left = min(a.x, b.x)
        val right = max(a.x, b.x)
        val overlap = min(right, box.right) - max(left, box.left)
        if (overlap <= 0f) return false
        return overlap >= (box.right - box.left) * HighlighterTuning.MIN_HORIZONTAL_COVERAGE_FRACTION
    }

    /** Shortest distance between the segment and the word's inset box, matching strict hit math. */
    private fun segmentDistance(a: HighlighterPoint, b: HighlighterPoint, box: FloatBox): Float {
        if (clipSegment(a, b, box) != null) return 0f
        val corners = listOf(
            HighlighterPoint(box.left, box.top), HighlighterPoint(box.right, box.top),
            HighlighterPoint(box.left, box.bottom), HighlighterPoint(box.right, box.bottom))
        return minOf(pointToBox(a, box), pointToBox(b, box),
            corners.minOf { pointToSegment(it, a, b) })
    }

    private fun pointToBox(point: HighlighterPoint, box: FloatBox): Float {
        val dx = maxOf(box.left - point.x, 0f, point.x - box.right)
        val dy = maxOf(box.top - point.y, 0f, point.y - box.bottom)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun pointToSegment(
        point: HighlighterPoint,
        a: HighlighterPoint,
        b: HighlighterPoint
    ): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0f) {
            return hypot((point.x - a.x).toDouble(), (point.y - a.y).toDouble()).toFloat()
        }
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        val nearestX = a.x + dx * t
        val nearestY = a.y + dy * t
        return hypot((point.x - nearestX).toDouble(), (point.y - nearestY).toDouble()).toFloat()
    }

    private fun clipSegment(
        a: HighlighterPoint,
        b: HighlighterPoint,
        box: FloatBox
    ): Pair<HighlighterPoint, HighlighterPoint>? {
        var enter = 0f
        var exit = 1f
        fun axis(origin: Float, delta: Float, low: Float, high: Float): Boolean {
            if (delta == 0f) return origin >= low && origin <= high
            val first = (low - origin) / delta
            val second = (high - origin) / delta
            enter = maxOf(enter, minOf(first, second))
            exit = minOf(exit, maxOf(first, second))
            return enter <= exit
        }
        val dx = b.x - a.x
        val dy = b.y - a.y
        if (!axis(a.x, dx, box.left, box.right) || !axis(a.y, dy, box.top, box.bottom)) {
            return null
        }
        return HighlighterPoint(a.x + dx * enter, a.y + dy * enter) to
            HighlighterPoint(a.x + dx * exit, a.y + dy * exit)
    }
}
