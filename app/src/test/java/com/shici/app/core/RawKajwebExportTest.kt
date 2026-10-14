package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RawKajwebExportTest {
    private fun learned(entry: DictionaryEntry) = LearnedWord(entry, entry.source, entry.version,
        entry.headword, 2, 100, 200, true, "已保存个人笔记")

    @Test fun pdfRowIncludesActualRawVersionAndUnreviewedNoticeWithoutLosingPersonalFields() {
        val word = learned(DictionaryEntry("quantum", "n", "夹具原义",
            RawKajwebDictionary.SOURCE, RawKajwebDictionary.VERSION))
        val content = LearningExportText.row(0, word)
        listOf("quantum", "夹具原义", "查询 2 次", "已掌握", "已保存个人笔记",
            RawKajwebDictionary.SOURCE, RawKajwebDictionary.VERSION, "未逐条核对", "不是官方大纲全覆盖").forEach {
            assertTrue(it, content.contains(it))
        }
        assertFalse(content.contains(DemoDictionary.VERSION))
    }
    @Test fun oldDemoExportRetainsOldSourceAndVersionEvenInMixedSnapshots() {
        val old = learned(DemoDictionary.entries.first())
        val raw = learned(old.entry.copy(source = RawKajwebDictionary.SOURCE, version = RawKajwebDictionary.VERSION))
        val content = LearningExportText.row(0, old)
        assertTrue(content.contains("自编演示数据"))
        assertTrue(content.contains(DemoDictionary.VERSION))
        assertFalse(content.contains(RawKajwebDictionary.VERSION))
        val frozen = LearningExportSnapshot(listOf(old, raw), "", LocalDate.of(2026, 10, 10))
        assertTrue(LearningExportText.notice(frozen.words).contains("未逐条核对"))
        assertTrue(LearningExportText.notice(listOf(old)).contains("自编演示数据"))
    }
}
