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

    fun paginate(lineCounts: List<Int>): List<ExportPage> {
        require(lineCounts.all { it > 0 })
        val pages = mutableListOf<ExportPage>()
        var slices = mutableListOf<TextSlice>()
        var y = TOP
        val capacity = ((BOTTOM - TOP) / LINE_HEIGHT).toInt()
        lineCounts.forEachIndexed { index, count ->
            if (count <= capacity && y + count * LINE_HEIGHT > BOTTOM && slices.isNotEmpty()) {
                pages += ExportPage(slices.toList())
                slices = mutableListOf()
                y = TOP
            }
            var line = 0
            while (line < count) {
                val available = ((BOTTOM - y) / LINE_HEIGHT).toInt()
                if (available <= 0) {
                    pages += ExportPage(slices.toList())
                    slices = mutableListOf()
                    y = TOP
                    continue
                }
                val take = minOf(available, count - line)
                slices += TextSlice(index, line, take, y)
                y += take * LINE_HEIGHT + ROW_GAP
                line += take
                if (line < count) {
                    pages += ExportPage(slices.toList())
                    slices = mutableListOf()
                    y = TOP
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
