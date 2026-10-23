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
import com.shici.app.data.ExactUndoOutcome
import com.shici.app.data.PreparedUndo
import com.shici.app.data.RestoreOutcome
import com.shici.app.data.RevokeOutcome
import com.shici.app.data.UndoPrepareOutcome
import com.shici.app.export.PdfExporter
import com.shici.app.export.LostPdfFileCleanup
import java.io.ByteArrayOutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * One dictionary read plus the committed revision observed on the worker immediately around it.
 * [stable] is false when a commit landed between the before/after reads, so the resolved entry and
 * the separately read user row may come from different snapshots. Delivery compares only the
 * in-memory revision (never SQLite on the main thread) and rejects an old read instead of relabelling
 * stale text with newer data.
 */
private data class LookupRead(
    val result: LookupResult,
    val user: UserDictionaryEntry?,
    val revision: Long,
    val stable: Boolean
) {
    fun isCurrent(now: Long): Boolean = stable && revision == now
}

class MainActivity : Activity() {
    companion object {
        private const val OVERLAY_PERMISSION = 10
        private const val CAPTURE_PERMISSION = 11
        private const val NOTIFICATION_PERMISSION = 12
    }

    /**
     * Stable page identity. Navigation, selection, restoration and late-result delivery all compare
     * this enum; display order is never used as the identity. [legacyTab] is only the historical
     * instance-state index (0 home, 1 vocabulary, 2 statistics, 3 settings, 4 study), so an old
     * "tab=3" still restores settings and can never turn into the new lookup page.
     */
    private enum class MainPage(val label: String, val legacyTab: Int) {
        HOME("首页", 0),
        LOOKUP("查词", -1),
        // Stable identity only: the historical tab indices below are untouched, and LOOKUP,
        // TRANSLATION and PERSONAL_ENTRY never borrow a legacy index.
        TRANSLATION("译句", -1),
        VOCABULARY("生词本", 1),
        STUDY("背词", 4),
        STATISTICS("统计", 2),
        PERSONAL_ENTRY("添加/补词", -1),
        SETTINGS("设置", 3);

        companion object {
            val mainPages = listOf(
                HOME, LOOKUP, TRANSLATION, VOCABULARY, STUDY, STATISTICS, PERSONAL_ENTRY
            )
            fun fromLegacyTab(tab: Int): MainPage = when (tab) {
                0 -> HOME
                1 -> VOCABULARY
                2 -> STATISTICS
                3 -> SETTINGS
                4 -> STUDY
                else -> HOME
            }
            fun fromStableName(name: String?): MainPage? =
                name?.let { runCatching { valueOf(it) }.getOrNull() }
        }
    }

