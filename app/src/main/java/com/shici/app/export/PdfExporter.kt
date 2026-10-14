package com.shici.app.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.shici.app.core.*
import java.io.OutputStream
import java.time.LocalDate

object PdfExporter {
    fun write(words: List<LearnedWord>, output: OutputStream, date: LocalDate) {
        require(words.isNotEmpty()) { "没有可导出的生词" }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f; typeface = Typeface.DEFAULT }
        val lines = words.mapIndexed { index, word ->
            ExportLayout.wrap(LearningExportText.row(index, word), 499f, paint::measureText)
        }
        val pages = ExportLayout.paginate(lines.map { it.size })
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { pageIndex, layout ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
                val canvas = page.canvas
                paint.textSize = 22f; paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                canvas.drawText("拾词 · 生词记忆单", 48f, 48f, paint)
                paint.textSize = 10f; paint.typeface = Typeface.DEFAULT
                canvas.drawText("$date · 当前搜索结果 · 最近查询优先 · ${words.size} 词", 48f, 70f, paint)
                canvas.drawText(LearningExportText.notice(words), 48f, 86f, paint)
                paint.textSize = 12f
                layout.slices.forEach { slice ->
                    repeat(slice.lineCount) { offset ->
                        canvas.drawText(lines[slice.rowIndex][slice.firstLine + offset], 48f,
                            slice.y + (offset + 1) * ExportLayout.LINE_HEIGHT - 4f, paint)
                    }
                }
                paint.textSize = 10f
                canvas.drawText("非官方词库 · 不保证大纲全覆盖 · 请核对释义", 48f, 815f, paint)
                canvas.drawText("${pageIndex + 1} / ${pages.size}", 510f, 815f, paint)
                pdf.finishPage(page)
            }
            pdf.writeTo(output)
        } finally { pdf.close() }
    }
}
