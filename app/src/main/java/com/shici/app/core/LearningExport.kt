package com.shici.app.core

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

enum class ExportMode { REVIEW, SELF_TEST }

enum class ExportSort { RECENT_QUERY, QUERY_COUNT }

/**
 * Export choices. [selectedKeys] `null` means "all rows surviving the other filters"; a non-null
 * set is an explicit allow-list of entry keys. [zone] is part of the frozen choice so the inclusive
 * local-day range cannot drift when the device zone changes between confirmation and writing.
 */
data class ExportOptions(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sort: ExportSort = ExportSort.RECENT_QUERY,
    val mode: ExportMode = ExportMode.REVIEW,
    val favoritesOnly: Boolean = false,
    val selectedKeys: Set<String>? = null,
    val zone: ZoneId = ZoneId.systemDefault()
) {
    init {
        require(from == null || to == null || !from.isAfter(to)) { "开始日期不能晚于结束日期" }
    }

    /** Deep-enough copy: later edits to the UI's mutable selection cannot leak into a frozen export. */
    fun frozen(): ExportOptions = copy(selectedKeys = selectedKeys?.toSet())
}

/**
 * Dictionary entries and learned words are immutable; copying the list freezes its membership.
 * [options] is frozen as well, including an immutable copy of [ExportOptions.selectedKeys].
 * The four-argument constructor is additive: existing `LearningExportSnapshot(words, search, date)`
 * callers keep the original review layout and membership exactly as given.
 */