    private val app get() = application as ShiciApplication
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    @Volatile private var page = MainPage.HOME
    // True while this Activity is started. Favorite writes are admitted on the worker thread and use
    // this plus [interactionEpoch] to refuse a mutation queued by a page that has since stopped or
    // navigated away. A background snapshot refresh never changes either value.
    @Volatile private var foreground = false
    @Volatile private var interactionEpoch = 0L
    // Vocabulary page filter/selection. They live in fields (not in rebuilt views) so an ordinary
    // snapshot refresh or a split-detail rebuild keeps the user's search, filter, checks and detail.
    private var onlyFavorites = false
    private val exportSelection = linkedSetOf<String>()
    // Export scope intent, kept together with [exportSelection] across refreshes, search/favorite
    // filters, navigation and rotation. `false` is the untouched default: no checkbox has ever been
    // edited for this page, so the export means "everything surviving the current filter"
    // (`selectedKeys = null`). Once the user checks, unchecks, selects all or clears, this becomes
    // `true` and [exportSelection] is the explicit allow-list: an empty set is an explicit zero and
    // must never fall back to "all". Only the visible "use all current results" action resets it.
    private var exportSelectionExplicit = false
    private var favoriteFilterView: FavoriteFilterView? = null
    // Translation page draft and the single owner of its ML Kit session. The draft survives page
    // switches and rotation; the translator itself is closed on leave/stop/destroy and is recreated
    // only when the page is entered again (which performs a local model check, never a download).
    private var translationInput = ""
    private var translationSelectionStart = 0
    private var translationSelectionEnd = 0
    private var translationScreen: TranslationScreen? = null
    private var translationNeedsResume = false
    // Legacy mirror only: old instrumented hosts set this field and call render(), and old saved
    // state used it. Product navigation never derives identity from this index; [page] is authoritative.
    private var currentTab = 0
    // The sole product navigation view. Page identity stays [page]; the component only receives
    // stable MainPage.name ids and paints the current selection in place.
    private lateinit var navigation: MainNavigation
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
    // Lookup page draft: the text and selection live in fields so a background snapshot refresh
    // (which deliberately does not re-render this page) and rotation cannot destroy the user's input.
    private var lookupQuery = ""
    private var lookupSelectionStart = 0
    private var lookupSelectionEnd = 0
    private var lookupInputView: EditText? = null
    // Personal-entry page: the page owns one editor generation at a time. A source captured from a
    // lookup card keeps that card's original deletion credential and user-row snapshot; a direct
    // navigation read is the only path allowed to capture a fresh credential, at the explicit action.
    private class PersonalEntrySource(
        val entryKey: String, val headword: String, val raw: String,
        val original: DictionaryEntry?, var user: UserDictionaryEntry?,
        val credential: Long, var readRevision: Long, val newHeadword: Boolean
    )
    private data class PersonalEntryDraft(
        val headword: String, val pos: String, val meaning: String,
        val baseUpdatedAt: Long, val newHeadword: Boolean
    )
    private class PersonalDraftConfirm(val source: PersonalEntrySource, val draft: PersonalEntryDraft)
    private var personalSource: PersonalEntrySource? = null
    private var personalDraft: PersonalEntryDraft? = null
    private var personalEditorDraft: PersonalEntryDraft? = null
    private var personalRestoredDraft: PersonalEntryDraft? = null
    private var personalDraftConfirm: PersonalDraftConfirm? = null
    private var personalHeadword = ""
    private var personalStatusText = ""
    private var personalStatusView: TextView? = null
    private var personalOwner = SupplementEditorOwner()
    private var personalSession = Any()
    private var personalNeedsResume = false
    private val personalGate = QueryGate()
    private val personalOpenGate = QueryGate()
    // One explicit card-to-page hand-off. [invalid] is set by the source card's dismiss, by page
    // navigation and by onStop/onDestroy; the worker and the delivery callback both require the same
    // live instance, so a late read can never open a new writable page from a retired card.
    private class PersonalHandoff(val cardToken: Any, private val cardLive: () -> Boolean) {
        @Volatile var invalid = false
        fun cardStillLive() = cardLive()
    }
    @Volatile private var pendingPersonalHandoff: PersonalHandoff? = null
    private var selectedKey: String? = null
    private var consentGeneration = -1L
    private val refreshGate = LearningRefreshGate()
    private val exportSession = LearningExportSession()
    private val backupExportSession = LearningBackupExportSession()
    private val restoreSession = LearningRestoreSession()
    // One persistent study surface so an in-flight feedback commit is not orphaned by a tab switch.
    private val studyScreenDelegate = lazy { StudyScreen(app, this, { render() }, { toast(it) }) }
    private val studyScreen by studyScreenDelegate
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
    @Volatile private var inlineDetail: WordDetailBinding? = null
    @Volatile private var dialogDetail: WordDetailBinding? = null
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
        // An already-displayed uncollected card belongs to the world its query started in. A restore
        // (or this key's deletion) must retire its write entry too; otherwise an old card could open
        // a new editor after the restore and capture the new world's credential.
        val missing = manualMissingGuard
        if (missing != null && !missing.invalid && app.repository.deletions.isStale(missing.key, missing.credential)) {
            main.post { if (manualMissingGuard === missing) invalidateManualMissing(missing) }
        }
        main.post { refresh(version) }
    }
    private var floatingSwitch: Switch? = null
    private var floatingText: TextView? = null
    private var syncSwitch = false
    private var searchList: LinearLayout? = null
    private var wordListHeader: TextView? = null
    private var detailPanel: LinearLayout? = null
    private val manualGate = QueryGate()
    // One cross-thread admission lease per open supplement editor. A lease is closed by its own
    // detach/cancel, by the dialog that owns it, and by the owning Activity's onStop/onDestroy; once
    // closed it is never revived, even if the same view tree is reattached. The lease only authorizes
    // the repository's real pre-transaction admission: an already-admitted save still commits atomically.
    private class SupplementEditorOwner { @Volatile var closed = false }
    private class SupplementEditorLease(val owner: SupplementEditorOwner, val admission: StudyAdmission) {
        @Volatile var closed = false
    }
    private val supplementEditorLeases = mutableSetOf<SupplementEditorLease>()
    private fun closeSupplementEditor(lease: SupplementEditorLease) {
        lease.closed = true
        lease.admission.invalidate()
        supplementEditorLeases -= lease
    }
    // Ends every editor, regardless of the dialog that opened it: used by onStop/onDestroy.
    private fun closeSupplementEditors() {
        supplementEditorLeases.toList().forEach { closeSupplementEditor(it) }
    }
    // A dialog's dismiss ends only the editors that dialog opened; another window's live editor is
    // untouched, so its already-running or later save is still admitted on its own owner.
    private fun closeSupplementEditorOwner(owner: SupplementEditorOwner) {
        owner.closed = true
        supplementEditorLeases.toList().forEach { lease ->
            if (lease.owner === owner) closeSupplementEditor(lease)
        }
    }
    // The visible manual "已收录" card, invalidated if its word is deleted while the card is still open.
    private class ManualGuard(
        val key: String, val credential: Long,
        val status: TextView, val undo: Button, val correct: Button, val retry: Button,
        val supplement: Button
    ) { @Volatile var invalid = false }
    @Volatile private var manualGuard: ManualGuard? = null
    // The visible manual "未收录" card. It carries the credential captured when its query started, so
    // neither a late result nor a re-opened editor can silently bind to a newer deletion/restore world.
    private class ManualMissingGuard(
        val key: String, val credential: Long,
        val add: Button?, val collect: Button, val status: TextView,
        val closeEditor: () -> Unit
    ) { @Volatile var invalid = false }
    @Volatile private var manualMissingGuard: ManualMissingGuard? = null
    private var dictionarySummary = "词库尚未校验；正在后台加载"
    private var dictionaryStatusView: TextView? = null
    private var activeDialog: AlertDialog? = null
    // One scoped-delete flow at a time. Every dialog keeps its own identity: a late dismiss can
    // settle only the flow it belongs to, and the admission is invalidated by page navigation,
    // onStop and onDestroy before any transaction that has not been admitted yet.
    private class DeleteFlow(
        val key: String,
        val binding: WordDetailBinding,
        private val ownerAlive: () -> Boolean
    ) {
        @Volatile var closed = false
        val admission = StudyAdmission { !closed && ownerAlive() }
        var currentDialog: AlertDialog? = null
        /**
         * The scope chooser must be consumed exactly once per flow. A replayed item callback is the
         * same explicit choice, so it must not prepare again, mount a second confirmation or switch
         * to the other scope; a genuinely new flow gets a fresh instance of this marker.
         */
        @Volatile var scopeConsumed = false
        /**
         * The write confirmation must be consumed exactly once per flow. A replayed positive
         * callback is the same confirmation, so it must not queue a second commit, settle the first
         * already-queued operation or touch a replacement flow.
         */
        @Volatile var commitConsumed = false
        /** True only while this flow was never retired and its page/owner still exists. */
        fun isLive() = !closed && ownerAlive()
    }
    @Volatile private var deleteFlow: DeleteFlow? = null
    private var stopCaptureButton: Button? = null
    private val wide get() = resources.configuration.screenWidthDp >= 840
    private val contentWidthDp get() = resources.configuration.screenWidthDp - (if (wide) 216 + 64 else 40)
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
        // New stable key first; the historical "tab" indices remain readable, with tab=3 -> settings.
        page = MainPage.fromStableName(savedInstanceState?.getString("page"))
            ?: savedInstanceState?.let { if (it.containsKey("tab")) MainPage.fromLegacyTab(it.getInt("tab")) else null }
            ?: MainPage.HOME
        currentTab = page.legacyTab
        search = savedInstanceState?.getString("search") ?: ""
        lookupQuery = savedInstanceState?.getString("lookup_query") ?: ""
        lookupSelectionStart = savedInstanceState?.getInt("lookup_sel_start", lookupQuery.length) ?: lookupQuery.length
        lookupSelectionEnd = savedInstanceState?.getInt("lookup_sel_end", lookupSelectionStart) ?: lookupSelectionStart
        translationInput = savedInstanceState?.getString("translation_draft") ?: ""
        translationSelectionStart = savedInstanceState?.getInt(
            "translation_sel_start", translationInput.length
        ) ?: translationInput.length
        translationSelectionEnd = savedInstanceState?.getInt(
            "translation_sel_end", translationSelectionStart
        ) ?: translationSelectionStart
        if (translationSelectionStart > translationSelectionEnd) {
            val swap = translationSelectionStart
            translationSelectionStart = translationSelectionEnd
            translationSelectionEnd = swap
        }
        translationSelectionStart = translationSelectionStart.coerceIn(0, translationInput.length)
        translationSelectionEnd = translationSelectionEnd.coerceIn(
            translationSelectionStart, translationInput.length
        )
        onlyFavorites = savedInstanceState?.getBoolean("favorites_only", false) ?: false
        savedInstanceState?.getStringArrayList("export_selection")?.let { exportSelection.addAll(it) }
        // Old bundles carried no intent flag: a restored non-empty list was the historical explicit
        // allow-list, while a restored empty list meant the default "current filter" scope. New
        // bundles carry the flag explicitly, so an intentionally emptied selection restores as an
        // explicit zero instead of silently turning back into "all".
        exportSelectionExplicit = savedInstanceState?.let { state ->
            if (state.containsKey("export_selection_explicit")) {
                state.getBoolean("export_selection_explicit", false)
            } else {
                exportSelection.isNotEmpty()
            }
        } ?: false
        personalHeadword = savedInstanceState?.getString("pe_headword") ?: ""
        if (page == MainPage.PERSONAL_ENTRY) {
            val hasDraft = savedInstanceState?.getBoolean("pe_has_draft", false) == true
            if (hasDraft) {
                val draft = PersonalEntryDraft(
                    savedInstanceState.getString("pe_draft_headword") ?: personalHeadword,
                    savedInstanceState.getString("pe_draft_pos") ?: "",
                    savedInstanceState.getString("pe_draft_meaning") ?: "",
                    savedInstanceState.getLong("pe_draft_base", 0L),
                    savedInstanceState.getBoolean("pe_draft_new", false)
                )
                personalHeadword = draft.headword
                // A restored draft is never an automatic write identity: the old editor/lease is
                // gone, so the page asks for a fresh user-triggered read before any editing.
                personalRestoredDraft = draft
                personalStatusText = "页面已重建。已保留草稿文字，但不会沿用离开前的写入身份；请重新读取当前词义后再确认。"
            } else if (personalHeadword.isNotBlank()) {
                personalStatusText = "页面已重建。请重新读取当前词义后再继续；离开前的编辑身份已失效。"
            }
        }
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
        foreground = true
        refreshGate.start()
        CaptureStatus.observe(statusListener)
        app.repository.changes.observe(dataListener)
        // A stopped personal-entry page kept its draft text but lost its editor lease. Re-render the
        // page in the explicit re-read state instead of leaving a disabled, closed editor on screen.
        if (page == MainPage.PERSONAL_ENTRY && personalNeedsResume) {
            personalNeedsResume = false
            personalStatusText = if (personalRestoredDraft != null)
                "已保留草稿文字；离开时的编辑身份已关闭。请重新读取当前词义并再次确认。"
            else "离开时的编辑身份已关闭。请重新读取当前词义后再继续。"
            render()
        }
        // A stopped translation page was detached and its ML Kit owner closed. Rebuild it from the
        // retained draft with a fresh owner; opening it only checks the local model state and never
        // starts a translation or a model download by itself.
        if (page == MainPage.TRANSLATION && translationNeedsResume) {
            translationNeedsResume = false
            render()
        }
        refresh()
        loadDictionaryStatus()
    }
    override fun onStop() {
        // Refuse every favorite mutation that was queued by a surface that is no longer in front.
        foreground = false
        interactionEpoch++
        // Retain the visible draft, then release the ML Kit owner. The next onStart rebuilds the page
        // with a fresh owner only on the local model check, never an automatic translation/download.
        captureTranslationDraft()
        closeTranslationOwner()
        translationNeedsResume = page == MainPage.TRANSLATION
        manualGate.next()
        // A scoped delete or card-to-page hand-off that has not reached its transaction yet must not
        // pop a writable surface after this Activity is no longer in front.
        closeDeleteFlow()
        personalOpenGate.next()
        cancelPendingPersonalHandoff()
        // The supplement editor belongs to this stopped page: refuse every queued save that has not
        // reached its transaction yet. A save admitted before this point keeps its atomic commit.
        closeSupplementEditors()
        closePersonalEntryOwner(keepDraft = true)
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
        foreground = false
        interactionEpoch++
        // Idempotent: also covers a destroy that never received onStop. The translator, its pending
        // callbacks and the async result window are all retired before this Activity is gone.
        captureTranslationDraft()
        closeTranslationOwner()
        refreshGate.stop(); exportSession.clear(); manualGate.next()
        backupExportSession.clear(); restoreSession.clear()
        personalGate.next(); personalOpenGate.next()
        closeDeleteFlow()
        cancelPendingPersonalHandoff()
        // Close an already-queued supplement save even if this page never received onStop.
        closeSupplementEditors()
        closePersonalEntryOwner(keepDraft = false)
        // Invalidate the study surface's cross-thread admission before the Activity is gone. Workers
        // that already reached their transaction may finish atomically; ones still reading refuse it.
        if (studyScreenDelegate.isInitialized()) studyScreen.onDestroy()
        restorePreviewDialog?.dismiss(); restorePreviewDialog = null
        activeDialog?.dismiss(); activeDialog = null
        super.onDestroy()
    }
    @Deprecated("Legacy Activity back handling is retained; no AndroidX dependency is added")
    override fun onBackPressed() {
        // Back from any functional page returns home and closes that page's editor generation;
        // pressing back on home keeps the platform exit behavior.
        if (page != MainPage.HOME) {
            selectPage(MainPage.HOME)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
    override fun onSaveInstanceState(outState: Bundle) {        captureLookupDraft()
        outState.putString("page", page.name)
        // Historical state writer retained for old readers: only the five original indices exist.
        outState.putInt("tab", page.legacyTab)
        outState.putString("search", search)
        outState.putString("lookup_query", lookupQuery)
        outState.putInt("lookup_sel_start", lookupSelectionStart)
        outState.putInt("lookup_sel_end", lookupSelectionEnd)
        captureTranslationDraft()
        outState.putString("translation_draft", translationInput)
        outState.putInt("translation_sel_start", translationSelectionStart)
        outState.putInt("translation_sel_end", translationSelectionEnd)
        outState.putBoolean("favorites_only", onlyFavorites)
        outState.putStringArrayList("export_selection", ArrayList(exportSelection))
        // The intent is part of the selection identity: an explicit empty selection is 0 words, not
        // the default "all". Old readers without this key keep their historical interpretation.
        outState.putBoolean("export_selection_explicit", exportSelectionExplicit)
        outState.putString("pe_headword", personalHeadword)
        val draft = personalDraft ?: personalRestoredDraft ?: personalDraftConfirm?.draft
        if (page == MainPage.PERSONAL_ENTRY && draft != null) {
            outState.putBoolean("pe_has_draft", true)
            outState.putString("pe_draft_headword", draft.headword)
            outState.putString("pe_draft_pos", draft.pos)
            outState.putString("pe_draft_meaning", draft.meaning)
            outState.putLong("pe_draft_base", draft.baseUpdatedAt)
            outState.putBoolean("pe_draft_new", draft.newHeadword)
        }
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
        // One navigation component owns both layouts. Its ids are the stable MainPage names, so a
        // restored/legacy selection never depends on display order or a child index.
        navigation = MainNavigation(
            context = this,
            wide = wide,
            items = MainPage.mainPages.map { MainNavigation.Item(it.name, it.label) },
            settings = MainNavigation.Item(MainPage.SETTINGS.name, MainPage.SETTINGS.label),
            selectedId = page.name,
            onSelect = { id -> MainPage.fromStableName(id)?.let { selectPage(it) } }
        )
        if (wide) {
            root.addView(navigation.view, LinearLayout.LayoutParams(dp(216), -1))
            root.addView(body, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(navigation.view, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        root.requestApplyInsets()
        render()
    }

    // Navigation rendering is a single delegation: MainNavigation restyles its own buttons in place
    // by stable id, so no page is rediscovered by position and no scroll region is rebuilt.
    private fun renderNavState() {
        navigation.setSelected(page.name)
    }

    private fun selectPage(target: MainPage) {
        if (target == page) { renderNavState(); return }
        // A new page retires mutations (for example a favorite write) queued by the old page.
        interactionEpoch++
        closeDeleteFlow()
        cancelPendingPersonalHandoff()
        captureLookupDraft()
        captureTranslationDraft()
        // Leaving the translation page releases its translator; the draft fields stay for the next
        // explicit visit. A re-entry creates a new owner and performs only a local state check.
        if (page == MainPage.TRANSLATION) {
            closeTranslationOwner()
            translationNeedsResume = false
        }
        // Leaving the lookup page retires any in-flight manual query so a late result cannot open a
        // writable card over another page.
        if (page == MainPage.LOOKUP) manualGate.next()
        // Changing page closes the personal-entry generation. A return starts from an explicit read;
        // no closed owner, credential or late read is reused.
        if (page == MainPage.PERSONAL_ENTRY) leavePersonalEntry()
        personalOpenGate.next()
        page = target
        currentTab = target.legacyTab
        if (target == MainPage.PERSONAL_ENTRY) startPersonalEntryFresh()
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
                    when (page) {
                        MainPage.VOCABULARY -> { renderWords(preserveDetail = true); updateExportButton() }
                        // Never rebuild a page that holds live input / an editor owner on an ordinary
                        // background refresh. Leaving the page is the only path that invalidates them.
                        // TRANSLATION owns an ML Kit session and a draft; it is deliberately excluded.
                        MainPage.LOOKUP, MainPage.TRANSLATION, MainPage.PERSONAL_ENTRY,
                        MainPage.SETTINGS -> Unit
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
        // Compatibility bridge for the historical reflection host and any old caller that still
        // writes the five legacy indices. Product navigation always keeps [currentTab] equal to
        // [page]'s legacy mirror, so this cannot override an enum-selected page (LOOKUP and
        // PERSONAL_ENTRY use -1). It is not the page identity.
        if (currentTab != page.legacyTab && currentTab in 0..4) page = MainPage.fromLegacyTab(currentTab)
        if (currentTab != page.legacyTab) currentTab = page.legacyTab
        // A rebuild never leaves an old translator alive behind detached views. Except for the
        // translation page itself (rebuilt immediately below), this is the page-leave path.
        if (translationScreen != null) { captureTranslationDraft(); closeTranslationOwner() }
        floatingSwitch = null; floatingText = null; stopCaptureButton = null; searchList = null; detailPanel = null
        wordListHeader = null
        exportButton = null; inlineDetail = null; dictionaryStatusView = null
        backupExportButton = null; restoreButton = null; backupStatusView = null
        lookupInputView = null; personalStatusView = null; favoriteFilterView = null
        body.removeAllViews()
        renderNavState()
        when (page) {
            MainPage.HOME -> home()
            MainPage.LOOKUP -> lookupPage()
            MainPage.TRANSLATION -> translationPage()
            MainPage.VOCABULARY -> vocabulary()
            MainPage.STUDY -> study()
            MainPage.STATISTICS -> statistics()
            MainPage.PERSONAL_ENTRY -> personalEntryPage()
            MainPage.SETTINGS -> settings()
        }
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
        session.addView(text("开启后，点悬浮「词」，直接在题目页面上划一笔查词", 16f, Paper.secondary))
        session.addView(text("不需要悬浮权限时，也可以到「查词」页手动输入。", 16f, Paper.secondary))
        content.addView(session); content.space(24)
        content.addView(text("主要功能", 22f, bold = true)); content.space(8)
        content.addView(entryCard("查词", "日常输入查询；缺词可新增词义，已有词可补充个人释义。") { selectPage(MainPage.LOOKUP) })
        content.space(8)
        content.addView(entryCard("译句", "输入英文句子，用本机离线模型整句翻译；不查词、不收录、不上传原文。") { selectPage(MainPage.TRANSLATION) })
        content.space(8)
        content.addView(entryCard("添加/补词", "只维护本机个人词性/释义，不产生查询次数、生词或复习记录。") { selectPage(MainPage.PERSONAL_ENTRY) })
        content.space(8)
        content.addView(entryCard("生词本", "查看和管理已经真实查询收录的个人生词。") { selectPage(MainPage.VOCABULARY) })
        content.space(8)
        content.addView(entryCard("背词", "继续原来的统一学习队列与复习反馈。") { selectPage(MainPage.STUDY) })
        content.space(8)
        content.addView(entryCard("统计", "查看真实查询与复习进度聚合。") { selectPage(MainPage.STATISTICS) })
        content.space()
        content.addView(text("打开悬浮查词 → 点「词」→ 划过文字 → 抬笔即查", 16f, Paper.secondary))
        statusListener(CaptureStatus.state)
    }
    private fun entryCard(title: String, description: String, action: () -> Unit): View = column().apply {
        setPadding(dp(20), dp(14), dp(20), dp(14)); background = shape(Paper.surface, border = true)
        addView(text(title, 20f, bold = true))
        addView(text(description, 15f, Paper.secondary))
        isClickable = true; isFocusable = true; minimumHeight = dp(64)
        contentDescription = "打开$title"
        setOnClickListener { action() }
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
        // Product filter for the same snapshot the list/export read. The widget itself never touches
        // the repository; it only selects which rows this page shows. Star/unstar never deletes data.
        favoriteFilterView = FavoriteFilterView(this).apply {
            setOnlyFavorites(this@MainActivity.onlyFavorites, notify = false)
            onFilterChanged = { value ->
                this@MainActivity.onlyFavorites = value
                renderWords(preserveDetail = true)
                updateExportButton()
            }
        }
        content.addView(favoriteFilterView); content.space(8)
        val selectionRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        selectionRow.addView(button("全选当前结果") { selectAllFilteredForExport() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        selectionRow.addView(button("清空勾选") { clearExportSelection() },
            LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(selectionRow); content.space(8)
        // The only explicit way back to the default scope. Clearing the checks is a deliberate zero
        // and never doubles as "all", so this separate action must stay visible to the user.
        content.addView(button("使用当前结果全部（不勾选）") { useAllFilteredForExport() },
            LinearLayout.LayoutParams(-1, -2))
        content.space(8)
        content.addView(text("勾选只决定 PDF 导出范围，不改词库、查询次数或学习进度。默认（尚未勾选/清空）导出当前筛选全部；一旦勾选、取消或清空，就按你的勾选集合导出，清空即 0 个，不会自动变回全部；点“使用当前结果全部（不勾选）”才回到默认全量。全选只包含当前搜索/仅收藏筛出的词，日期范围仍在导出选项中取交集。", 13f, Paper.secondary))
        content.space(8)
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
                renderWords(preserveDetail = true)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderWords()
    }
    private fun filteredWords() = words.filter { word ->
        (!onlyFavorites || word.favorite) && (search.isBlank() ||
            listOf(word.entry.headword, word.entry.meaning, word.lastForm, word.note)
                .any { it.contains(search.trim(), true) })
    }
    /**
     * The frozen scope for one request. `null` is the untouched default ("all rows of the current
     * filter"); a set — including an empty one — is the user's explicit allow-list. A defensive copy
     * is taken here so later checkbox edits cannot rebind a request that already started.
     */
    private fun exportScopeKeys(): Set<String>? =
        if (exportSelectionExplicit) exportSelection.toSet() else null

    /** Rows the current filter would export under the present intent; an explicit set intersects it. */
    private fun exportScopeCount(list: List<LearnedWord> = filteredWords()): Int =
        if (exportSelectionExplicit) list.count { it.entry.key in exportSelection } else list.size

    private fun updateExportButton() {
        // Eligibility is checked against a fresh repository read, never against the UI cache. An
        // empty current filter, an intentionally emptied selection (explicit zero), a busy session
        // or a lost SAF request can never start a new picker.
        exportButton?.isEnabled = !exportSession.busy && lostExportRequestCode < 0 &&
            !cleaningLostExport && exportScopeCount() > 0
    }
    private fun selectAllFilteredForExport() {
        // An explicit "all of this filter" intent, remembered as the current keys: exactly the rows
        // currently surviving search + "仅收藏". Hidden words are never added, and a later narrower
        // filter or deletion intersects this list instead of widening it back to everything.
        exportSelection.clear()
        exportSelection.addAll(filteredWords().map { it.entry.key })
        exportSelectionExplicit = true
        renderWords(preserveDetail = true)
        updateExportButton()
    }
    private fun clearExportSelection() {
        // Clearing is itself an explicit choice: zero words, never an implicit "all".
        exportSelection.clear()
        exportSelectionExplicit = true
        renderWords(preserveDetail = true)
        updateExportButton()
    }
    private fun useAllFilteredForExport() {
        // The only supported way back to the default scope, so an emptied selection can never be
        // mistaken for "all". This is a visible user action, not a side effect of clearing.
        exportSelection.clear()
        exportSelectionExplicit = false
        renderWords(preserveDetail = true)
        updateExportButton()
    }
    private fun renderWords(preserveDetail: Boolean = false) {
        val list = filteredWords()
        searchList?.removeAllViews()
        val summary = listSummary(list)
        wordListHeader = text(summary, 14f, Paper.secondary)
        searchList?.addView(wordListHeader)
        if (list.isEmpty()) searchList?.addView(text(if (words.isEmpty()) "还没有生词。成功查询后会自动收录。" else "没有匹配的生词。", 18f))
        list.forEach { word ->
            searchList?.addView(vocabularyRow(word) {
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
            inlineDetail?.let { closeDeleteFlowFor(it) }
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
    private fun listSummary(list: List<LearnedWord>): String {
        val filters = buildList {
            if (search.isNotBlank()) add("搜索“${search.trim()}”")
            if (onlyFavorites) add("仅收藏")
        }
        val count = if (filters.isEmpty()) "${list.size} 个词"
        else "筛选结果 ${list.size} 个词（${filters.joinToString(" · ")}）"
        val scope = when {
            !exportSelectionExplicit -> "导出范围：当前筛选全部（${list.size} 个）"
            exportSelection.isEmpty() -> "已清空勾选：导出 0 个（不会回退为全部）"
            else -> {
                val inFilter = list.count { it.entry.key in exportSelection }
                "已勾选 ${exportSelection.size} 个（当前筛选内 $inFilter 个；与搜索/仅收藏/日期取交集）"
            }
        }
        return "$count · 最近查询优先 · $scope"
    }
    private fun refreshListSummary() { wordListHeader?.text = listSummary(filteredWords()) }
    /**
     * One vocabulary row: an export checkbox, the original clickable word content and a star. The
     * checkbox only edits the in-memory export allow-list (never the database); the star sends one
     * frozen key/target/credential to the real repository through the existing worker.
     */
    private fun vocabularyRow(word: LearnedWord, action: () -> Unit): View {
        val container = row().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72) }
        val check = CheckBox(this).apply {
            isChecked = word.entry.key in exportSelection
            contentDescription = "勾选导出 ${word.entry.headword}"
            setOnCheckedChangeListener { _, checked ->
                // Any real checkbox edit switches the page into the explicit allow-list intent, so
                // unchecking the last row keeps an explicit 0 instead of reverting to "all".
                exportSelectionExplicit = true
                if (checked) exportSelection += word.entry.key else exportSelection -= word.entry.key
                refreshListSummary()
                updateExportButton()
            }
        }
        container.addView(check)
        container.addView(wordRow(word, action), LinearLayout.LayoutParams(0, -2, 1f))
        val star = button(if (word.favorite) "★" else "☆") { toggleListFavorite(word) }.apply {
            textSize = 22f
            minWidth = dp(48)
            contentDescription =
                if (word.favorite) "取消收藏 ${word.entry.headword}" else "收藏 ${word.entry.headword}"
            setTextColor(if (word.favorite) Paper.warning else Paper.green)
        }
        container.addView(star)
        return container
    }
    /**
     * Daily lookup lives on its own page. The input only ever feeds the existing [manualLookup]
     * pipeline (same resolver, gates, revision checks and query-record rules); no query SQL or fake
     * record is introduced here. Results still open the existing card, which now also offers the
     * explicit "在补词页编辑/新增" hand-off without removing the in-place supplement editor.
     */
    private fun lookupPage() {
        // View-only component. Its callbacks feed the existing fields/pipeline below; it never
        // resolves a word, records an event or opens a second gate.
        val screen = LookupScreen(
            context = this,
            initialText = lookupQuery,
            selectionStart = lookupSelectionStart,
            selectionEnd = lookupSelectionEnd,
            onTextChanged = { text -> lookupQuery = text },
            onQuery = { text ->
                // Keep the original draft/revision/credential chain: capture the caret first, then
                // run the same manualLookup that button/IME submissions always used.
                lookupQuery = text
                captureLookupDraft()
                manualLookup(text)
            },
            onOpenPersonalEntry = { selectPage(MainPage.PERSONAL_ENTRY) }
        )
        // onSaveInstanceState, page switches and rotation capture the draft through this field.
        lookupInputView = screen.input
        body.addView(screen.view, LinearLayout.LayoutParams(-1, -1))
    }

    /** Saves the current lookup text and caret so a page switch or rotation never drops either. */
    private fun captureLookupDraft() {
        val view = lookupInputView ?: return
        lookupQuery = view.text.toString()
        val start = view.selectionStart.coerceAtLeast(0)
        val end = view.selectionEnd.coerceAtLeast(0)
        lookupSelectionStart = minOf(start, end).coerceIn(0, lookupQuery.length)
        lookupSelectionEnd = maxOf(start, end).coerceIn(lookupSelectionStart, lookupQuery.length)
    }

    // ---- Translation page -----------------------------------------------------------------------

    /**
     * A real embedded [TranslationScreen], not a sample. Its draft is restored from [translationInput]
     * and its selection from the saved caret pair. The component itself starts only a local model
     * state query; translation happens exclusively on the user's "翻译整句" tap and a model download
     * only on the explicit Wi-Fi confirmation. Leaving/stop/destroy closes this owner, and the page
     * is excluded from background re-rendering so an old asynchronous result cannot paint a new page.
     */
    private fun translationPage() {
        val screen = TranslationScreen(
            this,
            initialText = translationInput,
            initialSelectionStart = translationSelectionStart,
            initialSelectionEnd = translationSelectionEnd
        )
        screen.onCloseRequested = { selectPage(MainPage.HOME) }
        translationScreen = screen
        body.addView(screen.view, LinearLayout.LayoutParams(-1, -1))
    }

    /** Retains the visible translation draft before its owner is closed. */
    private fun captureTranslationDraft() {
        val screen = translationScreen ?: return
        val draft = screen.draft()
        translationInput = draft.text
        translationSelectionStart = draft.selectionStart
        translationSelectionEnd = draft.selectionEnd
    }

    /** Single teardown path for the Activity-owned translator. Idempotent; the view is host-owned. */
    private fun closeTranslationOwner() {
        val screen = translationScreen ?: return
        translationScreen = null
        screen.close()
    }

    // ---- Personal-entry page --------------------------------------------------------------------

    private fun startPersonalEntryFresh() {
        closePersonalEntryOwner(keepDraft = false)
        personalNeedsResume = false
        personalStatusText = ""
    }

    private fun leavePersonalEntry() {
        closePersonalEntryOwner(keepDraft = false)
        personalNeedsResume = false
        personalStatusText = ""
    }

    /**
     * Ends the page's editor generation. [keepDraft] is used by onStop: the text is retained for an
     * explicit re-read prompt, but the owner, leases, credential and in-flight page gate are dropped.
     */
    private fun closePersonalEntryOwner(keepDraft: Boolean) {
        personalGate.next()
        closeSupplementEditorOwner(personalOwner)
        personalOwner = SupplementEditorOwner()
        personalSession = Any()
        val confirmingDraft = personalDraftConfirm?.draft
        personalEditorDraft = null
        personalDraftConfirm = null
        if (!keepDraft) {
            personalSource = null
            personalDraft = null
            personalRestoredDraft = null
            personalHeadword = ""
            personalStatusText = ""
        } else if (page == MainPage.PERSONAL_ENTRY) {
            personalNeedsResume = personalSource != null || personalDraft != null ||
                personalRestoredDraft != null || confirmingDraft != null || personalHeadword.isNotBlank()
            if (personalDraft != null) personalRestoredDraft = personalDraft
            else if (confirmingDraft != null) personalRestoredDraft = confirmingDraft
            personalSource = null
            personalDraft = null
        }
    }

    private fun cancelPersonalEntry() {
        closePersonalEntryOwner(keepDraft = false)
        personalNeedsResume = false
        personalStatusText = "已取消本次补词，未保存任何内容"
        render()
    }

    private fun personalEntryPage() {
        // The component owns only the page skeleton/input/status/editor host. Every credential,
        // revision, owner, lease, draft and save decision stays in this Activity.
        val screen = PersonalEntryScreen(
            context = this,
            initialHeadword = personalHeadword,
            statusText = personalStatusText,
            canCancel = personalSource != null || personalDraft != null ||
                personalRestoredDraft != null || personalDraftConfirm != null,
            onHeadwordChanged = { text -> personalHeadword = text },
            onRead = { raw -> readPersonalHeadword(raw) },
            onCancel = { cancelPersonalEntry() }
        )
        // Assigned before any callback can run, so read/save/conflict status paints this page.
        personalStatusView = screen.statusView
        body.addView(screen.view, LinearLayout.LayoutParams(-1, -1))
        val host = screen.editorHost
        // Original priority and early-return order: confirmation, restored draft, then source card.
        personalDraftConfirm?.let { confirm -> personalDraftConfirmCard(host, confirm); return }
        personalRestoredDraft?.let { draft -> personalRestoredDraftCard(host, draft); return }
        personalSource?.let { source -> personalSourceCard(host, source) }
    }

    private fun personalRestoredDraftCard(host: LinearLayout, draft: PersonalEntryDraft) {
        val card = panel().apply {
            addView(text("已恢复未保存草稿", 20f, bold = true))
            addView(text("草稿词性：${draft.pos.ifBlank { "（空）" }}", 16f))
            addView(text("草稿释义：${draft.meaning.ifBlank { "（空）" }}", 16f))
            addView(text("本页不会沿用离开前的写入身份，也不会静默覆盖当前词义。请重新读取当前词义并确认后再编辑。", 15f, Paper.warning))
        }
        card.addView(button("重新读取当前词义并继续", true) {
            readPersonalHeadword(draft.headword, draft)
        })
        card.addView(button("放弃草稿") {
            personalRestoredDraft = null
            personalDraft = null
            personalStatusText = "已放弃草稿，未保存任何内容"
            render()
        })
        host.addView(card)
    }

    private fun personalDraftConfirmCard(host: LinearLayout, confirm: PersonalDraftConfirm) {
        val card = panel().apply {
            addView(text("草稿需要再次确认", 20f, bold = true))
            addView(text("草稿词性：${confirm.draft.pos.ifBlank { "（空）" }}", 16f))
            addView(text("草稿释义：${confirm.draft.meaning.ifBlank { "（空）" }}", 16f))
            addView(text("该词的本机词义在本页离开后已经更新。草稿不会自动覆盖新内容；若仍打开草稿，保存时会按离开时的词义版本校验，冲突会拒绝写入。", 15f, Paper.warning))
        }
        card.addView(button("查看当前词义并编辑", true) {
            personalSource = confirm.source
            personalDraftConfirm = null
            personalRestoredDraft = null
            personalEditorDraft = null
            personalDraft = null
            personalStatusText = "已载入当前词义；保存只写本机个人词义。"
            render()
        })
        card.addView(button("仍打开草稿（保存时校验冲突）") {
            personalSource = confirm.source
            personalEditorDraft = confirm.draft
            personalDraft = confirm.draft
            personalDraftConfirm = null
            personalRestoredDraft = null
            personalStatusText = "已打开草稿；保存时会校验离开时的词义版本，不会静默覆盖。"
            render()
        })
        card.addView(button("放弃草稿") {
            personalSource = null
            personalDraftConfirm = null
            personalRestoredDraft = null
            personalDraft = null
            personalStatusText = "已放弃草稿，未保存任何内容"
            render()
        })
        host.addView(card)
    }

    private fun personalSourceCard(host: LinearLayout, source: PersonalEntrySource) {
        val card = panel()
        card.addView(text(source.headword, 28f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        val original = source.original
        if (original != null) {
            card.addView(text("原词库释义（只读）", 16f, bold = true))
            card.addView(text("${original.partOfSpeech} ${original.meaning}".trim(), 16f))
            card.addView(text(PersonalDictionary.provenance(original), 13f, Paper.warning))
        } else {
            card.addView(text("原始词库未收录这个词；保存只在本机新增用户词条。", 15f, Paper.warning))
        }
        card.addView(text("当前本机用户释义", 17f, bold = true))
        val userView = text(if (source.user == null) "本机暂无用户补充" else
            "${source.user?.partOfSpeech.orEmpty()} ${source.user?.meaning.orEmpty()}".trim(), 16f)
        card.addView(userView)
        card.addView(text("用户补充 · 仅本机", 13f, Paper.secondary))
        card.space(8)
        val editorHost = column()
        card.addView(editorHost)
        host.addView(card)
        val initialDraft = personalEditorDraft
        personalEditorDraft = null
        val base = initialDraft?.baseUpdatedAt ?: (source.user?.updatedAt ?: 0L)
        val initialPos = initialDraft?.pos ?: (source.user?.partOfSpeech ?: "")
        val initialMeaning = initialDraft?.meaning ?: (source.user?.meaning ?: "")
        if (initialDraft != null || initialPos.isNotBlank() || initialMeaning.isNotBlank()) {
            personalDraft = PersonalEntryDraft(source.headword, initialPos, initialMeaning, base, source.newHeadword)
        }
        addSupplementEditor(editorHost, personalOwner, source.entryKey, source.headword,
            original = source.original, currentUser = source.user, credential = source.credential,
            onSaved = { saved, _ ->
                source.user = saved
                source.readRevision = app.repository.changes.version
                personalDraft = null
                userView.text = "${saved.partOfSpeech} ${saved.meaning}".trim()
                personalStatusText = "已保存到本机用户词库；本次保存不产生查询次数、生词或复习反馈。"
                personalStatusView?.text = personalStatusText
                personalStatusView?.setTextColor(Paper.green)
            },
            newHeadword = source.newHeadword,
            showOriginal = false,
            initialPos = initialPos,
            initialMeaning = initialMeaning,
            expectedUpdatedAtOverride = initialDraft?.baseUpdatedAt,
            onDraftChanged = { pos, meaning ->
                personalDraft = PersonalEntryDraft(source.headword, pos, meaning, base, source.newHeadword)
            })
    }

    /**
     * A direct navigation read is the only path allowed to capture a new deletion credential, and
     * only at this explicit user action. Delivery is bound to the page, its session, and a gate; the
     * before/after revision pair rejects mixed or stale reads instead of showing old meaning.
     */
    private fun readPersonalHeadword(raw: String, forDraft: PersonalEntryDraft? = null) {
        if (page != MainPage.PERSONAL_ENTRY) return
        val text = raw.trim()
        if (text.isEmpty()) {
            personalStatusView?.text = "请输入一个英文词头"
            personalStatusView?.setTextColor(Paper.error)
            return
        }
        personalHeadword = raw
        if (forDraft == null) {
            // A new explicit headword read ends any previous page editor before it starts. Its lease
            // is closed, so nothing from the old source can be admitted after this point.
            closeSupplementEditorOwner(personalOwner)
            personalOwner = SupplementEditorOwner()
            personalSource = null
            personalDraft = null
            personalDraftConfirm = null
            personalRestoredDraft = null
        }
        val token = personalGate.next()
        val session = personalSession
        val credential = app.repository.deletions.capture()
        personalStatusView?.text = "正在读取当前词义…"
        personalStatusView?.setTextColor(Paper.secondary)
        app.worker.execute {
            if (!personalGate.accepts(token)) return@execute
            val read = runCatching {
                val before = app.repository.changes.version
                val result = app.lookupWord(text)
                val user = (result as? LookupResult.Match)?.let { app.repository.userEntry(it.form) }
                val after = app.repository.changes.version
                LookupRead(result, user, after, before == after)
            }
            main.post {
                if (isDestroyed || page != MainPage.PERSONAL_ENTRY || personalSession !== session ||
                    !personalGate.accepts(token)) return@post
                read.onSuccess { lookup ->
                    if (!lookup.stable || lookup.revision != app.repository.changes.version) {
                        personalStatusText = "读取期间词义或本机补充已更新。为避免显示旧内容，本次结果已丢弃；请重新读取。"
                        render()
                        return@onSuccess
                    }
                    when (val result = lookup.result) {
                        LookupResult.Invalid -> {
                            personalStatusText = "请输入一个有效英文词头"
                            render()
                        }
                        is LookupResult.Missing -> {
                            if (app.repository.deletions.isStale(result.form, credential)) {
                                personalStatusText = "读取已失效（词条已删除或数据已恢复），请重新读取"
                                render()
                            } else if (PersonalDictionary.normalizeNewKey(result.form) != result.form) {
                                personalStatusText = "该结果不是单个规范英文词头（可能是短语或多词），不能作为新词头；请修改为一个英文单词。"
                                render()
                            } else {
                                applyPersonalRead(PersonalEntrySource(result.form, result.form, text, null,
                                    lookup.user, credential, lookup.revision, newHeadword = true), forDraft)
                            }
                        }
                        is LookupResult.Match -> {
                            val entry = result.entries.single()
                            if (app.repository.deletions.isStale(entry.key, credential)) {
                                personalStatusText = "读取已失效（词条已删除或数据已恢复），请重新读取"
                                render()
                            } else {
                                val original = if (entry.source == PersonalDictionary.SOURCE) null
                                else PersonalDictionary.storedEntry(entry)
                                applyPersonalRead(PersonalEntrySource(entry.key, entry.headword, text,
                                    original, lookup.user, credential, lookup.revision, newHeadword = false), forDraft)
                            }
                        }
                    }
                }.onFailure {
                    personalStatusText = "词库不可用：${it.message}；未生成释义或新增记录"
                    render()
                }
            }
        }
    }

    private fun applyPersonalRead(source: PersonalEntrySource, forDraft: PersonalEntryDraft?) {
        if (forDraft != null) {
            val currentBase = source.user?.updatedAt ?: 0L
            personalRestoredDraft = null
            if (currentBase != forDraft.baseUpdatedAt) {
                personalSource = null
                personalDraft = null
                personalEditorDraft = null
                personalDraftConfirm = PersonalDraftConfirm(source, forDraft)
                personalStatusText = "已重新读取当前词义；发现本机词义在离开后已更新，草稿需要再次确认，不会自动覆盖。"
                render()
                return
            }
            personalSource = source
            personalEditorDraft = forDraft.copy(headword = source.headword, newHeadword = source.newHeadword)
            personalDraft = personalEditorDraft
            personalStatusText = "已重新读取当前词义并恢复草稿；保存前请确认内容。"
            render()
            return
        }
        personalSource = source
        personalEditorDraft = null
        personalDraft = null
        personalRestoredDraft = null
        personalDraftConfirm = null
        personalStatusText = "已读取当前词义。保存只会更新本机个人词义。"
        render()
    }

    /**
     * Hand-off from a lookup card into the full personal-entry page. The card's original deletion
     * credential, user-row snapshot and read revision are kept exactly as read; no fresh credential
     * is captured and no newer revision is copied onto older content.
     *
     * The worker re-reads the user row around a committed-revision pair and requires the same
     * revision the card was read at, both from the read pair and at delivery. [isCardLive] is the
     * source card's own owner check; [pendingPersonalHandoff] is compared by identity, and onStop,
     * page navigation or the card's dismiss invalidates it. A late callback therefore cannot open a
     * new writable page, while a late dismiss of an old card never touches a new page.
     */
    private fun openPersonalEntryFromCard(
        source: PersonalEntrySource,
        cardToken: Any,
        isCardLive: () -> Boolean,
        onAccepted: () -> Unit
    ) {
        if (page != MainPage.LOOKUP) return
        if (app.repository.deletions.isStale(source.entryKey, source.credential)) {
            toast("该词已删除或数据已恢复，请重新查询")
            return
        }
        if (!isCardLive()) return
        cancelPendingPersonalHandoff()
        val token = personalOpenGate.next()
        val handoff = PersonalHandoff(cardToken, isCardLive)
        pendingPersonalHandoff = handoff
        // Fixed identity from the query card. Never replaced with a later version.
        val expectedRevision = source.readRevision
        val expectedUser = source.user
        app.worker.execute {
            if (!personalOpenGate.accepts(token) || handoff.invalid) return@execute
            val check = runCatching {
                val before = app.repository.changes.version
                val user = app.repository.userEntry(source.entryKey)
                val after = app.repository.changes.version
                Triple(user, after, before == after)
            }
            if (!personalOpenGate.accepts(token) || handoff.invalid) return@execute
            main.post {
                if (isDestroyed || isFinishing || page != MainPage.LOOKUP ||
                    !personalOpenGate.accepts(token) ||
                    pendingPersonalHandoff !== handoff || handoff.invalid ||
                    !handoff.cardStillLive()) {
                    return@post
                }
                check.onSuccess { (userNow, revision, stable) ->
                    // The source content was read at expectedRevision. Both the read span and the
                    // delivery-time committed revision must still equal it; otherwise the old card
                    // would be relabelled as the current world.
                    if (!stable || revision != expectedRevision ||
                        app.repository.changes.version != expectedRevision ||
                        userNow != expectedUser ||
                        app.repository.deletions.isStale(source.entryKey, source.credential)) {
                        pendingPersonalHandoff = null
                        toast("查询结果已更新，请重新查询后再编辑")
                        return@onSuccess
                    }
                    pendingPersonalHandoff = null
                    onAccepted()
                    enterPersonalEntryFromSource(source)
                }.onFailure {
                    pendingPersonalHandoff = null
                    toast("读取当前词义失败：${it.message}")
                }
            }
        }
    }

    /**
     * Invalidates an unaccepted card hand-off without touching the current page or its owner. A
     * card-token mismatch means the pending hand-off belongs to a different card, so an old card's
     * late dismiss can never cancel a newer one.
     */
    private fun cancelPendingPersonalHandoff(cardToken: Any? = null) {
        val handoff = pendingPersonalHandoff ?: return
        if (cardToken != null && handoff.cardToken !== cardToken) return
        handoff.invalid = true
        pendingPersonalHandoff = null
    }

    private fun enterPersonalEntryFromSource(source: PersonalEntrySource) {
        captureLookupDraft()
        // The accepted hand-off no longer needs its open gate; retire it before creating the page's
        // own editor generation.
        personalOpenGate.next()
        closePersonalEntryOwner(keepDraft = false)
        personalNeedsResume = false
        personalSource = source
        personalHeadword = source.headword
        personalStatusText = "已沿用刚才查询结果的来源身份；保存只写本机个人词义。"
        page = MainPage.PERSONAL_ENTRY
        currentTab = page.legacyTab
        render()
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
        private val star: Button, private val mastery: Switch, private val note: EditText,
        private val form: TextView, private val count: TextView, private val dates: TextView,
        private val definition: TextView, private val source: TextView, private val sourceBadge: TextView,
        private val mutationAllowed: () -> Boolean,
        private val stale: () -> Boolean,
        private val onClose: (() -> Unit)?,
        private var savedMastered: Boolean, private var savedNote: String,
        private var savedFavorite: Boolean
    ) {
        private var busy = false
        private var confirming = false
        private var favoriteBusy = false
        @Volatile private var invalidated = false
        @Volatile private var closed = false
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
                source.text = PersonalDictionary.provenance(word.source, word.version)
                sourceBadge.text = PersonalDictionary.label(word.source, word.version)
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                fun date(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(formatter)
                dates.text = "首次查询：${date(word.firstAt)}\n最近查询：${date(word.lastAt)}"
                if (note.text.toString() == savedNote && savedNote != word.note) note.setText(word.note)
                if (mastery.isChecked == savedMastered) mastery.isChecked = word.mastered
                savedNote = word.note; savedMastered = word.mastered
                savedFavorite = word.favorite
                renderStar()
            }
            applyEnabled()
        }

        fun isBusy() = busy || confirming
        fun isStale() = stale()
        fun isClosed() = closed
        fun isInvalidated() = invalidated
        fun ownerAlive() = !closed && !invalidated
        fun dismiss() { closed = true; onClose?.invoke() }
        fun beginConfirm() { confirming = true; applyEnabled() }
        fun cancelConfirm() { confirming = false; applyEnabled() }
        fun beginBusy() { busy = true; applyEnabled() }
        fun endBusy() { busy = false; confirming = false; applyEnabled() }
        fun favoriteValue() = savedFavorite
        /** Applies a committed star value without waiting for the refresh round-trip. */
        fun setFavoriteState(value: Boolean) { savedFavorite = value; renderStar(); applyEnabled() }
        fun beginFavoriteBusy() { favoriteBusy = true; applyEnabled() }
        fun endFavoriteBusy() { favoriteBusy = false; applyEnabled() }
        // Re-applies the control state after the shared mutation flag changed, still honoring this
        // binding's own busy/confirming/invalidated state (so a frozen or invalidated card stays frozen).
        fun refreshEnabled() = applyEnabled()
        fun invalidate() {
            invalidated = true
            busy = true; confirming = true
            save.isEnabled = false; undo.isEnabled = false; delete.isEnabled = false
            star.isEnabled = false
            mastery.isEnabled = false; note.isEnabled = false
        }
        fun masteredValue() = mastery.isChecked
        fun noteValue() = note.text.toString()

        private fun renderStar() {
            star.text = if (savedFavorite) "★ 已收藏（点击取消）" else "☆ 收藏"
            star.contentDescription =
                if (savedFavorite) "取消收藏 $key" else "收藏 $key"
            star.setTextColor(if (savedFavorite) Paper.warning else Paper.green)
        }

        private fun applyEnabled() {
            // The binding's own key/original credential participates too: a stale or invalidated
            // detail can never be re-enabled by endBusy, update or a shared-busy release.
            val active = !invalidated && !closed && !stale() && !busy && !confirming && mutationAllowed()
            save.isEnabled = active
            undo.isEnabled = active && wordPresent
            delete.isEnabled = active
            star.isEnabled = active && wordPresent && !favoriteBusy
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
        val sourceBadge = badge(PersonalDictionary.label(word.source, word.version))
        content.addView(sourceBadge, LinearLayout.LayoutParams(-2, -2)); content.divider()
        val form = text("原词形：${word.lastForm}", 16f, Paper.secondary)
        content.addView(form)
        val source = text(PersonalDictionary.provenance(word.source, word.version), 16f, Paper.secondary)
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
        val star = button(if (word.favorite) "★ 已收藏（点击取消）" else "☆ 收藏") {}
        content.addView(star); content.space(8)
        content.addView(text("星标只是个人收藏标记；取消星标不会删除任何学习记录，也不改掌握状态、查询次数或复习进度。", 16f, Paper.secondary))
        content.space(8)
        val delete = button("移除学习记录…") {}
        delete.setTextColor(Paper.error)
        content.addView(delete)
        content.space(8)
        content.addView(text("先选择范围：可以只撤销最近一次查询；或另行确认删除该词的全部学习记录（含已撤销查询、个人笔记、掌握状态、复习进度与收藏星标），删除后无法恢复。原始词库、独立本机词义、其他生词与已导出 PDF 均不受影响。", 16f, Paper.secondary))
        val binding = WordDetailBinding(word.entry.key, deletionCredential, snapshotVersion, save, undo, delete, star, mastered, note,
            form, count, dates, definition, source, sourceBadge, { !detailBusy },
            { app.repository.deletions.isStale(word.entry.key, deletionCredential) }, close,
            word.mastered, word.note, word.favorite)
        save.setOnClickListener { saveDetail(binding, word, close) }
        star.setOnClickListener { toggleDetailFavorite(binding) }
        // The shortcut stays exact-event-only: it prepares the latest event under this binding's
        // original credential and confirms that one event, never the dangerous full-history scope.
        // The remove entry opens the scope chooser, which never deletes by itself and still needs a
        // second confirmation for full history.
        undo.setOnClickListener { startExactUndoFlow(binding, word, close) }
        delete.setOnClickListener { openDeleteScope(binding, word, close) }
        binding.update(word)
        return binding
    }
    /**
     * Owner check for a star commit. It is evaluated on the worker thread immediately before BEGIN
     * (through both [StudyAdmission] and `canCommit`), so a detail closed by navigation/stop, or a
     * word deleted/restored after the click, can never write. Nothing here re-captures a credential.
     */
    private fun favoriteOwnerLive(binding: WordDetailBinding, epoch: Long): Boolean =
        foreground && epoch == interactionEpoch && !isFinishing && !isDestroyed &&
            binding.ownerAlive() && !binding.isStale() &&
            (inlineDetail === binding || dialogDetail === binding)

    /**
     * Stars/unstars exactly the frozen key with the detail's original credential and the target
     * chosen at click time. A repeated click cannot retire the first valid write: the button is
     * disabled while in flight and an idempotent repeat returns false without a false success.
     */
    private fun toggleDetailFavorite(binding: WordDetailBinding) {
        if (binding.isBusy() || binding.isClosed() || binding.isInvalidated()) return
        if (binding.isStale()) {
            binding.invalidate()
            toast("该词已删除或数据已恢复，请刷新后重新打开详情再操作")
            return
        }
        val epoch = interactionEpoch
        val key = binding.key
        val credential = binding.deletionCredential
        val target = !binding.favoriteValue()
        val live = { favoriteOwnerLive(binding, epoch) }
        val admission = StudyAdmission(live)
        binding.beginFavoriteBusy()
        app.worker.execute {
            val result = runCatching {
                app.repository.setFavorite(key, target, credential,
                    admission = admission, canCommit = live)
            }
            main.post {
                if (isDestroyed) return@post
                binding.endFavoriteBusy()
                if (!live()) {
                    // Closed/stopped page or a newer deletion/restore: never report success. If this
                    // card is still visible, freeze its now-stale controls.
                    if (binding.isStale() && (inlineDetail === binding || dialogDetail === binding)) {
                        binding.invalidate()
                        toast("未变更：该词已删除或数据已恢复")
                    }
                    return@post
                }
                result.onSuccess { changed ->
                    if (changed) {
                        binding.setFavoriteState(target)
                        toast(if (target) "已收藏" else "已取消收藏")
                        // Re-read the same snapshot so the row order/filter and every other view of
                        // this word agree; the repository already committed the frozen write.
                        refresh()
                    } else {
                        toast("收藏状态已是目标值，未重复写入")
                    }
                }.onFailure { toast("收藏操作失败：${it.message}") }
            }
        }
    }

    /**
     * List-row star. The row carries the key and target from the snapshot it rendered, and the
     * credential of the list's [words] snapshot; neither is refreshed in the worker. A page change,
     * stop, deletion or full restore refuses the write before BEGIN.
     */
    private fun toggleListFavorite(word: LearnedWord) {
        if (page != MainPage.VOCABULARY || !foreground || isFinishing || isDestroyed) return
        val epoch = interactionEpoch
        val key = word.entry.key
        val credential = wordsCredential
        val target = !word.favorite
        val live = {
            foreground && epoch == interactionEpoch && page == MainPage.VOCABULARY &&
                !isFinishing && !isDestroyed && !app.repository.deletions.isStale(key, credential)
        }
        val admission = StudyAdmission(live)
        app.worker.execute {
            val result = runCatching {
                app.repository.setFavorite(key, target, credential,
                    admission = admission, canCommit = live)
            }
            main.post {
                if (isDestroyed) return@post
                if (!live()) {
                    if (page == MainPage.VOCABULARY &&
                        app.repository.deletions.isStale(key, credential)) {
                        toast("未变更：该词已删除或数据已恢复")
                    }
                    return@post
                }
                result.onSuccess { changed ->
                    if (changed) {
                        toast(if (target) "已收藏" else "已取消收藏")
                        refresh()
                    } else toast("收藏状态已是目标值，未重复写入")
                }.onFailure { toast("收藏操作失败：${it.message}") }
            }
        }
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
    /**
     * Shortcut entry of the exact single-event undo. It uses the same [DeleteFlow] start/settle,
     * owner, busy, stale and admission protections as the scope chooser, but skips the chooser so
     * the user never has to pick a dangerous scope for a "undo the latest query" action. The worker
     * preparation and confirmation still freeze the exact event before any write.
     */
    private fun startExactUndoFlow(binding: WordDetailBinding, word: LearnedWord, close: (() -> Unit)?) {
        if (binding.isBusy() || detailBusy) return
        if (binding.isStale()) {
            binding.invalidate()
            toast("该词已删除或数据已恢复，请刷新后重新打开详情再操作")
            return
        }
        closeDeleteFlow()
        val flow = DeleteFlow(word.entry.key, binding) { !isFinishing && !isDestroyed }
        deleteFlow = flow
        binding.beginConfirm()
        prepareExactUndo(flow, word, close)
    }

    /**
     * [flow] is the only scoped-delete flow allowed to act on the UI right now. A retired flow (page
     * stop/destroy/navigation), a flow replaced by a newer one, and a flow whose owner is gone all
     * fail this check, so their late callbacks cannot mount dialogs, rebind state or start work.
     */
    private fun isLiveDeleteFlow(flow: DeleteFlow): Boolean = deleteFlow === flow && flow.isLive()

    /**
     * Entry of the scope-choosing learning-record action. It never deletes immediately: the scope
     * chooser is shown first and both scopes then have their own explicit confirmation, so the
     * full-history path always needs a second, dangerous confirmation. The detail's original
     * deletion credential and snapshot stay with the binding; no fresh credential is taken.
     */
    private fun openDeleteScope(binding: WordDetailBinding, word: LearnedWord, close: (() -> Unit)?) {
        if (binding.isBusy() || detailBusy) return
        if (binding.isStale()) {
            binding.invalidate()
            toast("该词已删除或数据已恢复，请刷新后重新打开详情再操作")
            return
        }
        closeDeleteFlow()
        val flow = DeleteFlow(word.entry.key, binding) { !isFinishing && !isDestroyed }
        deleteFlow = flow
        binding.beginConfirm()
        var scopeChosen = false
        val dialog = DeleteScopeDialog.createScopeDialog(this, word.entry.headword) { scope ->
            // A queued chooser item can be delivered after onStop/onDestroy/navigation retired this
            // flow, or after a newer flow replaced it. Such a retired callback must not branch,
            // rebind currentDialog, dispatch preparation or mount any confirmation.
            if (!isLiveDeleteFlow(flow)) return@createScopeDialog
            // The same chooser item can be delivered more than once before or after the queued
            // preparation. It is still one user choice: ignore every replay without changing the
            // chosen scope, dispatching another preparation or mounting another confirmation.
            if (flow.scopeConsumed) return@createScopeDialog
            flow.scopeConsumed = true
            scopeChosen = true
            flow.currentDialog = null
            when (scope) {
                DeleteScope.UNDO_LAST_QUERY_ONLY -> prepareExactUndo(flow, word, close)
                DeleteScope.DELETE_ALL_LEARNING_RECORDS -> showDeleteAllConfirm(flow, word)
            }
        }
        flow.currentDialog = dialog
        dialog.setOnDismissListener {
            // A cancel/back settles only this flow. A chosen scope has already moved on to its own
            // confirmation (or a read-only preparation), so this listener does nothing.
            if (deleteFlow === flow && flow.currentDialog === dialog) {
                flow.currentDialog = null
                if (!scopeChosen) settleDeleteFlow(flow)
            }
        }
        dialog.show()
    }

    /**
     * Reads the exact latest event once under the detail's original credential. The worker is
     * read-only; if the page/owner ended while it ran, no confirmation is shown and nothing is
     * written. PREPARED carries the exact id/key/time that will be shown and committed.
     */
    private fun prepareExactUndo(flow: DeleteFlow, word: LearnedWord, close: (() -> Unit)?) {
        // Never dispatch even the read-only preparation for a flow that is already retired; a late
        // UNDO_LAST choice must not run work on behalf of an old page.
        if (!isLiveDeleteFlow(flow)) return
        app.worker.execute {
            val result = runCatching {
                app.repository.prepareUndoLatest(word.entry.key, flow.binding.deletionCredential)
            }
            main.post {
                if (!isLiveDeleteFlow(flow)) return@post
                result.onSuccess { preparation ->
                    when (preparation.outcome) {
                        UndoPrepareOutcome.PREPARED -> showExactUndoConfirm(
                            flow, word, checkNotNull(preparation.prepared), close
                        )
                        UndoPrepareOutcome.NONE -> {
                            settleDeleteFlow(flow); toast("没有可撤销的查询")
                        }
                        UndoPrepareOutcome.STALE -> {
                            flow.binding.invalidate()
                            val cleared = clearStaleDetail(flow.key, flow.binding)
                            if (cleared && selectedKey == flow.key) selectedKey = null
                            settleDeleteFlow(flow); releaseDetailBusy()
                            toast("未撤销：该词已删除或已重新收录，请刷新后重新打开详情")
                        }
                        UndoPrepareOutcome.CHANGED -> {
                            settleDeleteFlow(flow)
                            toast("数据刚有变化，未撤销任何记录；请重试")
                        }
                    }
                }.onFailure {
                    settleDeleteFlow(flow)
                    toast("撤销失败：${it.message}")
                }
            }
        }
    }

    /** Confirms exactly the event time that was prepared; the shown target cannot move underneath. */
    private fun showExactUndoConfirm(flow: DeleteFlow, word: LearnedWord,
                                     prepared: PreparedUndo, close: (() -> Unit)?) {
        // The preparation callback (or the scope chooser) may have raced a retirement: never build
        // or attach a confirmation for a flow that is no longer the live one.
        if (!isLiveDeleteFlow(flow)) return
        var proceed = false
        val dialog = DeleteScopeDialog.createUndoLastConfirmDialog(
            this, word.entry.headword, prepared.eventTime
        ) {
            if (!isLiveDeleteFlow(flow)) return@createUndoLastConfirmDialog
            proceed = true
            flow.currentDialog = null
            commitExactUndo(flow, word, prepared, close)
        }
        // Re-check immediately before this flow owns and shows the new dialog, so a retired flow can
        // never take currentDialog over or mount over the replacement page.
        if (!isLiveDeleteFlow(flow)) return
        flow.currentDialog = dialog
        dialog.setOnDismissListener {
            if (deleteFlow === flow && flow.currentDialog === dialog) {
                flow.currentDialog = null
                if (!proceed) settleDeleteFlow(flow)
            }
        }
        dialog.show()
    }

    /**
     * Commits the exact prepared event. [admission] and [canCommit] are evaluated by the repository
     * immediately before BEGIN: an owner/page invalidation that happened first refuses with
     * REJECTED and writes nothing, while an already-admitted commit finishes atomically and is
     * reported as such even if the page closes afterwards.
     */
    private fun commitExactUndo(flow: DeleteFlow, word: LearnedWord,
                                prepared: PreparedUndo, close: (() -> Unit)?) {
        // A confirmation delivered by a retired flow must not touch shared busy state, rewrite the
        // current flow's binding or start a transaction; an already-started transaction is below.
        if (!isLiveDeleteFlow(flow)) return
        // A repeated delivery of this same confirmation is not a second operation. Consume it once
        // per flow and ignore the replay before touching the shared busy flag, the flow or its
        // admission: settling here would turn the first already-queued commit into a cancellation.
        if (flow.commitConsumed) return
        if (detailBusy) { settleDeleteFlow(flow); return }
        flow.commitConsumed = true
        detailBusy = true
        flow.binding.beginBusy()
        app.worker.execute {
            val result = runCatching {
                app.repository.commitUndo(prepared, admission = flow.admission) {
                    deleteFlow === flow && !flow.closed && !flow.binding.isStale()
                }
            }
            main.post {
                if (isDestroyed) return@post
                detailBusy = false
                flow.binding.endBusy()
                val error = result.exceptionOrNull()
                val outcome = result.getOrNull()
                val staleNow = app.repository.deletions.isStale(
                    word.entry.key, flow.binding.deletionCredential
                )
                when {
                    error != null -> toast("撤销失败：${error.message}")
                    // A REVOKED outcome is a committed write and is reported as such even if the word
                    // was deleted immediately afterwards; the card is still frozen, never re-enabled.
                    outcome == ExactUndoOutcome.REVOKED -> {
                        if (staleNow) flow.binding.invalidate()
                        close?.invoke()
                        toast(if (staleNow) "最近一次查询已撤销；该词随后已被删除"
                        else "最近一次查询已撤销")
                    }
                    staleNow -> {
                        flow.binding.invalidate()
                        toast("撤销失败：该词已删除，未撤销任何记录")
                    }
                    outcome == ExactUndoOutcome.REJECTED ->
                        toast("操作已取消，未撤销任何记录")
                    else -> toast("没有可撤销的查询（可能已被撤销）")
                }
                settleDeleteFlow(flow)
                releaseDetailBusy()
            }
        }
    }

    /** Second, explicit confirmation for the dangerous per-word full-history scope. */
    private fun showDeleteAllConfirm(flow: DeleteFlow, word: LearnedWord) {
        // Same identity gate as the undo confirmation: a late queued choice for a retired flow may
        // never mount the dangerous full-history dialog or rebind currentDialog.
        if (!isLiveDeleteFlow(flow)) return
        var proceed = false
        val dialog = DeleteScopeDialog.createDeleteAllConfirmDialog(this, word.entry.headword) {
            if (!isLiveDeleteFlow(flow)) return@createDeleteAllConfirmDialog
            proceed = true
            flow.currentDialog = null
            performDeleteAll(flow, word)
        }
        // The shared scope dialog predates favorites. Its dangerous confirmation must name the
        // favorite flag too, because this word's star is deleted with the rest of its learning
        // records (un-starring alone never deletes anything).
        dialog.setMessage(
            "将永久删除「${word.entry.headword}」的：\n" +
                "• 全部查询历史（包括已撤销的）\n" +
                "• 我的记忆笔记与掌握状态\n" +
                "• 该词的复习进度、反馈记录和待答卡片\n" +
                "• 该词的收藏星标（取消星标不会删除任何记录，但整词删除会一并移除）\n\n" +
                "此操作无法恢复。独立保存的个人词义不会被删除；原始词库、其他生词和已导出的 PDF " +
                "也不受影响。\n\n" +
                "如果只想移除最近一次查询，请取消后选择“仅撤销最近一次有效查询”。"
        )
        // Check again at the actual attach point, after the dialog and its listener exist.
        if (!isLiveDeleteFlow(flow)) return
        flow.currentDialog = dialog
        dialog.setOnDismissListener {
            if (deleteFlow === flow && flow.currentDialog === dialog) {
                flow.currentDialog = null
                if (!proceed) settleDeleteFlow(flow)
            }
        }
        dialog.show()
    }

    /**
     * Full-history delete for exactly this key. The repository keeps the original transaction,
     * rollback, change publication and deletion generation; only the pre-BEGIN admission is new.
     * REJECTED is reported as "not deleted", never as "already absent"; a committed DELETED is
     * reported as committed.
     */
    private fun performDeleteAll(flow: DeleteFlow, word: LearnedWord) {
        // A retired flow's confirmation never starts the transaction or mutates shared busy state.
        if (!isLiveDeleteFlow(flow)) return
        // Same per-flow one-shot rule as the exact branch: a replayed positive callback must not
        // settle the first queued full delete or invalidate its admission.
        if (flow.commitConsumed) return
        if (detailBusy) { settleDeleteFlow(flow); return }
        flow.commitConsumed = true
        detailBusy = true
        flow.binding.beginBusy()
        app.worker.execute {
            val result = runCatching {
                app.repository.deleteWord(word.entry.key, flow.binding.deletionCredential,
                    admission = flow.admission) {
                    deleteFlow === flow && !flow.closed && !flow.binding.isStale()
                }
            }
            main.post {
                if (isDestroyed) return@post
                detailBusy = false
                flow.binding.endBusy()
                val error = result.exceptionOrNull()
                val outcome = result.getOrNull()
                when {
                    error != null -> toast("删除失败：${error.message}")
                    outcome == DeleteOutcome.REJECTED -> toast("操作已取消，未删除任何记录")
                    else -> {
                        // Every settled result (STALE/DELETED/NOT_FOUND) leaves the acting binding and
                        // any current detail for this exact word bound to an old generation: freeze it
                        // so it can never be re-enabled, and clear the current ones still showing the
                        // old cache. A genuinely newer detail for the same word (fresh snapshot or a new
                        // active query) is preserved; other words' selections and inputs are untouched.
                        flow.binding.invalidate()
                        val cleared = clearStaleDetail(word.entry.key, flow.binding)
                        if (cleared && selectedKey == word.entry.key) selectedKey = null
                        toast(when (outcome) {
                            DeleteOutcome.STALE -> "未删除：该词已删除或已重新收录，请刷新后重新打开详情再操作"
                            DeleteOutcome.DELETED -> "已删除「${word.entry.headword}」及全部记录"
                            else -> "「${word.entry.headword}」已不在生词本"
                        })
                    }
                }
                settleDeleteFlow(flow)
                // The acting binding settled above; re-enable whichever details are now current so a
                // detail rebuilt/opened during this operation is no longer stuck on the shared busy flag.
                releaseDetailBusy()
            }
        }
    }

    /** Explicit page/stop teardown of an uncommitted delete flow: dismiss its dialog and refuse writes. */
    private fun closeDeleteFlow() {
        val flow = deleteFlow ?: return
        deleteFlow = null
        flow.closed = true
        flow.admission.invalidate()
        val dialog = flow.currentDialog
        flow.currentDialog = null
        if (dialog?.isShowing == true) dialog.dismiss()
        flow.binding.cancelConfirm()
    }

    /** Settles a flow that produced no dialog (or whose dialog already closed) without any write. */
    private fun settleDeleteFlow(flow: DeleteFlow) {
        flow.closed = true
        flow.admission.invalidate()
        if (deleteFlow === flow) deleteFlow = null
        flow.currentDialog = null
        flow.binding.cancelConfirm()
    }

    /** Ends a delete flow only when it belongs to this exact detail binding. */
    private fun closeDeleteFlowFor(binding: WordDetailBinding) {
        if (deleteFlow?.binding === binding) closeDeleteFlow()
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
                closeDeleteFlowFor(current)
                current.invalidate(); inlineDetail = null; cleared = true
            }
        }
        dialogDetail?.let { current ->
            if (current === acting ||
                (current.key == key && (current.isStale() ||
                    (current.snapshotVersion <= acting.snapshotVersion &&
                        current.deletionCredential <= acting.deletionCredential)))) {
                closeDeleteFlowFor(current)
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
        showPaperDialog(dialog, 640) {
            closeDeleteFlowFor(binding)
            if (dialogDetail === binding) dialogDetail = null
        }
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
    private fun study() {
        studyScreen.render(scrolled())
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
        val lookupLink = panel().apply {
            addView(text("查词与补词入口", 20f, bold = true))
            addView(text("日常查词已移到独立“查词”页；词义维护已移到“添加/补词”页。设置不再承载查词输入。无悬浮窗或屏幕捕获权限时，仍可在查词页手动输入查询。", 16f, Paper.secondary)); space(12)
            addView(button("打开查词页", true) { selectPage(MainPage.LOOKUP) })
            addView(button("打开添加/补词页") { selectPage(MainPage.PERSONAL_ENTRY) })
        }
        library.addView(lookupLink); library.space(16)
        val backup = panel().apply {
            addView(text("备份与恢复", 20f, bold = true)); space(8)
            addView(text("把本机学习数据（全部词条与事件，含已撤销历史、笔记、掌握状态、统一背词进度与个人补充词库）导出为一个 .shicibackup 文件，或用备份完整覆盖当前数据。文件只通过系统文档选择器创建或读取，不联网、不上传。", 16f, Paper.secondary)); space(12)
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
            addView(text("只在首页主动开启时申请悬浮窗、屏幕捕获及通知权限。全屏捕获可看到整个屏幕；划词时原页面始终显示，仅由透明笔迹层在这一次取词期间接收手势。", 16f, Paper.secondary)); space(12)
            stopCaptureButton = button("关闭悬浮查词") { stopCapture(); toast("已请求停止查词") }
            addView(stopCaptureButton); space(8)
            addView(text("关闭会释放捕获和浮层，不会撤销系统悬浮窗权限。若需撤销，请在系统设置中操作。", 16f, Paper.secondary)); divider()
            addView(text("数据保存在本机", 18f, bold = true))
            addView(text("屏幕画面仅在内存中短暂读取并本机识别，不写截图文件、不上传；一笔抬起自动查询，轻点/多指/取消不查询，多个词时选择其中一个词查询，也可在多词原文卡中选择“翻译整句”。查询记录、释义快照、个人笔记、收藏与掌握状态保存在本机数据库。PDF 写入你选择的位置。", 16f, Paper.secondary)); divider()
            addView(text("译句与网络", 18f, bold = true))
            addView(text("“译句”页的英文由 Google ML Kit 在设备本机翻译，本应用不上传原文或译文、不使用在线翻译或收费服务。首次使用需你明确确认后下载英中离线模型（仅 Wi-Fi，约 30 MB 仅为估计），本应用不会自动下载，也不会自动改用移动数据或云端翻译。ML Kit 可能为更新模型、诊断和统计而与 Google 通信并发送性能/使用指标，因此本应用并非“完全不联网”；清单中的网络权限仅用于该 SDK。设备需支持 Google Play 服务，模型能否下载与译文质量仍需实测。", 16f, Paper.secondary))
        }
        boundaries.addView(privacy); boundaries.space(16)
        val limits = panel().apply {
            addView(text("原型限制", 20f, bold = true)); space(8)
            addView(text("PDF 导出已接入 A4 复习版与自测版＋答案页；支持按首次收录日期（含首尾）、最近查询/查询次数排序、仅收藏，以及“当前筛选全部”或手动勾选当前结果。导出选项只保存在本次导出中，不是全局设置；PDF 不是备份。正式词典、发音、例句仍未实现；本地备份仅为完整覆盖恢复，不提供合并或云同步。", 16f, Paper.secondary))
            divider(); addView(text("本轮三功能仅完成代码接线与编译，未运行逻辑测试；未真机验证 PDF 版面/系统文件选择器、收藏交互、备份跨版本恢复、译句模型下载与真实质量，也未验证速度、断网初装 OCR、海文兼容性与长时间功耗。", 16f, Paper.warning))
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
            .setMessage("系统会授予本工具整个屏幕的捕获权限。屏幕画面仅在内存中短暂读取并本机识别，不上传；划词时原页面保持显示并继续运行，不跳转截图页。一笔抬起自动查询。关闭开关或通知中的停止会释放捕获和浮层。")
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

    private fun manualLookup(raw: String, replaces: String? = null, originalRaw: String = raw,
                             credentialOverride: Long? = null) {
        val token = manualGate.next()
        // Captured at query start and carried through display, save and retry; never refreshed later.
        // An explicit retry/collect from a still-current card may pass its own query credential.
        val deletionCredential = credentialOverride ?: app.repository.deletions.capture()
        app.worker.execute {
            if (!manualGate.accepts(token)) return@execute
            // One worker-side resolution for the original asset + local supplements; the current user
            // row is carried separately so the editor never has to parse the merged display text.
            val lookup = runCatching {
                // The committed revision is read immediately before and after the actual lookup. A
                // commit in between means the merged entry and the user row may belong to different
                // snapshots; such a read is rejected rather than mixed or relabelled later.
                val before = app.repository.changes.version
                val result = app.lookupWord(raw)
                val user = (result as? LookupResult.Match)?.let { app.repository.userEntry(it.form) }
                val after = app.repository.changes.version
                LookupRead(result, user, after, before == after)
            }
            main.post {
                if (isDestroyed || page != MainPage.LOOKUP || !manualGate.accepts(token)) return@post
                lookup.onSuccess { read ->
                    val result = read.result
                    when (result) {
                        LookupResult.Invalid -> toast("请输入一个有效英文词头")
                        is LookupResult.Missing -> {
                            // The result belongs to the world the query started in. A restore/delete
                            // that committed while the worker ran must reject it explicitly instead of
                            // displaying a card whose save would bind to the new world.
                            if (app.repository.deletions.isStale(result.form, deletionCredential)) {
                                toast("查询已失效（词条已删除或数据已恢复），请重新查询")
                            } else if (!read.isCurrent(app.repository.changes.version)) {
                                // A supplement (or any committed data) changed after this read: drawing
                                // the old result would hide the current user meaning, so no card is made.
                                showManualLookupExpired()
                            } else showManualMissing(result.form, raw, replaces, deletionCredential, read.revision)
                        }
                        is LookupResult.Match -> {
                            val entry = result.entries.single()
                            if (app.repository.deletions.isStale(entry.key, deletionCredential)) {
                                toast("查询已失效（词条已删除或数据已恢复），请重新查询")
                            } else if (!read.isCurrent(app.repository.changes.version)) {
                                showManualLookupExpired()
                            } else showManualDefinition(originalRaw, result.form, entry, read.user, replaces,
                                deletionCredential, read.revision)
                        }
                    }
                }.onFailure { toast("词库不可用：${it.message}；未生成释义或新增记录") }
            }
        }
    }

    /**
     * A lookup read before a committed change is never drawn as a writable card: its old meaning (and
     * the separately captured user row) could hide a current supplement. This window is read-only and
     * requires a fresh explicit query.
     */
    private fun showManualLookupExpired() {
        val content = panel()
        content.addView(text("查询已失效", 20f, bold = true))
        content.addView(text("查询期间词条或本机补充已更新。为避免显示或保存过期释义，本次旧结果已丢弃；请重新查询。", 16f, Paper.warning))
        content.space(8)
        lateinit var dialog: AlertDialog
        content.addView(button("关闭") { dialog.dismiss() })
        dialog = paperDialog(content)
        showPaperDialog(dialog, 640)
    }

    /**
     * The uncollected state of a manual lookup. Saving a new user headword here is not a query: it
     * creates no QueryEvent, count, vocabulary row or study card. Only the explicit "查询并收录"
     * action after a successful save enters the normal matched-card flow.
     */
    private fun showManualMissing(form: String, raw: String, replaces: String?, deletionCredential: Long,
                                  readRevision: Long) {
        // Display-entry check: a stale query must not create any writable card at all.
        if (app.repository.deletions.isStale(form, deletionCredential)) {
            toast("查询已失效（词条已删除或数据已恢复），请重新查询")
            return
        }
        var sourceRevision = readRevision
        val content = panel()
        content.addView(text(form, 28f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        content.addView(text("原始词库未收录这个词。可以只在本机新增用户词条；保存本身不会产生查询记录或生词。", 16f, Paper.secondary))
        val canAdd = PersonalDictionary.normalizeNewKey(form) == form
        if (!canAdd) {
            content.addView(text("该结果不是单个规范英文词头（可能是短语或多词），不能作为新词头；请修改为一个英文单词。", 15f, Paper.warning, true))
        }
        content.space(8)
        val meaningView = text("", 18f)
        val status = text("", 15f, Paper.secondary)
        val editorHost = column()
        var editorRoot: LinearLayout? = null
        var currentUser: UserDictionaryEntry? = null
        // This dialog owns only the supplement editors opened from it; a later window's editor lease
        // must survive this dialog's late dismiss.
        val editorOwner = SupplementEditorOwner()
        // Identity of this card's hand-off: only this card's own dismiss invalidates it.
        val handoffToken = Any()
        lateinit var dialog: AlertDialog
        lateinit var guard: ManualMissingGuard
        val collect = button("查询并收录", true) {
            // A restore/delete committed after this card appeared retires the card before any new
            // explicit query can be launched from it.
            if (app.repository.deletions.isStale(form, deletionCredential)) {
                invalidateManualMissing(guard); return@button
            }
            dialog.dismiss()
            // This is an explicit, user-initiated active query in the card's still-current world and
            // may enter personal vocabulary. It keeps the original query credential instead of
            // recapturing, so a later restore cannot be laundered into a fresh write identity.
            manualLookup(form, replaces, raw, deletionCredential)
        }.apply { visibility = View.GONE }
        lateinit var add: Button
        if (canAdd) {
            add = button("新增这个词", true) {
                // The editor is bound to this query's original credential, never a fresh capture at
                // display time: after a restore the old card cannot mint a writable new-world editor.
                if (app.repository.deletions.isStale(form, deletionCredential)) {
                    invalidateManualMissing(guard); return@button
                }
                add.isEnabled = false
                editorRoot?.let { editorHost.removeView(it) }
                editorRoot = addSupplementEditor(editorHost, editorOwner, form, form, null, currentUser,
                    deletionCredential, onSaved = { saved, merged ->
                        currentUser = saved
                        sourceRevision = app.repository.changes.version
                        meaningView.text = "${merged.partOfSpeech} ${merged.meaning}".trim()
                        status.text = "已保存到本机用户词库（仅本机；尚未收录为生词）"
                        status.setTextColor(Paper.green)
                        collect.visibility = View.VISIBLE
                        add.text = "重新编辑词义"
                        add.isEnabled = true
                    }, onClosed = { add.isEnabled = true }, newHeadword = true)
            }
            content.addView(add); content.space(4)
            // Explicit hand-off to the full page. It keeps this card's original credential and the
            // current user-row snapshot; the card's own owner is closed only after acceptance.
            content.addView(button("在补词页新增") {
                if (app.repository.deletions.isStale(form, deletionCredential)) {
                    invalidateManualMissing(guard); return@button
                }
                openPersonalEntryFromCard(
                    PersonalEntrySource(form, form, raw, null, currentUser,
                        deletionCredential, sourceRevision, newHeadword = true),
                    cardToken = handoffToken,
                    isCardLive = {
                        manualMissingGuard === guard && !guard.invalid && dialog.isShowing
                    }
                ) { dialog.dismiss() }
            }); content.space(4)
        }
        content.addView(collect); content.space(4)
        content.addView(meaningView); content.addView(status)
        content.addView(editorHost)
        dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("关闭", null).create()
        guard = ManualMissingGuard(form, deletionCredential, if (canAdd) add else null, collect, status) {
            closeSupplementEditorOwner(editorOwner)
        }
        // Closing the dialog ends only the editors this dialog opened, even before the detached-view callback.
        dialog.setOnDismissListener {
            cancelPendingPersonalHandoff(handoffToken)
            closeSupplementEditorOwner(editorOwner)
            if (manualMissingGuard === guard) manualMissingGuard = null
        }
        activeDialog?.dismiss(); activeDialog = dialog
        dialog.show()
        manualMissingGuard = guard
    }

    private fun showManualDefinition(raw: String, form: String, entry: DictionaryEntry,
                                      initialUser: UserDictionaryEntry?, replaces: String?,
                                      deletionCredential: Long, readRevision: Long) {
        var sourceRevision = readRevision
        val content = panel()
        content.addView(text(entry.headword, 28f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        val meta = text("原词形：$form · ${entry.partOfSpeech}")
        val meaningView = text(entry.meaning, 18f)
        val sourceView = text(PersonalDictionary.label(entry), 14f, Paper.secondary)
        val provenanceView = text(PersonalDictionary.provenance(entry), 14f, Paper.warning)
        content.addView(meta); content.addView(meaningView)
        content.addView(sourceView); content.addView(provenanceView)
        val status = text("保存中…", 16f, Paper.secondary); content.addView(status)
        val undo = button("撤销本次收录") {}; undo.isEnabled = false; content.addView(undo); content.space(8)
        val correct = button("纠错") {}; correct.isEnabled = false; content.addView(correct); content.space(8)
        val retry = button("重试保存") {}; retry.visibility = View.GONE; content.addView(retry)
        var currentUser = initialUser
        val supplementStatus = text("", 14f, Paper.secondary)
        val editorHost = column()
        var editorRoot: LinearLayout? = null
        // This card owns only the supplement editors opened from it, so a late dismiss of an older
        // card cannot close a newer card's live editor.
        val editorOwner = SupplementEditorOwner()
        // Identity of this card's hand-off: only this card's own dismiss invalidates it.
        val handoffToken = Any()
        lateinit var guard: ManualGuard
        val supplement = button("补充/编辑词义") {
            if (manualCardStale(guard)) { invalidateManualCard(guard); return@button }
            val original = if (entry.source == PersonalDictionary.SOURCE) null
            else PersonalDictionary.storedEntry(entry)
            editorRoot?.let { editorHost.removeView(it) }
            editorRoot = addSupplementEditor(editorHost, editorOwner, entry.key, entry.headword, original, currentUser,
                deletionCredential, onSaved = { saved, merged ->
                currentUser = saved
                sourceRevision = app.repository.changes.version
                meta.text = "原词形：$form · ${merged.partOfSpeech}"
                meaningView.text = merged.meaning
                sourceView.text = PersonalDictionary.label(merged)
                provenanceView.text = PersonalDictionary.provenance(merged)
                supplementStatus.text = "用户补充已保存到本机；本次查询记录不受影响"
                supplementStatus.setTextColor(Paper.green)
            })
        }
        content.addView(supplement)
        // Explicit hand-off to the full page. The card's original deletion credential and the
        // current user-row snapshot are kept; opening this page never captures a fresh identity.
        lateinit var dialog: AlertDialog
        content.addView(button("在补词页编辑") {
            if (manualCardStale(guard)) { invalidateManualCard(guard); return@button }
            val original = if (entry.source == PersonalDictionary.SOURCE) null
            else PersonalDictionary.storedEntry(entry)
            openPersonalEntryFromCard(
                PersonalEntrySource(entry.key, entry.headword, raw, original,
                    currentUser, deletionCredential, sourceRevision, newHeadword = false),
                cardToken = handoffToken,
                isCardLive = { manualGuard === guard && !guard.invalid && dialog.isShowing }
            ) { dialog.dismiss() }
        })
        content.addView(supplementStatus)
        content.addView(editorHost)
        dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).setNegativeButton("关闭", null).create()
        activeDialog?.dismiss(); activeDialog = dialog
        val token = manualGate.next()
        guard = ManualGuard(entry.key, deletionCredential, status, undo, correct, retry, supplement)
        dialog.setOnDismissListener {
            // Closing this card window closes only the editors it opened; a newer card's editor lease
            // stays live, so its own save is still admitted on its owner.
            cancelPendingPersonalHandoff(handoffToken)
            closeSupplementEditorOwner(editorOwner)
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
                                // The query record has committed; the displayed meaning snapshot is
                                // unchanged, so the card's source identity is this settled revision.
                                sourceRevision = app.repository.changes.version
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

    /**
     * Local supplement editor shared by the manual and floating entry points. It edits only the
     * user-owned POS/meaning: the original asset is shown read-only and never rewritten. Saving does
     * not record a query, advance study or change mastery/notes/review state. A stale dialog (restore,
     * deletion, or a newer supplement saved meanwhile) is rejected instead of overwriting newer data.
     */
    private fun addSupplementEditor(host: LinearLayout, owner: SupplementEditorOwner,
                                    entryKey: String, headword: String,
                                    original: DictionaryEntry?, currentUser: UserDictionaryEntry?,
                                    credential: Long,
                                    onSaved: (UserDictionaryEntry, DictionaryEntry) -> Unit,
                                    onClosed: () -> Unit = {},
                                    newHeadword: Boolean = false,
                                    initialPos: String? = null,
                                    initialMeaning: String? = null,
                                    expectedUpdatedAtOverride: Long? = null,
                                    showOriginal: Boolean = true,
                                    onDraftChanged: ((String, String) -> Unit)? = null): LinearLayout {
        // A window that has already been dismissed can never open a new writable editor, and neither
        // can a card whose query world (deletion/restore) has already been replaced. Return a
        // non-editable notice without registering a lease, so no late save can be admitted on it.
        val staleWorld = app.repository.deletions.isStale(entryKey, credential)
        if (owner.closed || staleWorld) {
            val closed = column().apply {
                space(8); divider()
                addView(text(if (owner.closed) "该窗口已关闭，请重新打开后再编辑"
                else "该词条已删除或数据已恢复，请重新查询后再编辑", 15f, Paper.warning, true))
            }
            host.addView(closed)
            onClosed()
            return closed
        }
        val content = column().apply {
            space(8); divider()
            addView(text("补充/编辑词义 · 仅本机", 19f, bold = true))
            addView(text("只编辑用户词性/释义；原词库只读，不会被覆盖，也不计入查询或学习。", 13f, Paper.secondary))
        }
        content.addView(text(headword, 18f, bold = true))
        if (showOriginal) original?.let {
            content.addView(text("原词库释义（只读）", 15f, bold = true))
            content.addView(text("${it.partOfSpeech} ${it.meaning}".trim(), 15f))
            content.addView(text(PersonalDictionary.provenance(it), 12f, Paper.warning))
        }
        val posInput = inputField("词性（可留空），如 n. / v.", singleLine = true).apply {
            setText(initialPos ?: currentUser?.partOfSpeech ?: "")
        }
        val meaningInput = inputField("用户补充释义（必填）", singleLine = false).apply {
            minLines = 3; gravity = Gravity.TOP
            setText(initialMeaning ?: currentUser?.meaning ?: "")
        }
        content.addView(posInput); content.space(4); content.addView(meaningInput)
        if (onDraftChanged != null) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onDraftChanged(posInput.text.toString(), meaningInput.text.toString())
                }
                override fun afterTextChanged(s: Editable?) {}
            }
            posInput.addTextChangedListener(watcher)
            meaningInput.addTextChangedListener(watcher)
        }
        val status = text("", 14f, Paper.secondary); content.addView(status)
        val saveButton = button("保存到本机", true) {}
        val cancelButton = button("取消") {}
        content.addView(saveButton); content.space(4); content.addView(cancelButton)
        host.addView(content)
        var alive = true
        var saving = false
        // Bound to the deletion/restore world the originating query started in, passed in explicitly.
        // Opening the editor never captures a fresh credential, so displaying an old card cannot
        // rebind it to the world created by a later restore.
        val editorLease = SupplementEditorLease(owner, StudyAdmission {
            !app.repository.deletions.isStale(entryKey, credential)
        })
        supplementEditorLeases += editorLease
        fun closeEditor() = closeSupplementEditor(editorLease)
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            // Detach ends this editor for good. Reattaching the same old view cannot revive it.
            override fun onViewAttachedToWindow(v: View) { alive = true }
            override fun onViewDetachedFromWindow(v: View) { alive = false; closeEditor() }
        })
        val expectedUpdatedAt = expectedUpdatedAtOverride ?: (currentUser?.updatedAt ?: 0L)
        saveButton.setOnClickListener {
            if (!alive || owner.closed || editorLease.closed || saving) return@setOnClickListener
            val meaning = meaningInput.text.toString()
            if (meaning.isBlank()) {
                status.text = "释义不能为空，未保存"; status.setTextColor(Paper.error)
                return@setOnClickListener
            }
            if (app.repository.deletions.isStale(entryKey, credential)) {
                status.text = "词条已删除或数据已恢复，请重新查询后再编辑"; status.setTextColor(Paper.error)
                return@setOnClickListener
            }
            saving = true
            saveButton.isEnabled = false; cancelButton.isEnabled = false
            status.text = "保存中…"; status.setTextColor(Paper.secondary)
            app.worker.execute {
                val result = app.repository.saveUserEntry(entryKey, posInput.text.toString(), meaning,
                    expectedUpdatedAt = expectedUpdatedAt, newHeadword = newHeadword,
                    admission = editorLease.admission)
                main.post {
                    // A closed/stopped owner suppresses its old UI. It never reports an already
                    // committed save as "not saved"; the admission result below is the truth.
                    if (!alive || owner.closed || editorLease.closed) return@post
                    saving = false; cancelButton.isEnabled = true
                    if (!result.saved) {
                        saveButton.isEnabled = true
                        status.text = result.message; status.setTextColor(Paper.error)
                        return@post
                    }
                    if (app.repository.deletions.isStale(entryKey, credential)) {
                        // The save was admitted and committed before this deletion/restore. Do not
                        // claim it was unwritten, and do not repaint the card from the old world.
                        saveButton.isEnabled = false
                        status.text = "保存已提交，但该词随后被删除或数据已恢复"
                        status.setTextColor(Paper.warning)
                        return@post
                    }
                    val saved = result.entry ?: return@post
                    val merged = PersonalDictionary.merge(original, saved) ?: PersonalDictionary.userOnly(saved)
                    onSaved(saved, merged)
                    status.text = if (result.changed) "已保存到本机用户词库" else "词义未变化，未重复保存"
                    status.setTextColor(Paper.green)
                    // A successful save replaces the old generation; reopen to edit the new text.
                    saveButton.isEnabled = false
                }
            }
        }
        cancelButton.setOnClickListener {
            alive = false
            closeEditor()
            host.removeView(content)
            if (!saving) onClosed()
        }
        return content
    }
    // A manual card is out of date once it is invalidated or its word was deleted after the query
    // credential was captured; both must win over any late success/failure result.
    private fun manualCardStale(guard: ManualGuard): Boolean =
        guard.invalid || app.repository.deletions.isStale(guard.key, guard.credential)
    private fun invalidateManualCard(guard: ManualGuard) {
        guard.invalid = true
        guard.status.text = "未收录：该词已删除或查询已失效"; guard.status.setTextColor(Paper.error)
        guard.undo.isEnabled = false; guard.correct.isEnabled = false; guard.retry.visibility = View.GONE
        guard.supplement.isEnabled = false
    }
    // Retires an open uncollected card without any write path: both the new-headword editor and the
    // explicit collect action stay unavailable once the query's world has been replaced.
    private fun invalidateManualMissing(guard: ManualMissingGuard) {
        guard.invalid = true
        guard.add?.apply { isEnabled = false; text = "查询已失效" }
        guard.collect.isEnabled = false
        guard.status.text = "该词条已删除或数据已恢复，请重新查询"
        guard.status.setTextColor(Paper.error)
        guard.closeEditor()
    }
    private fun export() {
        if (lostExportRequestCode >= 0 || cleaningLostExport) return
        // An explicitly emptied selection — or one fully excluded by the current filter — is a real
        // zero scope. Refuse before any session, repository read, SAF picker or file work; it is
        // never promoted to "all". The visible button is disabled for the same state.
        if (exportSelectionExplicit && exportScopeCount() == 0) {
            toast(
                if (exportSelection.isEmpty())
                    "已清空勾选（0 个）：请先勾选，或点“使用当前结果全部（不勾选）”"
                else "当前勾选与筛选没有交集：请调整勾选或筛选"
            )
            updateExportButton(); return
        }
        // No picker for an empty current filter; the worker re-check below covers a stale cache.
        if (filteredWords().isEmpty()) { toast("当前筛选没有可导出的生词"); return }
        val request = exportSession.begin(search) ?: return
        updateExportButton()
        // One local zone and one defensive copy of the UI selection belong to this request. The
        // repository read below is the only source of words; the UI cache is never exported.
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        val requestedKeys = exportScopeKeys()
        // Freeze the current "仅收藏" filter with this request; later toggle changes cannot silently
        // widen or narrow what the confirmation panel first showed.
        val requestedFavoritesOnly = onlyFavorites
        app.worker.execute {
            val result = runCatching { app.repository.words(request.search) }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { loaded ->
                    if (loaded.isEmpty()) {
                        exportSession.cancel(request.id); updateExportButton()
                        toast("没有可导出的生词"); return@onSuccess
                    }
                    // Favorites come from the very same returned rows as the words being filtered
                    // and sorted, so the filter, sort and favorite set cannot disagree.
                    val favorites = loaded.asSequence().filter { it.favorite }
                        .map { it.entry.key }.toHashSet()
                    val initialOptions = ExportOptions(
                        favoritesOnly = requestedFavoritesOnly,
                        selectedKeys = requestedKeys,
                        zone = zone
                    )
                    val initial = LearningExportSelection.snapshot(
                        loaded, request.search, date, initialOptions, favorites
                    )
                    if (!exportSession.freeze(request, initial)) return@onSuccess
                    if (initial.words.isEmpty()) {
                        exportSession.cancel(request.id); updateExportButton()
                        toast("当前选择与筛选没有可导出的生词"); return@onSuccess
                    }
                    // Pass this request's own frozen selection into the panel. The global list can
                    // still be edited while the worker reads; it must never rebind this request.
                    confirmExport(
                        request, loaded, favorites, date, zone,
                        requestedFavoritesOnly, requestedKeys
                    )
                }.onFailure {
                    if (exportSession.cancel(request.id)) {
                        updateExportButton(); toast("读取导出数据失败：${it.message}")
                    }
                }
            }
        }
    }
    private fun confirmExport(
        request: LearningExportSession.Request,
        loadedWords: List<LearnedWord>,
        favorites: Set<String>,
        date: LocalDate,
        zone: ZoneId,
        favoritesOnlyAtRequest: Boolean,
        selectedKeysAtRequest: Set<String>?
    ) {
        val content = panel()
        val dialog = paperDialog(content)
        content.addView(text("导出生词", 24f, bold = true)); content.space(8)
        content.addView(text("内容已从本机数据库读取为一份内存快照。确认后即使用户再搜索、星标或后台刷新，也不会替换本次已选内容。", 14f, Paper.secondary))
        content.space(12)
        // The panel opens with this request's captured selection, not a fresh read of the mutable
        // list. Later checkbox edits in the vocabulary list cannot rebind an in-flight request;
        // edits made inside this panel still flow into the refreeze below.
        val selectedKeysAtOpen = selectedKeysAtRequest
        val optionsView = ExportOptionsView(this).apply {
            setFavoritesAvailable(true)
            setOptions(ExportOptions(
                favoritesOnly = favoritesOnlyAtRequest,
                selectedKeys = selectedKeysAtOpen,
                zone = zone
            ))
            setSelectedKeys(selectedKeysAtOpen)
        }
        content.addView(optionsView); content.space(12)
        val summary = text("", 16f); content.addView(summary)
        content.space(8)
        content.addView(text("复习版只输出词条与释义；自测版先输出不含释义/笔记的题面，再从新页输出同编号、同顺序的完整答案页。PDF 不是备份，不会改动词库或已导出文件。", 13f, Paper.secondary))
        content.space(16)
        val choose: Button = button("选择保存位置", true) {
            val checked = optionsView.readOptions()
            val options = checked.options
            if (options == null) {
                toast(checked.error ?: "导出选项无效")
                return@button
            }
            // Rebuild from the frozen in-memory rows with the exact validated options (including a
            // defensive copy of selectedKeys and the local zone). No repository re-read happens now.
            val frozenSelection = LearningExportSelection.snapshot(
                loadedWords, request.search, date, options, favorites
            )
            if (frozenSelection.words.isEmpty()) {
                toast("当前选项与筛选没有可导出的生词，未创建文件")
                return@button
            }
            if (!exportSession.refreeze(request, frozenSelection)) {
                toast("导出已失效，请关闭后重新开始")
                return@button
            }
            val frozen = exportSession.choose(request) ?: return@button
            dialog.dismiss()
            val base = when (frozen.options.mode) {
                ExportMode.SELF_TEST -> "拾词-生词自测单"
                ExportMode.REVIEW -> "拾词-生词记忆单"
            }
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_TITLE, "$base-${frozen.date}.pdf")
            runCatching { startActivityForResult(intent, request.id) }.onFailure {
                exportSession.cancel(request.id); toast("无法打开文件保存：${it.message}")
            }
            updateExportButton()
        }
        fun updateSummary(validation: ExportOptionsView.Validation) {
            val options = validation.options
            if (options == null) {
                summary.text = "无法导出：${validation.error ?: "选项无效"}"
                summary.setTextColor(Paper.error)
                choose.isEnabled = false
                return
            }
            val selected = LearningExportSelection.select(loadedWords, options, favorites)
            val dates = when {
                options.from != null && options.to != null ->
                    "首次收录 ${options.from} 至 ${options.to}（含首尾自然日）"
                options.from != null -> "首次收录 ${options.from} 起（含当天）"
                options.to != null -> "首次收录至 ${options.to}（含当天）"
                else -> "不限首次收录日期"
            }
            val mode = if (options.mode == ExportMode.SELF_TEST) "自测版＋答案页" else "复习版"
            val sort = if (options.sort == ExportSort.QUERY_COUNT) "查询次数优先" else "最近查询优先"
            val scope = if (options.selectedKeys == null)
                "当前筛选全部（${loadedWords.size} 个搜索候选${if (options.favoritesOnly) "，仅收藏" else ""}）"
            else "手动勾选 ${options.selectedKeys.size} 个（与当前筛选/仅收藏/日期取交集）"
            summary.text = "内容：$mode\n日期：$dates\n排序：$sort\n范围：$scope\n预计导出：${selected.size} 词"
            summary.setTextColor(if (selected.isEmpty()) Paper.warning else Paper.ink)
            choose.isEnabled = selected.isNotEmpty()
        }
        optionsView.onValidationChanged = { updateSummary(it) }
        content.addView(pair(
            button("取消") { dialog.dismiss() }.apply {
                background = shape(Paper.background, 8); setTextColor(Paper.ink)
            },
            choose,
            inlineAt(minOf(resources.configuration.screenWidthDp - 32, 640) - 48), 12
        ))
        showPaperDialog(dialog, 640) {
            if (exportDialog === dialog) exportDialog = null
            if (exportSession.cancelPreparation(request.id)) updateExportButton()
        }
        exportDialog = dialog
        updateSummary(optionsView.readOptions())
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
                output.use { PdfExporter.write(snapshot, it) }
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
        if (backup.hasFavorites) {
            content.addView(text("收藏星标：${backup.favoriteCount} 个当前列表可见词条（隐藏词上的星标仍会随备份写回）", 18f))
        }
        if (backup.legacyNoStudyData) {
            content.addView(text("这是旧版格式（v1）备份：只含生词与查询记录，不含背词进度和个人补充词库。", 16f, Paper.warning, true))
            content.addView(text("恢复后背词进度为空、个人补充词库为空，所选范围与每日目标恢复为默认值。", 16f, Paper.warning))
        } else {
            content.addView(text("背词进度：已学 ${backup.stateCount} 词 · 反馈 ${backup.reviewCount} 条${if (backup.data.session != null) " · 含 1 张未答卡" else ""}", 18f))
            if (backup.noPersonalDictionary) {
                content.addView(text("这是 v2 备份：不含个人补充词库。恢复会清空当前本机补充词条，不会与备份隐式合并。", 16f, Paper.warning, true))
            } else {
                content.addView(text("个人补充词库：${backup.userEntryCount} 个词条（含未产生查询记录的新词头）", 18f))
            }
        }
        if (backup.noFavorites) {
            content.addView(text("这是 v1/v2/v3 旧版备份：不含收藏星标。完整恢复会清空当前本机的全部收藏，且不会按同名词条隐式保留；如需保留当前收藏，请先另存当前版本备份。", 16f, Paper.warning, true))
        }
        content.addView(text("备份应用版本：${backup.appVersionName}（${backup.appVersionCode}）", 16f, Paper.secondary)); content.space(12)
        val warning = column().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12)); background = shape(Paper.background, 8)
            addView(text(if (backup.wordCount == 0 && backup.eventCount == 0 &&
                    backup.stateCount == 0 && backup.userEntryCount == 0 &&
                    backup.favoriteCount == 0 && backup.data.session == null)
                "这是一个空备份：确认恢复后会清空当前全部学习数据、收藏星标和个人补充词库。" else
                "恢复会用备份内容完整替换当前全部学习数据、收藏星标与个人补充词库（不合并、不猜测冲突）；备份里没有的词、查询记录、收藏和补充词条也会被移除。",
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
                            toast("已完整恢复：替换为备份中的 ${backup.wordCount} 个词、${backup.eventCount} 条事件、${backup.userEntryCount} 条用户补充")
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
