package com.shici.app.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How [SelectionRecovery.resolve] obtained its result.
 *
 * The strict [HighlighterGeometry.hitWords] path stays the only source of [EXACT]; recovery never
 * broadens centreline hits or changes their order. [RECOVERED] is one uniquely
 * plausible underlined word; [AMBIGUOUS] carries original nearby words
 * for explicit confirmation; [NONE] has neither an exact hit nor a candidate.
 */
enum class SelectionResolutionMode { EXACT, RECOVERED, AMBIGUOUS, NONE }

/**
 * One explicit selection decision for a committed highlighter gesture.
 *
 * [selected] is non-empty only for [SelectionResolutionMode.EXACT] (centreline hits, in reading
 * order) and [SelectionResolutionMode.RECOVERED] (exactly one word).
 * [candidates] is non-empty only for [SelectionResolutionMode.AMBIGUOUS]; the caller must show
 * those original words for an explicit choice and must not concatenate them or silently take
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
 * An exact hit containing an English letter wins without adding brush-only neighbors. Without
 * such a hit, a single nearby word can be recovered only with positive underline evidence; any
 * other nearby matches require an explicit choice. Recovery is bounded by horizontal coverage and
 * brush, word and line height limits applied to actual segments, not the full gesture envelope.
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
        val nearby = nearCandidates(selection, words)
        val underline = nearby.singleOrNull()?.takeIf { it.underline }
        return when {
            underline != null ->
                SelectionResolution(SelectionResolutionMode.RECOVERED, listOf(underline.word))
            nearby.isNotEmpty() ->
                SelectionResolution(SelectionResolutionMode.AMBIGUOUS, emptyList(), limited(nearby))
            // A separator alone is not recognized text. Keep other punctuation and original text
            // (including dashes beside words) on the existing correction/selection path.
            exact.any { !isLineOnly(it.text) } -> SelectionResolution(SelectionResolutionMode.EXACT, exact)
            else -> SelectionResolution(SelectionResolutionMode.NONE, emptyList())
        }
    }

    private fun isLineOnly(text: String): Boolean = text.all {
        it.isWhitespace() || it in "_−─━" || Character.getType(it) == Character.DASH_PUNCTUATION.toInt()
    }

    private data class Candidate(
        val word: HighlighterWord,
        val distance: Float,
        val lineTop: Float,
        val left: Float,
        val underline: Boolean
    )

    private fun nearCandidates(
        selection: HighlighterSelection,
        words: List<HighlighterWord>
    ): List<Candidate> {
        val bands = lineBands(words)
        val ink = selection.strokes.asSequence()
            .filter { stroke -> stroke.points.all { it.x.isFinite() && it.y.isFinite() } }
            .flatMap { it.points.asSequence().zipWithNext() }
            .filter { (a, b) -> a.x != b.x }
            .toList()
        if (ink.isEmpty()) return emptyList()
        val found = LinkedHashMap<HighlighterWord, Candidate>()
        for (word in words) {
            if (!isEnglishWord(word.text)) continue
            val inner = HighlighterGeometry.wordInterior(word.bounds) ?: continue
            val band = bands[word.lineIndex] ?: continue
            val allowed = allowedDistance(
                inner.bottom - inner.top, band.bottom - band.top, selection.brushRadius)
            if (!allowed.isFinite() || allowed <= 0f) continue
            val nearbyBox = inner.copy(top = inner.top - allowed, bottom = inner.bottom + allowed)
            val underlineBox = nearbyBox.copy(top = word.bounds.bottom)
            val spans = ArrayList<Pair<Float, Float>>()
            val underlines = ArrayList<Pair<Float, Float>>()
            var best = Float.POSITIVE_INFINITY
            for ((a, b) in ink) {
                if (max(a.x, b.x) <= nearbyBox.left || min(a.x, b.x) >= nearbyBox.right ||
                    max(a.y, b.y) < nearbyBox.top || min(a.y, b.y) > nearbyBox.bottom) continue
                val (start, end) = HighlighterGeometry.clipSegment(a, b, nearbyBox) ?: continue
                if (start.x == end.x) continue
                spans += min(start.x, end.x) to max(start.x, end.x)
                best = min(best, maxOf(inner.top - max(start.y, end.y),
                    min(start.y, end.y) - inner.bottom, 0f))
                // A near miss above/alongside the word can be offered, but only actual ink below
                // it moving primarily across it is evidence for an automatic underline lookup.
                if (underlineBox.top <= underlineBox.bottom && abs(b.x - a.x) >= abs(b.y - a.y)) {
                    val (underStart, underEnd) = HighlighterGeometry.clipSegment(start, end, underlineBox)
                        ?: continue
                    if (underStart.x != underEnd.x) {
                        underlines += min(underStart.x, underEnd.x) to max(underStart.x, underEnd.x)
                    }
                }
            }
            val required = (inner.right - inner.left) * HighlighterTuning.MIN_HORIZONTAL_COVERAGE_FRACTION
            if (horizontalCoverage(spans) < required) continue
            found[word] = Candidate(word, best, band.top, word.bounds.left,
                horizontalCoverage(underlines) >= required)
        }
        return found.values.sortedWith(compareBy<Candidate>(
            { it.distance }, { it.lineTop }, { it.left }, { it.word.text }))
    }

    /** Project only clipped ink and count overlapping/retraced pieces once, not the spaces between them. */
    private fun horizontalCoverage(spans: MutableList<Pair<Float, Float>>): Float {
        spans.sortBy { it.first }
        var right = Float.NEGATIVE_INFINITY
        var coverage = 0f
        for ((start, end) in spans) {
            if (end > right) {
                coverage += end - max(start, right)
                right = end
            }
        }
        return coverage
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
            if (word.text.isBlank() || isLineOnly(word.text)) continue
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
}
