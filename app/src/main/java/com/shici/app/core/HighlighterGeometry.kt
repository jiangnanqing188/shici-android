package com.shici.app.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/** Prototype tuning constants, not user settings. All gesture dimensions are in view dp. */
object HighlighterTuning {
    const val BRUSH_WIDTH_DP = 24f
    const val MIN_STROKE_LENGTH_DP = 6f
    const val OCR_CONTEXT_PADDING_DP = 64f
    const val WORD_INSET_FRACTION = 0.08f
    /**
     * The painted ink is [BRUSH_WIDTH_DP] wide, but a hit is not decided by the centreline alone:
     * a word whose box lies under the visibly painted band is selected as well. The effective
     * radius deliberately stays below the full nominal radius (round cap/anti-aliasing and the
     * word's own box already provide tolerance), and never grows beyond half of the word's
     * smaller side, so a stroke cannot silently reach into a neighbouring line.
     */
    const val EFFECTIVE_BRUSH_RADIUS_FRACTION = 0.8f
    const val MAX_COVERAGE_OF_WORD_FRACTION = 0.5f
    /**
     * Coverage only counts when the stroke actually spans part of the word. This keeps a stroke
     * that stops before the next word (or starts after the previous one) from selecting a merely
     * adjacent element whose centre happens to be inside the nominal brush radius.
     */
    const val MIN_HORIZONTAL_COVERAGE_FRACTION = 0.25f
}

data class HighlighterPoint(val x: Float, val y: Float)
data class HighlighterStroke(val points: List<HighlighterPoint>)
/**
 * Strokes and crop are in screenshot pixels, never overlay/window coordinates. [brushRadius] is
 * the visible highlighter radius in the same screenshot pixels, so coverage and centreline hits
 * use one coordinate/density/OCR-scale basis.
 */
data class HighlighterSelection(
    val strokes: List<HighlighterStroke>,
    val crop: PixelBox,
    val brushRadius: Float = 0f
)
data class HighlighterWord(val text: String, val bounds: FloatBox, val lineIndex: Int, val lineBounds: FloatBox)

object HighlighterGeometry {
    fun strokeLength(stroke: HighlighterStroke): Float = stroke.points.zipWithNext().sumOf { (a, b) ->
        hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
    }.toFloat()

    fun selection(strokes: List<HighlighterStroke>, imageWidth: Int, imageHeight: Int,
                  viewWidth: Int, viewHeight: Int, density: Float): HighlighterSelection? {
        require(density > 0 && density.isFinite())
        val image = SelectionGeometry.imageBox(imageWidth, imageHeight, viewWidth, viewHeight)
        val scale = (image.right - image.left) / imageWidth
        val screen = FloatBox(0f, 0f, imageWidth.toFloat(), imageHeight.toFloat())
        val valid = mutableListOf<HighlighterStroke>()
        val visiblePoints = mutableListOf<HighlighterPoint>()
        strokes.forEach { stroke ->
            if (stroke.points.size < 2 || stroke.points.any { !it.x.isFinite() || !it.y.isFinite() }) return@forEach
            // Do not clamp individual points: clamping would invent strokes along the image edge.
            val mapped = HighlighterStroke(stroke.points.map {
                HighlighterPoint((it.x - image.left) / scale, (it.y - image.top) / scale)
            })
            val visible = mapped.points.zipWithNext().mapNotNull { (a, b) -> clipSegment(a, b, screen) }
            val length = visible.sumOf { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }
            if (length * scale < HighlighterTuning.MIN_STROKE_LENGTH_DP * density) return@forEach
            valid += mapped
            visible.forEach { (a, b) -> visiblePoints += a; visiblePoints += b }
        }
        if (valid.isEmpty()) return null
        val padding = HighlighterTuning.OCR_CONTEXT_PADDING_DP * density / scale
        val crop = PixelBox(
            floor(visiblePoints.minOf { it.x } - padding).toInt().coerceIn(0, imageWidth),
            floor(visiblePoints.minOf { it.y } - padding).toInt().coerceIn(0, imageHeight),
            ceil(visiblePoints.maxOf { it.x } + padding).toInt().coerceIn(0, imageWidth),
            ceil(visiblePoints.maxOf { it.y } + padding).toInt().coerceIn(0, imageHeight)
        )
        val brushRadius = HighlighterTuning.BRUSH_WIDTH_DP * density / scale / 2f
        return HighlighterSelection(valid, crop, brushRadius.takeIf { it.isFinite() && it > 0f } ?: 0f)
    }

    /** Restore crop-local OCR boxes, including a resized OCR input, to screenshot coordinates. */
    fun restoreBox(box: FloatBox, crop: PixelBox, ocrWidth: Int = crop.width, ocrHeight: Int = crop.height): FloatBox {
        require(crop.width > 0 && crop.height > 0 && ocrWidth > 0 && ocrHeight > 0)
        val sx = crop.width.toFloat() / ocrWidth
        val sy = crop.height.toFloat() / ocrHeight
        return FloatBox(crop.left + box.left * sx, crop.top + box.top * sy,
            crop.left + box.right * sx, crop.top + box.bottom * sy)
    }

