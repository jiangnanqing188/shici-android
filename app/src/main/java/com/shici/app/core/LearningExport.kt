package com.shici.app.core

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/** Dictionary entries and learned words are immutable; copying the list freezes its membership. */
class LearningExportSnapshot(words: List<LearnedWord>, val search: String, val date: LocalDate) {
    val words: List<LearnedWord> = words.toList()
}

/** Main-thread export state; a request can load, confirm and consume exactly one snapshot. */
class LearningExportSession {
    data class Request(val id: Int, val search: String)
    private enum class Stage { IDLE, LOADING, CONFIRMING, PICKER, WRITING }
    private var stage = Stage.IDLE
    private var request: Request? = null
    private var snapshot: LearningExportSnapshot? = null
    val busy get() = stage != Stage.IDLE
    val fileRequestCode: Int? get() = if (stage == Stage.PICKER) request?.id else null

    companion object {
        private val nextRequestCode = AtomicInteger(1000)
        fun reserveRequestCode(code: Int) { nextRequestCode.updateAndGet { maxOf(it, code + 1) } }

        /**
         * Single source of document request codes. PDF export and learning backup/restore all draw
         * from here so no two ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT requests can collide.
         */
        fun allocateRequestCode(): Int = nextRequestCode.getAndIncrement()
    }

    fun begin(search: String): Request? {
        if (busy) return null
        return Request(allocateRequestCode(), search).also { request = it; stage = Stage.LOADING }
    }
    fun freeze(request: Request, snapshot: LearningExportSnapshot): Boolean {
        if (this.request != request || stage != Stage.LOADING || snapshot.search != request.search) return false
        this.snapshot = snapshot
        stage = Stage.CONFIRMING
        return true
    }
    fun choose(request: Request): LearningExportSnapshot? {
        if (this.request != request || stage != Stage.CONFIRMING) return null
        stage = Stage.PICKER
        return snapshot
    }
    fun takeResult(code: Int): LearningExportSnapshot? {
        if (request?.id != code || stage != Stage.PICKER) return null
        stage = Stage.WRITING
        return snapshot.also { snapshot = null }
    }
    fun cancel(code: Int): Boolean {
        if (request?.id != code) return false
        clear()
        return true
    }
    fun cancelPreparation(code: Int? = null): Boolean {
        if (code != null && request?.id != code) return false
        if (stage != Stage.LOADING && stage != Stage.CONFIRMING) return false
        clear()
        return true
    }
    fun clear() { request = null; snapshot = null; stage = Stage.IDLE }
}

/** Shared with PDF rendering so every saved word keeps its actual source/version and review notice. */
object LearningExportText {
    fun row(index: Int, word: LearnedWord): String =
        "${index + 1}. ${word.entry.headword}  ${word.entry.partOfSpeech}\n" +
            "原词形：${word.lastForm} · 查询 ${word.queryCount} 次 · ${if (word.mastered) "已掌握" else "待复习"}\n" +
            word.entry.meaning + (if (word.note.isBlank()) "" else "\n笔记：${word.note}") +
            "\n${RawKajwebDictionary.provenance(word.source, word.version)}"

    fun notice(words: List<LearnedWord>): String = when {
        words.any { it.source == RawKajwebDictionary.SOURCE } -> "原始词库未逐条核对；历史记录按各自来源标注"
        words.all { it.source == DemoDictionary.SOURCE && it.version == DemoDictionary.VERSION } -> "自编演示数据，仅用于原型流程验证"
        else -> "历史词源核对状态未确认；按各自来源版本标注"
    }
}
