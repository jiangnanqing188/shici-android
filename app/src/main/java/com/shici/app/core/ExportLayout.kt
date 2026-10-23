package com.shici.app.core

/** Coordinates in PDF points. Long entries continue on another page; nothing is truncated. */
data class TextSlice(val rowIndex: Int, val firstLine: Int, val lineCount: Int, val y: Float)
data class ExportPage(val slices: List<TextSlice>)

object ExportLayout {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val TOP = 100f
    const val BOTTOM = 786f
    const val LINE_HEIGHT = 18f
    const val ROW_GAP = 14f

    /**
     * Flow rows onto pages. [top]/[bottom] are the body bounds in points; the defaults reproduce the
     * original layout exactly, so a taller wrapped page header can move [top] down without changing
     * the two-argument callers or their tests.
     */
    fun paginate(lineCounts: List<Int>, top: Float = TOP, bottom: Float = BOTTOM): List<ExportPage> {
        require(lineCounts.all { it > 0 })
        require(top >= 0f && top < bottom) { "正文区域无效" }
        val pages = mutableListOf<ExportPage>()
        var slices = mutableListOf<TextSlice>()
        var y = top
        val capacity = ((bottom - top) / LINE_HEIGHT).toInt().coerceAtLeast(1)
        lineCounts.forEachIndexed { index, count ->
            if (count <= capacity && y + count * LINE_HEIGHT > bottom && slices.isNotEmpty()) {
                pages += ExportPage(slices.toList())
                slices = mutableListOf()
                y = top
            }
            var line = 0
            while (line < count) {
                val available = ((bottom - y) / LINE_HEIGHT).toInt()
                if (available <= 0) {
                    pages += ExportPage(slices.toList())
                    slices = mutableListOf()
                    y = top
                    continue
                }
                val take = minOf(available, count - line)
                slices += TextSlice(index, line, take, y)
                y += take * LINE_HEIGHT + ROW_GAP
                line += take
                if (line < count) {
                    pages += ExportPage(slices.toList())
                    slices = mutableListOf()
                    y = top
                }
            }
        }
        if (slices.isNotEmpty()) pages += ExportPage(slices.toList())
        return pages
    }

    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        require(maxWidth > 0)
        val lines = mutableListOf<String>()
        text.split('\n').forEach { paragraph ->
            var line = ""
            paragraph.codePoints().toArray().forEach { codePoint ->
                val character = String(Character.toChars(codePoint))
                if (line.isNotEmpty() && measure(line + character) > maxWidth) {
                    lines += line
                    line = ""
                }
                line += character
            }
            lines += line
        }
        return lines
    }
}
