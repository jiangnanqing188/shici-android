package com.shici.app.translation

/**
 * Pure state and ownership logic for one translation page.
 *
 * There is no Android UI, database, repository, query-event or persistence code here. A session:
 *
 * - checks the already-downloaded model when [start] is called (a local query, never a download);
 * - downloads only after [downloadModel] is called from the user's explicit confirmation;
 * - translates only while [State.modelReady] is true, so no hidden download can happen first;
 * - rejects blank input and input longer than [maxChars] with an explicit message instead of
 *   silently truncating it;
 * - tags every asynchronous result with the current request id. [cancelTranslation] and [close]
 *   advance that id, so an old ML Kit callback can never overwrite a newer state after the user
 *   cancelled, closed the page, or the service stopped the overlay;
 * - retries a failed/cancelled translation only from the text the caller says is currently
 *   visible ([retry] with `currentText`), never from a hidden older submission. The accepted
 *   snapshot, the engine request and the published result share that one text identity;
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

    /** Starts the initial model query exactly once. Construction alone never touches the SDK. */
    fun start() {
        if (closed || started) return
        started = true
        checkModel()
    }

    /**
     * Explicit user confirmation: "下载离线模型（仅 Wi-Fi）". Ignored while another download or a
     * translation is running, and when the model is already ready.
     */
    fun downloadModel() {
        if (closed) return
        if (phase == Phase.CHECKING_MODEL || phase == Phase.DOWNLOADING || phase == Phase.TRANSLATING) return
        if (modelReady) {
            publish(Phase.READY, "离线翻译模型已就绪 · 文本在本机处理")
            return
        }
        val token = ++requestId
        publish(Phase.DOWNLOADING, "正在下载离线模型（或等待 Wi-Fi）… 下载由系统调度，无准确进度；请保持 Wi-Fi。", result = "")
        engine.downloadModel download@{ outcome ->
            if (!isCurrent(token)) return@download
            when (outcome) {
                ModelDownloadResult.Ready -> {
                    modelReady = true
                    publish(Phase.READY, "离线模型已下载完成，可以开始翻译。")
                }
                is ModelDownloadResult.Failed -> publish(
                    Phase.ERROR,
                    "模型下载失败：${outcome.message}。不会自动改用移动数据或在线翻译；请确认已连接 Wi-Fi 后重试。",
                    retry = Retry.DOWNLOAD
                )
            }
        }
    }

    /** The user chose "暂不下载". No download is started and translation stays unavailable. */
    fun declineDownload() {
        if (closed || phase != Phase.NEEDS_MODEL) return
        publish(Phase.NEEDS_MODEL, "已暂不下载。在你主动点“下载”前不会发起模型下载，也无法离线翻译。")
    }

    /**
     * Translates [text] exactly as provided. Returns false when the request was not started; the
     * input is never modified or truncated. Blank/oversize input is rejected explicitly.
     */
    fun translate(text: String): Boolean {
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
            publish(Phase.NEEDS_MODEL, "需要先由你确认下载离线翻译模型；本次未发起翻译。")
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
        phase = Phase.CLOSED
        engine.close()
    }

    private fun checkModel() {
        val token = ++requestId
        modelReady = false
        sourceText = ""
        publish(Phase.CHECKING_MODEL, "正在检查本机离线翻译模型…", result = "")
        engine.checkModel check@{ state ->
            if (!isCurrent(token)) return@check
            when (state) {
                LocalModelState.Ready -> {
                    modelReady = true
                    publish(Phase.READY, "离线翻译模型已就绪 · 文本在本机处理")
                }
                LocalModelState.Missing -> publish(Phase.NEEDS_MODEL, "首次翻译前需要下载英→中离线模型。")
                is LocalModelState.Unavailable -> publish(
                    Phase.ERROR,
                    "无法确认离线翻译模型状态：${state.message}。请确认设备支持 Google Play 服务后重试。",
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
