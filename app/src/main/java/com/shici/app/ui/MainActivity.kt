package com.shici.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.accessibility.AccessibilityManager
import android.widget.*
import com.shici.app.ShiciApplication
import com.shici.app.BuildConfig
import com.shici.app.R
import com.shici.app.capture.*
import com.shici.app.core.*
import com.shici.app.update.*
import com.shici.app.data.DeleteOutcome
import com.shici.app.data.ExactUndoOutcome
import com.shici.app.data.PreparedUndo
import com.shici.app.data.RestoreOutcome
import com.shici.app.data.RevokeOutcome
import com.shici.app.data.UndoPrepareOutcome
import com.shici.app.data.WordDetailSaveOutcome
import com.shici.app.export.PdfExporter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.RejectedExecutionException

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
    val stable: Boolean,
    val users: Map<String, UserDictionaryEntry?> = emptyMap()
) {
    fun isCurrent(now: Long): Boolean = stable && revision == now
}

class MainActivity : Activity() {
    companion object {
        private const val OVERLAY_PERMISSION = 10
        private const val CAPTURE_PERMISSION = 11
        private const val NOTIFICATION_PERMISSION = 12
        // Row-menu item ids for the vocabulary list (see showVocabularyRowMenu).
        private const val MENU_FAVORITE = 1
        private const val MENU_REMOVE = 2
        private const val VOCABULARY_PAGE_SIZE = 32
        private const val SAVED_BACKUP_REQUEST_LIMIT = 64
        private const val DOCUMENT_TIMEOUT_MS = 120_000L
        private const val BACKUP_PICKER_TIMEOUT_MS = 600_000L
        private const val DOCUMENT_REJECTED = "文档操作繁忙或此文件仍在使用，本次未处理；请稍后重试"
        private const val BACKUP_PRIVACY = "备份是未加密的明文。拾词不会主动上传；若选择云盘位置，提供方可能上传或同步文件。敏感备份建议存到本机。"
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
        // The navigation label names the optional component explicitly; the stable id stays
        // TRANSLATION so saved state, tests and legacy entry points keep their identity.
        TRANSLATION("离线 AI 翻译", -1),
        VOCABULARY("生词本", 1),
        // Visible label only: the stable route/persistence id stays STUDY and legacyTab stays 4.
        STUDY("复习", 4),
        STATISTICS("统计", 2),
        PERSONAL_ENTRY("添加/补词", -1),
        SETTINGS("设置", 3),
        // Repeatable usage guide. It is a stable page name with no historical tab index; the
        // legacy 0..4 mapping above is untouched, so an old saved tab can never become the guide.
        GUIDE("使用引导", -1);

        companion object {
            // Visible main order requested for this integration round. Identity stays the enum
            // name/legacyTab, so only display order changes; settings remains the separate fixed
            // entry supplied by the navigation component.
            val mainPages = listOf(
                HOME, LOOKUP, VOCABULARY, STUDY, PERSONAL_ENTRY, TRANSLATION, STATISTICS, GUIDE
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
    // ---- Update check ------------------------------------------------------------------------
    // The coordinator owns the network thread, the persisted interval and every piece of update
    // state. This Activity only renders it: a non-blocking bar above the page body and the section
    // in 设置 → 关于. None of this touches lookup, study, backup or SQLite state.
    private var updateCenter: UpdateCenter? = null
    private lateinit var updateBannerHost: LinearLayout
    private var updateStateView: TextView? = null
    // Dismissal is per version for this Activity; the settings entry is never removed.
    private var dismissedUpdateVersion = -1
    private var pendingUpdateScroll = false
    @Volatile private var page = MainPage.HOME
    // True while this Activity is started. Favorite writes are admitted on the worker thread and use
    // this plus [interactionEpoch] to refuse a mutation queued by a page that has since stopped or
    // navigated away. A background snapshot refresh never changes either value.
    @Volatile private var foreground = false
    @Volatile private var interactionEpoch = 0L
    // Vocabulary page filter/selection. They live in fields (not in rebuilt views) so an ordinary
    // snapshot refresh or a split-detail rebuild keeps the user's search, filter, checks and detail.
    private var onlyFavorites = false
    // Display order for the vocabulary list. It lives in a field (not in the rebuilt strip) so a
    // search/favorite change, a snapshot refresh or rotation keeps the user's chosen order. It only
    // reorders the already-filtered snapshot; it never changes the export selection or any count.
    private var vocabularySort = VocabularySort.RECENT_FIRST
    private val exportSelection = linkedSetOf<String>()
    // Export scope intent, kept together with [exportSelection] across refreshes, search/favorite
    // filters, navigation and rotation. `false` is the untouched default: no checkbox has ever been
    // edited for this page, so the export means "everything surviving the current filter"
    // (`selectedKeys = null`). Once the user checks, unchecks, selects all or clears, this becomes
    // `true` and [exportSelection] is the explicit allow-list: an empty set is an explicit zero and
    // must never fall back to "all". Only the visible "use all current results" action resets it.
    private var exportSelectionExplicit = false
    private var exportScopeNotRestored = false
    // Compact vocabulary filter/sort/manage toolbar and the collapsible export-selection panel.
    // [vocabularyManaging] only decides whether the export controls and row checkboxes are shown; it
    // never edits the selection intent, the search/favorite filter or any learning data.
    private var vocabularyToolbar: VocabularyToolbar? = null
    private var selectionPanel: LinearLayout? = null
    // Scroll container that holds the toolbar, the manage panel and the rows (the left column in
    // split layout). Used only to bring the panel into view when managing starts.
    private var vocabularyScroll: ScrollView? = null
    private var vocabularyManaging = false
    private var vocabularyPage = 0
    private var vocabularyPageAnchor: String? = null
    private var restoredVocabularyScroll: Int? = null
    private var hasLearningSnapshot = false
    private class VocabularyFilter(val source: List<LearnedWord>, val search: String, val favorites: Boolean,
                                   val matches: List<LearnedWord>)
    private var vocabularyFilter: VocabularyFilter? = null
    private var vocabularyOrder: Pair<VocabularySort, List<LearnedWord>>? = null
    // The single live animated guide page. Only one may exist: a rebuild/re-entry pauses and closes
    // the old one before a new screen is created, so no detached instance keeps animating.
    private var guideScreen: UsageGuideScreen? = null
    private enum class PracticeStep { LOOKUP, INPUT, WAIT_RESULT, CLOSE_RESULT, VOCABULARY, STUDY, COMPLETE, CAPTURE }
    private var practiceStep: PracticeStep? = null
    private var practiceCoach: PracticeCoach? = null
    private var practiceGeneration = 0L
    private class PracticeLookupOwner(val generation: Long, val target: View)
    private var pendingCapturePractice = false
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
    private var draftOwner = UUID.randomUUID().toString()
    private var draftSnapshotId: String? = null
    private data class RetainedDraft(val owner: String, val id: String, val state: ActivityDraftState)
    private var retainedDraft: RetainedDraft? = null
    // Lookup page draft: the text and selection live in fields so a background snapshot refresh
    // (which deliberately does not re-render this page) and rotation cannot destroy the user's input.
    private var lookupQuery = ""
    private var lookupSelectionStart = 0
    private var lookupSelectionEnd = 0
    private var lookupInputView: EditText? = null
    private var lookupPracticeArea: View? = null
    // Personal-entry page: the page owns one editor generation at a time. A source captured from a
    // lookup card keeps that card's original deletion credential and user-row snapshot; a direct
    // navigation read is the only path allowed to capture a fresh credential, at the explicit action.
    private class PersonalEntrySource(
        val entryKey: String, val headword: String, val raw: String,
        val original: DictionaryEntry?, var user: UserDictionaryEntry?,
        val credential: Long, var readRevision: Long, val newHeadword: Boolean
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
    private enum class BackupPicker(val stateKey: String) {
        IMPORT("backup_import_requests"), EXPORT("backup_create_requests"),
        SAFETY("backup_safety_requests"), LEGACY("backup_requests")
    }
    // Only bounded codes and kinds survive recreation, never a URI, payload or private file path.
    private val interruptedDocumentRequests = mutableMapOf<Int, BackupPicker>()
    private val documentOperations = mutableMapOf<Int, DocumentOperation>()
    private var backupRequestHighWatermark = -1
    private var backupExportButton: Button? = null
    private var restoreButton: Button? = null
    private var backupStatusView: TextView? = null
    // Settings body is a pure-UI component; the Activity still owns every backup/capture view and
    // async callback that is mounted into its hosts. Cleared on each render with the other hosts.
    private var settingsScreen: SettingsScreen? = null
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
    private var vocabularyCountView: TextView? = null
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
    private var dictionarySummary = "词库正在后台校验…"
    private var dictionaryDetail = "校验完成后显示词头与词形别名数量。"
    private var dictionaryStatusView: TextView? = null
    private var dictionaryDetailView: TextView? = null
    private var dictionaryRetryButton: Button? = null
    private var lookupDictionaryHost: LinearLayout? = null
    private var dictionaryAttempt: ShiciApplication.LookupDictionaryAttempt? = null
    private var dictionaryStatusLoading = false
    private var lookupDictionaryRetryShown = false
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
    private val wide get() = resources.configuration.screenWidthDp >= 840 &&
        resources.configuration.screenWidthDp / resources.configuration.fontScale >= 650
    private val contentWidthDp get() = resources.configuration.screenWidthDp - (if (wide) 184 + 48 else 32)
    private val splitContent get() = wide && contentWidthDp >= 660 * resources.configuration.fontScale
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
        val savedDraftOwner = savedInstanceState?.getString("draft_owner")
        val savedDraftId = savedInstanceState?.getString("draft_snapshot")
        val usesDraftStore = savedInstanceState?.containsKey("draft_snapshot") == true
        val restored = if (savedDraftId != null && savedDraftOwner != null &&
            runCatching { UUID.fromString(savedDraftOwner).toString() == savedDraftOwner }.getOrDefault(false)) {
            draftOwner = savedDraftOwner
            draftSnapshotId = savedDraftId
            val retained = lastNonConfigurationInstance as? RetainedDraft
            retained?.takeIf { it.owner == draftOwner && it.id == savedDraftId }?.state
                ?: ActivityDraftState.Store(this, draftOwner).read(savedDraftId)
        } else null
        val searchNotRestored = usesDraftStore && restored?.search == null &&
            savedInstanceState?.getBoolean("draft_search_empty") != true
        search = (if (usesDraftStore) restored?.search else savedInstanceState?.getString("search")) ?: ""
        lookupQuery = (if (usesDraftStore) restored?.lookup else savedInstanceState?.getString("lookup_query")) ?: ""
        lookupSelectionStart = savedInstanceState?.getInt("lookup_sel_start", lookupQuery.length) ?: lookupQuery.length
        lookupSelectionEnd = savedInstanceState?.getInt("lookup_sel_end", lookupSelectionStart) ?: lookupSelectionStart
        translationInput = (if (usesDraftStore) restored?.translation else savedInstanceState?.getString("translation_draft")) ?: ""
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
        // An unknown/absent/old name falls back to the default instead of failing the restore.
        vocabularySort = savedInstanceState?.getString("vocabulary_sort")
            ?.let { name -> VocabularySort.entries.firstOrNull { it.name == name } }
            ?: VocabularySort.RECENT_FIRST
        vocabularyManaging = savedInstanceState?.getBoolean("vocabulary_managing", false) ?: false
        vocabularyPage = if (searchNotRestored) 0 else savedInstanceState?.getInt("vocabulary_page", 0) ?: 0
        vocabularyPageAnchor = restored?.pageAnchor?.takeUnless { searchNotRestored }
        restoredVocabularyScroll = if (searchNotRestored) null else savedInstanceState
            ?.takeIf { it.containsKey("vocabulary_scroll") }?.getInt("vocabulary_scroll")
        selectedKey = restored?.selectedKey
        val restoredSelection = if (usesDraftStore) {
            if (savedInstanceState?.getBoolean("draft_selection_empty") == true) emptySet()
            else restored?.exportSelection
        } else savedInstanceState?.getStringArrayList("export_selection")
        restoredSelection?.let { exportSelection.addAll(it) }
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
        exportScopeNotRestored = savedInstanceState?.getBoolean("export_scope_not_restored") == true ||
            searchNotRestored || (usesDraftStore && exportSelectionExplicit && restoredSelection == null)
        if (exportScopeNotRestored) {
            exportSelection.clear()
            exportSelectionExplicit = true
        }
        personalHeadword = (if (usesDraftStore) restored?.personalHeadword else savedInstanceState?.getString("pe_headword")) ?: ""
        if (page == MainPage.PERSONAL_ENTRY) {
            val hasDraft = savedInstanceState?.getBoolean("pe_has_draft", false) == true
            if (hasDraft) {
                val draft = if (usesDraftStore) restored?.personalDraft else PersonalEntryDraft(
                    savedInstanceState.getString("pe_draft_headword") ?: personalHeadword,
                    savedInstanceState.getString("pe_draft_pos") ?: "",
                    savedInstanceState.getString("pe_draft_meaning") ?: "",
                    savedInstanceState.getLong("pe_draft_base", 0L),
                    savedInstanceState.getBoolean("pe_draft_new", false)
                )
                if (draft != null) personalHeadword = draft.headword
                // A restored draft is never an automatic write identity: the old editor/lease is
                // gone, so the page asks for a fresh user-triggered read before any editing.
                personalRestoredDraft = draft
                personalStatusText = if (draft != null) "页面已重建，草稿已保留；请重新读取当前词义再确认。"
                    else "未能恢复补词草稿；请重新输入，不会自动保存。"
            } else if (personalHeadword.isNotBlank()) {
                personalStatusText = "页面已重建，请重新读取当前词义再继续。"
            }
        }
        consentGeneration = savedInstanceState?.getLong("consent", -1L) ?: -1L
        lostExportRequestCode = savedInstanceState?.getInt("export_request", -1) ?: -1
        if (lostExportRequestCode >= 0) LearningExportSession.reserveRequestCode(lostExportRequestCode)
        backupRequestHighWatermark = savedInstanceState?.getInt("backup_request_high_watermark", -1) ?: -1
        if (backupRequestHighWatermark >= 0) LearningExportSession.reserveRequestCode(backupRequestHighWatermark)
        BackupPicker.entries.forEach { picker ->
            savedInstanceState?.getIntArray(picker.stateKey)?.forEach { code ->
                if (code >= 0) {
                    LearningExportSession.reserveRequestCode(code)
                    backupRequestHighWatermark = maxOf(backupRequestHighWatermark, code)
                    if (interruptedDocumentRequests.size < SAVED_BACKUP_REQUEST_LIMIT) {
                        interruptedDocumentRequests.putIfAbsent(code, picker)
                    }
                }
            }
        }
        buildShell()
        if (usesDraftStore) {
            val lost = buildList {
                if (restored?.lookup == null) add("查词草稿")
                if (restored?.translation == null) add("翻译草稿")
                if (searchNotRestored) add("生词筛选")
                if (restored == null || restored.personalHeadword == null ||
                    (savedInstanceState?.getBoolean("pe_has_draft") == true && restored.personalDraft == null)) add("补词草稿")
                if (exportScopeNotRestored) add("PDF 范围（未恢复时不导出）")
            }
            if (lost.isNotEmpty()) toast("未能恢复：${lost.joinToString("、")}。请重新输入或选择。")
        }
    }
    override fun onStart() {
        super.onStart()
        foreground = true
        // Handler delays use uptime; reconcile elapsed deadlines after sleep/backgrounding too.
        documentOperations.values.toList().forEach { if (!it.isCurrent()) it.timeout.run() }
        refreshGate.start()
        CaptureStatus.observe(statusListener)
        app.repository.changes.observe(dataListener)
        // A stopped personal-entry page kept its draft text but lost its editor lease. Re-render the
        // page in the explicit re-read state instead of leaving a disabled, closed editor on screen.
        if (page == MainPage.PERSONAL_ENTRY && personalNeedsResume) {
            personalNeedsResume = false
            personalStatusText = if (personalRestoredDraft != null)
                "草稿已保留；请重新读取当前词义再确认。"
            else "请重新读取当前词义后再继续。"
            render()
        }
        // A stopped translation page was detached and its ML Kit owner closed. Rebuild it from the
        // retained draft with a fresh owner; opening it only checks the local model state and never
        // starts a translation or a model download by itself.
        if (page == MainPage.TRANSLATION && translationNeedsResume) {
            translationNeedsResume = false
            render()
        }
        // Returning from a system permission page updates only the two status badges; it does not
        // rebuild the page, collapse the expanded details or replace Activity-owned hosts.
        if (page == MainPage.SETTINGS) {
            settingsScreen?.updatePermissionStatus(
                overlayPermissionStatus(), notificationPermissionStatus()
            )
            // Direct reading may have been enabled/disabled or disconnected while the user was in
            // Android accessibility settings; only the small status block is refreshed.
            settingsScreen?.updateAccessibilityStatus(accessibilityStatus())
        }
        // Only the page that is currently showing may run its single demo animation. The screen was
        // created by render() during onCreate (before onStart) or by a page switch while started.
        if (page == MainPage.GUIDE) guideScreen?.onHostResume()
        refresh()
        loadDictionaryStatus()
        // Foreground-only, frequency-limited version check on its own thread. It never blocks this
        // page, never opens a modal and never touches lookup/study/backup state.
        updateCenter().onForeground()
        renderUpdateBanner()
        // Returning from the system "install unknown apps" page may have changed the decision.
        if (page == MainPage.SETTINGS) renderUpdateSection()
    }
    override fun onStop() {
        // No coach can cover another app or a system permission window.
        closePracticeCoach()
        practiceStep = null
        practiceGeneration++
        // Refuse every favorite mutation that was queued by a surface that is no longer in front.
        foreground = false
        interactionEpoch++
        // Cancel the guide's single bounded animator; nothing keeps drawing in background. The
        // instance stays for a later onStart resume, and is closed on leave/rebuild/destroy.
        guideScreen?.onHostPause()
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
        // A live system picker stops this Activity itself. Retire only the pre-picker snapshot
        // and import/preview preparations here; writes/apply have their own deadline and owner.
        backupExportSession.preparingRequestCode?.let { documentOperations[it]?.let(::cancelDocument) }
        if (backupExportSession.cancelPreparation()) updateBackupButtons()
        restoreSession.readingRequestCode?.let { documentOperations[it]?.let(::cancelDocument) }
        if (restoreSession.cancelPreparation()) {
            restorePreviewDialog?.dismiss(); restorePreviewDialog = null; updateBackupButtons()
        }
        CaptureStatus.remove(statusListener)
        // Leaving the app drops any in-flight check result and cancels a running download; the
        // partial file is deleted and the release stays offerable after the next check.
        updateCenter?.onBackground()
        super.onStop()
    }
    override fun onDestroy() {
        closePracticeCoach(); practiceStep = null; pendingCapturePractice = false
        foreground = false
        interactionEpoch++
        // Idempotent: also covers a destroy that never received onStop. The translator, its pending
        // callbacks and the async result window are all retired before this Activity is gone.
        captureTranslationDraft()
        closeTranslationOwner()
        refreshGate.stop(); exportSession.clear(); manualGate.next()
        backupExportSession.clear(); restoreSession.clear()
        documentOperations.values.toList().forEach(::cancelDocument)
        personalGate.next(); personalOpenGate.next()
        closeDeleteFlow()
        cancelPendingPersonalHandoff()
        // Close an already-queued supplement save even if this page never received onStop.
        closeSupplementEditors()
        closePersonalEntryOwner(keepDraft = false)
        // Invalidate the study surface's cross-thread admission before the Activity is gone. Workers
        // that already reached their transaction may finish atomically; ones still reading refuse it.
        if (studyScreenDelegate.isInitialized()) studyScreen.onDestroy()
        // Permanent, idempotent stop; the Activity drops its only reference to the old screen.
        guideScreen?.close(); guideScreen = null
        restorePreviewDialog?.dismiss(); restorePreviewDialog = null
        activeDialog?.dismiss(); activeDialog = null
        // Rotation and a stopped task still own their saved state. A genuine finish retires it.
        if (isFinishing && draftSnapshotId != null) ActivityDraftState.Store(this, draftOwner).delete()
        updateCenter?.close(); updateCenter = null
        super.onDestroy()
    }
    @Deprecated("Legacy Activity back handling is retained")
    override fun onBackPressed() {
        if (practiceStep != null) { endPractice(false); return }
        // Back from any functional page returns home and closes that page's editor generation;
        // pressing back on home keeps the platform exit behavior.
        if (page != MainPage.HOME) {
            selectPage(MainPage.HOME)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
    private fun captureDraftState(): ActivityDraftState {
        captureLookupDraft()
        captureTranslationDraft()
        return ActivityDraftState(search, lookupQuery, translationInput, personalHeadword,
            if (page == MainPage.PERSONAL_ENTRY) personalDraft ?: personalRestoredDraft ?: personalDraftConfirm?.draft else null,
            exportSelection.toSet(), selectedKey, vocabularyPageAnchor)
    }

    override fun onRetainNonConfigurationInstance(): Any? = retainedDraft

    override fun onSaveInstanceState(outState: Bundle) {
        val state = captureDraftState()
        val id = UUID.randomUUID().toString()
        draftSnapshotId = id
        retainedDraft = RetainedDraft(draftOwner, id, state)
        ActivityDraftState.Store(this, draftOwner).write(id, state)
        outState.putString("draft_owner", draftOwner)
        outState.putString("draft_snapshot", id)
        // These two small facts are exact even if the cache file cannot be read.
        outState.putBoolean("draft_search_empty", search.isEmpty())
        outState.putBoolean("draft_selection_empty", exportSelection.isEmpty())
        outState.putString("page", page.name)
        // Historical state writer retained for old readers: only the five original indices exist.
        outState.putInt("tab", page.legacyTab)
        outState.putInt("lookup_sel_start", lookupSelectionStart)
        outState.putInt("lookup_sel_end", lookupSelectionEnd)
        outState.putInt("translation_sel_start", translationSelectionStart)
        outState.putInt("translation_sel_end", translationSelectionEnd)
        outState.putBoolean("favorites_only", onlyFavorites)
        outState.putString("vocabulary_sort", vocabularySort.name)
        // Local display state only: whether the export-selection panel is open. The selection and
        // its explicit-zero intent are saved separately and are never derived from this flag.
        outState.putBoolean("vocabulary_managing", vocabularyManaging)
        outState.putInt("vocabulary_page", vocabularyPage)
        (restoredVocabularyScroll ?: vocabularyScroll?.scrollY)?.let { outState.putInt("vocabulary_scroll", it) }
        // The intent is part of the selection identity: an explicit empty selection is 0 words, not
        // the default "all". Old readers without this key keep their historical interpretation.
        outState.putBoolean("export_selection_explicit", exportSelectionExplicit)
        outState.putBoolean("export_scope_not_restored", exportScopeNotRestored)
        outState.putBoolean("pe_has_draft", state.personalDraft != null)
        outState.putLong("consent", consentGeneration)
        outState.putInt("export_request", exportSession.fileRequestCode ?: lostExportRequestCode)
        val pendingBackupRequests = mutableMapOf<Int, BackupPicker>()
        fun rememberBackupRequest(code: Int, picker: BackupPicker) {
            if (pendingBackupRequests.size < SAVED_BACKUP_REQUEST_LIMIT) pendingBackupRequests[code] = picker
            backupRequestHighWatermark = maxOf(backupRequestHighWatermark, code)
        }
        restoreSession.importRequestCode?.let { rememberBackupRequest(it, BackupPicker.IMPORT) }
        backupExportSession.fileRequestCode?.let { rememberBackupRequest(it, BackupPicker.EXPORT) }
        restoreSession.safetyRequestCode?.let { rememberBackupRequest(it, BackupPicker.SAFETY) }
        interruptedDocumentRequests.forEach { (code, picker) -> rememberBackupRequest(code, picker) }
        // Overflowed codes still cannot bind a new picker; the combined arrays are capped at 64.
        outState.putInt("backup_request_high_watermark", backupRequestHighWatermark)
        BackupPicker.entries.forEach { picker ->
            outState.putIntArray(picker.stateKey, pendingBackupRequests.filterValues { it == picker }.keys.toIntArray())
        }
        super.onSaveInstanceState(outState)
    }

    private fun buildShell() {
        root = if (wide) row() else column()
        // The programmatic view tree is recreated from explicit state; never parcel its text a
        // second time via automatic EditText/view-hierarchy saving.
        root.isSaveFromParentEnabled = false
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
        body = column().apply { setPadding(dp(if (wide) 24 else 16), dp(20), dp(if (wide) 24 else 16), dp(12)) }
        // The version notice is a plain bar in the layout flow — never an overlay — so it cannot
        // cover the guide, an editor or a system permission window. It lives in its own wrapper so
        // the original body keeps its scroll, padding and insets handling unchanged.
        updateBannerHost = column().apply { visibility = View.GONE }
        val bodyWrapper = column()
        bodyWrapper.addView(updateBannerHost, LinearLayout.LayoutParams(-1, -2))
        bodyWrapper.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
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
            root.addView(navigation.view, LinearLayout.LayoutParams(dp(184), -1))
            root.addView(bodyWrapper, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            root.addView(bodyWrapper, LinearLayout.LayoutParams(-1, 0, 1f))
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
        if (target != page) {
            practiceStep = when {
                practiceStep == PracticeStep.LOOKUP && target == MainPage.LOOKUP -> PracticeStep.INPUT
                practiceStep == PracticeStep.VOCABULARY && target == MainPage.VOCABULARY -> PracticeStep.STUDY
                practiceStep == PracticeStep.STUDY && target == MainPage.STUDY -> PracticeStep.COMPLETE
                else -> null
            }
            closePracticeCoach()
        }
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
                    hasLearningSnapshot = true
                    dialogDetail?.update(words.firstOrNull { it.entry.key == dialogDetail?.key })
                    when (page) {
                        MainPage.VOCABULARY -> { renderWords(preserveDetail = true); updateExportButton() }
                        // Never rebuild a page that holds live input / an editor owner on an ordinary
                        // background refresh. Leaving the page is the only path that invalidates them.
                        // TRANSLATION owns an ML Kit session and a draft; it is deliberately excluded.
                        MainPage.LOOKUP, MainPage.TRANSLATION, MainPage.PERSONAL_ENTRY,
                        MainPage.SETTINGS, MainPage.GUIDE -> Unit
                        else -> render()
                    }
                }.onFailure { toast("读取失败：${it.message}") }
            }
        }
    }

    private fun loadDictionaryStatus(retryOf: ShiciApplication.LookupDictionaryAttempt? = null) {
        if (dictionaryStatusLoading) return
        dictionaryStatusLoading = true
        if (retryOf != null) lookupDictionaryRetryShown = true
        updateDictionaryStatusViews()
        app.worker.execute {
            val attempt = app.lookupDictionaryAttempt(retryOf)
            main.post {
                if (isDestroyed) return@post
                dictionaryAttempt = attempt
                dictionaryStatusLoading = false
                val result = attempt.result
                // The first screen shows one short status; the full counts stay available in the
                // collapsed "词库校验详情" next to it. A failure stays fully visible, never hidden.
                dictionarySummary = result.fold(
                    { "ECDICT 大词库已校验可用" },
                    { "词库不可用：${it.message}；不会生成释义或新增查询记录" })
                dictionaryDetail = result.fold(
                    { "已校验 ${it.entryCount} 个可检索词头 · ${it.aliasCount} 组词形关系（仅格式与完整性校验）" },
                    { "词库不可用：${it.message}；不会生成释义或新增查询记录" })
                updateDictionaryStatusViews()
            }
        }
    }

    private fun updateDictionaryStatusViews() {
        dictionaryStatusView?.text = dictionarySummary
        dictionaryDetailView?.text = dictionaryDetail
        val failed = dictionaryAttempt?.result?.isFailure == true
        dictionaryRetryButton?.apply {
            visibility = if (failed) View.VISIBLE else View.GONE
            isEnabled = failed && !dictionaryStatusLoading
            text = if (dictionaryStatusLoading) "正在重试词库加载…" else "重试词库加载"
        }
        // The lookup card stays unchanged in the ordinary successful state. Settings always
        // exposes the full verification details, and a retry success remains visible on lookup.
        lookupDictionaryHost?.visibility = if (failed || lookupDictionaryRetryShown) View.VISIBLE else View.GONE
    }

    private fun dictionaryStatus(showDetails: Boolean = true): View {
        val container = column()
        dictionaryStatusView = text(dictionarySummary, 16f, Paper.secondary)
        container.addView(dictionaryStatusView)
        val retry = button("重试词库加载") {
            if (!foreground || !container.isAttachedToWindow ||
                (page != MainPage.LOOKUP && page != MainPage.SETTINGS)) return@button
            dictionaryAttempt?.takeIf { it.result.isFailure }?.let { loadDictionaryStatus(it) }
        }
        dictionaryRetryButton = retry
        container.addView(retry)
        if (showDetails) container.addView(helpDisclosure("词库校验详情") {
            dictionaryDetailView = text(dictionaryDetail, 15f, Paper.secondary)
            addView(dictionaryDetailView)
        })
        updateDictionaryStatusViews()
        return container
    }

    private fun render() {
        closePracticeCoach()
        // Compatibility bridge for the historical reflection host and any old caller that still
        // writes the five legacy indices. Product navigation always keeps [currentTab] equal to
        // [page]'s legacy mirror, so this cannot override an enum-selected page (LOOKUP and
        // PERSONAL_ENTRY use -1). It is not the page identity.
        if (currentTab != page.legacyTab && currentTab in 0..4) page = MainPage.fromLegacyTab(currentTab)
        if (currentTab != page.legacyTab) currentTab = page.legacyTab
        // A rebuild never leaves an old translator alive behind detached views. Except for the
        // translation page itself (rebuilt immediately below), this is the page-leave path.
        if (translationScreen != null) { captureTranslationDraft(); closeTranslationOwner() }
        if (hasLearningSnapshot && restoredVocabularyScroll == null) restoredVocabularyScroll = vocabularyScroll?.scrollY
        floatingSwitch = null; floatingText = null; stopCaptureButton = null; searchList = null; detailPanel = null
        wordListHeader = null; vocabularyCountView = null
        exportButton = null; inlineDetail = null
        dictionaryStatusView = null; dictionaryDetailView = null
        dictionaryRetryButton = null; lookupDictionaryHost = null
        backupExportButton = null; restoreButton = null; backupStatusView = null
        settingsScreen = null
        lookupInputView = null; lookupPracticeArea = null; personalStatusView = null
        vocabularyToolbar = null; selectionPanel = null; vocabularyScroll = null
        // A rebuild never leaves the previous guide screen alive behind detached views. It is closed
        // permanently; the GUIDE branch below creates the only new instance when it is still current.
        guideScreen?.close(); guideScreen = null
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
            MainPage.GUIDE -> guidePage()
        }
        updatePracticeCoach()
        renderUpdateBanner()
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
        content.addView(text("拾词 · 阅读工作台", 26f, Paper.ink, true))
        content.addView(text("查词、积累、复习，都留在本机。", 15f, Paper.secondary)); content.space(12)
        content.addView(button("输入单词查词", true) { selectPage(MainPage.LOOKUP) }, LinearLayout.LayoutParams(-1, -2))
        content.space(12)
        // Non-blocking first-use recommendation. It never opens by itself, never steals focus and
        // only writes the independent guide preference when the user taps "跳过，不再提示".
        if (!guideSeen()) {
            content.addView(UsageGuideEntries.homeCard(
                this,
                onOpenGuide = { selectPage(MainPage.GUIDE) },
                onDismiss = { markGuideSeen(); render() }
            ))
            content.space(16)
        }
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
        // One visible sentence on the first screen; the extra wording stays available on demand.
        session.addView(text("开启后在题目页划一笔即可查词；也可到「查词」页手动输入。", 16f, Paper.secondary))
        session.addView(helpDisclosure("悬浮查词说明") {
            addView(text("开启后，点悬浮「词」，直接在题目页面上划一笔查词。", 15f, Paper.secondary))
            addView(text("不需要悬浮权限时，也可以到「查词」页手动输入。", 15f, Paper.secondary))
        })
        content.addView(session); content.space(16)
        content.addView(text("学习与工具", 20f, bold = true)); content.space(8)
        // Small pairs are shortcuts only; every destination keeps its original route and owner.
        content.addView(pair(
            entryCard("生词本", "释义、笔记与收藏") { selectPage(MainPage.VOCABULARY) },
            entryCard("复习", "四选一，继续今日计划") { selectPage(MainPage.STUDY) },
            inlineAt(contentWidthDp), 12))
        content.space(10)
        content.addView(pair(
            entryCard("添加/补词", "只维护本机个人词义") { selectPage(MainPage.PERSONAL_ENTRY) },
            entryCard("离线翻译（可选）", "确认后才下载模型") { selectPage(MainPage.TRANSLATION) },
            inlineAt(contentWidthDp), 12))
        content.space(10)
        content.addView(entryCard("统计", "查看真实查询与复习进度") { selectPage(MainPage.STATISTICS) })
        content.space(12)
        content.addView(text("打开悬浮查词 → 点「词」→ 划一笔 → 抬笔即查", 14f, Paper.secondary))
        statusListener(CaptureStatus.state)
    }
    private fun entryCard(title: String, description: String, action: () -> Unit): View = column().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10)); background = shape(Paper.surface, border = true)
        addView(text(title, 18f, bold = true))
        addView(text(description, 15f, Paper.secondary))
        isClickable = true; isFocusable = true; minimumHeight = dp(64)
        contentDescription = "打开$title"
        setOnClickListener { action() }
    }

    // ---- Repeatable usage guide ----------------------------------------------------------------

    /**
     * Independent preference for the non-blocking HOME card. It is never written by onCreate,
     * ordinary rendering, the guide page itself, navigation or back: only an explicit "跳过，不再
     * 提示"/"跳过引导"/"看完了" tap calls [markGuideSeen].
     */
    private fun guideSeen(): Boolean =
        getSharedPreferences("guide_prefs", MODE_PRIVATE)
            .getBoolean(UsageGuide.SEEN_PREFERENCE_KEY, false)

    private fun markGuideSeen() {
        getSharedPreferences("guide_prefs", MODE_PRIVATE)
            .edit().putBoolean(UsageGuide.SEEN_PREFERENCE_KEY, true).apply()
    }

    /**
     * Repeatable help page. The component owns only presentation; page navigation goes through the
     * existing [selectPage] cleanup (late async callbacks, translation owner, study owner, drafts),
     * and no topic action queries, records, downloads or deletes anything.
     */
    private fun guidePage() {
        guideScreen?.close(); guideScreen = null
        val content = scrolled()
        content.addView(text("跟着实际操作", 28f, bold = true)); content.space(8)
        content.addView(text("直接在你的界面练习，高亮真实按钮；完成操作后继续。", 16f, Paper.secondary))
        content.space(16)
        content.addView(button("从查词开始", true) {
            endPractice(false)
            selectPage(MainPage.HOME)
            practiceGeneration++; practiceStep = PracticeStep.LOOKUP
            updatePracticeCoach()
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(text("输入与查询 → 看真实词义 → 生词本 → 复习。查询成功会按正常规则收录。", 15f, Paper.secondary))
        content.space(16)
        content.addView(button("在我的题页练划词") {
            endPractice(false)
            if (CaptureStatus.state.phase == CapturePhase.ACTIVE) {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_PRACTICE))
                toast("切到你的阅读页，跟随真实悬浮条练习")
            } else {
                selectPage(MainPage.HOME)
                practiceGeneration++; practiceStep = PracticeStep.CAPTURE
                pendingCapturePractice = true
                updatePracticeCoach()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(text("使用当前页面和真实词卡。系统授权由你确认；不会自动截图、代点、查询或下载模型。", 15f, Paper.secondary))
        content.space(16)
        content.addView(button("返回首页") { selectPage(MainPage.HOME) })
    }

    private fun closePracticeCoach() { practiceCoach?.close(); practiceCoach = null }

    private fun endPractice(markSeen: Boolean) {
        closePracticeCoach(); practiceStep = null; pendingCapturePractice = false
        practiceGeneration++
        if (markSeen) markGuideSeen()
    }

    private fun nextPracticeStep() {
        when (practiceStep) {
            PracticeStep.LOOKUP -> selectPage(MainPage.LOOKUP)
            PracticeStep.INPUT -> {
                // Explicitly browsing the next real page is not a successful query or collection.
                practiceStep = PracticeStep.VOCABULARY
                selectPage(MainPage.VOCABULARY)
            }
            PracticeStep.VOCABULARY -> selectPage(MainPage.VOCABULARY)
            PracticeStep.STUDY -> selectPage(MainPage.STUDY)
            PracticeStep.CAPTURE -> {
                if (CaptureStatus.state.phase == CapturePhase.ACTIVE) {
                    startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_PRACTICE))
                    endPractice(false)
                } else if (CaptureStatus.state.phase != CapturePhase.PREPARING && CaptureStatus.state.phase != CapturePhase.STOPPING) {
                    beginCapture()
                } else toast("请先完成当前授权，也可以跳过引导")
            }
            else -> Unit
        }
    }

    private fun updatePracticeCoach() {
        closePracticeCoach()
        if (!foreground || isFinishing || isDestroyed) return
        val step = practiceStep ?: return
        if (step == PracticeStep.WAIT_RESULT || step == PracticeStep.CLOSE_RESULT) return
        val target: View? = when (step) {
            PracticeStep.LOOKUP -> navigation.practiceTarget(MainPage.LOOKUP.name)
            PracticeStep.INPUT -> lookupPracticeArea
            PracticeStep.VOCABULARY -> navigation.practiceTarget(MainPage.VOCABULARY.name)
            PracticeStep.STUDY -> navigation.practiceTarget(MainPage.STUDY.name)
            PracticeStep.CAPTURE -> floatingSwitch
            else -> null
        }
        if (target == null && step != PracticeStep.COMPLETE) { endPractice(false); return }
        val message = when (step) {
            PracticeStep.LOOKUP -> "先点这里的「查词」。"
            PracticeStep.INPUT -> "输入单词并查询；也可点「下一步」先认识生词本，不会自动查询或收录。"
            PracticeStep.VOCABULARY -> "词义已显示。是否收录以词卡状态为准；点「生词本」查看实际词条。"
            PracticeStep.STUDY -> "这里是你的生词本。点「复习」，查看今天的真实计划。"
            PracticeStep.COMPLETE -> "已到达真实复习页面。每题四个答案，只有你实际作答才产生反馈。"
            PracticeStep.CAPTURE -> "打开这个真实开关。授权说明和系统确认都由你操作；返回后切到自己的题页。"
            else -> return
        }
        val owner = practiceGeneration
        practiceCoach = PracticeCoach(window.decorView as ViewGroup, target, message,
            onSkip = { if (owner == practiceGeneration) endPractice(true) },
            onFinish = if (step == PracticeStep.COMPLETE) ({ if (owner == practiceGeneration) endPractice(true) }) else null,
            onNext = { if (owner == practiceGeneration && practiceStep == step) nextPracticeStep() },
            onTargetGone = { if (owner == practiceGeneration) endPractice(false) })
    }

    private fun isPracticeLookupOwner(owner: PracticeLookupOwner?): Boolean =
        owner != null && owner.generation == practiceGeneration && foreground && !isFinishing && !isDestroyed &&
            page == MainPage.LOOKUP && lookupPracticeArea === owner.target && owner.target.isAttachedToWindow

    private fun isWaitingForPracticeLookup(owner: PracticeLookupOwner?, token: Long): Boolean =
        isPracticeLookupOwner(owner) && practiceStep == PracticeStep.WAIT_RESULT && manualGate.accepts(token)

    private fun resumePracticeInput(owner: PracticeLookupOwner?, token: Long) {
        if (!isWaitingForPracticeLookup(owner, token) || activeDialog?.isShowing == true) return
        practiceStep = PracticeStep.INPUT
        lookupPracticeArea?.let { area ->
            area.requestRectangleOnScreen(android.graphics.Rect(0, 0, area.width, dp(48)), true)
        }
        updatePracticeCoach()
    }

    private fun vocabulary() {
        // List panel: title + count, search, filter/sort/manage, then the word list. Everything export-related — row checkboxes, select all, clear, restore *and*
        // the "导出 PDF" action — lives only in the explicit "管理与导出" panel, so the reading screen
        // stays a reading screen and the export scope is always read next to the action that uses
        // it. The whole list panel scrolls independently of the detail, including on short windows.
        //
        // The panel is built eagerly and only hidden while not managing, so the page always owns a
        // live [exportButton]. Existing device flows that look the button up right after the
        // vocabulary page renders (they traverse the view tree rather than requiring visibility)
        // still find the same enabled/disabled state.
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("生词本", 28f, bold = true),
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        val countView = text(vocabularyCountText(), 15f, Paper.secondary).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        vocabularyCountView = countView
        header.addView(countView, LinearLayout.LayoutParams(0, -2, 1f))

        // Filter, display order and the single entry to the export-selection panel. The toolbar only
        // mirrors the host state; it never touches the search text, the export selection or data.
        val toolbar = VocabularyToolbar(this)
        toolbar.setState(onlyFavorites, vocabularySort, vocabularyManaging)
        toolbar.onFilterChanged = { value ->
            onlyFavorites = value
            resetVocabularyPage()
            renderWords(preserveDetail = true)
            updateExportButton()
        }
        toolbar.onSortChanged = { value ->
            vocabularySort = value
            renderWords(preserveDetail = true)
        }
        toolbar.onManageToggled = { value -> setVocabularyManaging(value) }
        vocabularyToolbar = toolbar
        // Secondary panel, hidden by default. It starts with exactly the frozen intent kept in
        // [exportSelection]/[exportSelectionExplicit]; opening or closing it changes neither.
        val selectionCard = vocabularySelectionPanel()
        selectionCard.visibility = if (vocabularyManaging) View.VISIBLE else View.GONE
        selectionPanel = selectionCard
        val input = inputField("搜索单词、释义或笔记", true).apply { setText(search) }
        // The title/search belong to the list column, never stretched across the detail column.
        // Keep them inside its scroll so large fonts and the IME cannot strand the row controls.
        val rowList = column()
        searchList = rowList
        val scrolledList = column().apply {
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = shape(Paper.surface, border = true)
        }
        // The list owns its search/filter header, not the full workspace width. It all scrolls on
        // short windows/IME/large fonts; the detail has its own independent vertical scroll.
        scrolledList.addView(header)
        scrolledList.space(8)
        scrolledList.addView(input, LinearLayout.LayoutParams(-1, -2))
        scrolledList.space(8)
        scrolledList.addView(toolbar)
        scrolledList.space(8)
        scrolledList.addView(selectionCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        scrolledList.addView(rowList)
        val listScroll = ScrollView(this).apply { isFillViewport = true; addView(scrolledList) }
        vocabularyScroll = listScroll
        if (splitContent) {
            val container = row()
            container.addView(listScroll, LinearLayout.LayoutParams(0, -1, 0.40f))
            val detail = column().apply { setPadding(dp(16), 0, 0, dp(12)) }
            detailPanel = detail
            container.addView(ScrollView(this).apply { isFillViewport = true; addView(detail) },
                LinearLayout.LayoutParams(0, -1, 0.60f))
            body.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            body.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search = s.toString()
                resetVocabularyPage()
                renderWords(preserveDetail = true)
                updateExportButton()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderWords()
    }
    /**
     * The export-selection panel behind the toolbar's "管理与导出". The buttons keep their original
     * actions and wording: "全选结果" is the current filtered snapshot, "清空" is an explicit zero
     * (never "all"), and only "恢复全部" returns to the default current-result scope. The panel only
     * shows controls; it never edits the selection while being opened or closed.
     */
    private fun vocabularySelectionPanel(): LinearLayout {
        val card = panel().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val selectAll = button("全选结果") { selectAllFilteredForExport() }.apply { textSize = 14f }
        val clear = button("清空") { clearExportSelection() }.apply { textSize = 14f }
        // The only explicit way back to the default scope. Clearing the checks is a deliberate zero
        // and never doubles as "all", so this separate action stays visible while managing.
        val restore = button("恢复全部") { useAllFilteredForExport() }.apply { textSize = 14f }
        // Three equal buttons while the width really fits them at the current font scale; otherwise
        // they stack instead of squeezing the labels or letting one action disappear.
        val minRowWidth = (300 * resources.configuration.fontScale).toInt()
        if (contentWidthDp >= minRowWidth) {
            val selectionRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
            selectionRow.addView(selectAll, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            selectionRow.addView(clear, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
            selectionRow.addView(restore, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(selectionRow)
        } else {
            card.addView(selectAll, LinearLayout.LayoutParams(-1, -2))
            card.space(4)
            card.addView(clear, LinearLayout.LayoutParams(-1, -2))
            card.space(4)
            card.addView(restore, LinearLayout.LayoutParams(-1, -2))
        }
        card.space(4)
        card.addView(text("默认导出当前筛选全部（勾选框已按此显示）；取消勾选后按勾选导出；清空即 0 个。", 13f, Paper.secondary))
        // The single primary action of this panel, next to the scope it will use. It is created
        // eagerly (the panel itself is only hidden), so the page always owns one live export
        // button whose enabled state follows [exportScopeCount].
        val exportAction = button("导出 PDF", true) { export() }
        exportButton = exportAction
        card.space(8)
        card.addView(exportAction, LinearLayout.LayoutParams(-1, -2))
        updateExportButton()
        card.addView(helpDisclosure("导出范围说明") {
            addView(text("「恢复全部」把导出范围恢复为默认：当前搜索/仅收藏筛出的全部词，不必逐个勾选。", 14f, Paper.secondary))
            addView(text("勾选只决定 PDF 导出范围，不改词库、查询次数或学习进度。默认（尚未改动勾选）导出当前筛选全部；一旦勾选、取消或清空，就按你的勾选集合导出，清空即 0 个，不会自动变回全部；点“恢复全部”才回到默认全量。全选只包含当前搜索/仅收藏筛出的词，日期范围仍在导出选项中取交集。", 14f, Paper.secondary))
        })
        return card
    }
    /**
     * Display-only switch between the reading list and the export-selection state. It never edits
     * [exportSelection], [exportSelectionExplicit], the search text, the favorite filter or the sort,
     * so a hidden/reopened panel always keeps an explicit zero and an allow-list exactly as chosen.
     */
    private fun setVocabularyManaging(value: Boolean) {
        if (value == vocabularyManaging) return
        vocabularyManaging = value
        selectionPanel?.visibility = if (value) View.VISIBLE else View.GONE
        vocabularyToolbar?.setState(onlyFavorites, vocabularySort, value)
        renderWords(preserveDetail = true)
        updateExportButton()
        // The panel sits directly under the toolbar at the top of the scrolling area. If the list
        // was scrolled down, opening it would otherwise look like nothing happened, so bring that
        // top region back into view. Closing never scrolls and never edits the selection.
        if (value) vocabularyScroll?.post { vocabularyScroll?.smoothScrollTo(0, 0) }
    }
    /**
     * Title-line count from the same snapshot the list shows. An empty book leaves it blank: the
     * list itself already says "还没有生词", so the title does not repeat it.
     */
    private fun vocabularyCountText(): String =
        if (words.isEmpty()) ""
        else "共 ${words.size} 个词 · 待复习 ${words.count { !it.mastered }} 个"
    private fun filteredWords(): List<LearnedWord> {
        val query = search.trim()
        vocabularyFilter?.let {
            if (it.source === words && it.search == query && it.favorites == onlyFavorites) return it.matches
        }
        val matches = if (query.isEmpty() && !onlyFavorites) words else words.filter { word ->
            (!onlyFavorites || word.favorite) && (query.isEmpty() ||
                word.entry.headword.contains(query, true) || word.entry.meaning.contains(query, true) ||
                word.lastForm.contains(query, true) || word.note.contains(query, true))
        }
        vocabularyFilter = VocabularyFilter(words, query, onlyFavorites, matches)
        vocabularyOrder = null
        return matches
    }

    private fun orderedVocabulary(): List<LearnedWord> {
        val matches = filteredWords()
        return vocabularyOrder?.takeIf { it.first == vocabularySort }?.second
            ?: VocabularyOrdering.apply(matches, vocabularySort).also { vocabularyOrder = vocabularySort to it }
    }

    private fun resetVocabularyPage() {
        vocabularyPage = 0
        vocabularyPageAnchor = null
        restoredVocabularyScroll = null
        // Leave the search/filter controls reachable, including while editing with the IME.
        vocabularyScroll?.scrollTo(0, 0)
    }

    private fun scrollToVocabularyRows() {
        val scroll = vocabularyScroll ?: return
        val rows = searchList ?: return
        scroll.post { if (vocabularyScroll === scroll && searchList === rows) scroll.scrollTo(0, rows.top) }
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
        filteredWords().forEach { exportSelection += it.entry.key }
        exportSelectionExplicit = true
        exportScopeNotRestored = false
        renderWords(preserveDetail = true)
        updateExportButton()
    }
    private fun clearExportSelection() {
        // Clearing is itself an explicit choice: zero words, never an implicit "all".
        exportSelection.clear()
        exportSelectionExplicit = true
        exportScopeNotRestored = false
        renderWords(preserveDetail = true)
        updateExportButton()
    }
    private fun useAllFilteredForExport() {
        // The only supported way back to the default scope, so an emptied selection can never be
        // mistaken for "all". This is a visible user action, not a side effect of clearing.
        exportSelection.clear()
        exportSelectionExplicit = false
        exportScopeNotRestored = false
        renderWords(preserveDetail = true)
        updateExportButton()
    }
    /**
     * Rebuilds rows and (in split layout) the detail. [explicitSelection] is the exact row snapshot
     * a caller acted on (for example the per-row remove entry); it is resolved by key inside this
     * rebuild instead of trusting whatever [selectedKey] happened to be, so a later global selection
     * can never substitute another word's binding.
     */
    private fun renderWords(preserveDetail: Boolean = false, explicitSelection: LearnedWord? = null) {
        // Same filtered membership as the export scope, only the display order changes. Export
        // selection stays keyed by entry.key, so reordering never adds, drops or widens a check.
        val list = orderedVocabulary()
        val pageCount = if (list.isEmpty()) 1 else (list.lastIndex / VOCABULARY_PAGE_SIZE) + 1
        val anchor = explicitSelection?.entry?.key ?: vocabularyPageAnchor
        val oldPageStart = vocabularyPage.toLong() * VOCABULARY_PAGE_SIZE
        val anchoredIndex = if (anchor != null && oldPageStart in 0L until list.size.toLong() &&
            list[oldPageStart.toInt()].entry.key == anchor) oldPageStart.toInt()
        else anchor?.let { key -> list.indexOfFirst { it.entry.key == key } } ?: -1
        val pageIndex = if (anchoredIndex >= 0) anchoredIndex / VOCABULARY_PAGE_SIZE
            else vocabularyPage.coerceIn(0, pageCount - 1)
        val start = pageIndex * VOCABULARY_PAGE_SIZE
        val end = start + minOf(VOCABULARY_PAGE_SIZE, list.size - start)
        if (hasLearningSnapshot) {
            vocabularyPage = pageIndex
            vocabularyPageAnchor = if (anchoredIndex >= 0) anchor else list.getOrNull(start)?.entry?.key
        }
        vocabularyCountView?.text = vocabularyCountText()
        searchList?.removeAllViews()
        val summary = listSummary(list)
        wordListHeader = text(summary, 14f, Paper.secondary).apply {
            // The resting state (no filter, default export scope) needs no extra line.
            visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        }
        searchList?.addView(wordListHeader)
        // No per-row or per-list delete explanation: the row menu's "移除…" opens the existing
        // scope chooser, which states both scopes (undo-last vs. whole word) in full at the moment
        // of choice, followed by the second dangerous confirmation for the whole-word scope.
        if (list.isEmpty()) searchList?.addView(
            text(
                when {
                    !hasLearningSnapshot -> "正在读取生词本…"
                    words.isEmpty() -> "还没有生词。成功查询后会自动收录。"
                    else -> "没有匹配的生词。试试清空搜索或切回「全部」。"
                },
                18f
            ).apply { setPadding(0, dp(16), 0, dp(8)) }
        )
        if (pageCount > 1) addVocabularyPager(pageIndex, pageCount, list.size, start, end)
        for (index in start until end) {
            val word = list[index]
            searchList?.addView(vocabularyRow(word) {
                openVocabularySnapshot(word.entry.key)
            }.apply {
                if (splitContent && selectedKey == word.entry.key) background = shape(Paper.lightGreen, 8)
            })
            searchList?.divider()
        }
        if (pageCount > 1) addVocabularyPager(pageIndex, pageCount, list.size, start, end)
        if (hasLearningSnapshot) restoredVocabularyScroll?.let { position ->
            restoredVocabularyScroll = null
            val scroll = vocabularyScroll
            scroll?.post { if (vocabularyScroll === scroll && vocabularyPage == pageIndex) scroll.scrollTo(0, position) }
        }
        detailPanel?.let { detail ->
            if (preserveDetail && explicitSelection == null && inlineDetail != null) {
                inlineDetail?.update(words.firstOrNull { it.entry.key == inlineDetail?.key })
                return@let
            }
            inlineDetail?.let { closeDeleteFlowFor(it) }
            inlineDetail = null
            detail.removeAllViews()
            // An explicit action resolves only its own exact key; it never falls back to the
            // current/global selection, so the remove flow cannot bind a different word.
            val selected = if (explicitSelection != null)
                list.firstOrNull { it.entry.key == explicitSelection.entry.key }
            else list.firstOrNull { it.entry.key == selectedKey } ?: list.firstOrNull()
            if (selected == null) detail.addView(text("选择一个生词，查看释义和记录", 18f, Paper.secondary))
            else {
                selectedKey = selected.entry.key
                detail.addView(text("生词详情", 18f, bold = true)); detail.space(12)
                val card = panel(); detail.addView(card); inlineDetail = wordDetail(card, selected, wordsCredential, wordsVersion)
            }
        }
    }

    private fun addVocabularyPager(index: Int, count: Int, total: Int, start: Int, end: Int) {
        val owner = searchList ?: return
        val pager = column()
        fun move(target: Int) {
            if (page != MainPage.VOCABULARY || searchList !== owner || !pager.isAttachedToWindow ||
                index != vocabularyPage) return
            if (target !in 0 until count) return
            vocabularyPage = target
            vocabularyPageAnchor = null
            renderWords(preserveDetail = true)
            scrollToVocabularyRows()
        }
        pager.addView(text("第 ${index + 1} / $count 页 · 第 ${start + 1}–$end 个，共 $total 个", 13f, Paper.secondary))
        pager.addView(pair(
            button("上一页") { move(index - 1) }.apply { isEnabled = index > 0 },
            button("下一页") { move(index + 1) }.apply { isEnabled = index + 1 < count },
            horizontal = inlineAt(if (splitContent) (contentWidthDp * 0.40f).toInt() else contentWidthDp), gap = 8
        ))
        owner.addView(pager)
    }
    /**
     * One short status line for the list. Empty when the list is in the plain resting state (no
     * search/favorite filter and the default "all current results" export scope), because the title
     * already states the count. Any explicit selection — above all an explicit zero — is always
     * spelled out so it can never be mistaken for "all". The sort stays on the toolbar chip.
     */
    private fun listSummary(list: List<LearnedWord>): String {
        val filters = buildList {
            if (search.isNotBlank()) add("搜索“${search.trim()}”")
            if (onlyFavorites) add("仅收藏")
        }
        val count = if (filters.isEmpty()) null
        else "筛选 ${list.size} 个词（${filters.joinToString(" · ")}）"
        // Default scope is the calm resting state and stays implicit; while the manage panel is open
        // it is spelled out so "everything currently shown" is never guessed. Any explicit
        // allow-list — above all an explicit zero — is always spelled out.
        val scope = when {
            exportScopeNotRestored -> "上次 PDF 范围未恢复：不会导出；请重新勾选或明确选择恢复全部"
            !exportSelectionExplicit ->
                if (vocabularyManaging && list.isNotEmpty()) "导出当前筛选全部（${list.size} 个）" else ""
            exportSelection.isEmpty() -> "已清空勾选：导出 0 个"
            else -> {
                val inFilter = list.count { it.entry.key in exportSelection }
                "已勾选 ${exportSelection.size} 个（筛选内 $inFilter 个）"
            }
        }
        return listOfNotNull(count, scope.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
    private fun refreshListSummary() {
        val summary = listSummary(filteredWords())
        wordListHeader?.apply {
            text = summary
            visibility = if (summary.isBlank()) View.GONE else View.VISIBLE
        }
        vocabularyCountView?.text = vocabularyCountText()
    }
    /**
     * One compact vocabulary row: the word, a one-line meaning, a light query/time line and the
     * favorite state; the text opens the detail. In the explicit "管理与导出" state a leading
     * checkbox edits only the in-memory export allow-list (never the database). Every destructive or
     * favorite action stays in the row menu, which only invokes the existing exact-key flows
     * ([toggleListFavorite] and [openListRemoval] → scope chooser + second confirmation).
     */
    private fun vocabularyRow(word: LearnedWord, action: () -> Unit): View {
        val container = row().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64) }
        if (vocabularyManaging) {
            val check = CheckBox(this).apply {
                // The default scope is "all current results", so under that intent every visible
                // row really is in scope and is shown checked. Nothing is written by rendering it.
                isChecked = !exportSelectionExplicit || word.entry.key in exportSelection
                contentDescription = "勾选导出 ${word.entry.headword}"
                setOnCheckedChangeListener { _, checked ->
                    // The first real edit materialises the visible default scope before applying
                    // the change, so unchecking one row means "all of this filter except it" instead
                    // of collapsing into an accidental zero. From then on the set is the explicit
                    // allow-list: unchecking the last row stays an explicit 0, never "all".
                    if (!exportSelectionExplicit) {
                        exportSelection.clear()
                        filteredWords().forEach { exportSelection += it.entry.key }
                        exportSelectionExplicit = true
                    }
                    if (checked) exportSelection += word.entry.key else exportSelection -= word.entry.key
                    exportScopeNotRestored = false
                    refreshListSummary()
                    updateExportButton()
                }
            }
            container.addView(check)
        }
        // Light, glanceable metadata only: how often and how recently it was looked up, plus a star
        // when it is favorited. An unstarred word says nothing rather than adding a second marker;
        // mastery, full timestamps and provenance stay in the detail.
        val meta = buildList {
            add("${word.queryCount} 次")
            add(compactTime(word.lastAt))
            if (word.favorite) add("★ 已收藏")
        }.joinToString(" · ")
        val body = column().apply {
            setPadding(dp(4), dp(8), dp(4), dp(8)); isClickable = true; isFocusable = true
            addView(text(word.entry.headword, 21f, bold = true))
            addView(text("${word.entry.partOfSpeech} ${word.entry.meaning}", 15f, Paper.secondary).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(text(meta, 13f, if (word.favorite) Paper.warning else Paper.secondary))
            setOnClickListener { action() }
            contentDescription = "${word.entry.headword}，${word.entry.partOfSpeech} ${word.entry.meaning}，" +
                "$meta，${if (word.favorite) "已收藏" else "未收藏"}，点按查看详情"
        }
        container.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
        val favorite = button(if (word.favorite) "★" else "☆") { toggleListFavorite(word) }.apply {
            textSize = 23f
            setTextColor(if (word.favorite) Paper.warning else Paper.secondary)
            background = shape(Paper.surface, 8)
            contentDescription = "${if (word.favorite) "取消收藏" else "收藏"} ${word.entry.headword}"
        }
        container.addView(favorite, LinearLayout.LayoutParams(dp(48), -2))
        // Explicit removal entry reuses the original exact-key scope/confirmation flow.
        // Favorite is the adjacent star; neither action directly edits a database here.
        val remove = VocabularyActions.listRemoveButton(this, word.entry.headword) {
            openListRemoval(word.entry.key)
        }.apply { background = shape(Paper.surface, 8, true) }
        container.addView(remove)
        return container
    }
    /** Row menu: only the existing exact flows, chosen per row; it never deletes or edits by itself. */
    private fun showVocabularyRowMenu(anchor: View, word: LearnedWord) {
        if (page != MainPage.VOCABULARY || !foreground || isFinishing || isDestroyed) return
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_FAVORITE, 0, if (word.favorite) "取消收藏" else "收藏")
        menu.menu.add(0, MENU_REMOVE, 1, VocabularyActions.LIST_LABEL)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FAVORITE -> toggleListFavorite(word)
                MENU_REMOVE -> openListRemoval(word.entry.key)
            }
            true
        }
        menu.show()
    }
    /** Short, honest age label for the list row; the detail keeps the full timestamps. */
    private fun compactTime(time: Long): String {
        val zone = ZoneId.systemDefault()
        val day = LearningRules.localDate(time, zone)
        val today = LearningRules.localDate(System.currentTimeMillis(), zone)
        return when {
            day == today -> "今天"
            day == today.minusDays(1) -> "昨天"
            else -> day.format(DateTimeFormatter.ofPattern("MM-dd"))
        }
    }
    /** Opens the detail for exactly [key] from the current list snapshot, never a later selection. */
    private fun openVocabularySnapshot(key: String) {
        if (page != MainPage.VOCABULARY) return
        val exact = words.firstOrNull { it.entry.key == key } ?: return
        selectedKey = exact.entry.key
        if (splitContent) renderWords(explicitSelection = exact) else showWordDialog(exact)
    }
    /**
     * The per-row "移除…" action. It re-reads the clicked row's exact snapshot by key and forms a
     * fresh [WordDetailBinding] from that snapshot (split detail rebuild or dialog), then enters the
     * original [openDeleteScope] flow. It never synthesizes credentials, calls the repository delete
     * directly, or acts on a later/global selection; the binding's own stale/owner/admission checks
     * and the dangerous second confirmation are unchanged.
     */
    private fun openListRemoval(key: String) {
        if (page != MainPage.VOCABULARY || !foreground || isFinishing || isDestroyed) return
        if (detailBusy) { toast("有保存或删除操作正在进行，请稍后再试"); return }
        val exact = words.firstOrNull { it.entry.key == key } ?: run {
            toast("该词已不在当前生词本快照，请刷新后重试"); refresh(); return
        }
        if (app.repository.deletions.isStale(key, wordsCredential)) {
            toast("该词已删除或数据已恢复，请刷新后重新打开详情")
            refresh(); return
        }
        selectedKey = key
        val binding: WordDetailBinding = if (splitContent) {
            renderWords(explicitSelection = exact)
            inlineDetail?.takeIf { it.key == key } ?: run {
                toast("未能建立该词详情，请重新打开生词本后再试"); return
            }
        } else showWordDialog(exact)
        // A binding for another key, an old generation or an already closed surface must never start
        // a delete flow even if a late callback somehow reaches here.
        if (binding.key != key || binding.isStale() || binding.isClosed() || binding.isInvalidated()) return
        openDeleteScope(binding, exact, null)
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
        lateinit var screen: LookupScreen
        screen = LookupScreen(
            context = this,
            initialText = lookupQuery,
            selectionStart = lookupSelectionStart,
            selectionEnd = lookupSelectionEnd,
            onTextChanged = { text -> if (lookupInputView === screen.input) lookupQuery = text },
            onQuery = query@{ text ->
                if (!foreground || page != MainPage.LOOKUP || lookupInputView !== screen.input ||
                    !screen.input.isAttachedToWindow) return@query
                // Keep the original draft/revision/credential chain: capture the caret first, then
                // run the same manualLookup that button/IME submissions always used.
                lookupQuery = text
                captureLookupDraft()
                if (practiceStep == PracticeStep.INPUT) {
                    practiceStep = PracticeStep.WAIT_RESULT; closePracticeCoach()
                }
                val owner = if (practiceStep == PracticeStep.WAIT_RESULT)
                    PracticeLookupOwner(practiceGeneration, screen.practiceArea) else null
                manualLookup(text, practiceOwner = owner)
            },
            onOpenPersonalEntry = { selectPage(MainPage.PERSONAL_ENTRY) }
        )
        // onSaveInstanceState, page switches and rotation capture the draft through this field.
        lookupInputView = screen.input
        lookupPracticeArea = screen.practiceArea
        lookupDictionaryHost = screen.dictionaryStatusHost
        screen.dictionaryStatusHost.addView(dictionaryStatus(showDetails = false))
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
     * The optional offline AI translation component, embedded as a real [TranslationScreen]. Its
     * draft is restored from [translationInput] and its selection from the saved caret pair, even
     * when the model is not downloaded and the source is shown read-only. The component itself
     * starts only a local model state query; translation happens exclusively on the user's
     * "翻译整句" tap and a model download only on the explicit Wi-Fi confirmation. Leaving/stop/
     * destroy closes this owner, and the page is excluded from background re-rendering so an old
     * asynchronous result cannot paint a new page.
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
            addView(text("离开前的写入身份已失效；草稿不会静默覆盖，请重新读取当前词义并确认后再编辑。", 15f, Paper.warning))
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
            addView(text("该词本机词义在离开后已更新。草稿不会自动覆盖；保存时按离开时版本校验，冲突会拒绝写入。", 15f, Paper.warning))
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
            // Short source badge; verbatim provenance stays in Settings and lookup details.
            card.addView(text(PersonalDictionary.label(original), 13f, Paper.warning))
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
                val result = app.lookupExactWord(text)
                val users = (result as? LookupResult.Match)?.entries.orEmpty()
                    .associate { it.key to app.repository.userEntry(it.key) }
                val user = (result as? LookupResult.Match)?.entries?.singleOrNull()?.let { users[it.key] }
                val after = app.repository.changes.version
                LookupRead(result, user, after, before == after, users)
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
                            val entry = result.entries.first()
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
                personalStatusText = "词义已更新，草稿需再次确认；不会自动覆盖。"
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
                // Refresh only controls without an unsaved edit. A field the user is editing keeps
                // both its text and its baseline: the click-time expected-old value must stay the
                // value that edit was based on, so a newer row is a real conflict instead of being
                // silently merged under the draft.
                val noteEdited = note.text.toString() != savedNote
                val masteryEdited = mastery.isChecked != savedMastered
                if (!noteEdited && savedNote != word.note) note.setText(word.note)
                if (!masteryEdited) mastery.isChecked = word.mastered
                if (!noteEdited) savedNote = word.note
                if (!masteryEdited) savedMastered = word.mastered
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
        /** Last saved value this binding observed; frozen with [masteredValue]/[noteValue] at click. */
        fun baselineMastered() = savedMastered
        /** Last saved note this binding observed; frozen with the target at the save click. */
        fun baselineNote() = savedNote
        /**
         * Advances only the baselines of fields this click submitted after a committed or idempotent
         * save. Unedited fields pass null and keep their previous baseline, and the controls are not
         * touched, so a following edit compares against the database's new value instead of the
         * pre-save baseline (which would otherwise look like a false conflict).
         */
        fun acceptSavedFields(mastered: Boolean? = null, note: String? = null) {
            if (mastered != null) savedMastered = mastered
            if (note != null) savedNote = note
        }

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
            else (contentWidthDp * 0.60f).toInt() - 16
        content.addView(text(word.entry.headword, 32f, bold = true))
        val definition = text("${word.entry.partOfSpeech} ${word.entry.meaning}", 18f)
        content.addView(definition); content.space(8)
        val sourceBadge = badge(PersonalDictionary.label(word.source, word.version))
        content.addView(sourceBadge, LinearLayout.LayoutParams(-2, -2))
        // The primary "从生词本移除…" entry is deliberately in the top section, so opening a detail
        // from the list's remove entry shows it immediately instead of hiding it below long text.
        // The callback enters the original scope/exact/owner flow through this binding only.
        var removeBinding: WordDetailBinding? = null
        val removeSection = VocabularyActions.detailSection(this, word.entry.headword) {
            removeBinding?.let { openDeleteScope(it, word, close) }
        }
        removeSection.removeButton.apply {
            textSize = 15f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = shape(Paper.surface, 8, true)
        }
        content.addView(removeSection.view)
        content.space(8)
        content.divider()
        val form = text("原词形：${word.lastForm}", 16f, Paper.secondary)
        content.addView(form)
        // Long provenance text is created here (the binding updates it in place) but shown inside the
        // collapsed detail explanation below, next to the other reference text.
        val source = text(PersonalDictionary.provenance(word.source, word.version), 16f, Paper.secondary)
        content.space(16)
        val count = text("${word.queryCount} 次", 20f, bold = true)
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
        val star = button(if (word.favorite) "★ 已收藏（点击取消）" else "☆ 收藏") {}
        content.addView(star); content.space(8)
        // Reference text only; the controls themselves stay visible and unchanged. All original
        // safety/scope wording is preserved verbatim inside the disclosure.
        content.addView(helpDisclosure("来源、统计与收藏说明") {
            addView(source)
            addView(text("保存后生效；已掌握的词不计入待复习。", 16f, Paper.secondary))
            addView(text("撤销只影响最近一次成功查询；之前的查询与已保存的笔记保留。", 16f, Paper.secondary))
            addView(text("星标只是个人收藏标记；取消星标不会删除任何学习记录，也不改掌握状态、查询次数或复习进度。", 16f, Paper.secondary))
        })
        content.space(8)
        // removeSection.removeButton is the binding's delete control, so the existing
        // busy/confirming/stale/invalidated gating still freezes the visible remove entry.
        val binding = WordDetailBinding(word.entry.key, deletionCredential, snapshotVersion, save, undo, removeSection.removeButton, star, mastered, note,
            form, count, dates, definition, source, sourceBadge, { !detailBusy },
            { app.repository.deletions.isStale(word.entry.key, deletionCredential) }, close,
            word.mastered, word.note, word.favorite)
        removeBinding = binding
        save.setOnClickListener { saveDetail(binding, word, close) }
        star.setOnClickListener { toggleDetailFavorite(binding) }
        // The shortcut stays exact-event-only: it prepares the latest event under this binding's
        // original credential and confirms that one event, never the dangerous full-history scope.
        // The remove entry opens the scope chooser, which never deletes by itself and still needs a
        // second confirmation for full history.
        undo.setOnClickListener { startExactUndoFlow(binding, word, close) }
        binding.update(word)
        return binding
    }
    /**
     * Owner check for a detail mutation (save or star). It is evaluated on the worker thread at that
     * mutation's admission point through both [StudyAdmission] and `canCommit`: before BEGIN for the
     * star path, and for the detail save inside the transaction after the current-row read and
     * conflict decision but before its business UPDATE. A detail closed by navigation/stop, a replaced
     * detail binding, or a word deleted/restored after the click can therefore never write. The same
     * check is also used as a cheap pre-transaction rejection, which is not the final admission.
     * Nothing here re-captures a credential; the binding's original key/credential are used.
     */
    private fun detailOwnerLive(binding: WordDetailBinding, epoch: Long): Boolean =
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
        val live = { detailOwnerLive(binding, epoch) }
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
        if (binding.isStale()) {
            binding.invalidate()
            toast("该词已删除或数据已恢复，请刷新后重新打开详情再操作")
            return
        }
        // Freeze exactly what this click saw: the binding's last saved baseline and the control
        // targets. Neither is re-read on the worker, so an old constructor snapshot can never be
        // mistaken for a newer baseline.
        val epoch = interactionEpoch
        val key = binding.key
        val credential = binding.deletionCredential
        val baselineMastered = binding.baselineMastered()
        val baselineNote = binding.baselineNote()
        val marked = binding.masteredValue()
        val noteText = binding.noteValue()
        val editedMastered = marked != baselineMastered
        val editedNote = noteText != baselineNote
        val live = { detailOwnerLive(binding, epoch) }
        val admission = StudyAdmission(live)
        detailBusy = true
        binding.beginBusy()
        app.worker.execute {
            val result = runCatching {
                app.repository.saveWordDetail(
                    key = key, mastered = marked, note = noteText,
                    baselineMastered = baselineMastered, baselineNote = baselineNote,
                    deletionCredential = credential, admission = admission, canCommit = live
                )
            }
            main.post {
                if (isDestroyed) return@post
                // This operation's own busy state is released even when its owner was retired while
                // the worker ran; otherwise the shared flag and the old controls would stay locked.
                // Detail mutations are serialized by detailBusy on the main thread, so this release
                // cannot clear a newer operation, and it only refreshes bindings that are still open.
                detailBusy = false
                binding.endBusy()
                releaseDetailBusy()
                // Every user-visible consequence of this save (toast, accepting the new baseline,
                // closing the detail) belongs only to the original live owner/epoch that started it.
                // A closed or replaced detail must not talk into a newer page, adopt a stale
                // baseline or dismiss a newer window. The shared busy release above still happens.
                val stillCurrent = inlineDetail === binding || dialogDetail === binding
                val originalOwnerLive = stillCurrent && !binding.isClosed() && !binding.isInvalidated() &&
                    foreground && epoch == interactionEpoch && !isFinishing
                val error = result.exceptionOrNull()
                // Re-check the original deletion credential: a deletion may have committed between the
                // worker result and this callback, so a late save must not be shown as current nor
                // re-enable old actions.
                val stale = app.repository.deletions.isStale(key, credential)
                when {
                    // Deletion wins over a late failure: never re-enable old actions on an invalidated word.
                    stale -> {
                        binding.invalidate()
                        // The old-word notice is still a save result: only the live original owner
                        // may see it, never a page that replaced this detail.
                        if (originalOwnerLive) toast("未保存：该词已删除")
                    }
                    error != null -> if (originalOwnerLive) toast("保存失败：${error.message}")
                    else -> when (result.getOrNull()) {
                        WordDetailSaveOutcome.SAVED -> if (originalOwnerLive) {
                            binding.acceptSavedFields(
                                mastered = if (editedMastered) marked else null,
                                note = if (editedNote) noteText else null
                            )
                            toast("已保存"); close?.invoke()
                        }
                        WordDetailSaveOutcome.UNCHANGED -> if (originalOwnerLive) {
                            // An idempotent save already has these targets in the database; keep the
                            // binding baseline with the truth so the next edit is not a false conflict.
                            binding.acceptSavedFields(
                                mastered = if (editedMastered) marked else null,
                                note = if (editedNote) noteText else null
                            )
                            toast("未修改，无需重复保存")
                        }
                        // Honest conflict: nothing was written, the user's draft stays in the field and
                        // the detail stays open so it can be re-read before another save.
                        WordDetailSaveOutcome.CONFLICT ->
                            if (originalOwnerLive) toast("内容已更新，请重新查看后再保存")
                        // Owner retired before admission: nothing was written; a closed/replaced
                        // detail must not show an old success or touch a newer window.
                        WordDetailSaveOutcome.REJECTED -> Unit
                        WordDetailSaveOutcome.STALE, null -> {
                            binding.invalidate()
                            if (originalOwnerLive) toast("未保存：该词已删除")
                        }
                    }
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
                "如果只想撤销最近一次查询，请取消后选择“只撤销最近一次查询”。"
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
    /** Shows the detail dialog and returns its binding so an explicit row action can use the same flow. */
    private fun showWordDialog(word: LearnedWord): WordDetailBinding {
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
        return binding
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
            addView(text("统计", if (wide) 30f else 28f, bold = true))
            addView(text("只记录真实查询与复习进度。", 16f, Paper.secondary))
        }
        header.addView(heading, if (inline) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        header.addView(button("刷新统计", true) { refresh(force = true) }, LinearLayout.LayoutParams(if (inline) -2 else -1, -2))
        content.addView(header); content.space(24)
        val queries = panel().apply {
            addView(statisticMetrics(listOf("今日查询" to stats.todayQueries, "今日新增" to stats.todayNew,
                "累计查询" to stats.totalQueries), inlineAt(contentWidthDp - 48)))
        }
        content.addView(queries); content.space(16)
        val sectionWidth = if (splitContent) (contentWidthDp - 24) / 2 else contentWidthDp
        val mastery = panel().apply {
            addView(text("复习进度", 20f, bold = true)); space(12)
            addView(statisticMetrics(listOf("待复习单词" to stats.learning, "已掌握单词" to stats.mastered), inlineAt(sectionWidth - 48)))
        }
        val frequent = panel().apply {
            addView(text("最常查询的词", 20f, bold = true))
            if (words.isEmpty()) {
                space(16)
                addView(text("暂无查询记录", 18f, bold = true).apply { gravity = Gravity.CENTER })
                addView(text("开始阅读并查词后，这里会显示你常查的单词。", 16f, Paper.secondary).apply { gravity = Gravity.CENTER })
                space(16)
            } else words.sortedByDescending { it.queryCount }.take(10).forEachIndexed { index, word ->
                if (index > 0) divider()
                addView(wordRow(word) { showWordDialog(word) })
            }
        }
        content.addView(pair(mastery, frequent, splitContent, 24))
        content.space(16)
        // The counting method is unchanged; only its explanation is collapsed here.
        content.addView(helpDisclosure("统计口径说明") {
            addView(text("按设备当前时区统计；仅计入未撤销的成功查询。", 16f, Paper.secondary))
            addView(text("待复习：尚未标记为已掌握的单词。\n已掌握：你在生词详情中标记并保存的单词。", 16f, Paper.secondary))
            addView(text("按真实查询次数排序，不代表考试频率。", 16f, Paper.secondary))
        })
    }
    private fun settings() {
        val screen = SettingsScreen(
            context = this,
            overlayStatus = overlayPermissionStatus(),
            notificationStatus = notificationPermissionStatus(),
            accessibilityStatus = accessibilityStatus(),
            // Short, honest display: an internal experience build, not an official release.
            versionText = "体验版 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
            provenanceText = RawKajwebDictionary.provenance(
                EcdictDictionary.SOURCE, EcdictDictionary.VERSION
            ),
            onOpenOverlayPermission = { openOverlayPermissionSettings() },
            onOpenNotificationSettings = { openNotificationSettings() },
            onOpenUsageGuide = { selectPage(MainPage.GUIDE) },
            onAccessibilityTextEnabledChange = { enabled -> setAccessibilityTextEnabled(enabled) },
            onOpenAccessibilitySettings = { openAccessibilitySettings() }
        )
        settingsScreen = screen
        // SettingsScreen already owns the ScrollView body; do not wrap it in scrolled().
        body.addView(screen.view, LinearLayout.LayoutParams(-1, -1))

        // Activity-owned backup actions/status keep the original callbacks, freeze rules and
        // request-code handling; the component only hosts the views.
        val exportButton = button("导出学习数据备份", true) { exportLearningBackup() }
        backupExportButton = exportButton
        screen.backupActionHost.addView(exportButton, LinearLayout.LayoutParams(-1, -2))
        screen.backupActionHost.space(8)
        val restore = button("从备份完整恢复") { startRestore() }
        restoreButton = restore
        screen.backupActionHost.addView(restore, LinearLayout.LayoutParams(-1, -2))

        val backupStatus = text("", 16f, Paper.secondary)
        backupStatusView = backupStatus
        screen.backupStatusHost.addView(backupStatus)
        updateBackupButtons()

        // Dictionary verification keeps its original async update target.
        screen.dictionaryStatusHost.addView(dictionaryStatus())

        // Capture session status/stop stay owned by the Activity and the existing statusListener.
        val captureStatus = text(CaptureStatus.state.message, 16f, Paper.secondary)
        floatingText = captureStatus
        screen.captureStatusHost.addView(captureStatus)
        val stopButton = button("关闭悬浮查词") { stopCapture(); toast("已请求停止查词") }
        stopCaptureButton = stopButton
        screen.captureActionHost.addView(stopButton, LinearLayout.LayoutParams(-1, -2))

        statusListener(CaptureStatus.state)

        // Version update status/actions. The coordinator stays the single owner of network,
        // download and install state; these views are rebuilt from it on every page build.
        renderUpdateSection()
        if (pendingUpdateScroll) {
            pendingUpdateScroll = false
            scrollToUpdateSection()
        }
    }

    // ---- Update check -------------------------------------------------------------------------

    /**
     * Lazily created foreground-only coordinator. It owns its own thread and all update state and
     * needs no service, notification permission or background scheduling; the Activity only renders
     * what it publishes, and only while this Activity is started.
     */
    private fun updateCenter(): UpdateCenter = updateCenter ?: UpdateCenter(
        activity = this,
        channel = UpdateChannel.fromId(getString(R.string.update_channel)),
        installedVersionCode = BuildConfig.VERSION_CODE,
        listener = {
            if (!isFinishing && !isDestroyed) {
                renderUpdateBanner()
                if (page == MainPage.SETTINGS) renderUpdateSection()
            }
        }
    ).also { updateCenter = it }

    /**
     * Non-blocking notice above the page body. It is part of the layout (not an overlay), so it can
     * never cover the guide, an editor or a system permission window; "稍后" hides it for this
     * version only, and 设置 → 关于 keeps the manual entry either way.
     */
    private fun renderUpdateBanner() {
        val host = updateBannerHost
        val available = updateCenter?.state as? UpdateState.Available
        if (available == null || page == MainPage.SETTINGS || practiceStep != null ||
            available.release.versionCode == dismissedUpdateVersion) {
            host.removeAllViews()
            host.visibility = View.GONE
            return
        }
        val release = available.release
        host.removeAllViews()
        host.visibility = View.VISIBLE
        host.setBackgroundColor(Paper.lightGreen)
        host.setPadding(dp(16), dp(6), dp(8), dp(6))
        val bar = row().apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(
            text(
                "新版本 ${release.versionName}（${release.versionCode}）· ${formatBytes(release.sizeBytes)}",
                15f, Paper.green, true
            ).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        bar.addView(button("稍后") {
            dismissedUpdateVersion = release.versionCode
            renderUpdateBanner()
        }, LinearLayout.LayoutParams(-2, -2))
        bar.addView(button("查看", true) {
            dismissedUpdateVersion = release.versionCode
            pendingUpdateScroll = true
            selectPage(MainPage.SETTINGS)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        host.addView(bar, LinearLayout.LayoutParams(-1, -2))
    }

    /** Rebuilds only the 关于 update block from the coordinator's current state. */
    private fun renderUpdateSection() {
        val host = settingsScreen?.updateHost ?: return
        val state = updateCenter?.state ?: UpdateState.Idle
        host.removeAllViews()
        val status = text(updateStatusText(state), 16f, updateStatusColor(state))
        updateStateView = status
        host.addView(status)
        host.addView(
            text(
                "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）。" +
                    "更新只读取 GitHub 上的公开测试发布列表，不发送设备或使用数据。",
                15f, Paper.secondary
            )
        )
        when (state) {
            is UpdateState.Available -> host.addView(
                text(
                    "下载并通过校验后，仍需在系统安装器中确认才会安装。",
                    15f, Paper.secondary
                )
            )
            is UpdateState.Downloading -> host.addView(
                text(
                    "离开应用会中止本次下载并删除未完成的文件，回来后可在本页重新下载。",
                    15f, Paper.secondary
                )
            )
            is UpdateState.Ready -> host.addView(
                text(
                    "已校验的文件只保存在本应用私有缓存，并通过只读内容 URI 交给系统安装器。",
                    15f, Paper.secondary
                )
            )
            is UpdateState.InstallBlocked -> host.addView(
                text(
                    "系统不会自动放开安装权限：请点“打开未知来源设置”，允许后返回本页再点安装。",
                    15f, Paper.warning
                )
            )
            else -> Unit
        }
        val actions = column()
        fun action(label: String, primary: Boolean = false, enabled: Boolean = true, tap: () -> Unit) {
            actions.addView(
                button(label, primary, tap).apply { isEnabled = enabled },
                LinearLayout.LayoutParams(-1, -2)
            )
            actions.space(8)
        }
        when (state) {
            UpdateState.Idle -> action("检查更新", true) { updateCenter().checkNow() }
            UpdateState.Checking -> action("正在检查…", enabled = false) {}
            UpdateState.UpToDate, UpdateState.NoRelease ->
                action("再次检查更新") { updateCenter().checkNow() }
            is UpdateState.Available -> {
                action("下载更新（${formatBytes(state.release.sizeBytes)}）", true) {
                    updateCenter().download()
                }
                action("再次检查更新") { updateCenter().checkNow() }
            }
            is UpdateState.Downloading ->
                action("取消下载") { updateCenter().cancelDownload() }
            is UpdateState.Ready -> action("安装", true) { updateCenter().install() }
            is UpdateState.InstallBlocked -> {
                action("打开未知来源设置", true) { updateCenter().openUnknownSourcesSettings() }
                action("我已允许，重试安装") { updateCenter().install() }
            }
            is UpdateState.InstallerOpened -> action("再次打开安装器") { updateCenter().install() }
            is UpdateState.InstallFailed -> {
                if (state.file.isFile) {
                    action("重试安装", true) { updateCenter().install() }
                } else {
                    action("重新检查更新", true) { updateCenter().checkNow() }
                }
            }
            is UpdateState.Failure -> action("重试检查", true) { updateCenter().checkNow() }
            UpdateState.Disabled -> Unit
        }
        host.addView(actions)
    }

    private fun updateStatusText(state: UpdateState): String = when (state) {
        UpdateState.Idle -> "尚未检查更新。启动时会限频检查（约每 24 小时一次），也可现在手动检查。"
        UpdateState.Checking -> "正在检查新版本…"
        UpdateState.UpToDate -> "已是最新版本。"
        UpdateState.NoRelease -> "发布页目前还没有本渠道的测试版。"
        is UpdateState.Available ->
            "发现新版本 ${state.release.versionName}（${state.release.versionCode}）·" +
                " 安装包 ${formatBytes(state.release.sizeBytes)}。"
        is UpdateState.Failure -> state.message
        is UpdateState.Downloading ->
            "正在下载 ${formatBytes(state.bytes)} / ${formatBytes(state.total)}…"
        is UpdateState.Ready -> "已下载，并通过大小、SHA-256 与签名校验。"
        is UpdateState.InstallBlocked ->
            "系统当前未允许本应用安装应用。" + (state.notice?.let { " $it" } ?: "")
        is UpdateState.InstallerOpened -> "已打开系统安装器：在系统里确认后才算安装；未确认前不算已安装。"
        is UpdateState.InstallFailed -> state.message
        UpdateState.Disabled -> "本构建未配置更新渠道，更新检查已停用。"
    }

    private fun updateStatusColor(state: UpdateState): Int = when (state) {
        is UpdateState.Failure -> Paper.error
        is UpdateState.Available, is UpdateState.Ready, is UpdateState.UpToDate -> Paper.green
        is UpdateState.InstallBlocked, is UpdateState.InstallFailed -> Paper.warning
        else -> Paper.secondary
    }

    /** Scrolls the settings page to the update block after the banner's "查看" tap. */
    private fun scrollToUpdateSection() {
        val screen = settingsScreen ?: return
        val target = updateStateView ?: return
        val scroll = screen.view as? ScrollView ?: return
        val content = scroll.getChildAt(0) ?: return
        // Offsets are only meaningful after layout, so the walk happens inside the posted runnable.
        scroll.post {
            if (settingsScreen !== screen || !target.isAttachedToWindow) return@post
            var offset = 0
            var node: View? = target
            while (node != null && node !== content) {
                offset += node.top
                node = node.parent as? View
            }
            scroll.smoothScrollTo(0, (offset - dp(16)).coerceAtLeast(0))
        }
    }

    private fun formatBytes(bytes: Long): String =
        "%.1f MiB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))

    /** Build-time badge snapshot; onStart refreshes it after a system settings round-trip. */
    private fun overlayPermissionStatus(): SettingsScreen.PermissionStatus {
        val granted = Settings.canDrawOverlays(this)
        return SettingsScreen.PermissionStatus(if (granted) "已允许" else "未允许", granted)
    }

    private fun notificationPermissionStatus(): SettingsScreen.PermissionStatus {
        if (Build.VERSION.SDK_INT < 33) {
            return SettingsScreen.PermissionStatus("系统通知无需单独授权", true)
        }
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return SettingsScreen.PermissionStatus(if (granted) "已允许" else "未允许", granted)
    }

    /**
     * Honest direct-reading state for the settings block: the in-app switch (the actual
     * authorisation), whether the user-enabled accessibility service is connected, and whether the
     * service is enabled system-wide at all. A connected service without the in-app switch still
     * reads nothing, so "healthy" requires both.
     */
    private fun accessibilityStatus(): SettingsScreen.AccessibilityStatus {
        val enabled = AccessibleTextBridge.isEnabled(this)
        val connected = AccessibleTextBridge.isConnected
        val systemEnabled = accessibilityServiceEnabledInSystem()
        val label = when {
            !enabled -> "未开启"
            connected -> "已开启 · 系统服务已连接"
            systemEnabled -> "已开启 · 等待系统服务连接"
            else -> "已开启 · 尚未在系统无障碍设置启用服务"
        }
        return SettingsScreen.AccessibilityStatus(enabled, connected, label, enabled && connected)
    }

    private fun accessibilityServiceEnabledInSystem(): Boolean = runCatching {
        val manager = getSystemService(AccessibilityManager::class.java)
            ?: return@runCatching false
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo?.serviceInfo ?: return@any false
            val expected = AccessibleTextService::class.java.name
            service.packageName == packageName &&
                (service.name == expected || service.name == ".capture.AccessibleTextService")
        }
    }.getOrDefault(false)

    private fun updateAccessibilityStatus() {
        settingsScreen?.updateAccessibilityStatus(accessibilityStatus())
    }

    /**
     * The switch only stores an explicit local authorisation after the short privacy note is
     * confirmed. It never enables the system accessibility service and never performs gestures;
     * the user must enable that service in Android settings themselves.
     */
    private fun setAccessibilityTextEnabled(enabled: Boolean) {
        if (!enabled) {
            AccessibleTextBridge.setEnabled(this, false)
            updateAccessibilityStatus()
            toast("已关闭页面文字直读，不再读取页面文字")
            return
        }
        if (AccessibleTextBridge.isEnabled(this)) {
            updateAccessibilityStatus()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("开启页面文字直读（可选）")
            .setMessage(
                "开启后，只有你在悬浮查词中实际勾画的短暂请求内，本机会读取当前可见页面的文字和" +
                    "单词位置，用于直接取词。结果只留在内存，不写盘、不记录、不上传；不读取密码或" +
                    "系统标记的敏感内容，也不执行点击或手势。仍需你本人到 Android 无障碍设置中启用" +
                    "“拾词页面文字直读（可选）”服务；关闭此开关即停止读取。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("同意并开启") { _, _ ->
                AccessibleTextBridge.setEnabled(this, true)
                updateAccessibilityStatus()
                toast("已允许页面文字直读；请到系统无障碍设置启用服务")
            }
            .create()
        // A cancelled confirmation must revert the switch to the still-disabled persisted state.
        dialog.setOnDismissListener { updateAccessibilityStatus() }
        dialog.show()
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast("无法打开系统无障碍设置：${it.message}") }
    }

    // These entries only open system settings pages. They never request projection/capture and do
    // not reuse permissionError(), which would stop an active capture session as a side effect.
    private fun openOverlayPermissionSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }.onFailure { toast("无法打开悬浮窗权限设置：${it.message}") }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }.onFailure { toast("无法打开通知设置：${it.message}") }
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
                        .putExtra(CaptureService.EXTRA_PRACTICE, pendingCapturePractice)
                    runCatching { startForegroundService(intent) }
                        .onSuccess { endPractice(false) }
                        .onFailure { permissionError("无法启动捕获服务：${it.message}") }
                } else permissionError("已取消屏幕捕获，查词未开启")
            }
            else -> {
                if (isFinishing || isDestroyed) return
                if (requestCode == backupExportSession.fileRequestCode || requestCode == restoreSession.importRequestCode ||
                    requestCode == restoreSession.safetyRequestCode) {
                    documentOperations[requestCode]?.let { picker ->
                        if (picker.isCurrent()) cancelDocument(picker) else picker.timeout.run()
                    }
                }
                if (consumeInterruptedBackup(requestCode, resultCode)) return
                if (lostExportRequestCode >= 0 && requestCode == lostExportRequestCode) {
                    // Consume before IO: another recreation must not wait for this result again.
                    lostExportRequestCode = -1
                    if (resultCode != RESULT_OK) { toast("已取消导出"); updateExportButton() }
                    else if (data?.data == null) { toast(PdfPlaceholderCleanup.Result.NO_URI.message); updateExportButton() }
                    else cleanLostPdf(data.data!!, requestCode)
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
    }
    private fun permissionError(message: String) { endPractice(false); stopCapture(); CaptureStatus.update(CapturePhase.ERROR, message); toast(message) }

    private fun manualLookup(raw: String, replaces: String? = null, originalRaw: String = raw,
                             credentialOverride: Long? = null, practiceOwner: PracticeLookupOwner? = null) {
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
                val users = (result as? LookupResult.Match)?.entries.orEmpty()
                    .associate { it.key to app.repository.userEntry(it.key) }
                val user = (result as? LookupResult.Match)?.entries?.singleOrNull()?.let { users[it.key] }
                val after = app.repository.changes.version
                LookupRead(result, user, after, before == after, users)
            }
            main.post {
                if (isDestroyed || page != MainPage.LOOKUP || !manualGate.accepts(token)) return@post
                fun noCard(message: String) {
                    toast(message)
                    resumePracticeInput(practiceOwner, token)
                }
                lookup.onSuccess { read ->
                    val result = read.result
                    when (result) {
                        LookupResult.Invalid -> noCard("请输入一个有效英文词头")
                        is LookupResult.Missing -> {
                            // The result belongs to the world the query started in. A restore/delete
                            // that committed while the worker ran must reject it explicitly instead of
                            // displaying a card whose save would bind to the new world.
                            if (app.repository.deletions.isStale(result.form, deletionCredential)) {
                                noCard("查询已失效（词条已删除或数据已恢复），请重新查询")
                            } else if (!read.isCurrent(app.repository.changes.version)) {
                                // A supplement (or any committed data) changed after this read: drawing
                                // the old result would hide the current user meaning, so no card is made.
                                showManualLookupExpired { resumePracticeInput(practiceOwner, token) }
                            } else showManualMissing(result.form, raw, replaces, deletionCredential, read.revision,
                                practiceOwner, token)
                        }
                        is LookupResult.Match -> {
                            fun deliver(entry: DictionaryEntry) {
                                if (isDestroyed || page != MainPage.LOOKUP || !manualGate.accepts(token)) return
                                if (app.repository.deletions.isStale(entry.key, deletionCredential)) {
                                    noCard("查询已失效（词条已删除或数据已恢复），请重新查询")
                                } else if (!read.isCurrent(app.repository.changes.version)) {
                                    showManualLookupExpired { resumePracticeInput(practiceOwner, token) }
                                } else showManualDefinition(originalRaw, result.form, entry, read.users[entry.key],
                                    replaces, deletionCredential, read.revision,
                                    practiceOwner.takeIf { isWaitingForPracticeLookup(it, token) })
                            }
                            if (!read.isCurrent(app.repository.changes.version)) {
                                showManualLookupExpired { resumePracticeInput(practiceOwner, token) }
                            } else if (result.entries.size == 1) deliver(result.entries.first())
                            else {
                                val content = panel()
                                content.addView(text("「${result.form}」请选择词义", 22f, bold = true))
                                content.addView(text("原词条与词形原型分别保留；选择前不收录。", 15f, Paper.secondary))
                                lateinit var choices: AlertDialog
                                var selected = false
                                for (entry in result.entries) content.addView(button(
                                    (if (entry.key == result.form) "原词条：" else "词形原型：") + entry.headword +
                                        "\n" + entry.meaning.lineSequence().first().take(64)) {
                                    if (!selected && activeDialog === choices && choices.isShowing) {
                                        selected = true
                                        choices.dismiss()
                                        deliver(entry)
                                    }
                                })
                                content.addView(button("取消") { choices.dismiss() })
                                choices = paperDialog(content)
                                showPaperDialog(choices, 640) {
                                    if (!selected) resumePracticeInput(practiceOwner, token)
                                }
                            }
                        }
                    }
                }.onFailure { noCard("词库不可用：${it.message}；未生成释义或新增记录") }
            }
        }
    }

    /**
     * A lookup read before a committed change is never drawn as a writable card: its old meaning (and
     * the separately captured user row) could hide a current supplement. This window is read-only and
     * requires a fresh explicit query.
     */
    private fun showManualLookupExpired(onDismiss: () -> Unit) {
        val content = panel()
        content.addView(text("查询已失效", 20f, bold = true))
        content.addView(text("查询期间词条或本机补充已更新。为避免显示或保存过期释义，本次旧结果已丢弃；请重新查询。", 16f, Paper.warning))
        content.space(8)
        lateinit var dialog: AlertDialog
        content.addView(button("关闭") { dialog.dismiss() })
        dialog = paperDialog(content)
        showPaperDialog(dialog, 640, onDismiss)
    }

    /**
     * The uncollected state of a manual lookup. Saving a new user headword here is not a query: it
     * creates no QueryEvent, count, vocabulary row or study card. Only the explicit "查询并收录"
     * action after a successful save enters the normal matched-card flow.
     */
    private fun showManualMissing(form: String, raw: String, replaces: String?, deletionCredential: Long,
                                  readRevision: Long, practiceOwner: PracticeLookupOwner?, token: Long) {
        // Display-entry check: a stale query must not create any writable card at all.
        if (app.repository.deletions.isStale(form, deletionCredential)) {
            toast("查询已失效（词条已删除或数据已恢复），请重新查询")
            resumePracticeInput(practiceOwner, token)
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
            // This is an explicit, user-initiated active query in the card's still-current world and
            // may enter personal vocabulary. It keeps the original query credential instead of
            // recapturing, so a later restore cannot be laundered into a fresh write identity.
            manualLookup(form, replaces, raw, deletionCredential,
                practiceOwner.takeIf { isWaitingForPracticeLookup(it, token) })
            dialog.dismiss()
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
            if (activeDialog === dialog) activeDialog = null
            resumePracticeInput(practiceOwner, token)
        }
        activeDialog?.dismiss(); activeDialog = dialog
        dialog.show()
        manualMissingGuard = guard
    }

    private fun showManualDefinition(raw: String, form: String, entry: DictionaryEntry,
                                      initialUser: UserDictionaryEntry?, replaces: String?,
                                      deletionCredential: Long, readRevision: Long, practiceOwner: PracticeLookupOwner?) {
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
        fun ownsPracticeCard(): Boolean = isPracticeLookupOwner(practiceOwner) && practiceStep == PracticeStep.CLOSE_RESULT &&
            activeDialog === dialog && manualGate.accepts(token)
        dialog.setOnDismissListener {
            if (ownsPracticeCard()) {
                closePracticeCoach(); practiceStep = PracticeStep.VOCABULARY
                main.post {
                    if (isPracticeLookupOwner(practiceOwner) &&
                        practiceStep == PracticeStep.VOCABULARY && activeDialog?.isShowing != true) updatePracticeCoach()
                }
            }
            // Closing this card window closes only the editors it opened; a newer card's editor lease
            // stays live, so its own save is still admitted on its owner.
            cancelPendingPersonalHandoff(handoffToken)
            closeSupplementEditorOwner(editorOwner)
            if (manualGuard === guard) manualGuard = null
            if (manualGate.accepts(token)) manualGate.next()
        }
        dialog.show()
        if (isWaitingForPracticeLookup(practiceOwner, token)) {
            practiceStep = PracticeStep.CLOSE_RESULT
            practiceCoach = PracticeCoach(dialog.window!!.decorView as ViewGroup,
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE), "这是真实词义与收录状态。看完点「关闭」，继续查看生词本。",
                onSkip = { if (ownsPracticeCard()) endPractice(true) },
                onNext = { if (ownsPracticeCard() && dialog.isShowing) dialog.dismiss() },
                onTargetGone = { if (ownsPracticeCard() && dialog.isShowing) endPractice(false) })
        }
        manualGuard = guard
        val event = QueryEvent(UUID.randomUUID().toString(), entry.key, raw, entry.key, System.currentTimeMillis())
        val saveCommitted = java.util.concurrent.atomic.AtomicBoolean(false)
        fun save() {
            if (saveCommitted.get()) return
            // A stale card (word deleted after this query started) must not start/retry a save.
            if (manualCardStale(guard)) { invalidateManualCard(guard); return }
            retry.visibility = View.GONE; status.text = "保存中…"
            app.worker.execute {
                if (saveCommitted.get()) return@execute
                val result = runCatching {
                    app.repository.record(event, entry, replaces, deletionCredential) {
                        manualGate.accepts(token) && !guard.invalid &&
                            readRevision == app.repository.changes.version &&
                            !app.repository.deletions.isStale(entry.key, deletionCredential)
                    }
                }
                // Ignore queued duplicate submissions after this event committed, rather than
                // treating its own published revision as a stale failed query.
                if (result.getOrNull() == true) saveCommitted.set(true)
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
            addView(text("只保存本机个人词义；不改原词库，不计查询或学习。", 13f, Paper.secondary))
        }
        content.addView(text(headword, 18f, bold = true))
        if (showOriginal) original?.let {
            content.addView(text("原词库释义（只读）", 15f, bold = true))
            content.addView(text("${it.partOfSpeech} ${it.meaning}".trim(), 15f))
            content.addView(text(PersonalDictionary.label(it), 12f, Paper.warning))
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
                    "已清空勾选（0 个）：请先勾选，或点“恢复全部”回到当前筛选全部"
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
    private fun consumeInterruptedBackup(code: Int, resultCode: Int): Boolean {
        val picker = interruptedDocumentRequests.remove(code) ?: return false
        toast(when {
            resultCode != RESULT_OK -> "已取消文件选择"
            picker == BackupPicker.IMPORT -> "恢复请求已失效，本次未读取备份、未恢复学习数据；请重新开始"
            picker == BackupPicker.LEGACY -> "文件请求已失效，本次未读取或写入备份，也未恢复学习数据；若选择了创建文件，目标位置可能留下空文件"
            else -> "保存请求已失效，本次未写入备份内容，也未恢复学习数据；目标位置可能留下空文件，请检查后重新开始"
        })
        return true
    }

    // Main owns the operation table and Task handle; workers only read the cancellation/deadline.
    private class DocumentOperation(val code: Int, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        @Volatile var cancelled = false
        var task: DocumentIo.Task? = null
        lateinit var timeout: Runnable
        fun isCurrent() = !cancelled && SystemClock.elapsedRealtime() < deadline
        fun checkCurrent() { if (!isCurrent()) throw IOException("文档操作已结束") }
    }

    private fun beginDocument(code: Int, apply: LearningRestoreSession.ApplyHandle? = null,
                              timeoutMs: Long = DOCUMENT_TIMEOUT_MS, onTimeout: () -> Unit): DocumentOperation {
        val operation = DocumentOperation(code, timeoutMs)
        operation.timeout = Runnable {
            if (documentOperations[code] !== operation) return@Runnable
            // The final DB transaction may finish after the deadline once admitted.
            if (apply != null && !apply.cancelBeforeAdmission()) return@Runnable
            cancelDocument(operation)
            if (!isDestroyed && !isFinishing) onTimeout()
        }
        documentOperations[code] = operation
        main.postDelayed(operation.timeout, timeoutMs)
        return operation
    }

    private fun cancelDocument(operation: DocumentOperation) {
        if (documentOperations[operation.code] !== operation) return
        documentOperations.remove(operation.code)
        operation.cancelled = true
        main.removeCallbacks(operation.timeout)
        operation.task?.cancel()
        operation.task = null
    }

    private fun launchBackupPicker(code: Int, kind: BackupPicker, intent: Intent,
                                   cancel: () -> Boolean, errorPrefix: String) {
        val operation = beginDocument(code, timeoutMs = BACKUP_PICKER_TIMEOUT_MS) {
            if (cancel()) {
                // No URI yet. Remember just the retired kind if the still-open picker returns.
                if (interruptedDocumentRequests.size == SAVED_BACKUP_REQUEST_LIMIT) {
                    interruptedDocumentRequests.remove(interruptedDocumentRequests.keys.first())
                }
                interruptedDocumentRequests[code] = kind
                backupRequestHighWatermark = maxOf(backupRequestHighWatermark, code)
                updateBackupButtons()
            }
        }
        runCatching { startActivityForResult(intent, code) }.onFailure {
            cancelDocument(operation)
            if (cancel()) {
                updateBackupButtons(); toast("$errorPrefix：${it.message}")
            }
        }
    }

    private fun <T> runDocument(operation: DocumentOperation, uri: Uri, discard: () -> Unit = {},
                                discardResult: (T) -> Unit = {}, work: (() -> Unit) -> T,
                                complete: (Result<T>) -> Unit) {
        if (!operation.isCurrent()) {
            discard()
            operation.timeout.run()
            return
        }
        val task = DocumentIo.submit(onSkipped = discard, target = uri.toString()) { task ->
            val checkCurrent = {
                if (task.isCancelled) throw IOException("文档操作已结束")
                operation.checkCurrent()
            }
            val result = try {
                runCatching { checkCurrent(); work(checkCurrent) }
            } finally {
                discard()
            }
            // Until delivery, this callback owns any result resource, even if post loses a race
            // with cancellation or the Activity is destroyed before the callback can consume it.
            if (task.isCancelled || !operation.isCurrent() || !main.post {
                    if (documentOperations[operation.code] === operation && operation.isCurrent() && !isDestroyed && !isFinishing) {
                        operation.task = null
                        complete(result)
                    } else result.onSuccess(discardResult)
                }) result.onSuccess(discardResult)
        }
        if (task == null) {
            discard() // Rejection did not take ownership.
            complete(Result.failure(IOException(DOCUMENT_REJECTED)))
        } else operation.task = task
    }

    private fun guardedInput(input: InputStream, checkpoint: () -> Unit) = object : InputStream() {
        override fun read(): Int {
            checkpoint(); return input.read().also { checkpoint() }
        }
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            checkpoint(); return input.read(bytes, offset, count).also { checkpoint() }
        }
    }

    private fun guardedOutput(output: OutputStream, checkpoint: () -> Unit) = object : OutputStream() {
        override fun write(value: Int) { checkpoint(); output.write(value); checkpoint() }
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            checkpoint(); output.write(bytes, offset, count); checkpoint()
        }
        override fun flush() { checkpoint(); output.flush(); checkpoint() }
    }

    private fun cleanLostPdf(uri: Uri, requestCode: Int) {
        cleaningLostExport = true
        updateExportButton()
        fun finish(message: String) {
            cleaningLostExport = false
            toast(message)
            updateExportButton()
        }
        val operation = beginDocument(requestCode) {
            finish("PDF 占位文件检查超时，未确认清理结果，请检查所选位置；提供方可能仍在处理")
        }
        val resolver = applicationContext.contentResolver
        runDocument(operation, uri, work = { checkpoint ->
            // Keep the existing PDF-only URI/metadata guards. Check cancellation again before
            // delete: metadata query or cursor close itself may have outlived the operation.
            PdfPlaceholderCleanup.clean(uri, { target ->
                require(target.scheme == ContentResolver.SCHEME_CONTENT && !target.authority.isNullOrEmpty())
                val path = target.pathSegments
                require((path.size == 2 && path[0] == "document") ||
                    (path.size == 4 && path[0] == "tree" && path[2] == "document"))
                require(DocumentsContract.getDocumentId(target).isNotEmpty())
                checkpoint()
                resolver.query(target, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_FLAGS,
                    Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    checkpoint()
                    val single = cursor.count == 1
                    checkpoint()
                    if (!single || !cursor.moveToFirst()) PdfPlaceholderCleanup.Metadata(null, false)
                    else {
                        checkpoint()
                        val sizeIndex = cursor.getColumnIndex(Document.COLUMN_SIZE)
                        val size = if (sizeIndex >= 0 && cursor.getType(sizeIndex) == Cursor.FIELD_TYPE_INTEGER) cursor.getLong(sizeIndex) else null
                        val flagsIndex = cursor.getColumnIndex(Document.COLUMN_FLAGS)
                        val flags = if (flagsIndex >= 0 && cursor.getType(flagsIndex) == Cursor.FIELD_TYPE_INTEGER) cursor.getInt(flagsIndex) else 0
                        val mimeIndex = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
                        val isFile = mimeIndex >= 0 && !cursor.isNull(mimeIndex) && cursor.getString(mimeIndex) != Document.MIME_TYPE_DIR
                        PdfPlaceholderCleanup.Metadata(size, isFile && (flags and Document.FLAG_SUPPORTS_DELETE) != 0)
                    }
                } ?: PdfPlaceholderCleanup.Metadata(null, false)
            }, { target ->
                checkpoint()
                DocumentsContract.deleteDocument(resolver, target)
            })
        }) { result ->
            cancelDocument(operation)
            finish(result.fold({ it.message }, { "未清理 PDF 占位文件：${it.message}" }))
        }
    }

    private fun savePdf(uri: Uri, snapshot: LearningExportSnapshot, requestCode: Int) {
        val operation = beginDocument(requestCode) {
            if (exportSession.cancel(requestCode)) {
                updateExportButton()
                toast("PDF 导出超时，未确认保存成功；提供方可能仍在处理，请检查目标文件")
            }
        }
        val resolver = applicationContext.contentResolver
        runDocument(operation, uri, work = { checkpoint ->
            checkpoint()
            val output = resolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
            output.use {
                checkpoint()
                val guarded = guardedOutput(it, checkpoint)
                PdfExporter.write(snapshot, guarded)
                guarded.flush()
            }
        }) { result ->
            cancelDocument(operation)
            if (exportSession.cancel(requestCode)) {
                updateExportButton()
                toast(result.fold({ "PDF 已写入所选位置" }, { "导出失败：${it.message}；请检查目标文件" }))
            }
        }
    }

    private fun updateBackupButtons() {
        val busy = backupExportSession.busy || restoreSession.busy
        backupExportButton?.isEnabled = !busy
        restoreButton?.isEnabled = !busy
        backupStatusView?.text = when {
            restoreSession.busy -> "正在恢复：准备并读回校验安全副本后才会覆盖；已开始的数据库覆盖将原子完成"
            backupExportSession.busy -> "正在准备或校验备份；文件读写最多等待 2 分钟"
            else -> BACKUP_PRIVACY
        }
    }

    private fun exportLearningBackup() {
        if (backupExportSession.busy || restoreSession.busy) return
        if (lostExportRequestCode >= 0 || cleaningLostExport) { toast("请先完成或取消当前 PDF 导出"); return }
        val content = panel()
        val dialog = paperDialog(content)
        content.addView(text("导出学习数据备份", 22f, bold = true))
        content.space(12)
        content.addView(text(BACKUP_PRIVACY, 16f, Paper.warning))
        content.space(12)
        content.addView(button("继续并选择备份位置", true) {
            if (activeDialog !== dialog || !dialog.isShowing || isFinishing || isDestroyed) return@button
            dialog.dismiss()
            prepareBackupExport()
        })
        content.addView(button("取消") { dialog.dismiss() })
        showPaperDialog(dialog, 520)
    }

    private fun prepareBackupExport() {
        if (restoreSession.busy) return
        val id = backupExportSession.begin() ?: return
        updateBackupButtons()
        val operation = beginDocument(id) {
            if (backupExportSession.cancelPreparation(id)) {
                updateBackupButtons(); toast("准备备份超时，未创建备份文件")
            }
        }
        freezeBackup(operation) { result ->
            cancelDocument(operation)
            result.onSuccess { snapshot ->
                if (!backupExportSession.freezeFile(id, snapshot)) {
                    snapshot.discard()
                    return@onSuccess
                }
                updateBackupButtons()
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(LearningBackupCodec.MIME_TYPE)
                    .putExtra(Intent.EXTRA_TITLE, "拾词-学习数据备份-${LocalDate.now()}${LearningBackupCodec.FILE_SUFFIX}")
                launchBackupPicker(id, BackupPicker.EXPORT, intent, { backupExportSession.cancel(id) }, "无法打开文件保存")
            }.onFailure {
                if (backupExportSession.cancelPreparation(id)) {
                    updateBackupButtons(); toast("准备备份失败：${it.message}")
                }
            }
        }
    }

    private fun freezeBackup(operation: DocumentOperation, complete: (Result<FrozenLearningBackupFile>) -> Unit) {
        val application = app
        application.worker.execute {
            if (!operation.isCurrent()) return@execute
            val result = runCatching {
                val file = File.createTempFile("shici-backup-write-", ".tmp", application.cacheDir)
                var transferred = false
                try {
                    operation.checkCurrent()
                    val frozen = application.repository.writeBackupSnapshot(file, BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE, System.currentTimeMillis())
                    FrozenLearningBackupFile(file, frozen.fingerprint, frozen.revision).also { transferred = true }
                } finally {
                    if (!transferred) file.delete()
                }
            }
            // Until the main callback takes ownership, only this handoff may delete the file.
            if (!operation.isCurrent() || !main.post {
                    if (documentOperations[operation.code] === operation && operation.isCurrent() && !isDestroyed && !isFinishing) {
                        complete(result)
                    } else result.getOrNull()?.discard()
                }) result.getOrNull()?.discard()
        }
    }

    private fun writeVerifiedBackup(uri: Uri, snapshot: FrozenLearningBackupFile, checkpoint: () -> Unit) {
        val resolver = applicationContext.contentResolver
        checkpoint()
        snapshot.file.inputStream().use { input ->
            checkpoint()
            val output = resolver.openOutputStream(uri, "wt") ?: error("无法写入所选备份位置")
            output.use {
                val guarded = guardedOutput(it, checkpoint)
                guardedInput(input, checkpoint).copyTo(guarded, 64 * 1024)
                guarded.flush()
            }
        }
        checkpoint() // Reopen only after write, flush and both closes succeeded.
        val input = resolver.openInputStream(uri) ?: error("无法读回所选备份文件")
        val verified = input.use { LearningBackupCodec.verifyExactBytes(guardedInput(it, checkpoint), snapshot.fingerprint) }
        check(verified) { "备份文件读回校验不一致" }
    }

    private fun handleBackupExportResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (backupExportSession.fileRequestCode != requestCode) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            backupExportSession.cancel(requestCode); updateBackupButtons(); toast("已取消备份"); return
        }
        val snapshot = backupExportSession.takeFileResult(requestCode) ?: return
        val operation = beginDocument(requestCode) {
            if (backupExportSession.cancel(requestCode)) {
                updateBackupButtons()
                toast("备份超时，未确认保存成功；提供方可能仍在处理，请检查目标文件")
            }
        }
        runDocument(operation, uri, discard = snapshot::discard,
            work = { writeVerifiedBackup(uri, snapshot, it) }) { result ->
            cancelDocument(operation)
            if (backupExportSession.cancel(requestCode)) {
                updateBackupButtons()
                toast(result.fold({ "学习数据备份已保存并读回校验一致" }, { "备份失败：${it.message}；请检查目标文件" }))
            }
        }
    }

    private fun startRestore() {
        if (backupExportSession.busy || restoreSession.busy) return
        if (lostExportRequestCode >= 0 || cleaningLostExport) { toast("请先完成或取消当前 PDF 导出"); return }
        val id = restoreSession.beginImport() ?: return
        updateBackupButtons()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        launchBackupPicker(id, BackupPicker.IMPORT, intent, { restoreSession.cancelImport(id) }, "无法打开文件选择")
    }

    private fun handleRestoreImportResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (restoreSession.importRequestCode != requestCode) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            restoreSession.cancelImport(requestCode); updateBackupButtons(); toast("已取消恢复"); return
        }
        if (!restoreSession.beginReading(requestCode, uri.toString())) return
        updateBackupButtons()
        val operation = beginDocument(requestCode) {
            if (restoreSession.cancelPreparation(requestCode)) {
                updateBackupButtons(); toast("读取备份超时，未恢复学习数据；提供方可能仍在处理")
            }
        }
        val application = app
        runDocument(operation, uri, discardResult = LearningBackupCodec.StagedImport::close, work = { checkpoint ->
            LearningBackupCodec.stageImport(application.cacheDir, checkpoint) {
                application.contentResolver.openInputStream(uri) ?: throw BackupFormatException("无法读取所选文件")
            }
        }) { result ->
            cancelDocument(operation)
            result.onSuccess { source ->
                if (!restoreSession.freezeStagedImport(requestCode, source)) {
                    source.close()
                    return@onSuccess
                }
                try {
                    updateBackupButtons()
                    showRestorePreview(source.preview)
                } catch (e: Exception) {
                    restoreSession.cancelPreview()
                    updateBackupButtons(); toast("无法显示恢复预览：${e.message}")
                }
            }.onFailure {
                if (restoreSession.cancelPreparation(requestCode)) {
                    updateBackupButtons(); toast("无法读取备份：${it.message}")
                }
            }
        }
    }

    private fun showRestorePreview(backup: LearningBackupPreview) {
        val content = panel()
        val dialog = paperDialog(content)
        content.addView(text("从备份完整恢复", 24f, bold = true)); content.space(12)
        content.addView(text("备份创建时间：${date(backup.createdAt)}", 18f))
        content.addView(text("词条 ${backup.wordCount} 个 · 事件 ${backup.eventCount} 条", 18f))
        if (backup.hasFavorites) {
            content.addView(text("收藏星标：${backup.favoriteCount} 个当前列表可见词条（隐藏词上的星标仍会随备份写回）", 18f))
        }
        if (backup.legacyNoStudyData) {
            content.addView(text("这是旧版格式（v1）备份：只含生词与查询记录，不含复习进度和个人补充词库。", 16f, Paper.warning, true))
            content.addView(text("恢复后复习进度为空、个人补充词库为空，所选范围与每日目标恢复为默认值。", 16f, Paper.warning))
        } else {
            content.addView(text("复习进度：已学 ${backup.stateCount} 词 · 反馈 ${backup.reviewCount} 条${if (backup.hasSession) " · 含 1 张未答卡" else ""}", 18f))
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
                    backup.favoriteCount == 0 && !backup.hasSession)
                "这是一个空备份：确认恢复后会清空当前全部学习数据、收藏星标和个人补充词库。" else
                "恢复会用备份内容完整替换当前全部学习数据、收藏星标与个人补充词库（不合并、不猜测冲突）；备份里没有的词、查询记录、收藏和补充词条也会被移除。",
                16f, Paper.warning, true))
            addView(text("恢复前必须先把当前数据另存为一个安全备份文件；只有写入关闭并读回校验一致、且数据未在此期间变化时才会覆盖。", 16f, Paper.secondary))
            addView(text(BACKUP_PRIVACY + " 备份不防恶意篡改。", 16f, Paper.secondary))
        }
        content.addView(warning); content.space(24)
        val cancel = button("取消") { dialog.dismiss() }.apply { background = shape(Paper.background, 8); setTextColor(Paper.ink) }
        val confirm = button("继续并选择安全备份位置", true) {
            if (restorePreviewDialog !== dialog || !dialog.isShowing || isFinishing || isDestroyed) return@button
            val safetyId = restoreSession.confirm() ?: return@button
            dialog.dismiss()
            updateBackupButtons()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(LearningBackupCodec.MIME_TYPE)
                .putExtra(Intent.EXTRA_TITLE, "拾词-恢复前安全备份-${LocalDate.now()}${LearningBackupCodec.FILE_SUFFIX}")
            launchBackupPicker(safetyId, BackupPicker.SAFETY, intent,
                { restoreSession.cancelSafety(safetyId) }, "无法打开安全备份保存位置")
        }
        content.addView(pair(cancel, confirm, inlineAt(minOf(resources.configuration.screenWidthDp - 32, 560) - 48), 12))
        showPaperDialog(dialog, 560) {
            if (restorePreviewDialog === dialog) {
                restorePreviewDialog = null
                if (restoreSession.cancelPreview()) updateBackupButtons()
            }
        }
        restorePreviewDialog = dialog
    }
    private fun handleRestoreSafetyResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (restoreSession.safetyRequestCode != requestCode) return
        val safetyUri = data?.data
        if (resultCode != RESULT_OK || safetyUri == null) {
            restoreSession.cancelSafety(requestCode); updateBackupButtons(); toast("已取消恢复：未选择安全备份位置"); return
        }
        if (safetyUri.toString() == restoreSession.pendingImportUri) {
            restoreSession.cancelSafety(requestCode); updateBackupButtons()
            toast("安全备份位置不能与导入的备份文件相同，已取消恢复"); return
        }
        val backup = restoreSession.beginStagedApply(requestCode) ?: return
        val apply = checkNotNull(restoreSession.applyHandle)
        updateBackupButtons()
        val operation = beginDocument(requestCode, apply) {
            if (restoreSession.finish(requestCode)) {
                updateBackupButtons()
                toast("安全备份等待超时，未覆盖学习数据；提供方可能仍在处理，请检查目标文件")
            }
        }
        fun finish(result: Result<RestoreOutcome>) {
            if (documentOperations[requestCode] !== operation || restoreSession.applyHandle !== apply ||
                isDestroyed || isFinishing || (!apply.isAdmitted && !operation.isCurrent())) return
            cancelDocument(operation)
            if (!restoreSession.finish(requestCode)) return
            updateBackupButtons()
            result.onSuccess { outcome ->
                when (outcome) {
                    RestoreOutcome.RESTORED ->
                        toast("已完整恢复：替换为备份中的 ${backup.wordCount} 个词、${backup.eventCount} 条事件、${backup.userEntryCount} 条用户补充")
                    RestoreOutcome.REVISION_CHANGED ->
                        toast("学习数据在准备期间有新变化，本次未覆盖；请重新开始恢复")
                }
            }.onFailure { toast("恢复失败，学习数据未改变：${it.message}；请检查安全备份位置") }
        }
        val application = app
        try {
            freezeBackup(operation) { frozen ->
                frozen.onSuccess { snapshot ->
                    val revision = snapshot.revision
                    runDocument(operation, safetyUri, discard = snapshot::discard,
                        work = { writeVerifiedBackup(safetyUri, snapshot, it) }) { verified ->
                        verified.onSuccess {
                            try {
                                application.worker.execute {
                                    if (!apply.isActive() || !operation.isCurrent()) return@execute
                                    val result = runCatching {
                                        val repository = application.repository
                                        if (repository.changes.version != revision) return@runCatching RestoreOutcome.REVISION_CHANGED
                                        // Admission takes ownership in the same critical section. Clear
                                        // can retire the UI, but cannot unlink this worker's source.
                                        val source = apply.admitStaged(operation::isCurrent) ?: return@execute
                                        source.use { repository.restoreBackup(it, revision) }
                                    }
                                    main.post { finish(result) }
                                }
                            } catch (e: RejectedExecutionException) {
                                finish(Result.failure(e))
                            }
                        }.onFailure { finish(Result.failure(it)) }
                    }
                }.onFailure { finish(Result.failure(it)) }
            }
        } catch (e: RejectedExecutionException) {
            finish(Result.failure(e))
        }
    }
    private fun date(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
