package com.shici.app.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 添加/补词页的独立正文，只负责本页视图的骨架，不持有业务状态。
 *
 * 职责边界（刻意保持很窄）：
 * - 不访问 repository / app / worker，不读取、创建或更新删除 credential、提交 revision、租约或 owner；
 * - 不保存、不构造词条，也不做“规范词头”等业务校验；读取动作只把输入框里的原始字符串交给 [onRead]；
 * - 输入框内容原样回调 [onHeadwordChanged]，不 trim、不替换、不清空，草稿由调用方保存与恢复；
 * - 原始词库只读、只写本机个人补充、不计入查询/生词/复习，这些说明只作为正文文案呈现。
 *
 * 汇总接线提示：`MainActivity.personalEntryPage()` 构造本组件，把 [statusView] 赋给原
 * `personalStatusView`，再把原 `personalSourceCard` / `personalRestoredDraftCard` /
 * `personalDraftConfirmCard` 装配进 [editorHost]。保存仍走已有 `addSupplementEditor`。
 * 原查询卡的删除凭据、revision、owner、lease 与草稿重读确认逻辑全部留在 MainActivity。
 */
class PersonalEntryScreen(
    context: Context,
    initialHeadword: String,
    statusText: String,
    canCancel: Boolean,
    onHeadwordChanged: (String) -> Unit,
    onRead: (String) -> Unit,
    onCancel: () -> Unit
) {
    /** 完整可滚动正文；加到页面 body 时建议使用 MATCH_PARENT 高宽。 */
    val view: View

    /** 词头输入框；公开给汇总端以保持选择位置等原有行为。 */
    val headwordInput: EditText

    /** 读取/保存/冲突等状态行；汇总端把它赋给原 personalStatusView 即可沿用原配色逻辑。 */
    val statusView: TextView

    /** 供汇总端挂载原 personalSourceCard 与草稿提示卡的容器。 */
    val editorHost: LinearLayout

    init {
        val content = context.column()
        content.addView(context.text("添加/补词", 28f, Paper.ink, true))
        content.space(8)
        content.addView(
            context.text(
                "先确认一个英文词头，读取当前原始或用户词义，再编辑本机个人词性/释义。",
                16f, Paper.secondary
            )
        )
        content.space(8)

        // 三句边界说明必须逐条清楚呈现，避免用户把本页保存误解为查询或背词行为。
        content.addView(context.column().apply {
            setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(10))
            background = context.shape(Paper.lightGreen, 10)
            addView(context.text("原始词库只读", 15f, Paper.green, true))
            addView(context.text("只存本机个人补充", 15f, Paper.green))
            addView(context.text("不计入查询/生词/复习", 15f, Paper.green))
        })
        content.space(16)

        val card = context.column().apply {
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
            background = context.shape(Paper.surface, border = true)
        }

        val input = context.inputField("输入一个英文词头，例如 evidence", true).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(initialHeadword)
            if (initialHeadword.isNotEmpty()) setSelection(initialHeadword.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // 原样回调，不 trim、不规范化；调用方保持自己的词头状态。
                    onHeadwordChanged(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            setOnEditorActionListener { _, actionId, event ->
                val enterDown = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE || enterDown
                ) {
                    val raw = text?.toString() ?: ""
                    submitEmptyHint(raw)
                    onRead(raw)
                    true
                } else {
                    false
                }
            }
        }
        headwordInput = input
        card.addView(input)
        card.space(8)
        card.addView(context.text("提示：一次只需一个词头；读取只会展示当前词义，保存时只更新本机个人补充。", 14f, Paper.secondary))
        card.space(8)

        card.addView(context.button("读取当前词义", true) {
            // 空输入给出准确提示，但不做“是否像英文单词”之类的假规范校验。
            val raw = headwordInput.text?.toString() ?: ""
            submitEmptyHint(raw)
            onRead(raw)
        })

        if (canCancel) {
            card.space(4)
            card.addView(context.button("取消本次补词") { onCancel() })
        }

        card.space(4)
        statusView = context.text(statusText, 15f, Paper.secondary).apply {
            // 冲突、失效等提示可能是长句，保持可换行、可选择，便于读清后被用户处理。
            setTextIsSelectable(true)
            minHeight = context.dp(24)
        }
        card.addView(statusView)
        content.addView(card)

        content.space(16)
        editorHost = context.column()
        content.addView(editorHost)

        view = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * 只在输入为空时给出本页的即时提示。这是提示，不是业务校验：
     * 非空内容不做任何字符判断，空格、短语等仍原样交给调用方决定。
     */
    private fun submitEmptyHint(raw: String) {
        if (raw.isEmpty()) {
            statusView.text = "请输入一个英文词头"
            statusView.setTextColor(Paper.error)
        }
    }
}
