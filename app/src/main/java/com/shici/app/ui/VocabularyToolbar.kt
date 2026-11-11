package com.shici.app.ui

import android.content.Context
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
 * Filter occupies its own row; a single sort chooser and management entry share the next row
 * when they fit. Narrow/large-text windows stack them without hiding or shortening any choice.
 * Every control retains a >=48dp touch target.
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

        // Filter is the first layer; sort and management are secondary, never a horizontal scroller.
        addView(filterView, LinearLayout.LayoutParams(-1, -2))
        val stack = context.resources.configuration.fontScale >= PaperLayout.LARGE_FONT_SCALE ||
            context.resources.configuration.screenWidthDp < 360
        val actions = if (stack) context.column() else context.row()
        actions.addView(sortView, if (stack) LinearLayout.LayoutParams(-1, -2)
            else LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(6) })
        actions.addView(manageButton, LinearLayout.LayoutParams(if (stack) -1 else -2, -2))
        addView(actions, LinearLayout.LayoutParams(-1, -2))
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
        manageButton.background = context.shape(if (managing) Paper.lightGreen else Paper.background, 10, !managing)
        manageButton.contentDescription = if (managing)
            "完成勾选，收起导出与选择栏"
        else
            "管理导出勾选，展开导出与选择栏"
    }
}
