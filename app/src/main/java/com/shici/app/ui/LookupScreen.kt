package com.shici.app.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ScrollView

/**
 * The daily-lookup page body, extracted from the Activity so the page can be assembled and reasoned
 * about on its own. It owns only presentation: the input draft, the "查询"/"清空" actions, the IME
 * search action and the hand-off button to the personal-entry page.
 *
 * Every submission goes through [onQuery]; this component never resolves a word, touches the
 * repository, records a query event or fakes a record. The Activity's existing `manualLookup`
 * pipeline (same gate, credential, revision and result-card rules) stays the single entry point.
 * [onTextChanged] mirrors ordinary edits so the Activity can keep its draft fields in sync without
 * re-rendering this page. Nothing is submitted while constructing or restoring the draft.
 *
 * @param initialText restored draft text (may be empty).
 * @param selectionStart restored selection start; negative or reversed values are clamped.
 * @param selectionEnd restored selection end; negative or reversed values are clamped.
 * @param onTextChanged called for every ordinary edit with the current text; never a query.
 * @param onQuery called by the "查询" button and by the keyboard search action.
 * @param onOpenPersonalEntry called by the "添加/补词" hand-off button.
 */
class LookupScreen(
    context: Context,
    initialText: String,
    selectionStart: Int,
    selectionEnd: Int,
    private val onTextChanged: (String) -> Unit,
    private val onQuery: (String) -> Unit,
    private val onOpenPersonalEntry: () -> Unit
) {
    /** A snapshot of the input draft. Selection is always a valid, forward range inside [text]. */
    data class Draft(val text: String, val selectionStart: Int, val selectionEnd: Int)

    /** The editable field; the Activity keeps this as its `lookupInputView` for draft capture. */
    val input: EditText

    /** Complete scrollable page body. No sidebar and no root padding; the host body supplies those. */
    val view: View

    init {
        val content = context.column()
        content.addView(context.text("查词", 28f, bold = true))
        content.space(8)
        content.addView(
            context.text(
                "输入一个英文单词或词形。无需悬浮窗或屏幕捕获权限；成功查询按现有规则形成真实的本地记录。",
                16f,
                Paper.secondary
            )
        )
        content.space(16)

        val card = context.column().apply {
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
            background = context.shape(Paper.surface, border = true)
        }

        input = context.inputField("例如 evidence、quantum", true)
        // Restore the draft before the watcher is attached so restoring never looks like a user edit
        // and can never be mistaken for a query.
        input.setText(initialText)
        val (start, end) = clampSelection(initialText, selectionStart, selectionEnd)
        input.setSelection(start, end)
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.setOnEditorActionListener { _, actionId, event ->
            val imeSearch = actionId == EditorInfo.IME_ACTION_SEARCH
            val keyboardEnter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (imeSearch || keyboardEnter) {
                submit()
                true
            } else {
                false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onTextChanged(s?.toString() ?: "")
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        card.addView(input)
        card.space(8)
        card.addView(context.button("查询", true) { submit() })
        card.addView(context.button("清空") {
            // Clearing only empties the field. The text watcher forwards the empty draft, and no
            // lookup, record or query count is created here.
            input.setText("")
            input.setSelection(0)
        })
        card.addView(context.button("添加/补词（只维护词义，不收录）") { onOpenPersonalEntry() })
        card.space(4)
        card.addView(
            context.text(
                "未知词或未收录词形不生成释义、不新增记录。可从结果卡在本机新增词义，或对已有词补充个人词性和释义；保存词义本身不产生查询次数、生词或复习反馈，只有明确的“查询并收录”才收录。",
                15f,
                Paper.secondary
            )
        )
        content.addView(card)
        content.space(16)
        content.addView(
            context.text(
                "查词结果中的“补充/编辑词义”仍可原地使用；需要完整页面时，结果卡也提供“在补词页编辑/新增”。",
                15f,
                Paper.secondary
            )
        )

        view = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
    }

    /** Returns the current draft with a clamped, forward selection range. */
    fun capture(): Draft {
        val text = input.text.toString()
        val (start, end) = clampSelection(text, input.selectionStart, input.selectionEnd)
        return Draft(text, start, end)
    }

    private fun submit() {
        // Both the button and the keyboard action take the exact same path; no second gate is
        // introduced here. The Activity's original manualLookup performs validation and recording.
        onQuery(capture().text)
    }

    private fun clampSelection(text: String, start: Int, end: Int): Pair<Int, Int> {
        val length = text.length
        val first = start.coerceIn(0, length)
        val second = end.coerceIn(0, length)
        return minOf(first, second) to maxOf(first, second)
    }
}
