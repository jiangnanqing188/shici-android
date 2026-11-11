package com.shici.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.shici.app.core.DictionaryEntry
import com.shici.app.core.SentenceWord
import com.shici.app.core.SentenceWords
import com.shici.app.translation.OfflineTranslation
import com.shici.app.translation.TranslationSession

/**
 * The optional English -> Chinese offline translation component, assembled from plain Android views.
 *
 * It is intentional that this component can live either in an Activity body or in a Service-owned
 * `TYPE_APPLICATION_OVERLAY` window: it never uses a Dialog, never needs an Activity and never
 * writes data. The host owns the window/view lifetime; [close] only retires the ML Kit session and
 * makes every late callback inert. For an overlay host, set [onCloseRequested] to remove the
 * window and return to the floating button.
 *
 * Product/component gate enforced here:
 * - opening the page (ordinary navigation, rotation, resume, page rebuild) performs only a local
 *   model-state query. It never downloads a model and never translates; [autoTranslate] is the one
 *   explicit capture-entry opt-in that submits the already frozen OCR text once after the local
 *   model is observed Ready (or after the user explicitly confirms the model download in the same
 *   still-open page), and any visible edit, explicit request or close discards that intent;
 * - until the local model is Ready, the frozen source is shown read-only (text and caret are kept),
 *   the translation/clear actions stay disabled and only the component explanation, the explicit
 *   "下载并启用（仅 Wi-Fi）" confirmation, "暂不下载" and a return action are offered;
 * - the model is downloaded only after that explicit confirmation, with the SDK's Wi-Fi condition
 *   and the real `downloadModelIfNeeded`; the page never fakes a percentage, size or Ready state,
 *   and repeated taps cannot start a second same-page download request;
 * - leaving/closing the page does not cancel a system download (the SDK exposes no reliable cancel
 *   API); the wording says the system download may continue and a re-entry only re-checks local
 *   state instead of silently downloading again;
 * - the sentence is processed on device by ML Kit; this app does not upload it, use an online
 *   translator, a paid service or an API key;
 * - ML Kit may contact Google for model updates and performance/usage metrics, so the wording
 *   below never claims that the whole app is offline or that ML Kit is a newly integrated
 *   open-source LLM;
 * - input is never silently truncated: more than [TranslationSession.MAX_SOURCE_CHARS] UTF-16
 *   characters is refused with an explicit message and the text is preserved;
 * - copy happens only on an explicit "复制译文" tap, and only while a current non-blank result is
 *   shown under the same Ready/admission gate as editing and translating; [close] retires it.
 * - an optional, host-supplied inline word section lets the user tap one English fragment of the
 *   exact visible source and ask the host to look it up in the local dictionary. The section is
 *   independent of the ML Kit model state: it stays usable while the model is checking, missing or
 *   downloading, and it never calls the translation session, downloads anything or starts a second
 *   window/dialog. A tap does not close the page or change the source/caret/translation result.
 *
 * All methods must be called on the main thread.
 */
