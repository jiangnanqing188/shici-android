package com.shici.app.translation

/**
 * Pure state and ownership logic for one translation page.
 *
 * There is no Android UI, database, repository, query-event or persistence code here. A session:
 *
 * - is the optional offline-translation component's state machine: nothing here downloads a model
 *   or translates until the user has explicitly opted in;
 * - checks the already-downloaded model when [start] is called (a local query, never a download);
 * - downloads only after [downloadModel] is called from the user's explicit confirmation, and a
 *   second confirmation while Downloading is ignored (no duplicate same-page SDK request);
 * - translates only while [State.modelReady] is true, so no hidden download can happen first;
 * - rejects blank input and input longer than [maxChars] with an explicit message instead of
 *   silently truncating it;
 * - tags every asynchronous result with the current request id. [cancelTranslation] and [close]
 *   advance that id, so an old ML Kit callback can never overwrite a newer state after the user
 *   cancelled, closed the page, or the service stopped the overlay;
 * - retries a failed/cancelled translation only from the text the caller says is currently
 *   visible ([retry] with `currentText`), never from a hidden older submission. The accepted
 *   snapshot, the engine request and the published result share that one text identity;
 * - supports one explicit, opt-in auto-translate intent ([requestAutoTranslate]) for a capture
 *   entry that already froze its OCR sentence. It is off by default, runs at most once per session
 *   after a Ready model is observed, and is discarded by [cancelAutoIntent], an explicit
 *   [translate]/[retry] or [close], so an ordinary page open can never translate by itself. An
 *   explicit refusal ([declineDownload]) retires the pending intent permanently: a later model
 *   download in the same page only enables the component and never translates the declined
 *   sentence;
 * - never reopens a window, writes a record or transmits the sentence itself.
 *
 * Threading: every method and every engine callback must run on the main thread. The screen and
 * [OfflineTranslation] both guarantee that. [close] is idempotent and is the final owner retirement.
 */