class LearningExportSnapshot(
    words: List<LearnedWord>,
    val search: String,
    val date: LocalDate,
    options: ExportOptions = ExportOptions()
) {
    val words: List<LearnedWord> = words.toList()
    val options: ExportOptions = options.frozen()

    /**
     * Same frozen rows with display options replaced (for example switching mode after confirmation).
     * This does not re-run date/key filtering; if the filter choices themselves changed, rebuild via
     * [LearningExportSelection.snapshot] instead.
     */
    fun withOptions(options: ExportOptions): LearningExportSnapshot =
        LearningExportSnapshot(words, search, date, options)
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
    /**
     * Replaces the frozen content while the confirmation panel is still open, for example after the
     * user edits date/sort/mode/selection there. It keeps the same request identity, one-shot stage
     * and search guard as [freeze]; it is rejected once [choose] has started the SAf picker, so an
     * old or late callback can never swap what an already-chosen export will write.
     */
    fun refreeze(request: Request, snapshot: LearningExportSnapshot): Boolean {
        if (this.request != request || stage != Stage.CONFIRMING || snapshot.search != request.search) return false
        this.snapshot = snapshot
        return true
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

/**
 * Pure filtering/sorting for export, deliberately independent of SQLite and of the UI.
 *
 * Call this on the repository's already search-scoped rows and put the result into
 * [LearningExportSnapshot] before the user confirms. The PDF writer renders only the frozen list,
 * so rows learned by a later query can never be slipped into an export that was already confirmed.
 *
 * [favorites] is supplied by the caller that owns favorites (entry keys); this file does not depend
 * on any favorite field on [LearnedWord]. When [ExportOptions.favoritesOnly] is true and this set is
 * empty, the selection is empty rather than silently exporting everything.
 *
 * Date range: `firstAt` (first collection time) must be at or after `from` at the start of that day
 * in [ExportOptions.zone] and strictly before the start of the day after `to`, so both endpoints are
 * inclusive natural days. `null` bounds are unbounded. Reversed bounds are rejected by
 * [ExportOptions] before this function is reached.
 *
 * Sort: last-query time or query count, both descending, with the entry key as a deterministic
 * ascending tie-break. An explicit `selectedKeys` is intersected with the other filters and keeps
 * the sorted order instead of the caller's set iteration order.
 */
object LearningExportSelection {
    fun select(
        words: List<LearnedWord>,
        options: ExportOptions,
        favorites: Set<String> = emptySet()
    ): List<LearnedWord> {
        val fromMillis = options.from?.atStartOfDay(options.zone)?.toInstant()?.toEpochMilli()
        val untilMillis = options.to?.plusDays(1)?.atStartOfDay(options.zone)?.toInstant()?.toEpochMilli()
        val selected = options.selectedKeys
        return words.asSequence()
            .filter { fromMillis == null || it.firstAt >= fromMillis }
            .filter { untilMillis == null || it.firstAt < untilMillis }
            .filter { !options.favoritesOnly || it.entry.key in favorites }
            .filter { selected == null || it.entry.key in selected }
            .sortedWith(comparator(options.sort))
            .toList()
    }

    /** Descending primary order with a stable ascending key tie-break. */
    fun comparator(sort: ExportSort): Comparator<LearnedWord> {
        val byKey = compareBy<LearnedWord> { it.entry.key }
        return when (sort) {
            ExportSort.RECENT_QUERY -> compareByDescending<LearnedWord> { it.lastAt }.then(byKey)
            ExportSort.QUERY_COUNT -> compareByDescending<LearnedWord> { it.queryCount }.then(byKey)
        }
    }

    /** Convenience: filter/sort and freeze in one step, returning the snapshot the session expects. */
    fun snapshot(
        words: List<LearnedWord>,
        search: String,
        date: LocalDate,
        options: ExportOptions,
        favorites: Set<String> = emptySet()
    ): LearningExportSnapshot =
        LearningExportSnapshot(select(words, options, favorites), search, date, options)
}

/** Shared with PDF rendering so every saved word keeps its actual source/version and review notice. */
object LearningExportText {
    fun row(index: Int, word: LearnedWord): String =
        "${index + 1}. ${word.entry.headword}  ${word.entry.partOfSpeech}\n" +
            "原词形：${word.lastForm} · 查询 ${word.queryCount} 次 · ${if (word.mastered) "已掌握" else "待复习"}\n" +
            word.entry.meaning + (if (word.note.isBlank()) "" else "\n笔记：${word.note}") +
            // Route every row through the same source classification as the detail: the user-only
            // source keeps its local/offline statement, while raw/demo/unknown labels are unchanged.
            "\n${PersonalDictionary.provenance(word.source, word.version)}"

    /**
     * Self-test prompt: number, English headword and part of speech only. It intentionally omits the
     * meaning, note, last form, query history and provenance so no Chinese answer text leaks onto
     * the question pages.
     */
    fun question(index: Int, word: LearnedWord): String =
        "${index + 1}. ${word.entry.headword}  ${word.entry.partOfSpeech}"

    /** Answer-page entry: same number and order, with the complete row (nothing truncated). */
    fun answer(index: Int, word: LearnedWord): String = row(index, word)

    /**
     * Export-level source notice. The raw declaration is retained whenever a raw row is present;
     * otherwise an all-demo or all-user export names its known source honestly instead of calling it
     * an unknown historical source. A mix of the two known local/demo sources is also named as such.
     * Any genuinely unknown historical source keeps the explicit "not confirmed" wording.
     */
    fun notice(words: List<LearnedWord>): String {
        val allDemo = words.isNotEmpty() && words.all {
            it.source == DemoDictionary.SOURCE && it.version == DemoDictionary.VERSION
        }
        val allUser = words.isNotEmpty() && words.all {
            it.source == PersonalDictionary.SOURCE && it.version == PersonalDictionary.VERSION
        }
        val allKnown = words.isNotEmpty() && words.all { it.knownExportSource() }
        return when {
            words.any { it.source == RawKajwebDictionary.SOURCE } ->
                "原始词库未逐条核对；历史记录按各自来源标注"
            allDemo -> "自编演示数据，仅用于原型流程验证"
            allUser -> "用户补充（仅保存在本机）；未联网、未自动生成或核验"
            allKnown -> "用户补充仅保存在本机；自编演示数据仅用于原型流程验证"
            else -> "历史词源核对状态未确认；按各自来源版本标注"
        }
    }

    private fun LearnedWord.knownExportSource(): Boolean =
        (source == PersonalDictionary.SOURCE && version == PersonalDictionary.VERSION) ||
            (source == DemoDictionary.SOURCE && version == DemoDictionary.VERSION)
}
