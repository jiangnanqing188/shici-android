package com.shici.app.ui

import android.app.AlertDialog
import android.content.Context
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
     * Creates (but does not show) the scope chooser. The headword is echoed, both scopes are described
     * with their real effects, and the independent personal meaning / original dictionary are stated as
     * untouched. Tapping an item invokes [onScopeSelected] with the chosen scope; the dialog itself
     * performs no deletion. Cancelling invokes nothing.
     */
    fun createScopeDialog(
        context: Context,
        headword: String,
        onScopeSelected: (DeleteScope) -> Unit
    ): AlertDialog {
        val items = arrayOf(
            "仅撤销最近一次有效查询\n保留之前的查询、笔记、掌握状态与复习记录",
            "删除该词的全部学习记录\n含全部查询历史（包括已撤销）、笔记、掌握状态、复习记录和待答卡片"
        )
        return AlertDialog.Builder(context)
            .setTitle("处理「$headword」的学习记录")
            .setMessage(
                "请明确选择处理范围。\n\n" +
                    "两项都不会删除独立保存的个人词义，也不会改动原始词库、其他生词或已导出的 PDF。"
            )
            .setItems(items) { _, which ->
                onScopeSelected(
                    if (which == 0) DeleteScope.UNDO_LAST_QUERY_ONLY
                    else DeleteScope.DELETE_ALL_LEARNING_RECORDS
                )
            }
            .setNegativeButton("取消", null)
            .create()
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
        .setPositiveButton("撤销这次查询") { _, _ -> onConfirm() }
        .setNegativeButton("取消", null)
        .create()

    /**
     * Creates (but does not show) the dangerous full-history confirmation. It names every category
     * removed for this word (including already-revoked history, the note, mastery, this word's reviews
     * and any pending card) and explicitly preserves the independent `user_entries` meaning, the
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
                "• 该词的复习进度、反馈记录和待答卡片\n\n" +
                "此操作无法恢复。独立保存的个人词义不会被删除；原始词库、其他生词和已导出的 PDF " +
                "也不受影响。\n\n" +
                "如果只想移除最近一次查询，请取消后选择“仅撤销最近一次有效查询”。"
        )
        .setPositiveButton("删除全部记录") { _, _ -> onConfirm() }
        .setNegativeButton("取消", null)
        .create()
}