class TranslationSession(
    private val engine: TranslationEngine,
    private val onState: (State) -> Unit,
    private val maxChars: Int = MAX_SOURCE_CHARS
) {
    companion object {
        /** 5000 UTF-16 code units, as counted by [String.length]. */
        const val MAX_SOURCE_CHARS = 5000
    }

    enum class Phase {
        CHECKING_MODEL,
        NEEDS_MODEL,
        DOWNLOADING,
        READY,
        TRANSLATING,
        DONE,
        ERROR,
        CANCELLED,
        CLOSED
    }

    enum class Retry { NONE, CHECK_MODEL, DOWNLOAD, TRANSLATE }

    data class State(
        val phase: Phase,
        val message: String = "",
        val result: String = "",
        val modelReady: Boolean = false,
        val retry: Retry = Retry.NONE,
        /**
         * The exact submitted snapshot that the current [result] belongs to. Empty until a
         * translation snapshot has been accepted (model check/download and rejected blank or
         * oversize input do not create a result of their own).
         */
        val sourceText: String = ""
    )

    private var started = false
    private var closed = false
    private var requestId = 0L
    private var phase = Phase.CHECKING_MODEL
    private var modelReady = false
    private var result = ""
    private var retry = Retry.NONE
    private var lastSubmitted = ""
    private var sourceText = ""
    // One-shot capture-entry intent. It is armed only by an explicit caller, never by the default
    // TranslationScreen/MainActivity path, and it can submit at most once after Ready.
    private var autoPending = false
    private var autoSubmitted = false
    private var autoText = ""
    // Set when the user explicitly refuses the optional component. It retires this page's
    // capture-entry intent for the rest of the page's lifetime, so a later download consent only
    // enables the component and cannot translate the declined text.
    private var autoRetired = false

    /** Starts the initial model query exactly once. Construction alone never touches the SDK. */
    fun start() {
        if (closed || started) return
        started = true
        checkModel()
    }

    /**
     * Arms the one explicit auto-translation intent for this session. [text] is the frozen OCR
     * sentence from this capture entry. It stays pending through a Missing-model check so the
     * user's later explicit download confirmation in the same still-open page can continue this
     * exact text once; it never starts a download, never retries in the background and is cleared
     * by [cancelAutoIntent], an explicit [translate]/[retry] or [close]. Calling it more than once,
     * after the single submission or after an explicit refusal ([declineDownload]) is a no-op.
     */
    fun requestAutoTranslate(text: String) {
        if (closed || autoRetired || autoSubmitted || autoPending || text.isBlank()) return
        autoText = text
        autoPending = true
        // Covers a caller that arms the intent after an already-delivered Ready state.
        maybeAutoTranslate()
    }

    /**
     * Discards the pending one-shot intent without touching a translation already submitted by the
     * user. Called when the visible source is edited, so a hidden older OCR snapshot can never be
     * translated later.
     */
    fun cancelAutoIntent() {
        autoPending = false
        autoText = ""
    }

    /**
     * Explicit user confirmation: "下载离线模型（仅 Wi-Fi）". Ignored while another download or a
     * translation is running, and when the model is already ready.
     */
    fun downloadModel() {
        if (closed) return
        if (phase == Phase.CHECKING_MODEL || phase == Phase.DOWNLOADING || phase == Phase.TRANSLATING) return
        if (modelReady) {
            publish(Phase.READY, "离线 AI 翻译组件已启用 · 模型在本机 · 译文由设备本机生成")
            return
        }
        val token = ++requestId
        publish(
            Phase.DOWNLOADING,
            "正在下载离线模型（或等待 Wi-Fi）… 下载由系统调度，本页不显示准确进度。可以离开或关闭页面，" +
                "但这不会取消系统下载，系统下载可能继续；再次进入只会重新检查本地状态。",
            result = ""
        )
        engine.downloadModel download@{ outcome ->
            if (!isCurrent(token)) return@download
            when (outcome) {
                ModelDownloadResult.Ready -> {
                    modelReady = true
                    publish(
                        Phase.READY,
                        "离线模型已下载并启用，可以开始翻译。下载成功只代表模型可用，真实译文质量与设备兼容性仍需单独验证。"
                    )
                    // Only the user-confirmed download in this still-valid session may resume the
                    // frozen capture-entry sentence, and only once.
                    maybeAutoTranslate()
                }
                is ModelDownloadResult.Failed -> publish(
                    Phase.ERROR,
                    "模型下载失败：${outcome.message}。不会自动改用移动数据或在线翻译；请确认已连接 Wi-Fi 后主动重试。",
                    retry = Retry.DOWNLOAD
                )
            }
        }
    }

    /**
     * The user chose "暂不下载". No download is started, translation/editing stays locked, and a
     * later explicit "下载并启用（仅 Wi-Fi）" tap is still required before anything is downloaded.
     *
     * This is also the explicit refusal that retires this capture entry's pending auto intent. A
     * later download consent in the same page is only an enable action: it must not translate the
     * declined frozen sentence. Only the user's new explicit [translate]/[retry] on the visible
     * text, or a new capture page, may translate afterwards.
     */
    fun declineDownload() {
        if (closed || phase != Phase.NEEDS_MODEL) return
        // Permanently retire this page's auto intent before publishing the refusal. Even if a
        // late caller re-arms it, the later download consent cannot translate the declined text.
        autoRetired = true
        autoPending = false
        autoText = ""
        publish(
            Phase.NEEDS_MODEL,
            "已暂不下载。在你主动点“下载并启用（仅 Wi-Fi）”前不会发起模型下载，也不会开放编辑或翻译。"
        )
    }

    /**
     * Translates [text] exactly as provided. Returns false when the request was not started; the
     * input is never modified or truncated. Blank/oversize input is rejected explicitly.
     */
    fun translate(text: String): Boolean {
        // An explicit user request supersedes the one capture-entry auto intent.
        cancelAutoIntent()
        return submit(text)
    }

    /** Shared validation/submission path for an explicit translate and the one-shot auto intent. */
    private fun submit(text: String): Boolean {
        if (closed) return false
        if (phase == Phase.CHECKING_MODEL || phase == Phase.DOWNLOADING || phase == Phase.TRANSLATING) {
            return false
        }
        if (text.isBlank()) {
            publish(Phase.ERROR, "请先输入要翻译的英文句子；本次未翻译。", retry = Retry.NONE)
            return false
        }
        if (text.length > maxChars) {
            publish(
                Phase.ERROR,
                "句子过长：当前 ${text.length} 个 UTF-16 字符，上限 $maxChars。本次未翻译、未截断；请缩短原文后重新翻译。",
                retry = Retry.NONE
            )
            return false
        }
        if (!modelReady) {
            publish(Phase.NEEDS_MODEL, "需要先由你确认下载并启用离线 AI 翻译模型；本次未发起翻译。")
            return false
        }
        val token = ++requestId
        lastSubmitted = text
        sourceText = text
        publish(Phase.TRANSLATING, "翻译中…（本机处理，不上传原文）", result = "")
        engine.translate(text) translate@{ outcome ->
            if (!isCurrent(token)) return@translate
            when (outcome) {
                is LocalTranslationResult.Translated -> {
                    val translated = outcome.text.trim()
                    if (translated.isEmpty()) {
                        publish(
                            Phase.ERROR,
                            "翻译返回空结果；原文已保留，请检查原文或重试。",
                            result = "",
                            retry = Retry.TRANSLATE
                        )
                    } else {
                        publish(Phase.DONE, "翻译完成 · 机器翻译仅供参考", result = translated)
                    }
                }
                is LocalTranslationResult.Failed -> publish(
                    Phase.ERROR,
                    "翻译失败：${outcome.message}。原文已保留，可重试。",
                    result = "",
                    retry = Retry.TRANSLATE
                )
            }
        }
        return true
    }

    /**
     * Cancels only this session's view of an in-flight translation. The already-started on-device
     * computation may finish briefly, but its result is retired by the request id and never shown.
     */
    fun cancelTranslation() {
        if (closed || phase != Phase.TRANSLATING) return
        requestId++
        publish(
            Phase.CANCELLED,
            "已取消本次翻译；原文保留，可重新翻译。已开始的本机计算可能短暂继续，但结果不会显示。",
            result = "",
            retry = Retry.TRANSLATE
        )
    }

    /**
     * Retries the pending action using [currentText], the text currently visible in the caller's
     * editor. For [Retry.TRANSLATE] the text is validated and submitted through the same path as
     * [translate], so blank/oversize input is refused without contacting the engine, the accepted
     * snapshot becomes [State.sourceText], and the eventual result is bound to that request id.
     * A failed or cancelled sentence that the user has since edited is therefore never replaced
     * by a hidden older snapshot. [Retry.CHECK_MODEL] and [Retry.DOWNLOAD] ignore [currentText]
     * and never start a translation; the model download still requires the user's earlier or
     * explicit confirmation.
     */
    fun retry(currentText: String) {
        if (closed) return
        when (retry) {
            Retry.CHECK_MODEL -> checkModel()
            Retry.DOWNLOAD -> downloadModel()
            Retry.TRANSLATE -> translate(currentText)
            Retry.NONE -> Unit
        }
    }

    /**
     * Legacy no-argument retry for callers that own no editable input (for example a pure state
     * machine host). It cannot observe a visible editor, so for [Retry.TRANSLATE] it re-submits
     * this session's last submitted snapshot. A screen that shows an editable source field must
     * call [retry] with the currently visible text instead; otherwise an edited sentence would be
     * silently replaced by the hidden old snapshot.
     */
    fun retry() {
        if (closed) return
        when (retry) {
            Retry.CHECK_MODEL -> checkModel()
            Retry.DOWNLOAD -> downloadModel()
            Retry.TRANSLATE -> if (lastSubmitted.isNotEmpty()) translate(lastSubmitted)
            Retry.NONE -> Unit
        }
    }

    /** Final owner retirement. No callback is emitted afterwards; late SDK results are ignored. */
    fun close() {
        if (closed) return
        closed = true
        requestId++
        autoPending = false
        autoText = ""
        phase = Phase.CLOSED
        engine.close()
    }

    private fun maybeAutoTranslate() {
        if (closed || autoRetired || !autoPending || autoSubmitted || !modelReady) return
        val text = autoText
        autoPending = false
        autoSubmitted = true
        autoText = ""
        if (text.isNotBlank()) submit(text)
    }

    private fun checkModel() {
        val token = ++requestId
        modelReady = false
        sourceText = ""
        publish(Phase.CHECKING_MODEL, "正在检查本机离线 AI 翻译模型…", result = "")
        engine.checkModel check@{ state ->
            if (!isCurrent(token)) return@check
            when (state) {
                LocalModelState.Ready -> {
                    modelReady = true
                    publish(Phase.READY, "离线 AI 翻译组件已启用 · 模型在本机 · 译文由设备本机生成")
                    // At most one submission per agreed Ready observation. Missing/Unavailable do
                    // nothing here; only an explicit download confirmation or retry can get here.
                    maybeAutoTranslate()
                }
                LocalModelState.Missing -> publish(
                    Phase.NEEDS_MODEL,
                    "尚未下载英译中离线模型。本组件在下载并启用前不会开放翻译。"
                )
                is LocalModelState.Unavailable -> publish(
                    Phase.ERROR,
                    "无法确认离线 AI 翻译组件状态：${state.message}。请确认设备支持 Google Play 服务后重试。",
                    retry = Retry.CHECK_MODEL
                )
            }
        }
    }

    private fun isCurrent(token: Long): Boolean = !closed && token == requestId

    private fun publish(
        newPhase: Phase,
        message: String,
        result: String = this.result,
        retry: Retry = Retry.NONE,
        sourceText: String = this.sourceText
    ) {
        if (closed) return
        phase = newPhase
        this.result = result
        this.retry = retry
        this.sourceText = sourceText
        onState(State(newPhase, message, result, modelReady, retry, sourceText))
    }
}
