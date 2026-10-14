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
    }

    fun begin(search: String): Request? {
        if (busy) return null
        return Request(nextRequestCode.getAndIncrement(), search).also { request = it; stage = Stage.LOADING }
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
