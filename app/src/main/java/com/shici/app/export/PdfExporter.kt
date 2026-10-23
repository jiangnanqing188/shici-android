package com.shici.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.shici.app.core.*
import java.io.OutputStream
import java.time.LocalDate

/**
 * A4 PDF writer built only on Android's [PdfDocument]. Content is laid out before any page is
 * created: widths are measured with the same paint used to draw, so long words/CJK/emoji wrap by
 * code point, long meanings continue on following pages, and a long header moves the body down
 * instead of overlapping it. The caller owns [output]; it is never closed here.
 */
object PdfExporter {
    private const val LEFT = 48f
    private const val CONTENT_WIDTH = 499f
    private const val TITLE_STEP = 26f
    private const val SMALL_STEP = 14f
    private const val SELF_TEST_BLANK_LINES = 5

    /**
     * Legacy entry point. [words] are rendered exactly as given in review mode (no re-filtering),
     * preserving the original API and its frozen-list contract.
     */
    fun write(words: List<LearnedWord>, output: OutputStream, date: LocalDate) {
        write(LearningExportSnapshot(words, "", date), output)
    }

    /**
     * Renders the frozen [snapshot]. Review mode prints every row; self-test mode prints a question
     * section (number + headword + part of speech + writing space only) followed by a separately
     * paginated answer section with the same numbering/order and full meaning, note and provenance.
     */
    fun write(snapshot: LearningExportSnapshot, output: OutputStream) {
        require(snapshot.words.isNotEmpty()) { "没有可导出的生词" }
        val styles = Styles()
        val header = buildHeader(snapshot, styles)
        val words = snapshot.words
        val sections: List<Section> = when (snapshot.options.mode) {
            ExportMode.REVIEW -> {
                val blocks = words.mapIndexed { index, word ->
                    Block(
                        ExportLayout.wrap(
                            LearningExportText.row(index, word), CONTENT_WIDTH, styles.body::measureText
                        ),
                        styles.body
                    )
                }
                listOf(Section(ExportLayout.paginate(blocks.map { it.lines.size }, header.bodyTop), blocks))
            }
            ExportMode.SELF_TEST -> {
                // One ordered list feeds both sections, so question n and answer n are the same word.
                val questions = mutableListOf<Block>()
                questions += Block(
                    ExportLayout.wrap(
                        "自测题 · 写出下列英文单词的释义", CONTENT_WIDTH, styles.section::measureText
                    ),
                    styles.section
                )
                words.forEachIndexed { index, word ->
                    val prompt = ExportLayout.wrap(
                        LearningExportText.question(index, word), CONTENT_WIDTH, styles.body::measureText
                    )
                    // Blank writing lines are real, empty body lines; they paginate like text.
                    questions += Block(prompt + List(SELF_TEST_BLANK_LINES) { "" }, styles.body)
                }
                val answers = mutableListOf<Block>()
                answers += Block(
                    ExportLayout.wrap(
                        "答案页 · 与自测题编号和顺序一一对应", CONTENT_WIDTH, styles.section::measureText
                    ),
                    styles.section
                )
                words.forEachIndexed { index, word ->
                    answers += Block(
                        ExportLayout.wrap(
                            LearningExportText.answer(index, word), CONTENT_WIDTH, styles.body::measureText
                        ),
                        styles.body
                    )
                }
                // Separate pagination forces the answer section to start on a new page.
                listOf(
                    Section(ExportLayout.paginate(questions.map { it.lines.size }, header.bodyTop), questions),
                    Section(ExportLayout.paginate(answers.map { it.lines.size }, header.bodyTop), answers)
                )
            }
        }
        render(header, sections, output, styles)
    }

    private class Block(val lines: List<String>, val paint: Paint)
    private class Section(val pages: List<ExportPage>, val blocks: List<Block>)

    private class Header(
        val title: List<String>,
        val meta: List<String>,
        val notice: List<String>,
        val metaTop: Float,
        val noticeTop: Float,
        val bodyTop: Float
    )

    private class Styles {
        val title = paint(22f, Typeface.BOLD)
        val section = paint(15f, Typeface.BOLD)
        val body = paint(12f, Typeface.NORMAL)
        val small = paint(10f, Typeface.NORMAL)

        private fun paint(size: Float, style: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size
            typeface = Typeface.create("sans-serif", style)
        }
    }

