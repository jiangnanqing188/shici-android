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
    /** Minimum horizontal overlap for a nearby word to qualify for recovery. */
    const val MIN_HORIZONTAL_COVERAGE_FRACTION = 0.25f
}

data class HighlighterPoint(val x: Float, val y: Float)
data class HighlighterStroke(val points: List<HighlighterPoint>)
/**
 * Strokes and crop are in screenshot pixels, never overlay/window coordinates. [brushRadius] is
 * the visible highlighter radius in those pixels, used to bound recovery and protect the ink area.
 */
data class HighlighterSelection(
    val strokes: List<HighlighterStroke>,
    val crop: PixelBox,
    val brushRadius: Float = 0f,
    /**
     * The necessary painted-ink region (visible stroke band) in screenshot pixels. This is the
     * protection region used by page-identity checks: it is deliberately **not** [crop], whose
     * 64 dp OCR context margin may legitimately sit under this application's own result card.
     * Null only when the stroke geometry could not produce a non-empty visible band.
     */
    val inkBounds: PixelBox? = null
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
        val safeBrush = brushRadius.takeIf { it.isFinite() && it > 0f } ?: 0f
        // The painted band is the necessary selected region. It is kept separate from [crop]:
        // the context padding is for OCR input only and must never be required to stay visible.
        val inkBounds = PixelBox(
            floor(visiblePoints.minOf { it.x } - safeBrush).toInt().coerceIn(0, imageWidth),
            floor(visiblePoints.minOf { it.y } - safeBrush).toInt().coerceIn(0, imageHeight),
            ceil(visiblePoints.maxOf { it.x } + safeBrush).toInt().coerceIn(0, imageWidth),
            ceil(visiblePoints.maxOf { it.y } + safeBrush).toInt().coerceIn(0, imageHeight)
        ).takeIf { it.width > 0 && it.height > 0 }
        return HighlighterSelection(valid, crop, safeBrush, inkBounds)
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
            word.text.isNotBlank() && centerlineHits(selection.strokes, word.bounds)
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

    /**
     * Explicit EXACT/RECOVERED/AMBIGUOUS/NONE decision for a committed gesture, for the caller that
     * wants bounded fallback selection. [hitWords] supplies centreline hits; [SelectionRecovery.resolve]
     * ignores line-only results and can recover an original word. It starts no lookup or record.
     */
    fun resolveWords(selection: HighlighterSelection, words: List<HighlighterWord>): SelectionResolution =
        SelectionRecovery.resolve(selection, words)

    internal fun wordInterior(box: FloatBox): FloatBox? {
        if (!box.left.isFinite() || !box.right.isFinite() ||
            !box.top.isFinite() || !box.bottom.isFinite()) return null
        val width = box.right - box.left
        val height = box.bottom - box.top
        if (width <= 0 || height <= 0) return null
        val inset = min(width, height) * HighlighterTuning.WORD_INSET_FRACTION
        return FloatBox(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
    }

    private fun centerlineHits(strokes: List<HighlighterStroke>, box: FloatBox): Boolean {
        val inner = wordInterior(box) ?: return false
        return strokes.any { stroke -> stroke.points.zipWithNext().any { (a, b) ->
            val clipped = clipSegment(a, b, inner)
            if (clipped == null || clipped.first == clipped.second) false else {
                val x = (clipped.first.x + clipped.second.x) / 2f
                val y = (clipped.first.y + clipped.second.y) / 2f
                x > inner.left && x < inner.right && y > inner.top && y < inner.bottom
            }
        } }
    }

    /** Segment clipping rather than sampled-point tests also handles a two-point fast swipe. */
    internal fun clipSegment(a: HighlighterPoint, b: HighlighterPoint, box: FloatBox): Pair<HighlighterPoint, HighlighterPoint>? {
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
