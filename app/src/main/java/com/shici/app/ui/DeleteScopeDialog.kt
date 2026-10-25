package com.shici.app.ui

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The two destructive scopes the user must choose between explicitly. There is deliberately no
 * "default" value: every dialog built by [DeleteScopeDialog] starts with nothing selected, so the
 * dangerous full-history scope can only be reached by two deliberate taps.
 */
enum class DeleteScope {
    /** Revoke exactly the one event the user was shown; keep everything else. */
    UNDO_LAST_QUERY_ONLY,

    /** Delete every learning record of this word; requires a second, dangerous confirmation. */
    DELETE_ALL_LEARNING_RECORDS
}

/**
 * Pure UI factory for the scoped-delete flow. It never reads or writes the database, never shows a
 * dialog and never installs an `OnDismissListener`/`OnShowListener`: the integrator owns the returned
 * [AlertDialog], binds its own dismiss/owner invalidation handling and calls `show()` on the main
 * thread. Each factory returns a freshly created dialog so a late dismiss of an older dialog can only
 * invalidate that older owner, never close a newer confirmation.
 *
 * Intended flow (all on the main thread):
 * 1. [createScopeDialog] -> the user explicitly picks a [DeleteScope] (no preselected choice).
 * 2. For [DeleteScope.UNDO_LAST_QUERY_ONLY], the worker calls
 *    `LearningRepository.prepareUndoLatest` first, then [createUndoLastConfirmDialog] with the exact
 *    event time it prepared, and `commitUndo(prepared)` only after confirmation.
 * 3. For [DeleteScope.DELETE_ALL_LEARNING_RECORDS], [createDeleteAllConfirmDialog] states the full
 *    scope; only then call `LearningRepository.deleteWord(key, credential, admission, canCommit)`.
 */
object DeleteScopeDialog {

    private val eventTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Formats the exact event time shown to the user in the device's [zone]. */
    fun formatEventTime(time: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        eventTimeFormatter.withZone(zone).format(Instant.ofEpochMilli(time))

    /**
     * Creates (but does not show) the scope chooser. The headword is echoed and both scopes are
     * rendered at once as two explicit, always-visible option blocks with their own descriptions.
     *
     * The list is a custom scrollable content view rather than `setMessage` + `setItems`: an
     * `AlertDialog` built with both may hide the item list behind the message panel, so neither
     * option could be seen. There is no preselected/default option. A tap reports the chosen scope
     * through [onScopeSelected] and then closes this chooser; the dialog itself performs no deletion.
     * Cancelling invokes nothing.
     */
    fun createScopeDialog(
        context: Context,
        headword: String,
        onScopeSelected: (DeleteScope) -> Unit
    ): AlertDialog {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
        }
        content.addView(context.text(
            "请明确选择处理范围（没有默认选项）。两项都不会删除个人补词、原始词库、其他生词" +
                "或已导出的 PDF。",
            14f, Paper.secondary
        ))
        content.space(12)
        var dialog: AlertDialog? = null

        fun addOption(scope: DeleteScope, title: String, description: String) {
            val option = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = context.dp(64)
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
                background = context.shape(Paper.lightGreen, 8)
                contentDescription = "$title。$description"
                setOnClickListener {
                    // Report first: the integrator clears/rebinds its own current dialog inside the
                    // callback, so the subsequent dismiss can never be mistaken for a cancel.
                    onScopeSelected(scope)
                    dialog?.dismiss()
                }
            }
            option.addView(context.text(title, 17f, bold = true))
            option.addView(context.text(description, 14f, Paper.secondary))
            content.addView(option, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = context.dp(12)
            })
        }

        // The main "remove from vocabulary" intent stays easy to recognize; it is only a scope
        // choice here and still requires the separate dangerous confirmation below.
        addOption(
            DeleteScope.DELETE_ALL_LEARNING_RECORDS,
            "从生词本移除该词（删除全部学习记录）",
            "删除该词的全部查询历史（包括已撤销的）、笔记、掌握状态、复习记录和收藏星标；" +
                "下一步还需再次确认，删除后无法恢复。"
        )
        addOption(
            DeleteScope.UNDO_LAST_QUERY_ONLY,
            "只撤销最近一次查询",
            "只撤销最近一次有效查询；之前的查询、笔记、掌握状态与复习记录保留，" +
                "该词仍留在生词本中。"
        )

        val scroll = ScrollView(context).apply { addView(content) }
        val created = AlertDialog.Builder(context)
            .setTitle("处理「$headword」的学习记录")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .create()
        dialog = created
        return created
    }

    /**
     * Creates (but does not show) the single-event confirmation. [eventTime] must be the exact time
     * carried by the prepared event, not a freshly resolved "latest".
     */
    fun createUndoLastConfirmDialog(
        context: Context,
        headword: String,
        eventTime: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        onConfirm: () -> Unit
    ): AlertDialog = createUndoLastConfirmDialog(
        context, headword, formatEventTime(eventTime, zone), onConfirm
    )

    /**
     * Creates (but does not show) the single-event confirmation with an already formatted timestamp.
     * Only the shown event is revoked; other queries, notes, mastery and this word's reviews stay.
     */
    fun createUndoLastConfirmDialog(
        context: Context,
        headword: String,
        eventTimeText: String,
        onConfirm: () -> Unit
    ): AlertDialog = AlertDialog.Builder(context)
        .setTitle("撤销最近一次查询？")
        .setMessage(
            "只撤销「$headword」在 $eventTimeText 的这一条成功查询。\n\n" +
                "之前的查询、笔记、掌握状态和复习记录都会保留；撤销后该词仍在生词本中。" +
                "独立保存的个人词义和原始词库不受影响。"
        )
        .setPositiveButton("只撤销这次查询") { _, _ -> onConfirm() }
        .setNegativeButton("取消", null)
        .create()

    /**
     * Creates (but does not show) the dangerous full-history confirmation. It names every category
     * removed for this word (including already-revoked history, the note, mastery, this word's reviews
     * and its favorite star) and explicitly preserves the independent `user_entries` meaning, the
     * original dictionary, other words and exported PDFs.
     */
    fun createDeleteAllConfirmDialog(
        context: Context,
        headword: String,
        onConfirm: () -> Unit
    ): AlertDialog = AlertDialog.Builder(context)
        .setTitle("删除「$headword」的全部学习记录？")
        .setMessage(
            "将永久删除「$headword」的：\n" +
                "• 全部查询历史（包括已撤销的）\n" +
                "• 我的记忆笔记与掌握状态\n" +
                "• 该词的复习进度、反馈记录和待答卡片\n" +
                "• 该词的收藏星标\n\n" +
                "此操作无法恢复。独立保存的个人词义（个人补词）不会被删除；原始词库、其他生词" +
                "和已导出的 PDF 也不受影响。\n\n" +
                "如果只想撤销最近一次查询，请取消后选择“只撤销最近一次查询”。"
        )
        .setPositiveButton("删除全部记录") { _, _ -> onConfirm() }
        .setNegativeButton("取消", null)
        .create()
}