    private fun buildHeader(snapshot: LearningExportSnapshot, styles: Styles): Header {
        val titleText = if (snapshot.options.mode == ExportMode.SELF_TEST) {
            "拾词 · 生词自测单"
        } else {
            "拾词 · 生词记忆单"
        }
        val title = ExportLayout.wrap(titleText, CONTENT_WIDTH, styles.title::measureText)
        val meta = ExportLayout.wrap(metaText(snapshot), CONTENT_WIDTH, styles.small::measureText)
        val notice = ExportLayout.wrap(
            LearningExportText.notice(snapshot.words), CONTENT_WIDTH, styles.small::measureText
        )
        val metaTop = 48f + (title.size - 1) * TITLE_STEP + 22f
        val noticeTop = metaTop + meta.size * SMALL_STEP + 2f
        val headerBottom = noticeTop + (notice.size - 1) * SMALL_STEP + 14f
        // Keep at least one body line above the footer even if a pathological header wraps forever.
        val bodyTop = minOf(
            maxOf(ExportLayout.TOP, headerBottom + 8f),
            ExportLayout.BOTTOM - ExportLayout.LINE_HEIGHT
        )
        return Header(title, meta, notice, metaTop, noticeTop, bodyTop)
    }

    private fun metaText(snapshot: LearningExportSnapshot): String {
        val options = snapshot.options
        val parts = mutableListOf(snapshot.date.toString())
        parts += when {
            options.from != null && options.to != null -> "首次收录 ${options.from}~${options.to}"
            options.from != null -> "首次收录 ${options.from} 起"
            options.to != null -> "首次收录至 ${options.to}"
            else -> "当前搜索结果"
        }
        parts += when (options.sort) {
            ExportSort.QUERY_COUNT -> "查询次数优先"
            ExportSort.RECENT_QUERY -> "最近查询优先"
        }
        parts += "${snapshot.words.size} 词"
        if (options.favoritesOnly) parts += "仅收藏"
        if (options.mode == ExportMode.SELF_TEST) parts += "自测版＋答案页"
        return parts.joinToString(" · ")
    }

    private fun render(
        header: Header,
        sections: List<Section>,
        output: OutputStream,
        styles: Styles
    ) {
        val totalPages = sections.fold(0) { count, section -> count + section.pages.size }
        val pdf = PdfDocument()
        var failure: Throwable? = null
        try {
            var pageNumber = 0
            sections.forEach { section ->
                section.pages.forEach { layout ->
                    pageNumber++
                    val page = pdf.startPage(
                        PdfDocument.PageInfo.Builder(
                            ExportLayout.PAGE_WIDTH, ExportLayout.PAGE_HEIGHT, pageNumber
                        ).create()
                    )
                    try {
                        drawHeader(page.canvas, header, styles)
                        drawBody(page.canvas, layout, section.blocks)
                        drawFooter(page.canvas, pageNumber, totalPages, styles.small)
                    } finally {
                        pdf.finishPage(page)
                    }
                }
            }
            pdf.writeTo(output)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            try {
                pdf.close()
            } catch (close: Throwable) {
                if (failure != null) failure.addSuppressed(close) else throw close
            }
        }
    }

    private fun drawHeader(canvas: Canvas, header: Header, styles: Styles) {
        header.title.forEachIndexed { index, line ->
            canvas.drawText(line, LEFT, 48f + index * TITLE_STEP, styles.title)
        }
        header.meta.forEachIndexed { index, line ->
            canvas.drawText(line, LEFT, header.metaTop + index * SMALL_STEP, styles.small)
        }
        header.notice.forEachIndexed { index, line ->
            canvas.drawText(line, LEFT, header.noticeTop + index * SMALL_STEP, styles.small)
        }
    }

    private fun drawBody(canvas: Canvas, layout: ExportPage, blocks: List<Block>) {
        layout.slices.forEach { slice ->
            val block = blocks[slice.rowIndex]
            repeat(slice.lineCount) { offset ->
                val text = block.lines[slice.firstLine + offset]
                if (text.isNotEmpty()) {
                    canvas.drawText(
                        text,
                        LEFT,
                        slice.y + (offset + 1) * ExportLayout.LINE_HEIGHT - 4f,
                        block.paint
                    )
                }
            }
        }
    }

    private fun drawFooter(canvas: Canvas, pageNumber: Int, totalPages: Int, paint: Paint) {
        canvas.drawText("非官方词库 · 不保证大纲全覆盖 · 请核对释义", LEFT, 815f, paint)
        val label = "$pageNumber / $totalPages"
        canvas.drawText(
            label,
            ExportLayout.PAGE_WIDTH - LEFT - paint.measureText(label),
            815f,
            paint
        )
    }
}
