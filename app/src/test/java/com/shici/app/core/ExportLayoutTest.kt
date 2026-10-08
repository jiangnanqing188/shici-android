package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class ExportLayoutTest {
    @Test fun emptyExportDoesNotInventPage() { assertTrue(ExportLayout.paginate(emptyList()).isEmpty()) }
    @Test fun ordinaryEntryMovesWholeToNextPage() {
        val pages = ExportLayout.paginate(listOf(30, 12))
        assertEquals(2, pages.size)
        assertEquals(TextSlice(1, 0, 12, ExportLayout.TOP), pages[1].slices.single())
    }
    @Test fun longEntryContinuesAndKeepsEveryLineExactlyOnce() {
        val pages = ExportLayout.paginate(listOf(100, 4))
        val slices = pages.flatMap { it.slices }
        assertEquals(100, slices.filter { it.rowIndex == 0 }.sumOf { it.lineCount })
        assertEquals((0..99).toList(), slices.filter { it.rowIndex == 0 }.flatMap { slice ->
            (slice.firstLine until slice.firstLine + slice.lineCount).toList()
        })
        assertTrue(slices.all { it.y >= ExportLayout.TOP && it.y + it.lineCount * ExportLayout.LINE_HEIGHT <= ExportLayout.BOTTOM })
    }
    @Test(timeout = 1000) fun rowGapBeyondPageEndDoesNotLoopOrEmitNegativeSlice() {
        val pages = ExportLayout.paginate(listOf(37, 80))
        assertEquals(117, pages.flatMap { it.slices }.sumOf { it.lineCount })
        assertTrue(pages.flatMap { it.slices }.all { it.lineCount > 0 })
    }
    @Test fun wrapPreservesChineseNewlinesAndSurrogatePairs() {
        val lines = ExportLayout.wrap("中文学习\nA😀B", 2f) { it.codePointCount(0, it.length).toFloat() }
        assertEquals(listOf("中文", "学习", "A😀", "B"), lines)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyRows() { ExportLayout.paginate(listOf(0)) }
}
