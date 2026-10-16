package com.shici.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.shici.app.ShiciApplication
import com.shici.app.BuildConfig
import com.shici.app.capture.*
import com.shici.app.core.*
import com.shici.app.data.DeleteOutcome
import com.shici.app.data.RestoreOutcome
import com.shici.app.data.RevokeOutcome
import com.shici.app.export.PdfExporter
import com.shici.app.export.LostPdfFileCleanup
import java.io.ByteArrayOutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : Activity() {
    companion object {
        private const val OVERLAY_PERMISSION = 10
        private const val CAPTURE_PERMISSION = 11
        private const val NOTIFICATION_PERMISSION = 12
    }
    private val app get() = application as ShiciApplication
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var nav: LinearLayout
    private var currentTab = 0
    private var words = emptyList<LearnedWord>()
    // Deletion generation of the snapshot the current [words] cache was read at; details opened from
    // this cache stay bound to this data instead of capturing a newer epoch at construction time.
    private var wordsCredential = 0L
    // Committed revision of the snapshot the current [words] cache was read at. Kept separate from the
    // deletion generation so a genuinely newer same-word snapshot (a change since this cache was read)
    // can be told apart from an old-snapshot rebuild that happens to keep an equal deletion credential.
    private var wordsVersion = 0L
    private var stats = LearningStats(0, 0, 0, 0, 0)
    private var search = ""
    private var selectedKey: String? = null
    private var consentGeneration = -1L
    private val refreshGate = LearningRefreshGate()
    private val exportSession = LearningExportSession()
    private val backupExportSession = LearningBackupExportSession()
    private val restoreSession = LearningRestoreSession()
    // Document request codes whose picker was open when the activity was recreated. Frozen backup
    // state is in memory only, so these results can no longer be applied: reserve the codes (never
    // reuse) and just tell the user to restart, without touching any file.
    private val interruptedDocumentRequests = mutableSetOf<Int>()
    private var backupExportButton: Button? = null
    private var restoreButton: Button? = null
    private var backupStatusView: TextView? = null
    private var restorePreviewDialog: AlertDialog? = null
    private var lostExportRequestCode = -1
    private var cleaningLostExport = false
    private var exportDialog: AlertDialog? = null
    private var exportButton: Button? = null
    private var inlineDetail: WordDetailBinding? = null
    private var dialogDetail: WordDetailBinding? = null
    // A save/revoke/delete in the open detail is in flight; every detail control is frozen, and a
    // rebuilt view or a late refresh must not re-enable a button for the duration.
    private var detailBusy = false
    private val dataListener: (Long) -> Unit = { version ->
        // A committed deletion may arrive while a manual "已收录" card is still open; invalidate it so
        // its old undo/correct buttons cannot act on a re-collected record. Other words are untouched.
        val guard = manualGuard
        if (guard != null && !guard.invalid && app.repository.deletions.isStale(guard.key, guard.credential)) {
            main.post { if (manualGuard === guard) invalidateManualCard(guard) }
        }
        main.post { refresh(version) }
    }
    private var floatingSwitch: Switch? = null
    private var floatingText: TextView? = null
    private var syncSwitch = false
    private var searchList: LinearLayout? = null
    private var detailPanel: LinearLayout? = null
    private val manualGate = QueryGate()
    // The visible manual "已收录" card, invalidated if its word is deleted while the card is still open.
    private class ManualGuard(
        val key: String, val credential: Long,
        val status: TextView, val undo: Button, val correct: Button, val retry: Button
    ) { @Volatile var invalid = false }
    @Volatile private var manualGuard: ManualGuard? = null
    private var dictionarySummary = "词库尚未校验；正在后台加载"
    private var dictionaryStatusView: TextView? = null
    private var activeDialog: AlertDialog? = null
    private var stopCaptureButton: Button? = null
    private val wide get() = resources.configuration.screenWidthDp >= 840
    private val contentWidthDp get() = resources.configuration.screenWidthDp - (if (wide) 188 + 64 else 40)
    private val splitContent get() = contentWidthDp >= 840 && resources.configuration.fontScale <= 1.3f
    private fun inlineAt(widthDp: Int) = widthDp >= 440 * resources.configuration.fontScale
    private val statusListener: (CaptureState) -> Unit = { state ->
        syncSwitch = true
        floatingSwitch?.isEnabled = state.phase != CapturePhase.STOPPING
        floatingSwitch?.isChecked = state.phase == CapturePhase.ACTIVE || state.phase == CapturePhase.PREPARING
        syncSwitch = false
        floatingText?.apply {
            text = state.message
            setTextColor(if (state.phase == CapturePhase.ERROR) Paper.error else Paper.secondary)
        }
        stopCaptureButton?.apply {
            isEnabled = state.phase != CapturePhase.CLOSED && state.phase != CapturePhase.STOPPING
            text = when (state.phase) {
                CapturePhase.CLOSED -> "悬浮查词已关闭"
                CapturePhase.STOPPING -> "正在释放捕获与浮层"
                else -> "关闭悬浮查词"
            }
            alpha = if (isEnabled) 1f else 0.6f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentTab = savedInstanceState?.getInt("tab") ?: 0
        search = savedInstanceState?.getString("search") ?: ""
        consentGeneration = savedInstanceState?.getLong("consent", -1L) ?: -1L
        lostExportRequestCode = savedInstanceState?.getInt("export_request", -1) ?: -1
        if (lostExportRequestCode >= 0) LearningExportSession.reserveRequestCode(lostExportRequestCode)
        savedInstanceState?.getIntArray("backup_requests")?.forEach { code ->
            if (code >= 0) { interruptedDocumentRequests += code; LearningExportSession.reserveRequestCode(code) }
        }
        buildShell()
    }
    override fun onStart() {
        super.onStart()
        refreshGate.start()
        CaptureStatus.observe(statusListener)
        app.repository.changes.observe(dataListener)
        refresh()
        loadDictionaryStatus()
    }
    override fun onStop() {
        manualGate.next()
        app.repository.changes.remove(dataListener)
        refreshGate.stop()
        if (exportSession.cancelPreparation()) { exportDialog?.dismiss(); updateExportButton() }
        // A backup/restore picker (AWAIT_IMPORT/AWAIT_SAFETY) stops this activity itself, so only the
        // non-picker preparations are cancelled here; a live picker and an in-flight apply are kept.
        if (backupExportSession.cancelPreparation()) updateBackupButtons()
        if (restoreSession.cancelPreparation()) {
            restorePreviewDialog?.dismiss(); restorePreviewDialog = null; updateBackupButtons()
        }
        CaptureStatus.remove(statusListener)
        super.onStop()
    }
    override fun onDestroy() {
        refreshGate.stop(); exportSession.clear(); manualGate.next()
        backupExportSession.clear(); restoreSession.clear()
        restorePreviewDialog?.dismiss(); restorePreviewDialog = null
        activeDialog?.dismiss(); activeDialog = null
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", currentTab); outState.putString("search", search)
        outState.putLong("consent", consentGeneration)
        outState.putInt("export_request", exportSession.fileRequestCode ?: lostExportRequestCode)
        val pendingBackupRequests = ArrayList<Int>()
        backupExportSession.fileRequestCode?.let { pendingBackupRequests += it }
        restoreSession.importRequestCode?.let { pendingBackupRequests += it }
        restoreSession.safetyRequestCode?.let { pendingBackupRequests += it }
        pendingBackupRequests += interruptedDocumentRequests
        outState.putIntArray("backup_requests", pendingBackupRequests.toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun buildShell() {
        root = if (wide) row() else column()
        root.setBackgroundColor(Paper.background)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        body = column().apply { setPadding(dp(if (wide) 32 else 20), dp(24), dp(if (wide) 32 else 20), dp(12)) }
        nav = if (wide) column() else row()
        nav.setPadding(dp(12), dp(12), dp(12), dp(12))
        if (wide) {
            nav.addView(text("拾词", 32f, Paper.green, true)); nav.addView(text("专注阅读\n随手拾词", 16f, Paper.secondary)); nav.space(40)
            root.addView(ScrollView(this).apply { addView(nav) }, LinearLayout.LayoutParams(dp(188), -1))
            root.addView(body, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        }
        listOf("首页", "生词本", "统计", "设置").forEachIndexed { index, label ->
            nav.addView(button(label) { currentTab = index; render() },
                if (wide) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                else LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        setContentView(root)
        root.requestApplyInsets()
        render()
    }

    private fun refresh(version: Long = app.repository.changes.version, force: Boolean = false) {
        val request = refreshGate.request(version, force) ?: return
        app.worker.execute {
            val result = runCatching { app.repository.snapshot() }
            main.post {
                if (isDestroyed || !refreshGate.isCurrent(request)) return@post
                result.onSuccess { snapshot ->
                    if (!refreshGate.accept(request, snapshot.version, app.repository.changes.version)) {
                        refresh(); return@onSuccess
                    }
                    words = snapshot.words; stats = snapshot.stats
                    wordsCredential = snapshot.deletionCredential; wordsVersion = snapshot.version
                    dialogDetail?.update(words.firstOrNull { it.entry.key == dialogDetail?.key })
                    when (currentTab) {
                        1 -> { renderWords(preserveDetail = true); updateExportButton() }
                        3 -> Unit // Keep an in-progress manual/correction input intact.
                        else -> render()
                    }
                }.onFailure { toast("读取失败：${it.message}") }
            }
        }
    }

    private fun loadDictionaryStatus() {
        app.worker.execute {
            val result = app.dictionary
            main.post {
                if (isDestroyed) return@post
                dictionarySummary = result.fold(
                    { "已校验 ${it.entryCount} 个原始词头 · ${it.aliasCount} 个词形别名（仅格式与完整性校验）" },
                    { "词库不可用：${it.message}；不会生成释义或新增查询记录" })
                dictionaryStatusView?.text = dictionarySummary
            }
        }
    }

    private fun dictionaryStatus() = text(dictionarySummary, 16f, Paper.secondary)
        .also { dictionaryStatusView = it }

    private fun render() {
        floatingSwitch = null; floatingText = null; stopCaptureButton = null; searchList = null; detailPanel = null
        exportButton = null; inlineDetail = null; dictionaryStatusView = null
        backupExportButton = null; restoreButton = null; backupStatusView = null
        body.removeAllViews()
        val navOffset = if (wide) 3 else 0
        repeat(4) { index ->
            val button = nav.getChildAt(index + navOffset) as Button
            button.background = shape(if (index == currentTab) Paper.lightGreen else Paper.background, 8)
            button.setTypeface(button.typeface, if (index == currentTab) Typeface.BOLD else Typeface.NORMAL)
            button.isSelected = index == currentTab
        }
        when (currentTab) { 0 -> home(); 1 -> vocabulary(); 2 -> statistics(); 3 -> settings() }
    }
    private fun scrolled(): LinearLayout {
        val content = column()
        body.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, -1))
        return content
    }
    private fun panel(): LinearLayout = column().apply {
        setPadding(dp(24), dp(24), dp(24), dp(24)); background = shape(Paper.surface, border = true)
    }
    private fun home() {
        val content = scrolled()
        content.addView(text("专注阅读，随手拾词", 16f, Paper.secondary))
        content.addView(text("让生词停在这里", if (wide) 36f else 28f, Paper.ink, true)); content.space(24)
        val session = panel()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("悬浮查词", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        floatingSwitch = Switch(this).apply {
            contentDescription = "开启或关闭悬浮查词"; minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (!syncSwitch) {
                    if (checked) beginCapture() else stopCapture()
                }
            }
        }
        heading.addView(floatingSwitch)
        session.addView(heading)
        floatingText = text(CaptureStatus.state.message, 16f, Paper.secondary)
        session.addView(floatingText)
        session.addView(text("开启后，可在题目页面用荧光笔划选查词", 16f, Paper.secondary))
        session.divider()
        val numbers = row()
        addMetric(numbers, "今日查询", stats.todayQueries); addMetric(numbers, "今日新增", stats.todayNew)
        session.addView(numbers)
        content.addView(session); content.space(24)
        content.addView(text("最近查到的单词", 22f, bold = true)); content.space(8)
        if (words.isEmpty()) {
            val empty = panel()
            empty.addView(text("查到的第一个单词，会留在这里", 18f))
            empty.addView(text("开启悬浮查词，或在设置中手动输入单词。", 16f, Paper.secondary))
            content.addView(empty)
        } else words.take(5).forEach { word -> content.addView(wordRow(word) { showWordDialog(word) }); content.divider() }
        content.space(24)
        val demo = column().apply {
            setPadding(dp(20), dp(16), dp(20), dp(16)); background = shape(Paper.lightGreen)
            addView(text("原始离线词库 · 未逐条核对", 18f, Paper.green, true))
            addView(dictionaryStatus())
            addView(text(RawKajwebDictionary.provenance(RawKajwebDictionary.SOURCE, RawKajwebDictionary.VERSION), 16f, Paper.secondary))
        }
        content.addView(demo); content.space()
        content.addView(text("打开悬浮查词 → 划过文字 → 完成", 16f, Paper.secondary))
        statusListener(CaptureStatus.state)
    }
    private fun addMetric(row: LinearLayout, label: String, value: Int) {
        val content = column().apply { addView(text(label, 16f, Paper.secondary)); addView(text(value.toString(), 30f, bold = true)) }
        row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun vocabulary() {
        val content = if (splitContent) body else scrolled()
        val inline = inlineAt(contentWidthDp)
        val header = (if (inline) row() else column()).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("生词本", 28f, bold = true),
            if (inline) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        exportButton = button("导出 PDF", true) { export() }
        updateExportButton()
        header.addView(exportButton, LinearLayout.LayoutParams(if (inline) -2 else -1, -2))
        content.addView(header); content.space(16)
        val input = inputField("搜索单词、释义或笔记", true).apply { setText(search) }
        content.addView(input); content.space(16)
        searchList = column()
        if (splitContent) {
            val container = row()
            container.addView(ScrollView(this).apply { addView(searchList) }, LinearLayout.LayoutParams(0, -1, 0.44f))
            detailPanel = column().apply { setPadding(dp(24), 0, 0, 0) }
            container.addView(ScrollView(this).apply { addView(detailPanel) }, LinearLayout.LayoutParams(0, -1, 0.56f))
            content.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        } else content.addView(searchList)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search = s.toString(); updateExportButton()
                renderWords()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderWords()
    }
    private fun filteredWords() = words.filter { word -> search.isBlank() ||
        listOf(word.entry.headword, word.entry.meaning, word.lastForm, word.note).any { it.contains(search.trim(), true) } }
    private fun updateExportButton() {
        // Eligibility is checked against a fresh repository read, never against the UI cache.
        exportButton?.isEnabled = !exportSession.busy && lostExportRequestCode < 0 && !cleaningLostExport
    }
    private fun renderWords(preserveDetail: Boolean = false) {
        val list = filteredWords()
        searchList?.removeAllViews()
        searchList?.addView(text("${list.size} 个词 · 最近查询优先", 14f, Paper.secondary))
        if (list.isEmpty()) searchList?.addView(text(if (words.isEmpty()) "还没有生词。成功查询后会自动收录。" else "没有匹配的生词。", 18f))
        list.forEach { word ->
            searchList?.addView(wordRow(word) {
                selectedKey = word.entry.key
                if (splitContent) renderWords() else showWordDialog(word)
            }.apply {
                if (splitContent && selectedKey == word.entry.key) background = shape(Paper.lightGreen, 8)
            })
            searchList?.divider()
        }
        detailPanel?.let { detail ->
            if (preserveDetail && inlineDetail != null) {
                inlineDetail?.update(words.firstOrNull { it.entry.key == inlineDetail?.key })
                return@let
            }
            inlineDetail = null
            detail.removeAllViews()
            val selected = list.firstOrNull { it.entry.key == selectedKey } ?: list.firstOrNull()
            if (selected == null) detail.addView(text("选择一个生词，查看释义和记录", 18f, Paper.secondary))
            else {
                selectedKey = selected.entry.key
                detail.addView(text("生词详情", 18f, bold = true)); detail.space(12)
                val card = panel(); detail.addView(card); inlineDetail = wordDetail(card, selected, wordsCredential, wordsVersion)
            }
        }
    }
    private fun wordRow(word: LearnedWord, action: () -> Unit): View = column().apply {
        setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(72)
        addView(text(word.entry.headword, 23f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        addView(text("${word.entry.partOfSpeech} ${word.entry.meaning}", 16f, Paper.secondary))
        addView(text("查询 ${word.queryCount} 次 · ${if (word.mastered) "已掌握" else "待复习"}", 14f, Paper.secondary))
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    /**
     * Holds one detail view's controls and its own operation state. [deletionCredential] and
     * [snapshotVersion] come from the snapshot this detail was opened from and are kept until the
     * binding/confirm dialog ends, so a save/undo/delete never edits a record that was re-collected after
     * that snapshot, and a settled deletion can tell this detail apart from an old-snapshot rebuild that
     * keeps the same deletion credential. While [busy] or [confirming] the controls stay disabled even if
     * a background refresh calls [update].
     */
    private class WordDetailBinding(
        val key: String, val deletionCredential: Long, val snapshotVersion: Long,
        private val save: Button, private val undo: Button, private val delete: Button,
        private val mastery: Switch, private val note: EditText,
        private val form: TextView, private val count: TextView, private val dates: TextView,
        private val definition: TextView, private val source: TextView, private val sourceBadge: TextView,
        private val mutationAllowed: () -> Boolean,
        private val stale: () -> Boolean,
        private val onClose: (() -> Unit)?,
        private var savedMastered: Boolean, private var savedNote: String
    ) {
        private var busy = false
        private var confirming = false
        private var invalidated = false
        private var wordPresent = true

        fun update(word: LearnedWord?) {
            // A binding whose own key/original credential is stale (the word was deleted after this
            // detail's snapshot) or that was explicitly invalidated must never be repopulated as an
            // operable record, even when a re-collected word with the same key now exists.
            if (invalidated || stale()) {
                wordPresent = false
                form.text = "该词已删除或已重新收录，此详情已失效"
                dates.text = "请重新打开详情查看当前记录。"
                applyEnabled()
                return
            }
            wordPresent = word != null
            form.text = if (word == null) "原词形：暂无有效查询" else "原词形：${word.lastForm}"
            count.text = "${word?.queryCount ?: 0} 次"
            if (word == null) {
                dates.text = "当前已无有效查询；已保存的笔记仍保留。"
            } else {
                definition.text = "${word.entry.partOfSpeech} ${word.entry.meaning}"
                source.text = RawKajwebDictionary.provenance(word.source, word.version)
                sourceBadge.text = RawKajwebDictionary.label(word.source, word.version)
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                fun date(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(formatter)
                dates.text = "首次查询：${date(word.firstAt)}\n最近查询：${date(word.lastAt)}"
                if (note.text.toString() == savedNote && savedNote != word.note) note.setText(word.note)
                if (mastery.isChecked == savedMastered) mastery.isChecked = word.mastered
                savedNote = word.note; savedMastered = word.mastered
            }
            applyEnabled()
        }

        fun isBusy() = busy || confirming
        fun isStale() = stale()
        fun dismiss() { onClose?.invoke() }
        fun beginConfirm() { confirming = true; applyEnabled() }
        fun cancelConfirm() { confirming = false; applyEnabled() }
        fun beginBusy() { busy = true; applyEnabled() }
        fun endBusy() { busy = false; confirming = false; applyEnabled() }
        // Re-applies the control state after the shared mutation flag changed, still honoring this
        // binding's own busy/confirming/invalidated state (so a frozen or invalidated card stays frozen).
        fun refreshEnabled() = applyEnabled()
        fun invalidate() {
            invalidated = true
            busy = true; confirming = true
            save.isEnabled = false; undo.isEnabled = false; delete.isEnabled = false
            mastery.isEnabled = false; note.isEnabled = false
        }
        fun masteredValue() = mastery.isChecked
        fun noteValue() = note.text.toString()

        private fun applyEnabled() {
            // The binding's own key/original credential participates too: a stale or invalidated
            // detail can never be re-enabled by endBusy, update or a shared-busy release.
            val active = !invalidated && !stale() && !busy && !confirming && mutationAllowed()
            save.isEnabled = active
            undo.isEnabled = active && wordPresent
            delete.isEnabled = active
            mastery.isEnabled = active
            note.isEnabled = active
        }
    }
    /**
     * Re-applies the currently open details' control state once the shared [detailBusy] flag is
     * cleared. A detail opened or refreshed while another word's operation was in flight is disabled
     * by [mutationAllowed] together with that flag; a path that publishes no change notification
     * (failure, STALE/NONE/NOT_FOUND, unchanged save) has nothing else to re-enable it. Each binding
     * still keeps itself disabled while busy, confirming or invalidated.
     */
    private fun releaseDetailBusy() {
        inlineDetail?.refreshEnabled()
        dialogDetail?.refreshEnabled()
    }
    private fun wordDetail(content: LinearLayout, word: LearnedWord, deletionCredential: Long, snapshotVersion: Long, close: (() -> Unit)? = null): WordDetailBinding {
        val detailWidth = if (close != null) minOf(resources.configuration.screenWidthDp - 32, 640)
            else (contentWidthDp * 0.56f).toInt() - 24
        content.addView(text(word.entry.headword, 30f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        val definition = text("${word.entry.partOfSpeech} ${word.entry.meaning}", 18f)
        content.addView(definition); content.space(8)
        val sourceBadge = badge(RawKajwebDictionary.label(word.source, word.version))
        content.addView(sourceBadge, LinearLayout.LayoutParams(-2, -2)); content.divider()
        val form = text("原词形：${word.lastForm}", 16f, Paper.secondary)
        content.addView(form)
        val source = text(RawKajwebDictionary.provenance(word.source, word.version), 16f, Paper.secondary)
        content.addView(source); content.space(16)
        val count = text("${word.queryCount} 次", 28f, bold = true)
        val queries = column().apply {
            addView(text("查询次数", 16f, Paper.secondary)); addView(count)
        }
        val mastered = Switch(this).apply {
            text = if (word.mastered) "已掌握" else "待复习"; textSize = 16f; setTextColor(Paper.ink)
            isChecked = word.mastered; minHeight = dp(48); contentDescription = "已掌握开关，保存后生效"
            setOnCheckedChangeListener { _, checked -> text = if (checked) "已掌握" else "待复习" }
        }
        val mastery = column().apply {
            addView(text("掌握状态", 16f, Paper.secondary)); addView(mastered)
            addView(text("保存后生效；已掌握的词不计入待复习。", 16f, Paper.secondary))
        }
        content.addView(pair(queries, mastery, inlineAt(detailWidth - 48)))
        content.space(12)
        val dates = text("首次查询：${date(word.firstAt)}\n最近查询：${date(word.lastAt)}", 16f, Paper.secondary)
        content.addView(dates)
        content.divider()
        content.addView(text("我的记忆笔记", 18f, bold = true)); content.space(8)
        val note = inputField("添加自己的记忆方法或做题提示…").apply {
            setText(word.note); minLines = 3; gravity = Gravity.TOP
        }
        content.addView(note); content.space(16)
        val save = button("保存笔记与状态", true) {}
        val undo = button("撤销最近一次查询") {}
        content.addView(pair(save, undo, inlineAt(detailWidth - 48), 8)); content.space(8)
        content.addView(text("撤销只影响最近一次成功查询；之前的查询与已保存的笔记保留。", 16f, Paper.secondary))
        content.space(8)
        val delete = button("删除生词") {}
        delete.setTextColor(Paper.error)
        content.addView(delete)
        content.space(8)
        content.addView(text("删除会清除该词的全部查询历史（含已撤销）、笔记与掌握状态，无法恢复；原始词库和其他生词不受影响。与“撤销最近一次查询”不同。", 16f, Paper.secondary))
        val binding = WordDetailBinding(word.entry.key, deletionCredential, snapshotVersion, save, undo, delete, mastered, note,
            form, count, dates, definition, source, sourceBadge, { !detailBusy },
            { app.repository.deletions.isStale(word.entry.key, deletionCredential) }, close,
            word.mastered, word.note)
        save.setOnClickListener { saveDetail(binding, word, close) }
        undo.setOnClickListener { revokeLast(binding, word, close) }
        delete.setOnClickListener { confirmDelete(binding, word) }
        binding.update(word)
        return binding
    }
    private fun saveDetail(binding: WordDetailBinding, word: LearnedWord, close: (() -> Unit)?) {
        if (binding.isBusy() || detailBusy) return
        val marked = binding.masteredValue()
        val noteText = binding.noteValue()
        detailBusy = true
        binding.beginBusy()
        app.worker.execute {
            val result = runCatching {
                app.repository.updateWord(word.entry.key, marked, noteText, binding.deletionCredential)
            }
            main.post {
                if (isDestroyed) return@post
                detailBusy = false
                binding.endBusy()
                releaseDetailBusy()
                val error = result.exceptionOrNull()
                // Re-check the original deletion credential: a deletion may have committed between the
                // worker result and this callback, so a late true must not be shown as saved nor re-enable old actions.
                val stale = app.repository.deletions.isStale(word.entry.key, binding.deletionCredential)
                when {
                    // Deletion wins over a late failure: never re-enable old actions on an invalidated word.
                    stale -> { binding.invalidate(); toast("未保存：该词已删除") }
                    error != null -> toast("保存失败：${error.message}")
                    result.getOrNull() == true -> { toast("已保存"); close?.invoke() }
                    else -> { binding.invalidate(); toast("未保存：该词已删除") }
                }
            }
        }
    }
    private fun revokeLast(binding: WordDetailBinding, word: LearnedWord, close: (() -> Unit)?) {
        if (binding.isBusy() || detailBusy) return
        binding.beginConfirm()
        var proceed = false
        val dialog = AlertDialog.Builder(this).setTitle("撤销最近一次查询？")
            .setMessage("只撤销 ${word.entry.headword} 最近的一次成功事件，之前的查询和笔记保留。")
            .setPositiveButton("撤销") { _, _ -> proceed = true; performRevoke(binding, word, close) }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { if (!proceed) binding.cancelConfirm() }
        dialog.show()
    }
    private fun performRevoke(binding: WordDetailBinding, word: LearnedWord, close: (() -> Unit)?) {
        if (detailBusy) { binding.cancelConfirm(); return }
        detailBusy = true
        binding.beginBusy()
        app.worker.execute {
            // Resolve-and-revoke happens in the worker only after the stale check against the detail's
            // original credential, so a stale confirm can never revoke an event of a re-collected word.
            val result = runCatching { app.repository.revokeLatest(word.entry.key, binding.deletionCredential) }
            main.post {
                if (isDestroyed) return@post
                detailBusy = false
                binding.endBusy()
                releaseDetailBusy()
                val outcome = result.getOrNull()
                // Deletion wins over a late result: a REVOKED that raced a committed deletion must not
                // report success nor re-enable actions on an invalidated card.
                if (app.repository.deletions.isStale(word.entry.key, binding.deletionCredential)) {
                    binding.invalidate()
                    toast("撤销失败：该词已删除，未撤销任何记录")
                } else when {
                    result.isFailure -> toast("撤销失败：${result.exceptionOrNull()?.message}")
                    outcome == RevokeOutcome.REVOKED -> { close?.invoke(); toast("最近一次查询已撤销") }
                    outcome == RevokeOutcome.STALE -> { binding.invalidate(); toast("撤销失败：该词已删除，未撤销任何记录") }
                    else -> toast("没有可撤销的查询")
                }
            }
        }
    }
    private fun confirmDelete(binding: WordDetailBinding, word: LearnedWord) {
        if (binding.isBusy() || detailBusy) return
        binding.beginConfirm()
        var proceed = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除生词「${word.entry.headword}」？")
            .setMessage("将永久删除「${word.entry.headword}」的全部查询历史（含已撤销）、笔记和掌握状态，删除后无法恢复；原始词库、其他生词和已导出的 PDF 不受影响。\n\n这与“撤销最近一次查询”不同：撤销只移除最近一次查询，保留其余历史和笔记。")
            .setPositiveButton("删除") { _, _ -> proceed = true; performDelete(binding, word) }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnDismissListener { if (!proceed) binding.cancelConfirm() }
        dialog.show()
    }
    private fun performDelete(binding: WordDetailBinding, word: LearnedWord) {
        if (detailBusy) { binding.cancelConfirm(); return }
        detailBusy = true
        binding.beginBusy()
        app.worker.execute {
            val result = runCatching { app.repository.deleteWord(word.entry.key, binding.deletionCredential) }
            main.post {
                if (isDestroyed) return@post
                detailBusy = false
                val error = result.exceptionOrNull()
                val outcome = result.getOrNull()
                when {
                    error != null -> { binding.endBusy(); toast("删除失败：${error.message}") }
                    else -> {
                        // Every settled result (STALE/DELETED/NOT_FOUND) leaves the acting binding and
                        // any current detail for this exact word bound to an old generation: freeze it
                        // so it can never be re-enabled, and clear the current ones still showing the
                        // old cache. A genuinely newer detail for the same word (fresh snapshot or a new
                        // active query) is preserved; other words' selections and inputs are untouched.
                        binding.invalidate()
                        val cleared = clearStaleDetail(word.entry.key, binding)
                        if (cleared && selectedKey == word.entry.key) selectedKey = null
                        toast(when (outcome) {
                            DeleteOutcome.STALE -> "未删除：该词已删除或已重新收录，请刷新后重新打开详情再操作"
                            DeleteOutcome.DELETED -> "已删除「${word.entry.headword}」及全部记录"
                            else -> "「${word.entry.headword}」已不在生词本"
                        })
                    }
                }
                // The acting binding settled above; re-enable whichever details are now current so a
                // detail rebuilt/opened during this operation is no longer stuck on the shared busy flag.
                releaseDetailBusy()
            }
        }
    }
    /**
     * After a deletion settles, freeze and clear every currently open detail that still belongs to the
     * settled word's old generation: the acting binding itself, or any same-key detail whose original data
     * provenance is not newer than the acting binding's. Provenance is the pair of a detail's original
     * snapshot revision and its original deletion credential, both taken when the binding was built and
     * never re-read at render/rebuild time. A settled NOT_FOUND publishes no new revision and no deletion
     * epoch, so a detail rebuilt from the same snapshot keeps the acting binding's provenance and must
     * still be frozen rather than re-enabled on release; a stale same-key detail is always cleared. A
     * genuinely newer detail for the same word (a fresh snapshot revision after a committed change, or a
     * newer deletion credential after a committed deletion) keeps its inputs, and other words are never
     * touched. Returns whether a current detail was cleared.
     */
    private fun clearStaleDetail(key: String, acting: WordDetailBinding): Boolean {
        var cleared = false
        inlineDetail?.let { current ->
            if (current === acting ||
                (current.key == key && (current.isStale() ||
                    (current.snapshotVersion <= acting.snapshotVersion &&
                        current.deletionCredential <= acting.deletionCredential)))) {
                current.invalidate(); inlineDetail = null; cleared = true
            }
        }
        dialogDetail?.let { current ->
            if (current === acting ||
                (current.key == key && (current.isStale() ||
                    (current.snapshotVersion <= acting.snapshotVersion &&
                        current.deletionCredential <= acting.deletionCredential)))) {
                current.invalidate(); dialogDetail = null; current.dismiss(); cleared = true
            }
        }
        return cleared
    }
    private fun showWordDialog(word: LearnedWord) {
        val content = panel()
        val dialog = paperDialog(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("生词详情", 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("关闭") { dialog.dismiss() }.apply { contentDescription = "关闭生词详情" })
        content.addView(header); content.divider()
        val binding = wordDetail(content, word, wordsCredential, wordsVersion) { dialog.dismiss() }
        showPaperDialog(dialog, 640) { if (dialogDetail === binding) dialogDetail = null }
        dialogDetail = binding
    }
    private fun paperDialog(content: LinearLayout): AlertDialog = AlertDialog.Builder(this).create().apply {
        setView(ScrollView(this@MainActivity).apply { addView(content) }, 0, 0, 0, 0)
    }
    private fun showPaperDialog(dialog: AlertDialog, maxWidth: Int, onDismiss: () -> Unit = {}) {
        activeDialog?.dismiss(); activeDialog = dialog; dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(shape(Paper.surface, border = true))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(dp(minOf(resources.configuration.screenWidthDp - 32, maxWidth)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null; onDismiss() }
    }
    private fun statisticMetrics(values: List<Pair<String, Int>>, inline: Boolean): LinearLayout {
        val metrics = if (inline) row() else column()
        values.forEachIndexed { index, (label, value) ->
            if (index > 0 && !inline) metrics.divider()
            val metric = column().apply {
                addView(text(label, 16f, Paper.secondary)); addView(text(value.toString(), 30f, bold = true))
            }
            metrics.addView(metric, if (inline) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
        }
        return metrics
    }
    private fun statistics() {
        val content = scrolled()
        val inline = inlineAt(contentWidthDp)
        val header = if (inline) row().apply { gravity = Gravity.CENTER_VERTICAL } else column()
        val heading = column().apply {
            addView(text("每一次查词，都算数", if (wide) 30f else 28f, bold = true))
            addView(text("只记录你的真实查询。", 16f, Paper.secondary))
        }
        header.addView(heading, if (inline) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        header.addView(button("刷新统计", true) { refresh(force = true) }, LinearLayout.LayoutParams(if (inline) -2 else -1, -2))
        content.addView(header); content.space(24)
        val queries = panel().apply {
            addView(statisticMetrics(listOf("今日查询" to stats.todayQueries, "今日新增" to stats.todayNew,
                "累计查询" to stats.totalQueries), inlineAt(contentWidthDp - 48)))
            divider(); addView(text("按设备当前时区统计；仅计入未撤销的成功查询。", 16f, Paper.secondary))
        }
        content.addView(queries); content.space(16)
        val sectionWidth = if (splitContent) (contentWidthDp - 24) / 2 else contentWidthDp
        val mastery = panel().apply {
            addView(text("复习进度", 20f, bold = true)); space(12)
            addView(statisticMetrics(listOf("待复习单词" to stats.learning, "已掌握单词" to stats.mastered), inlineAt(sectionWidth - 48)))
            divider(); addView(text("待复习：尚未标记为已掌握的单词。\n已掌握：你在生词详情中标记并保存的单词。", 16f, Paper.secondary))
        }
        val frequent = panel().apply {
            addView(text("最常查询的词", 20f, bold = true))
            addView(text("按真实查询次数排序，不代表考试频率。", 16f, Paper.secondary)); space(16)
            if (words.isEmpty()) {
                addView(text("暂无查询记录", 18f, bold = true).apply { gravity = Gravity.CENTER })
                addView(text("开始阅读并查词后，这里会显示你常查的单词。", 16f, Paper.secondary).apply { gravity = Gravity.CENTER })
                space(16)
            } else words.sortedByDescending { it.queryCount }.take(10).forEachIndexed { index, word ->
                if (index > 0) divider()
                addView(wordRow(word) { showWordDialog(word) })
            }
        }
        content.addView(pair(mastery, frequent, splitContent, 24))
    }
    private fun settings() {
        val content = scrolled()
        content.addView(text("设置", 28f, bold = true)); content.space(24)
        val library = column()
        val source = panel().apply {
            addView(text("词库来源", 20f, bold = true)); space(12)
            val identity = column().apply {
                setPadding(dp(16), dp(12), dp(16), dp(12)); background = shape(Paper.lightGreen, 8)
                addView(text("原始离线词库 · 未逐条核对", 18f, Paper.green, true))
                addView(dictionaryStatus())
            }
            addView(identity); space(12)
            addView(text(RawKajwebDictionary.provenance(RawKajwebDictionary.SOURCE, RawKajwebDictionary.VERSION), 16f, Paper.secondary))
            addView(text("考研 / 四级 / 六级 / 专四 / 专八五本原始词书合并；未应用核验或候选修订。仅查原词头，不猜词形，不回退演示词库。", 16f, Paper.warning))
        }
        library.addView(source); library.space(16)
        val demo = panel().apply {
            addView(text("手动输入查词", 20f, bold = true))
            addView(text("无需悬浮窗或屏幕捕获权限；成功查询会形成真实的本地记录。", 16f, Paper.secondary)); space(12)
            val input = inputField("例如 evidence、quantum", true)
            val query = button("查询原始词库", true) { manualLookup(input.text.toString()) }
            val sectionWidth = if (splitContent) (contentWidthDp - 24) / 2 else contentWidthDp
            addView(pair(input, query, inlineAt(sectionWidth - 48))); space(12)
            addView(text("未知词或未收录词形不生成释义、不新增记录；旧演示记录仍按旧来源展示。", 16f, Paper.secondary))
        }
        library.addView(demo); library.space(16)
        val backup = panel().apply {
            addView(text("备份与恢复", 20f, bold = true)); space(8)
            addView(text("把本机学习数据（全部词条与事件，含已撤销历史、笔记与掌握状态）导出为一个 .shicibackup 文件，或用备份完整覆盖当前数据。文件只通过系统文档选择器创建或读取，不联网、不上传。", 16f, Paper.secondary)); space(12)
            backupExportButton = button("导出学习数据备份", true) { exportLearningBackup() }
            addView(backupExportButton); space(8)
            restoreButton = button("从备份完整恢复") { startRestore() }
            addView(restoreButton); space(12)
            addView(text("恢复是完整覆盖、不合并、不猜测冲突：备份里没有的词也会被移除。恢复前必须先把当前数据另存为一个安全备份文件，写回校验通过且数据未变化时才会覆盖。空备份可在确认后清空学习数据。备份是本地明文个人数据，未加密、不防恶意篡改，请妥善保存。", 16f, Paper.warning)); space(8)
            backupStatusView = text("", 16f, Paper.secondary); addView(backupStatusView)
            updateBackupButtons()
        }
        library.addView(backup)
        val boundaries = column()
        val privacy = panel().apply {
            addView(text("权限与隐私", 20f, bold = true)); space(8)
            addView(text("悬浮窗权限", 18f, bold = true))
            val overlayAllowed = Settings.canDrawOverlays(this@MainActivity)
            addView(badge(if (overlayAllowed) "已允许" else "未允许").apply {
                if (!overlayAllowed) { setTextColor(Paper.secondary); background = shape(Paper.background, 8) }
            }, LinearLayout.LayoutParams(-2, -2))
            addView(text("仅用于在其他应用上显示查词浮层。这是权限状态展示，不是授权开关。", 16f, Paper.secondary)); divider()
            addView(text("查词会话", 18f, bold = true))
            floatingText = text(CaptureStatus.state.message, 16f, Paper.secondary); addView(floatingText)
            addView(text("只在首页主动开启时申请悬浮窗、屏幕捕获及通知权限。全屏捕获可看到整个屏幕；荧光笔划选时的静态画面不会暂停题目计时。", 16f, Paper.secondary)); space(12)
            stopCaptureButton = button("关闭悬浮查词") { stopCapture(); toast("已请求停止查词") }
            addView(stopCaptureButton); space(8)
            addView(text("关闭会释放捕获和浮层，不会撤销系统悬浮窗权限。若需撤销，请在系统设置中操作。", 16f, Paper.secondary)); divider()
            addView(text("数据保存在本机", 18f, bold = true))
            addView(text("截图仅临时保存在内存；荧光笔划选可累积多笔，点“完成”后在本机识别；多个词可选一个查词，尚无翻译。不写截图文件、不上传。查询记录、释义快照、个人笔记与掌握状态保存在本机数据库。PDF 写入你选择的位置。", 16f, Paper.secondary))
        }
        boundaries.addView(privacy); boundaries.space(16)
        val limits = panel().apply {
            addView(text("原型限制", 20f, bold = true)); space(8)
            addView(text("当前 PDF 仅支持搜索结果的 A4 记忆版。正式词典、发音、例句、自测版与日期筛选尚未实现；本地备份仅为完整覆盖恢复，不提供合并或云同步。", 16f, Paper.secondary))
            divider(); addView(text("尚未真机验证速度、断网初装 OCR、海文兼容性与长时间功耗。", 16f, Paper.warning))
        }
        boundaries.addView(limits)
        content.addView(pair(library, boundaries, splitContent, 24))
        statusListener(CaptureStatus.state)
    }

    private fun beginCapture() {
        if (CaptureStatus.state.phase == CapturePhase.ACTIVE || CaptureStatus.state.phase == CapturePhase.PREPARING ||
            CaptureStatus.state.phase == CapturePhase.STOPPING) { statusListener(CaptureStatus.state); return }
        consentGeneration = CaptureStatus.prepare()
        AlertDialog.Builder(this).setTitle("开启全屏悬浮查词")
            .setMessage("系统会授予本工具整个屏幕的捕获权限。截图仅临时保存在内存，在本机识别，不上传；荧光笔划选时显示的静态画面不会暂停题目或视频。关闭开关或通知中的停止会释放捕获和浮层。")
            .setPositiveButton("继续") { _, _ ->
                if (consentIsCurrent()) {
                    if (!Settings.canDrawOverlays(this)) {
                        runCatching { startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), OVERLAY_PERMISSION) }
                            .onFailure { permissionError("无法打开悬浮窗授权：${it.message}") }
                    } else requestNotificationThenCapture()
                }
            }.setNegativeButton("取消") { _, _ -> stopCapture() }.setOnCancelListener { stopCapture() }.show()
    }
    private fun consentIsCurrent() = consentGeneration == CaptureStatus.generation && CaptureStatus.state.phase == CapturePhase.PREPARING
    private fun stopCapture() { consentGeneration = -1L; CaptureStatus.stop(this) }
    private fun requestNotificationThenCapture() {
        if (!consentIsCurrent()) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        } else requestCapture()
    }
    private fun requestCapture() {
        if (!consentIsCurrent()) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        runCatching { startActivityForResult(intent, CAPTURE_PERMISSION) }.onFailure { permissionError("无法请求捕获授权：${it.message}") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION && consentIsCurrent()) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("通知未允许；仍可在首页开关或系统前台服务入口停止")
            requestCapture()
        }
    }
    @Deprecated("Platform result API retained to keep this native prototype dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION -> if (consentIsCurrent()) {
                if (Settings.canDrawOverlays(this)) requestNotificationThenCapture() else permissionError("未允许悬浮窗，查词未开启")
            }
            CAPTURE_PERMISSION -> if (consentIsCurrent()) {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT, resultCode).putExtra(CaptureService.EXTRA_DATA, data)
                        .putExtra(CaptureService.EXTRA_GENERATION, consentGeneration)
                    runCatching { startForegroundService(intent) }.onFailure { permissionError("无法启动捕获服务：${it.message}") }
                } else permissionError("已取消屏幕捕获，查词未开启")
            }
            else -> if (interruptedDocumentRequests.remove(requestCode)) {
                // Frozen backup/restore state does not survive recreation: never auto-apply from the
                // URI or touch any file; the reserved request code is now consumed for good.
                toast("上次的备份或恢复操作已中断，请重新开始；未改动任何文件")
                updateBackupButtons()
            } else if (lostExportRequestCode >= 0 && requestCode == lostExportRequestCode) {
                // Consume before IO: another recreation must not wait for this result again.
                lostExportRequestCode = -1
                if (resultCode != RESULT_OK) { toast("已取消导出"); updateExportButton() }
                else if (data?.data == null) { toast(PdfPlaceholderCleanup.Result.NO_URI.message); updateExportButton() }
                else cleanLostPdf(data.data!!)
            } else if (requestCode == exportSession.fileRequestCode) {
                val snapshot = exportSession.takeResult(requestCode) ?: return
                if (resultCode == RESULT_OK && data?.data != null) savePdf(data.data!!, snapshot, requestCode)
                else {
                    exportSession.cancel(requestCode); toast("已取消导出"); updateExportButton()
                }
            } else if (requestCode == backupExportSession.fileRequestCode) {
                handleBackupExportResult(requestCode, resultCode, data)
            } else if (requestCode == restoreSession.importRequestCode) {
                handleRestoreImportResult(requestCode, resultCode, data)
            } else if (requestCode == restoreSession.safetyRequestCode) {
                handleRestoreSafetyResult(requestCode, resultCode, data)
            }
        }
    }
    private fun permissionError(message: String) { stopCapture(); CaptureStatus.update(CapturePhase.ERROR, message); toast(message) }

    private fun manualLookup(raw: String, replaces: String? = null, originalRaw: String = raw) {
        val token = manualGate.next()
        // Captured at query start and carried through display, save and retry; never refreshed later.
        val deletionCredential = app.repository.deletions.capture()
        app.worker.execute {
            if (!manualGate.accepts(token)) return@execute
            val lookup = runCatching { app.dictionary.getOrThrow().lookup(raw) }
            main.post {
                if (isDestroyed || !manualGate.accepts(token)) return@post
                lookup.onSuccess { result ->
                    when (result) {
                        LookupResult.Invalid -> toast("请输入一个有效英文词头")
                        is LookupResult.Missing -> toast("${result.form} 未收录于原始词库；未新增记录")
                        is LookupResult.Match -> showManualDefinition(originalRaw, result.form, result.entries.single(), replaces, deletionCredential)
                    }
                }.onFailure { toast("词库不可用：${it.message}；未生成释义或新增记录") }
            }
        }
    }
    private fun showManualDefinition(raw: String, form: String, entry: DictionaryEntry, replaces: String?, deletionCredential: Long) {
        val content = panel()
        content.addView(text(entry.headword, 28f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        content.addView(text("原词形：$form · ${entry.partOfSpeech}")); content.addView(text(entry.meaning, 18f))
        content.addView(text(RawKajwebDictionary.provenance(entry.source, entry.version), 14f, Paper.warning))
        val status = text("保存中…", 16f, Paper.secondary); content.addView(status)
        val undo = button("撤销本次收录") {}; undo.isEnabled = false; content.addView(undo); content.space(8)
        val correct = button("纠错") {}; correct.isEnabled = false; content.addView(correct); content.space(8)
        val retry = button("重试保存") {}; retry.visibility = View.GONE; content.addView(retry)
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).setNegativeButton("关闭", null).create()
        activeDialog?.dismiss(); activeDialog = dialog
        val token = manualGate.next()
        val guard = ManualGuard(entry.key, deletionCredential, status, undo, correct, retry)
        dialog.setOnDismissListener {
            if (manualGuard === guard) manualGuard = null
            if (manualGate.accepts(token)) manualGate.next()
        }
        dialog.show()
        manualGuard = guard
        val event = QueryEvent(UUID.randomUUID().toString(), entry.key, raw, form, System.currentTimeMillis())
        fun save() {
            // A stale card (word deleted after this query started) must not start/retry a save.
            if (manualCardStale(guard)) { invalidateManualCard(guard); return }
            retry.visibility = View.GONE; status.text = "保存中…"
            app.worker.execute {
                if (!manualGate.accepts(token)) return@execute
                val result = runCatching { app.repository.record(event, entry, replaces, deletionCredential) { manualGate.accepts(token) } }
                main.post {
                    if (!isDestroyed && manualGate.accepts(token) && dialog.isShowing && manualGuard === guard && !guard.invalid) {
                        val error = result.exceptionOrNull()
                        val saved = result.getOrNull() == true
                        when {
                            // A committed deletion (even before the invalidation message runs) wins over a
                            // late error/success: never re-show retry nor overwrite the invalid text.
                            manualCardStale(guard) -> invalidateManualCard(guard)
                            error != null -> {
                                status.text = "保存失败：${error.message}"; status.setTextColor(Paper.error)
                                undo.isEnabled = false; correct.isEnabled = false; retry.visibility = View.VISIBLE
                            }
                            saved -> {
                                status.text = "已收录"; status.setTextColor(Paper.green)
                                undo.isEnabled = true; correct.isEnabled = true; retry.visibility = View.GONE
                            }
                            else -> invalidateManualCard(guard)
                        }
                    }
                }
            }
        }
        retry.setOnClickListener { save() }
        undo.setOnClickListener {
            // Reject an expired card at the entry too, not only after the worker returns.
            if (manualCardStale(guard)) { invalidateManualCard(guard); return@setOnClickListener }
            undo.isEnabled = false; correct.isEnabled = false
            app.worker.execute {
                // A no-op revoke (word already deleted / event gone) must not be reported as success.
                val result = runCatching { app.repository.revokeEvent(event.id, entry.key, deletionCredential) }
                main.post {
                    if (!isDestroyed && manualGate.accepts(token) && dialog.isShowing && manualGuard === guard) {
                        // Deletion wins over a late revoke result: never re-enable undo/correct nor report REVOKED.
                        if (manualCardStale(guard)) invalidateManualCard(guard) else {
                            val outcome = result.getOrNull()
                            when {
                                result.isFailure -> {
                                    status.text = "撤销失败：${result.exceptionOrNull()?.message}"
                                    undo.isEnabled = true; correct.isEnabled = true
                                }
                                outcome == RevokeOutcome.REVOKED -> { status.text = "本次已撤销，历史查询保留"; undo.text = "已撤销" }
                                else -> {
                                    status.text = "撤销失败：该记录已不存在"; status.setTextColor(Paper.error)
                                    undo.isEnabled = false; correct.isEnabled = false
                                }
                            }
                        }
                    }
                }
            }
        }
        correct.setOnClickListener {
            if (manualCardStale(guard)) { invalidateManualCard(guard); return@setOnClickListener }
            dialog.dismiss()
            val input = EditText(this).apply { setText(form); setSingleLine(true) }
            AlertDialog.Builder(this).setTitle("纠正词形").setView(input).setMessage("确认成功后撤销原事件，不重复累计。")
                .setPositiveButton("确认查询") { _, _ -> manualLookup(input.text.toString(), event.id, raw) }.setNegativeButton("取消", null).show()
        }
        save()
    }
    // A manual card is out of date once it is invalidated or its word was deleted after the query
    // credential was captured; both must win over any late success/failure result.
    private fun manualCardStale(guard: ManualGuard): Boolean =
        guard.invalid || app.repository.deletions.isStale(guard.key, guard.credential)
    private fun invalidateManualCard(guard: ManualGuard) {
        guard.invalid = true
        guard.status.text = "未收录：该词已删除或查询已失效"; guard.status.setTextColor(Paper.error)
        guard.undo.isEnabled = false; guard.correct.isEnabled = false; guard.retry.visibility = View.GONE
    }
    private fun export() {
        if (lostExportRequestCode >= 0 || cleaningLostExport) return
        val request = exportSession.begin(search) ?: return
        updateExportButton()
        app.worker.execute {
            val result = runCatching { app.repository.exportSnapshot(request.search) }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { snapshot ->
                    if (!exportSession.freeze(request, snapshot)) return@onSuccess
                    if (snapshot.words.isEmpty()) {
                        exportSession.cancel(request.id); updateExportButton(); toast("没有可导出的生词")
                    } else confirmExport(request, snapshot)
                }.onFailure {
                    if (exportSession.cancel(request.id)) { updateExportButton(); toast("读取导出数据失败：${it.message}") }
                }
            }
        }
    }
    private fun confirmExport(request: LearningExportSession.Request, snapshot: LearningExportSnapshot) {
        val content = panel()
        val dialog = paperDialog(content)
        content.addView(text("导出生词记忆单", 24f, bold = true)); content.space(12)
        content.addView(text("当前搜索结果 · ${snapshot.words.size} 个单词", 18f))
        if (snapshot.search.isNotBlank()) content.addView(text("搜索条件：${snapshot.search}", 16f, Paper.secondary))
        content.addView(text("A4 · 记忆版 · 最近查询优先", 16f, Paper.secondary)); content.space(16)
        val notice = column().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12)); background = shape(Paper.background, 8)
            addView(text(LearningExportText.notice(snapshot.words), 16f, Paper.warning, true))
            snapshot.words.map { it.source to it.version }.distinct().forEach { (source, version) ->
                addView(text(RawKajwebDictionary.provenance(source, version), 16f, Paper.secondary))
            }
            addView(text("导出包含词头、原始释义、各自来源版本与核对状态、原词形、查询次数、掌握状态和已保存的笔记；不包含自测题。", 16f, Paper.secondary))
        }
        content.addView(notice); content.space(12)
        content.addView(text("已从数据库读取并按本次搜索条件冻结内容；尚未保存的笔记与状态不会导出。取消不会创建导出文件。", 16f, Paper.secondary)); content.space(24)
        val cancel = button("取消") { dialog.dismiss() }.apply { background = shape(Paper.background, 8); setTextColor(Paper.ink) }
        val choose = button("选择保存位置", true) {
            val frozen = exportSession.choose(request) ?: return@button
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf").putExtra(Intent.EXTRA_TITLE, "拾词-生词记忆单-${frozen.date}.pdf")
            runCatching { startActivityForResult(intent, request.id) }.onFailure {
                exportSession.cancel(request.id); toast("无法打开文件保存：${it.message}")
            }
            updateExportButton()
        }
        content.addView(pair(cancel, choose, inlineAt(minOf(resources.configuration.screenWidthDp - 32, 560) - 48), 12))
        showPaperDialog(dialog, 560) {
            if (exportDialog === dialog) exportDialog = null
            if (exportSession.cancelPreparation(request.id)) updateExportButton()
        }
        exportDialog = dialog
    }
    private fun cleanLostPdf(uri: Uri) {
        cleaningLostExport = true
        updateExportButton()
        val resolver = applicationContext.contentResolver
        app.worker.execute {
            val result = LostPdfFileCleanup.clean(resolver, uri)
            main.post {
                if (isDestroyed || isFinishing) return@post
                cleaningLostExport = false
                toast(result.message)
                updateExportButton()
            }
        }
    }
    private fun savePdf(uri: Uri, snapshot: LearningExportSnapshot, requestCode: Int) {
        app.worker.execute {
            val error = runCatching {
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                output.use { PdfExporter.write(snapshot.words, it, snapshot.date) }
            }.exceptionOrNull()
            main.post { if (!isDestroyed && exportSession.cancel(requestCode)) {
                toast(if (error == null) "PDF 已写入所选位置" else "导出失败：${error.message}；目标文件可能不完整，请删除后重试")
                updateExportButton()
            } }
        }
    }
    private fun updateBackupButtons() {
        val busy = backupExportSession.busy || restoreSession.busy
        backupExportButton?.isEnabled = !busy
        restoreButton?.isEnabled = !busy
        backupStatusView?.text = when {
            restoreSession.busy -> "正在处理恢复：读取并校验备份、准备安全备份和原子覆盖，请勿离开本页"
            backupExportSession.busy -> "正在准备备份文件，请稍候"
            else -> "仅本地备份，不联网、不上传。"
        }
    }
    private fun exportLearningBackup() {
        if (backupExportSession.busy || restoreSession.busy) return
        if (lostExportRequestCode >= 0 || cleaningLostExport) { toast("请先完成或取消当前 PDF 导出"); return }
        val id = backupExportSession.begin() ?: return
        updateBackupButtons()
        app.worker.execute {
            val result = runCatching {
                val snapshot = app.repository.backupSnapshot()
                LearningBackupCodec.encode(snapshot.data, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, System.currentTimeMillis())
            }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { bytes ->
                    if (!backupExportSession.freeze(id, bytes)) return@onSuccess
                    updateBackupButtons()
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(LearningBackupCodec.MIME_TYPE)
                        .putExtra(Intent.EXTRA_TITLE, "拾词-学习数据备份-${LocalDate.now()}${LearningBackupCodec.FILE_SUFFIX}")
                    runCatching { startActivityForResult(intent, id) }.onFailure {
                        backupExportSession.cancel(id); updateBackupButtons()
                        toast("无法打开文件保存：${it.message}")
                    }
                }.onFailure {
                    backupExportSession.cancel(id); updateBackupButtons()
                    toast("准备备份失败：${it.message}")
                }
            }
        }
    }
    private fun handleBackupExportResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (backupExportSession.fileRequestCode != requestCode) return
        if (resultCode != RESULT_OK || data?.data == null) {
            backupExportSession.cancel(requestCode); updateBackupButtons(); toast("已取消备份"); return
        }
        val bytes = backupExportSession.takeResult(requestCode) ?: return
        val uri = data.data!!
        app.worker.execute {
            val error = runCatching {
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                output.use { it.write(bytes); it.flush() }
            }.exceptionOrNull()
            main.post {
                if (isDestroyed) return@post
                backupExportSession.cancel(requestCode)
                updateBackupButtons()
                toast(if (error == null) "学习数据备份已写入所选位置" else "备份失败：${error.message}；目标文件可能不完整，请删除后重试")
            }
        }
    }
    private fun startRestore() {
        if (backupExportSession.busy || restoreSession.busy) return
        if (lostExportRequestCode >= 0 || cleaningLostExport) { toast("请先完成或取消当前 PDF 导出"); return }
        val id = restoreSession.beginImport() ?: return
        updateBackupButtons()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        runCatching { startActivityForResult(intent, id) }.onFailure {
            restoreSession.cancelImport(id); updateBackupButtons()
            toast("无法打开文件选择：${it.message}")
        }
    }
    private fun handleRestoreImportResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (restoreSession.importRequestCode != requestCode) return
        if (resultCode != RESULT_OK || data?.data == null) {
            restoreSession.cancelImport(requestCode); updateBackupButtons(); toast("已取消恢复"); return
        }
        val uri = data.data!!
        if (!restoreSession.beginReading(requestCode, uri.toString())) return
        updateBackupButtons()
        app.worker.execute {
            // Read and validate the whole file first; the frozen payload is never re-read at apply time.
            val result = runCatching { LearningBackupCodec.decode(readBoundedBytes(uri)) }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { backup ->
                    if (!restoreSession.freezeImport(requestCode, backup)) return@onSuccess
                    updateBackupButtons()
                    showRestorePreview(backup)
                }.onFailure {
                    restoreSession.cancelImport(requestCode); updateBackupButtons()
                    toast("无法读取备份：${it.message}")
                }
            }
        }
    }
    private fun showRestorePreview(backup: LearningBackup) {
        val content = panel()
        val dialog = paperDialog(content)
        content.addView(text("从备份完整恢复", 24f, bold = true)); content.space(12)
        content.addView(text("备份创建时间：${date(backup.createdAt)}", 18f))
        content.addView(text("词条 ${backup.wordCount} 个 · 事件 ${backup.eventCount} 条", 18f))
        content.addView(text("备份应用版本：${backup.appVersionName}（${backup.appVersionCode}）", 16f, Paper.secondary)); content.space(12)
        val warning = column().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12)); background = shape(Paper.background, 8)
            addView(text(if (backup.wordCount == 0 && backup.eventCount == 0)
                "这是一个空备份：确认恢复后会清空当前全部学习数据。" else
                "恢复会用备份内容完整替换当前全部学习数据（不合并、不猜测冲突）；备份里没有的词也会被移除。",
                16f, Paper.warning, true))
            addView(text("恢复前必须先把当前数据另存为一个安全备份文件；只有写入关闭并读回校验一致、且数据未在此期间变化时才会覆盖。", 16f, Paper.secondary))
            addView(text("备份是本地明文个人数据，未加密、不防恶意篡改。", 16f, Paper.secondary))
        }
        content.addView(warning); content.space(24)
        val cancel = button("取消") { dialog.dismiss() }.apply { background = shape(Paper.background, 8); setTextColor(Paper.ink) }
        val confirm = button("继续并选择安全备份位置", true) {
            val safetyId = restoreSession.confirm() ?: return@button
            dialog.dismiss()
            updateBackupButtons()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(LearningBackupCodec.MIME_TYPE)
                .putExtra(Intent.EXTRA_TITLE, "拾词-恢复前安全备份-${LocalDate.now()}${LearningBackupCodec.FILE_SUFFIX}")
            runCatching { startActivityForResult(intent, safetyId) }.onFailure {
                restoreSession.cancelSafety(safetyId); updateBackupButtons()
                toast("无法打开安全备份保存位置：${it.message}")
            }
        }
        content.addView(pair(cancel, confirm, inlineAt(minOf(resources.configuration.screenWidthDp - 32, 560) - 48), 12))
        showPaperDialog(dialog, 560) {
            if (restorePreviewDialog === dialog) restorePreviewDialog = null
            if (restoreSession.cancelPreview()) updateBackupButtons()
        }
        restorePreviewDialog = dialog
    }
    private fun handleRestoreSafetyResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (restoreSession.safetyRequestCode != requestCode) return
        if (resultCode != RESULT_OK || data?.data == null) {
            restoreSession.cancelSafety(requestCode); updateBackupButtons(); toast("已取消恢复：未选择安全备份位置"); return
        }
        val safetyUri = data.data!!
        val importUri = restoreSession.pendingImportUri
        if (importUri != null && safetyUri.toString() == importUri) {
            restoreSession.cancelSafety(requestCode); updateBackupButtons()
            toast("安全备份位置不能与导入的备份文件相同，已取消恢复"); return
        }
        val backup = restoreSession.beginApply(requestCode) ?: return
        // Cross-thread cancellation identity for exactly this apply. onDestroy (through restoreSession.clear),
        // a replaced session or an invalidated apply must not let this queued worker overwrite the database.
        val apply = restoreSession.applyHandle ?: return
        updateBackupButtons()
        app.worker.execute {
            // Revalidate on the worker thread before any I/O: an apply invalidated while this task was
            // queued (destroyed/replaced session) must not even write the safety backup.
            if (!apply.isActive()) return@execute
            val result = runCatching {
                val pre = app.repository.backupSnapshot()
                val encoded = LearningBackupCodec.encode(pre.data, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, System.currentTimeMillis())
                if (!writeSafetyBackup(safetyUri, encoded)) error("恢复前安全备份未能读回校验一致")
                // Same worker and pre-transaction check: a change since the snapshot must not be overwritten.
                // This is also the last admission gate: admit() is linearized against session invalidation, so
                // a destroy/clear before it must not write, while an already admitted transaction is allowed to
                // commit atomically — no half-write, no false cancellation claim, no forced mid-commit rollback.
                if (!apply.admit()) return@runCatching null
                app.repository.restoreBackup(backup.data, pre.revision)
            }
            main.post {
                if (isDestroyed) return@post
                // Settle only this operation; finish() is request/phase guarded, so a replaced session
                // keeps its own busy state and a late callback cannot end the new session.
                restoreSession.finish(requestCode)
                updateBackupButtons()
                result.onSuccess { outcome ->
                    when (outcome) {
                        RestoreOutcome.RESTORED -> {
                            restorePreviewDialog?.dismiss(); restorePreviewDialog = null
                            toast("已完整恢复：替换为备份中的 ${backup.wordCount} 个词、${backup.eventCount} 条事件")
                        }
                        RestoreOutcome.REVISION_CHANGED ->
                            toast("学习数据在准备期间有新变化，本次未覆盖；请重新开始恢复")
                        else -> Unit
                    }
                }.onFailure { toast("恢复失败，学习数据未改变：${it.message}") }
            }
        }
    }
    /** Reads at most the backup size cap + 1 byte so a huge or endless stream cannot allocate freely. */
    private fun readBoundedBytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw BackupFormatException("无法读取所选文件")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > LearningBackupCodec.MAX_FILE_BYTES) throw BackupFormatException("备份文件超过 ${LearningBackupCodec.MAX_BYTES_LABEL} 上限")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }
    /** Writes the pre-restore snapshot, then reads it back and requires byte-identical content. */
    private fun writeSafetyBackup(uri: Uri, bytes: ByteArray): Boolean {
        val output = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("无法写入所选安全备份位置")
        output.use { it.write(bytes); it.flush() }
        return readBoundedBytes(uri).contentEquals(bytes)
    }
    private fun date(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
