package com.shici.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import com.shici.app.translation.OfflineTranslation
import com.shici.app.translation.TranslationSession

/**
 * One English -> Chinese offline translation page, assembled from plain Android views.
 *
 * It is intentional that this component can live either in an Activity body or in a Service-owned
 * `TYPE_APPLICATION_OVERLAY` window: it never uses a Dialog, never needs an Activity and never
 * writes data. The host owns the window/view lifetime; [close] only retires the ML Kit session and
 * makes every late callback inert. For an overlay host, set [onCloseRequested] to remove the
 * window and return to the floating button.
 *
 * Privacy/product boundaries enforced here:
 * - opening the page does not translate, download or collect anything;
 * - the model is downloaded only after the user taps the explicit Wi-Fi download confirmation;
 * - the sentence is processed on device by ML Kit; this app does not upload it, use an online
 *   translator, a paid service or an API key;
 * - ML Kit may contact Google for model updates and performance/usage metrics, so the wording
 *   below never claims that the whole app is offline;
 * - input is never silently truncated: more than [TranslationSession.MAX_SOURCE_CHARS] UTF-16
 *   characters is refused with an explicit message and the text is preserved;
 * - copy happens only on an explicit "复制译文" tap.
 *
 * All methods must be called on the main thread.
 */
class TranslationScreen(
    context: Context,
    initialText: String = "",
    initialSelectionStart: Int = initialText.length,
    initialSelectionEnd: Int = initialSelectionStart
) {
    /** Plain snapshot of the editor, used by the host to survive navigation/rotation. */
    data class Draft(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /** Optional host hook for the Back key and the "返回/关闭" button. */
    var onCloseRequested: (() -> Unit)? = null

    private val context: Context = context
    private val root: ScreenRoot
    val view: View

    private lateinit var session: TranslationSession

    private val input: EditText
    private val counter: TextView
    private val status: TextView
    private val consentCard: LinearLayout
    private val translateButton: Button
    private val retryButton: Button
    private val cancelButton: Button
    private val resultCard: LinearLayout
    private val resultView: TextView

    private var closing = false

    init {
        root = ScreenRoot(context) { requestClose() }.apply {
            setBackgroundColor(Paper.background)
            isClickable = true
            isFocusableInTouchMode = true
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        }

        val content = context.column()

        content.addView(context.text("译句 · 英译中（离线）", 28f, bold = true))
        content.space(8)
        content.addView(
            context.text(
                "输入或粘贴英文句子，由你明确发起整句翻译。翻译不会查词、不写入生词本、不增加查询次数，也不上传原文。",
                16f,
                Paper.secondary
            )
        )
        content.space(16)

        val inputCard = context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.surface, border = true)
        }
        inputCard.addView(context.text("英文原文", 16f, bold = true))
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
        val clear = context.button("清空原文") {
            input.setText("")
            input.setSelection(0)
            input.requestFocus()
        }
        inputCard.addView(translateButton)
        inputCard.addView(clear)
        inputCard.addView(
            context.text(
                "上限 ${TranslationSession.MAX_SOURCE_CHARS} 个 UTF-16 字符（约等于常见英文句中字符数）。超出时会明确提示，不会截断后翻译。",
                13f,
                Paper.secondary
            )
        )
        content.addView(inputCard)
        content.space(16)

        val modelCard = context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.surface, border = true)
        }
        modelCard.addView(context.text("离线模型", 16f, bold = true))
        status = context.text("", 15f, Paper.secondary)
        modelCard.addView(status)
        modelCard.space(4)

        consentCard = context.column()
        consentCard.addView(
            context.text(
                "首次翻译需要下载英→中离线模型。模型大小约 30 MB（只是估计值，实际以系统下载为准）。" +
                    "只有你点下面的按钮后才会下载，且仅限 Wi-Fi；不会上传你输入的句子，" +
                    "也不会自动改用移动数据或在线翻译。下载由 Google ML Kit / Google Play 服务管理，失败可主动重试。",
                14f,
                Paper.secondary
            )
        )
        consentCard.addView(context.button("下载离线模型（仅 Wi-Fi）", true) { session.downloadModel() })
        consentCard.addView(context.button("暂不下载") { session.declineDownload() })
        modelCard.addView(consentCard)

        // A retry is a new translation request for the text that is visible right now, not a
        // replay of a hidden older submission. The session re-validates blank/oversize/model
        // state before submitting and binds the result to this snapshot.
        retryButton = context.button("重试") { session.retry(input.text.toString()) }
        retryButton.visibility = View.GONE
        modelCard.addView(retryButton)

        cancelButton = context.button("取消本次翻译") { session.cancelTranslation() }
        cancelButton.visibility = View.GONE
        modelCard.addView(cancelButton)

        modelCard.addView(
            context.text(
                "设备需支持 Google Play 服务；模型在当前设备/网络能否成功下载、以及机器翻译质量，需要实际验证。",
                13f,
                Paper.secondary
            )
        )
        content.addView(modelCard)
        content.space(16)

        resultCard = context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.lightGreen, 12)
            visibility = View.GONE
        }
        resultCard.addView(context.text("中文译文", 16f, bold = true))
        resultView = context.text("", 20f).apply { setTextIsSelectable(true) }
        resultCard.addView(resultView)
        resultCard.addView(
            context.text(
                "对应你上一次提交翻译时的原文；如果改动了上方原文，请按“翻译整句/重新翻译”以当前原文重新提交。",
                13f,
                Paper.secondary
            )
        )
        resultCard.addView(
            context.text("Google Translate 自动翻译（ML Kit 离线）· 仅供参考", 13f, Paper.warning)
        )
        resultCard.addView(context.button("复制译文") { copyResult() })
        content.addView(resultCard)
        content.space(16)

        val privacyCard = context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.surface, border = true)
        }
        privacyCard.addView(context.text("关于离线翻译与隐私", 16f, bold = true))
        privacyCard.addView(context.text(PRIVACY_TEXT, 13f, Paper.secondary))
        privacyCard.addView(
            context.text(
                "下面的链接只在你自己点击时用浏览器打开，App 不会自动访问网页。",
                13f,
                Paper.secondary
            )
        )
        privacyCard.addView(link("ML Kit 翻译官方说明", "https://developers.google.com/ml-kit/language/translation/android"))
        privacyCard.addView(link("ML Kit 服务条款", "https://developers.google.com/ml-kit/terms"))
        privacyCard.addView(link("翻译模型附加条款", "https://developers.google.com/ml-kit/language/translation/translation-terms"))
        privacyCard.addView(link("Google 翻译署名要求", "https://docs.cloud.google.com/translate/attribution"))
        content.addView(privacyCard)
        content.space(8)

        content.addView(context.button("返回/关闭译句") { requestClose() })

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

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCounter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        updateCounter()

        session = TranslationSession(OfflineTranslation(context), ::render)
        render(TranslationSession.State(TranslationSession.Phase.CHECKING_MODEL, "正在检查本机离线翻译模型…"))
        // Opening the page performs only the local model query; it never translates or downloads.
        session.start()
    }

    /** Focuses the source field and asks for the keyboard without submitting anything. */
    fun focusInput() {
        if (closing) return
        input.requestFocus()
        input.post {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Copies the current source text and caret for host-side state restoration. Reading the draft
     * never translates, downloads, records or uploads anything. Safe to call after [close] while the
     * host still holds the view.
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
     * callback can paint or reopen anything. Idempotent.
     */
    fun close() {
        if (closing) return
        closing = true
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
        status.text = state.message
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

        translateButton.isEnabled = state.modelReady &&
            phase != TranslationSession.Phase.CHECKING_MODEL &&
            phase != TranslationSession.Phase.DOWNLOADING &&
            phase != TranslationSession.Phase.TRANSLATING &&
            phase != TranslationSession.Phase.CLOSED
        translateButton.text =
            if (phase == TranslationSession.Phase.DONE) "重新翻译" else "翻译整句"
        // Only the in-flight translation locks the field; failures, blank input and oversize input
        // always leave the user's text editable and preserved.
        input.isEnabled = phase != TranslationSession.Phase.TRANSLATING

        val hasResult = state.result.isNotBlank()
        resultCard.visibility = if (hasResult) View.VISIBLE else View.GONE
        resultView.text = state.result
    }

    private fun updateCounter() {
        val length = input.text.length
        counter.text = "$length / ${TranslationSession.MAX_SOURCE_CHARS}"
        counter.setTextColor(
            if (length > TranslationSession.MAX_SOURCE_CHARS) Paper.error else Paper.secondary
        )
    }

    private fun copyResult() {
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
            "你输入的句子和译文由 Google ML Kit 在设备本机处理；本应用不会把原文、句子或图片上传到服务器，" +
                "不使用在线翻译、收费服务或 API Key。首次使用需由你确认下载英中翻译模型（约 30 MB，只是估计值，" +
                "实际以系统下载为准），仅 Wi-Fi，由 Google ML Kit / Google Play 服务管理；失败可主动重试，" +
                "不会自动改用移动数据或在线翻译。ML Kit SDK 可能为更新模型、诊断和统计使用情况而与 Google 通信并" +
                "发送性能/使用指标，因此本应用并非“完全不联网”。Google Translate 是 Google LLC 的商标；" +
                "本应用与 Google 无隶属关系，不是 Google 官方应用。机器翻译不保证准确，仅供参考。"
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
