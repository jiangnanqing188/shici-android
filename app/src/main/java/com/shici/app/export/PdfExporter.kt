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
            val content = "${index + 1}. ${word.entry.headword}  ${word.entry.partOfSpeech}\n" +
                "原词形：${word.lastForm} · 查询 ${word.queryCount} 次 · ${if (word.mastered) "已掌握" else "待复习"}\n" +
                word.entry.meaning + if (word.note.isBlank()) "" else "\n笔记：${word.note}"
            ExportLayout.wrap(content, 499f, paint::measureText)
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
                canvas.drawText("演示词库 / 自编示例，仅用于原型流程验证", 48f, 86f, paint)
                paint.textSize = 12f
                layout.slices.forEach { slice ->
                    repeat(slice.lineCount) { offset ->
                        canvas.drawText(lines[slice.rowIndex][slice.firstLine + offset], 48f,
                            slice.y + (offset + 1) * ExportLayout.LINE_HEIGHT - 4f, paint)
                    }
                }
                paint.textSize = 10f
                canvas.drawText("演示数据，不是正式考试词典", 48f, 815f, paint)
                canvas.drawText("${pageIndex + 1} / ${pages.size}", 510f, 815f, paint)
                pdf.finishPage(page)
            }
            pdf.writeTo(output)
        } finally { pdf.close() }
    }
}