    fun hitWords(selection: HighlighterSelection, words: List<HighlighterWord>): List<HighlighterWord> {
        val hit = words.filter { word ->
            word.text.isNotBlank() && hits(selection.strokes, word.bounds, selection.brushRadius)
        }
        // Line boxes establish top-to-bottom order; element boxes establish left-to-right order.
        // Do not use gesture direction, OCR block iteration order or normalized/deduplicated words.
        return hit.groupBy { it.lineIndex }.values
            .sortedWith(compareBy({ it.first().lineBounds.top }, { it.first().lineBounds.left }))
            .flatMap { line -> line.sortedWith(compareBy({ it.bounds.left }, { it.bounds.top })) }
    }

    fun selectedText(words: List<HighlighterWord>): String = buildString {
        var previous: HighlighterWord? = null
        words.forEach { word ->
            val before = previous
            if (before != null) {
                if (before.lineIndex != word.lineIndex) append('\n')
                else if (word.text.first() !in ",.;:!?%)]}" && before.text.last() !in "([{") append(' ')
            }
            append(word.text)
            previous = word
        }
    }

    private fun hits(strokes: List<HighlighterStroke>, box: FloatBox, brushRadius: Float): Boolean {
        val width = box.right - box.left
        val height = box.bottom - box.top
        if (width <= 0 || height <= 0) return false
        val inset = min(width, height) * HighlighterTuning.WORD_INSET_FRACTION
        val inner = FloatBox(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
        // Coverage is bounded by the word's own smaller side as well as the real painted radius.
        // It therefore follows the visible 24dp ink near the word without becoming an
        // unconditional nearest-word or whole-line fallback.
        val coverage = if (brushRadius.isFinite() && brushRadius > 0f) {
            min(brushRadius * HighlighterTuning.EFFECTIVE_BRUSH_RADIUS_FRACTION,
                min(width, height) * HighlighterTuning.MAX_COVERAGE_OF_WORD_FRACTION)
        } else 0f
        return strokes.any { stroke -> stroke.points.zipWithNext().any { (a, b) ->
            val clipped = clipSegment(a, b, inner)
            if (clipped != null && clipped.first != clipped.second) {
                val x = (clipped.first.x + clipped.second.x) / 2f
                val y = (clipped.first.y + clipped.second.y) / 2f
                if (x > inner.left && x < inner.right && y > inner.top && y < inner.bottom) {
                    true
                } else {
                    // The centreline only grazes the inset boundary. The painted band may still
                    // cover the word, so fall through to the coverage test.
                    coverage > 0f && coversWord(a, b, inner, coverage)
                }
            } else {
                coverage > 0f && coversWord(a, b, inner, coverage)
            }
        } }
    }

    /**
     * True when the finite stroke segment lies within [coverage] of the word's inner box and
     * actually spans it horizontally. Per-segment testing (rather than the whole stroke's
     * bounding box) is what keeps an untouched word between two painted words out of the result.
     */
    private fun coversWord(a: HighlighterPoint, b: HighlighterPoint, inner: FloatBox, coverage: Float): Boolean {
        if (a == b) return false
        if (!spansWordHorizontally(a, b, inner)) return false
        return distanceToBox(a, b, inner) <= coverage
    }

    private fun spansWordHorizontally(a: HighlighterPoint, b: HighlighterPoint, box: FloatBox): Boolean {
        // A vertical or diagonal stroke inside the word's horizontal extent always counts, even
        // when its x-range is a single point. Otherwise require a meaningful overlap with the
        // word, so a stroke ending before the next word cannot select it merely by brush radius.
        if (a.x in box.left..box.right || b.x in box.left..box.right) return true
        val left = min(a.x, b.x)
        val right = maxOf(a.x, b.x)
        if (right < box.left || left > box.right) return false
        val overlap = min(right, box.right) - maxOf(left, box.left)
        return overlap >= (box.right - box.left) * HighlighterTuning.MIN_HORIZONTAL_COVERAGE_FRACTION
    }

    /** Shortest distance between the finite segment and the (axis-aligned) inner word box. */
    private fun distanceToBox(a: HighlighterPoint, b: HighlighterPoint, box: FloatBox): Float {
        if (clipSegment(a, b, box) != null) return 0f
        val corners = listOf(
            HighlighterPoint(box.left, box.top), HighlighterPoint(box.right, box.top),
            HighlighterPoint(box.left, box.bottom), HighlighterPoint(box.right, box.bottom)
        )
        return minOf(
            pointToBox(a, box), pointToBox(b, box),
            corners.minOf { pointToSegment(it, a, b) }
        )
    }

    private fun pointToBox(point: HighlighterPoint, box: FloatBox): Float {
        val dx = maxOf(box.left - point.x, 0f, point.x - box.right)
        val dy = maxOf(box.top - point.y, 0f, point.y - box.bottom)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun pointToSegment(point: HighlighterPoint, a: HighlighterPoint, b: HighlighterPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0f) return hypot((point.x - a.x).toDouble(), (point.y - a.y).toDouble()).toFloat()
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        val nearestX = a.x + dx * t
        val nearestY = a.y + dy * t
        return hypot((point.x - nearestX).toDouble(), (point.y - nearestY).toDouble()).toFloat()
    }

    /** Segment clipping rather than sampled-point tests also handles a two-point fast swipe. */
    private fun clipSegment(a: HighlighterPoint, b: HighlighterPoint, box: FloatBox): Pair<HighlighterPoint, HighlighterPoint>? {
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
        if (!axis(a.x, dx, box.left, box.right) || !axis(a.y, dy, box.top, box.bottom)) return null
        return HighlighterPoint(a.x + dx * enter, a.y + dy * enter) to
            HighlighterPoint(a.x + dx * exit, a.y + dy * exit)
    }
}
