package com.shici.app.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.shici.app.core.VocabularySort

/**
 * Compact first-screen toolbar for the vocabulary page. It composes the existing
 * [FavoriteFilterView] ("全部 / 仅收藏") and [VocabularySortView] (four display orders) and adds one
 * "管理与导出 / 完成" button that reveals the export-selection panel (which also holds the single
 * "导出 PDF" action).
 *
 * It is purely presentational. It never reads or writes the repository, query counts, learning data,
 * the search text or the PDF export selection; the host owns all of those. Callbacks fire only on a
 * real user tap on a different value. The programmatic [setState] never notifies, so restoring the
 * host's state or reacting to a manage toggle cannot loop back into another list rebuild.
 *
 * The layout uses two rows (filter + manage, then the sort chips) so a narrow screen never has to
 * fit every control on one line. Every control keeps the shared >=48dp minimum touch height.
 */
class VocabularyToolbar(context: Context) : LinearLayout(context) {

    /** Invoked only on a real user filter change. */
    var onFilterChanged: ((Boolean) -> Unit)? = null

    /** Invoked only on a real user sort change. */
    var onSortChanged: ((VocabularySort) -> Unit)? = null

    /** Invoked on every explicit tap of the manage/finish toggle. */
    var onManageToggled: ((Boolean) -> Unit)? = null

    /** True while the host shows the export-selection panel. */
    var managing: Boolean = false
        private set

    private val filterView = FavoriteFilterView(context).apply {
        onFilterChanged = { value -> this@VocabularyToolbar.onFilterChanged?.invoke(value) }
    }

    private val sortView = VocabularySortView(context, VocabularySort.RECENT_FIRST) { value ->
        this@VocabularyToolbar.onSortChanged?.invoke(value)
    }

    // The label names both halves of what the panel holds, because the PDF export action lives
    // inside that panel: a user looking for "导出" must still find the entry on this screen.
    private val manageButton = context.button("管理与导出") { chooseManage(!managing) }

    /** Mirrors the host's "仅收藏" filter; changed only by [setState] or a user tap. */
    val onlyFavorites: Boolean get() = filterView.onlyFavorites

    /** Mirrors the host's display order; changed only by [setState] or a user tap. */
    val sort: VocabularySort get() = sortView.sort

    init {
        orientation = VERTICAL

        manageButton.apply {
            textSize = 14f
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        }

        val filterRow = context.row().apply { gravity = Gravity.CENTER_VERTICAL }
        filterRow.addView(
            filterView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = context.dp(6)
            }
        )
        filterRow.addView(
            manageButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        addView(filterRow, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(sortView, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        render()
    }

    /**
     * Applies the host's state without notifying any callback. Used on page construction or after a
     * restore, never as a reaction to the user's own tap.
     */
    fun setState(onlyFavorites: Boolean, sort: VocabularySort, managing: Boolean) {
        filterView.setOnlyFavorites(onlyFavorites, notify = false)
        sortView.setSort(sort, notify = false)
        this.managing = managing
        render()
    }

    private fun chooseManage(value: Boolean) {
        if (value == managing) return
        managing = value
        render()
        onManageToggled?.invoke(value)
    }

    private fun render() {
        // Always the calm secondary style: the only filled primary action of this page is the
        // panel's own "导出 PDF". While managing, the open panel and the row checkboxes already
        // show the mode, so this button only needs to become the plain way back ("完成").
        manageButton.text = if (managing) "完成" else "管理与导出"
        manageButton.isSelected = managing
        manageButton.setTextColor(Paper.green)
        manageButton.background = context.shape(Paper.lightGreen, 8)
        manageButton.contentDescription = if (managing)
            "完成勾选，收起导出与选择栏"
        else
            "管理导出勾选，展开导出与选择栏"
    }
}
