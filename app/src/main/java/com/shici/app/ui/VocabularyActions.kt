package com.shici.app.ui

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Pure-UI remove entry shared by the vocabulary list and the word detail. It only renders a clearly
 * labelled "remove learning records" button plus the short scope explanation; it never shows a
 * dialog, never reads/writes the database, never takes a deletion credential and never installs an
 * `OnDismissListener`/owner. The caller's [onClick] is invoked as-is, and the caller keeps full
 * ownership of the existing [DeleteScopeDialog]/`openDeleteScope` flow (including its per-detail
 * busy/stale/admission checks and the second dangerous confirmation).
 *
 * Wiring guidance (final integrator):
 * * Detail: build [detailSection] with `{ openDeleteScope(binding, word, close) }`, insert
 *   `section.view` where the old "移除学习记录…" button + scope hint were, and pass
 *   `section.removeButton` as the `delete` control of `WordDetailBinding` so the existing
 *   busy/confirming/invalidated/stale gating still disables it. Keep the old dangerous callback
 *   refusal checks in `createScopeDialog`/`createDeleteAllConfirmDialog`.
 * * List: add [listRemoveButton] to a row and route it through the same exact flow as the row/detail
 *   (for example select/open that word's detail first). Never call the repository delete directly,
 *   never synthesize a credential, and never reuse a binding whose key/credential is stale.
 * * Optional: place one [listScopeHint] under the list header so both remove scopes stay visible
 *   without repeating the hint in every row.
 *
 * The remove entry deliberately does not offer "clear all vocabulary" or batch deletion.
 */
object VocabularyActions {

    /** Compact row label; the full meaning is carried by the content description and scope hint. */
    const val LIST_LABEL = "移除…"

    /** Primary detail entry. The scope chooser explains the two scopes after the tap. */
    const val DETAIL_LABEL = "从生词本移除…"

    /**
     * One visible sentence for the list/detail: which scopes exist and what full-history deletion
     * really removes, without presenting full deletion as "only the newly added record".
     */
    const val SCOPE_HINT =
        "先选择范围：只撤销最近一次查询，或删除该词的全部学习记录（整词删除需再次确认）。" +
            "整词删除会移除全部查询（含已撤销）、笔记、掌握状态、复习记录与收藏；" +
            "个人补词、原始词库、其他生词与已导出的 PDF 不受影响。"

    /**
     * Compact list-row button. The caller adds it to the row and supplies the exact existing flow in
     * [onClick]; the returned button can be enabled/disabled by the caller.
     */
    fun listRemoveButton(context: Context, headword: String, onClick: () -> Unit): Button =
        Button(context).apply {
            text = LIST_LABEL
            textSize = 14f
            isAllCaps = false
            minWidth = context.dp(56)
            minHeight = context.dp(48)
            setTextColor(Paper.error)
            background = context.shape(Paper.lightGreen, 8)
            setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
            contentDescription = "从生词本移除 $headword 的学习记录。$SCOPE_HINT"
            setOnClickListener { onClick() }
        }

    /** The scope explanation as a standalone view for the list header (or anywhere else). */
    fun listScopeHint(context: Context): TextView =
        context.text(SCOPE_HINT, 13f, Paper.secondary)

    /**
     * Detail-panel entry: the primary remove button followed by the scope explanation. The caller
     * inserts [view] and passes [removeButton] into the existing binding so its enabled state stays
     * under the same busy/confirming/stale/invalidated rules as the other detail controls.
     */
    class DetailSection internal constructor(
        val view: LinearLayout,
        val removeButton: Button,
        val scopeHint: TextView
    )

    fun detailSection(context: Context, headword: String, onClick: () -> Unit): DetailSection {
        val removeButton = context.button(DETAIL_LABEL) { onClick() }.apply {
            setTextColor(Paper.error)
            contentDescription = "从生词本移除 $headword 的学习记录。$SCOPE_HINT"
        }
        val scopeHint = context.text(SCOPE_HINT, 13f, Paper.secondary)
        val view = context.column().apply {
            addView(removeButton, LinearLayout.LayoutParams(-1, -2))
            addView(scopeHint, LinearLayout.LayoutParams(-1, -2))
        }
        return DetailSection(view, removeButton, scopeHint)
    }
}