class TranslationScreen(
    context: Context,
    initialText: String = "",
    initialSelectionStart: Int = initialText.length,
    initialSelectionEnd: Int = initialSelectionStart,
    /**
     * One-shot capture-entry intent: submit [initialText] once after the local model is Ready.
     * Default false, so an Activity page/navigation/rotation open never translates by itself.
     */
    private val autoTranslate: Boolean = false,
    /** Optional secondary action for a multi-word capture: return to per-word selection. */
    private val onSelectionRequested: (() -> Unit)? = null,
    /**
     * Adds the CaptureService-only inline "点英文词查释义" section. Default false keeps every
     * existing Activity construction source-compatible and exposes no new lookup entry there.
     */
    private val inlineWordLookup: Boolean = false
) {
    /** Plain snapshot of the editor, used by the host to survive navigation/rotation. */
    data class Draft(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /**
     * One explicit tap on one fragment of [source]. [requestId] is invalidated by source edits,
     * another tap, collapse-independent page retirement and [close]; a host callback carrying an
     * older id must not display a result or commit a save.
     */
    data class WordTap(
        val requestId: Long,
        val word: String,
        val start: Int,
        val end: Int,
        val source: String
    )

    /** A resolved first dictionary meaning plus the exact source/notice strings to keep visible. */
    data class WordDisplay(
        val requestId: Long,
        val queried: String,
        val headword: String,
        val partOfSpeech: String,
        val meaning: String,
        val sourceNote: String
    )

    /** Optional host hook for the Back key and the "返回/关闭" button. */
    var onCloseRequested: (() -> Unit)? = null

    /**
     * Supplied by the CaptureService host. Only an explicit word tap calls it; rendering,
     * construction, restore, translation and collapsing never do.
     */
    var onWordRequested: ((WordTap) -> Unit)? = null

    /**
     * Called when the page retires the request id still in flight (source edit, another word tap,
     * or close). The host must reject that request's late lookup/save; this callback never creates
     * a query and never displays anything.
     */
    var onWordRequestRetired: ((Long) -> Unit)? = null

    private val context: Context = context
    private val root: ScreenRoot
    val view: View

    private lateinit var session: TranslationSession

    private val input: EditText
    private val counter: TextView
    private val status: TextView
    private lateinit var statusDetail: TextView
    private val consentCard: LinearLayout
    private val translateButton: Button
    private val clearButton: Button
    private val retryButton: Button
    private val cancelButton: Button
    private val resultCard: LinearLayout
    private val resultView: TextView
    private val copyButton: Button

    // Inline word section state. All of it is main-thread only; the host owns the real query,
    // deletion credential, save admission and lifecycle checks.
    private lateinit var wordToggle: Button
    private lateinit var wordBody: LinearLayout
    private lateinit var wordTokens: TextView
    private lateinit var wordStatus: TextView
    private lateinit var wordMeaning: TextView
    private lateinit var wordChoices: LinearLayout
    private lateinit var wordSource: TextView
    private var wordSourceGeneration = 0L
    private var wordRequestId = 0L
    private var wordActive: SentenceWord? = null
    private var wordDisplayed: SentenceWord? = null
    private var wordBusy = false
    private var wordDelivered = false
    private var wordRejectedId = -1L

    private var closing = false

    init {
        root = ScreenRoot(context) { requestClose() }.apply {
            setBackgroundColor(Paper.background)
            isClickable = true
            isFocusableInTouchMode = true
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        }

        val content = context.column()

        content.addView(context.text("离线翻译", 28f, bold = true))
        content.addView(context.text("Google ML Kit · 英译中（可选组件）", 15f, Paper.secondary))
        content.space(12)

        // One short line for the current state. Long explanations live in the collapsed
        // "模型与隐私说明" below; errors keep their cause and next step here.
        status = context.text("", 15f, Paper.secondary)
        content.addView(status)

        // A retry is a new translation request for the text that is visible right now, not a
        // replay of a hidden older submission. The session re-validates blank/oversize/model
        // state before submitting and binds the result to this snapshot.
        retryButton = context.button("重试") { session.retry(input.text.toString()) }
        retryButton.visibility = View.GONE
        content.addView(retryButton)

        cancelButton = context.button("取消本次翻译") { session.cancelTranslation() }
        cancelButton.visibility = View.GONE
        content.addView(cancelButton)
        content.space(8)

        // The inline word section is deliberately above the model-consent card and the source
        // editor: it is a local-dictionary capability, so it stays obviously reachable while the
        // ML Kit model is checking, missing or downloading. It never starts the translation session.
        if (inlineWordLookup) {
            val wordCard = context.column().apply {
                setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
                background = context.shape(Paper.surface, 12, true)
            }
            // One short, obvious control. Expanding shows the same source as clickable spans in a
            // single compact paragraph; collapsing hides only this sub-area and leaves the source,
            // caret, translation state and result untouched.
            wordToggle = context.button("点英文词查释义") { toggleWordArea() }
            wordCard.addView(wordToggle)
            wordBody = context.column().apply { visibility = View.GONE }
            wordBody.addView(
                context.text(
                    "在下面原文词上直接点一下；只查本机词库，不重新翻译，不关闭译句。",
                    13f,
                    Paper.secondary
                )
            )
            wordTokens = TextView(context).apply {
                textSize = 15f
                setTextColor(Paper.ink)
                setPadding(0, context.dp(6), 0, context.dp(6))
                setLineSpacing(0f, 1.15f)
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = Color.TRANSPARENT
                isClickable = true
                isFocusable = false
            }
            wordBody.addView(wordTokens)
            wordStatus = context.text("", 14f, Paper.secondary)
            wordBody.addView(wordStatus)
            wordMeaning = context.text("", 18f).apply { visibility = View.GONE }
            wordBody.addView(wordMeaning)
            wordSource = context.text("", 13f, Paper.warning).apply { visibility = View.GONE }
            wordBody.addView(wordSource)
            wordChoices = context.column()
            wordBody.addView(wordChoices)
            wordCard.addView(wordBody)
            content.addView(wordCard)
            content.space(16)
        }

        // Visible only while the local model is Missing/declined. The essential consent points
        // stay above the download button; only the long-form privacy text is collapsed later.
        // Download starts exclusively from this explicit user confirmation.
        consentCard = context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.surface, border = true)
        }
        consentCard.addView(context.text("需要先下载翻译模型", 16f, bold = true))
        consentCard.addView(context.text("可选组件，不影响查词与复习。", 14f, Paper.secondary))
        consentCard.addView(
            context.text("仅 Wi-Fi 下载，模型约 30 MB（估计值，实际以系统下载为准）。", 14f, Paper.secondary)
        )
        consentCard.addView(context.text("设备需支持 Google Play 服务。", 14f, Paper.secondary))
        consentCard.addView(
            context.text("SDK 会与 Google 通信并发送使用指标；原文只在本机处理，不上传。", 14f, Paper.secondary)
        )
        consentCard.addView(context.button("下载并启用（仅 Wi-Fi）", true) { session.downloadModel() })
        consentCard.addView(context.button("暂不下载") { session.declineDownload() })
        consentCard.addView(context.button("返回，暂不使用") { requestClose() })
        content.addView(consentCard)
        content.space(16)

        val inputCard = context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.surface, border = true)
        }
        inputCard.addView(context.text("英文原文", 16f, bold = true))
        inputCard.addView(context.text("模型未启用时只读，草稿与选择仍会保留。", 14f, Paper.secondary))
        input = context.inputField("例如 The evidence was inconclusive.", singleLine = false).apply {
            minLines = 5
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // Restore/paste the caller's text and caret without submitting or translating it.
            setText(initialText)
            setSelection(clampedSelection(initialText, initialSelectionStart, initialSelectionEnd).first,
                clampedSelection(initialText, initialSelectionStart, initialSelectionEnd).second)
        }
        inputCard.addView(input)
        counter = context.text("", 13f, Paper.secondary)
        inputCard.addView(counter)

        translateButton = context.button("翻译整句", true) {
            // The session validates blank/oversize input explicitly; the EditText keeps the text.
            session.translate(input.text.toString())
        }
        clearButton = context.button("清空原文") {
            input.setText("")
            input.setSelection(0)
            input.requestFocus()
        }
        inputCard.addView(translateButton)
        inputCard.addView(clearButton)
        inputCard.addView(
            context.text(
                "最多 ${TranslationSession.MAX_SOURCE_CHARS} 个字符；超出会明确提示，不会截断。",
                14f,
                Paper.secondary
            )
        )
        content.addView(inputCard)
        content.space(16)

        resultCard = context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.surface, 16, true)
            visibility = View.GONE
        }
        resultCard.addView(context.text("中文译文", 16f, bold = true))
        resultView = context.text("", 20f).apply { setTextIsSelectable(true) }
        resultCard.addView(resultView)
        resultCard.addView(
            context.text(
                "对应上次提交的原文；修改上方原文后，请重新翻译。",
                14f,
                Paper.secondary
            )
        )
        resultCard.addView(
            context.text("Google Translate 自动翻译（ML Kit 离线）· 仅供参考", 14f, Paper.warning)
        )
        // The copy action follows the same admission as the source/translate/clear controls: it is
        // only enabled for a current non-blank result while the local model and phase allow it.
        copyButton = context.button("复制译文") { copyResult() }
        resultCard.addView(copyButton)
        content.addView(resultCard)
        content.space(16)

        // Long-form backend/privacy facts are collapsed by default. The shared helper owns the
        // >=48dp toggle, the initially GONE body and the no-fixed-height wrapping; the body is
        // static text/links with no business action.
        statusDetail = context.text("", 14f, Paper.secondary)
        content.addView(context.helpDisclosure("模型与隐私说明") {
            addView(
                context.text(
                    "可选组件，不影响查词、补词、生词本、PDF 与复习。初装不下载任何模型。",
                    14f,
                    Paper.secondary
                )
            )
            addView(
                context.text(
                    "真实后端：Google ML Kit 英译中，在设备本机推理；不是新接入的开源大模型。" +
                        "模型约 30 MB 只是估计值，实际下载大小与进度以系统下载为准，本页不显示伪造的百分比。",
                    14f,
                    Paper.secondary
                )
            )
            addView(context.text(PRIVACY_TEXT, 14f, Paper.secondary))
            addView(
                context.text(
                    "可以离开本页或关闭窗口，但这不等于取消系统下载：系统下载可能继续，再次进入只会重新检查本地状态，" +
                        "不会未经确认重新下载。下载成功只代表模型已可用，真实译文质量与设备兼容性仍需单独验证。",
                    14f,
                    Paper.secondary
                )
            )
            addView(statusDetail)
            addView(
                context.text(
                    "下面的链接只在你自己点击时用浏览器打开，App 不会自动访问网页。",
                    14f,
                    Paper.secondary
                )
            )
            addView(link("ML Kit 翻译官方说明", "https://developers.google.com/ml-kit/language/translation/android"))
            addView(link("ML Kit 服务条款", "https://developers.google.com/ml-kit/terms"))
            addView(link("翻译模型附加条款", "https://developers.google.com/ml-kit/language/translation/translation-terms"))
            addView(link("Google 翻译署名要求", "https://docs.cloud.google.com/translate/attribution"))
        })
        content.space(8)

        // Secondary action for a multi-word capture, never a prerequisite: the sentence result is
        // already shown above, and this only returns to the original per-word lookup card.
        if (onSelectionRequested != null) {
            content.addView(context.button("返回选词（多词时查其中一个词）") {
                if (closing) return@button
                close()
                onSelectionRequested?.invoke()
            })
            content.space(8)
        }
        content.addView(context.button("返回/关闭") { requestClose() })

        root.addView(
            ScrollView(context).apply {
                isFillViewport = true
                addView(content)
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        view = root

        session = TranslationSession(OfflineTranslation(context), ::render)
        render(TranslationSession.State(TranslationSession.Phase.CHECKING_MODEL, "正在检查本机离线 AI 翻译模型…"))
        // Arm before start so an already-Ready local check can still submit the frozen OCR text
        // exactly once. The default path never arms this.
        if (autoTranslate) session.requestAutoTranslate(initialText)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCounter()
            }

            override fun afterTextChanged(s: Editable?) {
                // A visible edit discards the capture-entry intent; a later Ready callback must not
                // translate the hidden older OCR snapshot over the user's current text.
                session.cancelAutoIntent()
                // The inline section is bound to the exact text it displayed. An edit invalidates
                // every old fragment/result and retires any in-flight host request; the translation
                // session, visible draft and any already-shown translation result stay as they are.
                onWordSourceChanged()
            }
        })
        updateCounter()
        if (inlineWordLookup) rebuildWordTokens()
        // Opening the page performs only the local model query. Only the explicit capture-entry
        // auto intent above may translate, and only after Ready. Nothing here downloads.
        session.start()
    }

    /** Focuses the source field and asks for the keyboard without submitting anything. */
    fun focusInput() {
        if (closing || !input.isEnabled) return
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Candidate selection stays in the current source/request and does not record a query. */
    fun showWordChoices(requestId: Long, queried: String, entries: List<DictionaryEntry>,
                        onSelected: (DictionaryEntry) -> Unit) {
        if (closing || !inlineWordLookup || requestId != wordRequestId) return
        wordChoices.removeAllViews()
        wordStatus.text = "「$queried」请选择原词条或词形原型（选择前不收录）"
        var selected = false
        for (entry in entries) wordChoices.addView(context.button(
            (if (entry.key == queried) "原词条：" else "词形原型：") + entry.headword +
                "\n" + entry.meaning.lineSequence().first().take(64)) {
            if (!closing && requestId == wordRequestId && !selected) {
                selected = true
                wordChoices.removeAllViews()
                onSelected(entry)
            }
        })
    }

    /**
     * Shows one successfully resolved meaning for the still-current tap. [WordDisplay.requestId]
     * must be the id most recently handed to [onWordRequested]; a duplicate delivery for the same
     * id only repaints text and never starts another lookup or save. This method never edits the
     * source, moves the caret, changes the translation session or writes data.
     */
    fun showWordFound(display: WordDisplay) {
        if (closing || !inlineWordLookup) return
        if (display.requestId != wordRequestId) return
        // A message/stale outcome already settled this id; a duplicate late definition must not
        // repaint a successful meaning over it.
        if (display.requestId == wordRejectedId) return
        // Duplicate callback delivery for one tap is inert; the host owns the single save attempt.
        if (wordDelivered) return
        wordChoices.removeAllViews()
        wordDelivered = true
        wordBusy = false
        wordDisplayed = wordActive
        wordStatus.text = "查询成功 · 保存中…"
        wordStatus.setTextColor(Paper.secondary)
        wordMeaning.text = buildString {
            append(display.headword)
            if (display.partOfSpeech.isNotBlank()) append(" · ").append(display.partOfSpeech)
            if (display.meaning.isNotBlank()) append('\n').append(display.meaning)
        }
        wordMeaning.visibility = View.VISIBLE
        wordSource.text = buildString {
            if (display.queried.isNotBlank() && display.queried != display.headword) {
                append("点选词形：").append(display.queried).append('\n')
            }
            append(display.sourceNote)
        }
        wordSource.visibility = if (display.sourceNote.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Updates the compact status line under the meaning (for example "保存中…"/"已收录"/保存失败).
     * [retryable] explicitly frees the same word to be tapped again as a new request; it is used
     * only when the host reports that this request was not recorded. A stale request id is ignored.
     */
    fun showWordStatus(requestId: Long, message: String, error: Boolean = false,
                       retryable: Boolean = false) {
        if (closing || !inlineWordLookup || requestId != wordRequestId) return
        wordBusy = false
        if (retryable) {
            wordDisplayed = null
            // This id ended without a recorded result; a duplicate definition delivery is inert.
            wordRejectedId = requestId
        }
        wordStatus.text = message
        wordStatus.setTextColor(if (error) Paper.error else Paper.green)
    }

    /**
     * Shows a no-definition outcome (missing/invalid/stale/dictionary failure). No meaning is
     * fabricated and no empty definition is shown. A retryable message lets the same fragment be
     * tapped again; a non-retryable one remains the current displayed outcome until another word
     * or an edit is chosen.
     */
    fun showWordMessage(requestId: Long, message: String, error: Boolean = true,
                        retryable: Boolean = false) {
        if (closing || !inlineWordLookup || requestId != wordRequestId) return
        wordBusy = false
        wordDelivered = false
        wordRejectedId = requestId
        wordDisplayed = if (retryable) null else wordActive
        wordChoices.removeAllViews()
        wordMeaning.visibility = View.GONE
        wordSource.visibility = View.GONE
        wordStatus.text = message
        wordStatus.setTextColor(if (error) Paper.error else Paper.warning)
    }

    private fun toggleWordArea() {
        if (closing || !inlineWordLookup) return
        val expand = wordBody.visibility != View.VISIBLE
        wordBody.visibility = if (expand) View.VISIBLE else View.GONE
        wordToggle.text = if (expand) "收起词义" else "点英文词查释义"
        wordToggle.contentDescription = if (expand) {
            "收起句内词义，不关闭译句"
        } else {
            "点英文词查释义，在本页查本机词库"
        }
    }

    private fun rebuildWordTokens() {
        if (!inlineWordLookup || closing) return
        val source = input.text.toString()
        val generation = ++wordSourceGeneration
        val spans = SpannableString(source)
        SentenceWords.scan(source).forEach { word ->
            spans.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onWordClicked(word, source, generation)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = Paper.green
                    ds.isUnderlineText = true
                }
            }, word.start, word.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        wordTokens.text = spans
    }

    private fun onWordClicked(word: SentenceWord, source: String, generation: Long) {
        if (closing || !inlineWordLookup) return
        // A span built for an older edit is never convertible into a query for the current text.
        if (generation != wordSourceGeneration) return
        val current = input.text.toString()
        if (current != source || word.start < 0 || word.end > current.length ||
            word.start >= word.end || current.substring(word.start, word.end) != word.text) return
        val same = wordActive?.let {
            it.start == word.start && it.end == word.end && it.text == word.text
        } == true
        // One tap in flight, or a currently displayed result for this same fragment, is a no-op.
        // Switching to another word (or an explicit retryable failure) makes a later tap new.
        if (same && (wordBusy || wordDisplayed != null)) return
        if (wordBusy || wordDisplayed != null) onWordRequestRetired?.invoke(wordRequestId)
        wordRequestId++
        wordActive = word
        wordDisplayed = null
        wordDelivered = false
        wordBusy = true
        wordStatus.text = "正在查「${word.text}」…"
        wordStatus.setTextColor(Paper.secondary)
        wordChoices.removeAllViews()
        wordMeaning.visibility = View.GONE
        wordSource.visibility = View.GONE
        val request = onWordRequested
        if (request == null) {
            wordBusy = false
            wordStatus.text = "当前页面未启用句内查词；本次未查询、未收录。"
            wordStatus.setTextColor(Paper.warning)
            return
        }
        request(WordTap(wordRequestId, word.text, word.start, word.end, source))
    }

    private fun onWordSourceChanged() {
        if (!inlineWordLookup) return
        val retired = wordRequestId
        val hadWork = wordBusy || wordActive != null || wordDisplayed != null
        wordRequestId++
        wordBusy = false
        wordActive = null
        wordDisplayed = null
        wordDelivered = false
        if (hadWork) onWordRequestRetired?.invoke(retired)
        rebuildWordTokens()
        wordChoices.removeAllViews()
        wordMeaning.visibility = View.GONE
        wordSource.visibility = View.GONE
        wordStatus.text = if (hadWork) {
            "原文已更改；请重新点词。已收录的查询不会因改文撤销。"
        } else {
            ""
        }
        if (hadWork) wordStatus.setTextColor(Paper.warning)
    }

    private fun retireWordRequest() {
        if (!inlineWordLookup) return
        val retired = wordRequestId
        val hadWork = wordBusy || wordActive != null || wordDisplayed != null
        wordBusy = false
        wordActive = null
        wordDisplayed = null
        if (hadWork) onWordRequestRetired?.invoke(retired)
    }

    /**
     * Copies the current source text and caret for host-side state restoration. Reading the draft
     * never translates, downloads, records or uploads anything. Safe to call after [close] while the
     * host still holds the view. The text and caret are kept even while the pre-download page shows
     * them read-only.
     */
    fun draft(): Draft {
        val text = input.text.toString()
        val (start, end) = clampedSelection(text, input.selectionStart, input.selectionEnd)
        return Draft(text, start, end)
    }

    /**
     * Restores a host-held draft into this fresh screen without submitting or translating it. The
     * caller must still let the user explicitly press "翻译整句"; opening/resuming a page never
     * starts a translation or a model download.
     */
    fun restoreDraft(draft: Draft) {
        if (closing) return
        input.setText(draft.text)
        val (start, end) = clampedSelection(draft.text, draft.selectionStart, draft.selectionEnd)
        input.setSelection(start, end)
    }

    private fun clampedSelection(text: CharSequence, start: Int, end: Int): Pair<Int, Int> {
        val length = text.length
        val first = start.coerceIn(0, length)
        val second = end.coerceIn(0, length)
        return minOf(first, second) to maxOf(first, second)
    }

    /**
     * Retires the ML Kit session. The host still owns view/window removal; after this call no
     * callback can paint or reopen anything. It does not cancel a system model download, because
     * the SDK exposes no reliable cancellation. Idempotent.
     */
    fun close() {
        if (closing) return
        closing = true
        // A closed page can never let an inline lookup display or commit. The host also retires
        // this request on window removal; this callback covers a direct screen close as well.
        retireWordRequest()
        // render() stops painting after close, so retire the copy action here as well: a closed
        // page must not leave the button usable even if the host still holds the view briefly.
        copyButton.isEnabled = false
        session.close()
    }

    private fun requestClose() {
        if (closing) return
        close()
        onCloseRequested?.invoke()
    }

    private fun render(state: TranslationSession.State) {
        if (closing) return
        val phase = state.phase
        status.text = shortStatus(state)
        statusDetail.text = "本页最近一次状态的完整说明：${state.message}"
        status.setTextColor(
            when (phase) {
                TranslationSession.Phase.ERROR -> Paper.error
                TranslationSession.Phase.NEEDS_MODEL,
                TranslationSession.Phase.DOWNLOADING,
                TranslationSession.Phase.CANCELLED -> Paper.warning
                TranslationSession.Phase.READY,
                TranslationSession.Phase.DONE -> Paper.green
                else -> Paper.secondary
            }
        )
        // The explicit download gate is shown only for a locally Missing model (including after
        // "暂不下载"). Unavailable/download failures get a visible retry entry instead.
        consentCard.visibility =
            if (phase == TranslationSession.Phase.NEEDS_MODEL) View.VISIBLE else View.GONE

        retryButton.visibility =
            if (state.retry == TranslationSession.Retry.NONE) View.GONE else View.VISIBLE
        retryButton.text = when (state.retry) {
            TranslationSession.Retry.CHECK_MODEL -> "重新检查模型"
            TranslationSession.Retry.DOWNLOAD -> "重试下载（仅 Wi-Fi）"
            TranslationSession.Retry.TRANSLATE -> "重新翻译"
            TranslationSession.Retry.NONE -> "重试"
        }

        cancelButton.visibility =
            if (phase == TranslationSession.Phase.TRANSLATING) View.VISIBLE else View.GONE

        // Ready is the only gate that opens editing/translating/copying. Checking, Missing,
        // Unavailable, Downloading and Closed all keep them locked.
        val componentEnabled = state.modelReady &&
            phase != TranslationSession.Phase.CHECKING_MODEL &&
            phase != TranslationSession.Phase.DOWNLOADING &&
            phase != TranslationSession.Phase.TRANSLATING &&
            phase != TranslationSession.Phase.CLOSED
        translateButton.isEnabled = componentEnabled
        clearButton.isEnabled = componentEnabled
        translateButton.text = when {
            !state.modelReady -> "翻译整句（需先启用）"
            phase == TranslationSession.Phase.DONE -> "重新翻译"
            else -> "翻译整句"
        }
        // A disabled source is a read-only display: its text and caret stay in the view, and the
        // host's draft() still captures both. Only a Ready model with no translation in flight is
        // editable.
        input.isEnabled = componentEnabled

        val hasResult = state.result.isNotBlank()
        resultCard.visibility = if (hasResult) View.VISIBLE else View.GONE
        resultView.text = state.result
        // Copy is admitted only for the current non-blank result under the same component gate:
        // Checking, Missing, Downloading, Translating, a cancel without a result and Closed never
        // enable it. The result card is hidden without a result, but its copy child must also be
        // explicitly disabled rather than relying on the hidden ancestor alone.
        copyButton.isEnabled = componentEnabled && hasResult
    }

    /**
     * Short first-screen status for the current phase. The session's full message (which may be a
     * long paragraph) stays available in the collapsed disclosure. Errors are the exception: their
     * cause and next step must remain visible, so the session message is shown as-is.
     */
    private fun shortStatus(state: TranslationSession.State): String =
        when (state.phase) {
            TranslationSession.Phase.CHECKING_MODEL -> "正在检查本机翻译模型…"
            TranslationSession.Phase.NEEDS_MODEL ->
                if (state.message.startsWith("已暂不下载")) {
                    "未启用 · 已暂不下载，可稍后再启用"
                } else {
                    "未启用 · 需先下载并启用模型"
                }
            TranslationSession.Phase.DOWNLOADING ->
                "正在下载模型（或等待 Wi-Fi）… 可离开本页，系统下载可能继续。"
            TranslationSession.Phase.READY -> "模型已就绪，可以翻译。"
            TranslationSession.Phase.TRANSLATING -> "翻译中… 本机处理，不上传原文。"
            TranslationSession.Phase.DONE -> "翻译完成 · 机器翻译仅供参考"
            TranslationSession.Phase.CANCELLED -> "已取消 · 原文保留，可重新翻译。"
            TranslationSession.Phase.ERROR -> state.message
            TranslationSession.Phase.CLOSED -> "已关闭"
        }

    private fun updateCounter() {
        val length = input.text.length
        counter.text = "$length / ${TranslationSession.MAX_SOURCE_CHARS}"
        counter.setTextColor(
            if (length > TranslationSession.MAX_SOURCE_CHARS) Paper.error else Paper.secondary
        )
    }

    private fun copyResult() {
        if (closing || !copyButton.isEnabled) return
        val text = resultView.text.toString()
        if (text.isBlank()) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            toast("无法访问剪贴板，未复制")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("中文译文", text))
        status.text = "译文已复制到剪贴板"
        status.setTextColor(Paper.green)
    }

    private fun link(label: String, url: String): TextView =
        context.text(label, 14f, Paper.green).apply {
            setPadding(0, context.dp(6), 0, context.dp(6))
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            // Browser navigation happens only on this explicit user tap.
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    toast("无法打开链接：${e.message}")
                }
            }
        }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PRIVACY_TEXT =
            "这是一个可选组件，不是本应用其他功能的前置条件。你输入的句子和译文由 Google ML Kit 在设备本机处理；" +
                "本应用不会把原文、句子或图片上传到服务器，不使用在线翻译、收费服务或 API Key。首次使用需由你确认下载" +
                "英中翻译模型（约 30 MB，只是估计值，实际以系统下载为准），仅 Wi-Fi，由 Google ML Kit / Google Play " +
                "服务管理；下载失败可主动重试，不会自动改用移动数据或在线翻译。离开/关闭页面不会取消系统下载，系统下载" +
                "可能继续，再次进入只会重新检查。ML Kit SDK 可能为更新模型、诊断和统计使用情况而与 Google 通信并发送" +
                "性能/使用指标，因此本应用并非“完全不联网”。Google Translate 是 Google LLC 的商标；本应用与 Google " +
                "无隶属关系，不是 Google 官方应用。机器翻译不保证准确，仅供参考。"
    }
}

/**
 * Intercepts Back before the focused child (for example the source EditText) can consume it, so an
 * overlay page always has a working keyboard-free exit. The host removes the window in the callback.
 */
private class ScreenRoot(
    context: Context,
    private val onBack: () -> Unit
) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
