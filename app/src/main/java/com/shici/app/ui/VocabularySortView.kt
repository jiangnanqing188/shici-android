package com.shici.app.ui

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import com.shici.app.core.VocabularySort

/**
 * Compact single-choice sort strip for the personal vocabulary list.
 *
 * It is purely a display/selection widget: it never reads or writes the database, never changes a
 * query count and never touches the PDF export selection. The caller keeps the current
 * [VocabularySort] in its own state and re-renders its already-filtered snapshot through
 * [com.shici.app.core.VocabularyOrdering.apply].
 *
 * The constructor shows [selected] immediately and deliberately does **not** invoke [onChanged];
 * only a user tap on a different menu option does. Tapping the already selected menu option is a no-op. A
 * programmatic [setSort] call notifies only with `notify = true`, so restoring persisted UI state
 * can never loop back into another list reload.
 *
 * Wiring example (final integrator, inside `MainActivity.vocabulary()`):
 * ```
 * vocabularySortView = VocabularySortView(this, vocabularySort) { value ->
 *     vocabularySort = value
 *     renderWords(preserveDetail = true)
 * }
 * content.addView(vocabularySortView)
 * ```
 * and in `renderWords()` use `VocabularyOrdering.apply(filteredWords(), vocabularySort)` for the
 * displayed list only, keeping the unfiltered/unsorted snapshot semantics for export selection.
 *
 * The view also persists its own choice through [onSaveInstanceState] (for hosts that give it an
 * id and let the hierarchy save/restore it). The recommended primary path is still the caller's
 * saved `vocabulary_sort` field, because this page is rebuilt programmatically.
 */
class VocabularySortView(
    context: Context,
    selected: VocabularySort,
    var onChanged: (VocabularySort) -> Unit
) : LinearLayout(context) {

    private var sortMenu: PopupMenu? = null
    private val orderButton = context.button("") { }.apply {
        textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        background = context.shape(Paper.surface, 8)
        setOnClickListener { anchor ->
            retireSortMenu()
            if (!anchor.isAttachedToWindow || !this@VocabularySortView.isAttachedToWindow) return@setOnClickListener
            val chooser = PopupMenu(context, anchor)
            sortMenu = chooser
            VocabularySort.entries.forEachIndexed { index, option ->
                chooser.menu.add(0, index, index, description(option)).isChecked = option == sort
            }
            chooser.menu.setGroupCheckable(0, true, true)
            chooser.setOnDismissListener { dismissed ->
                if (sortMenu === dismissed) sortMenu = null
            }
            chooser.setOnMenuItemClickListener { item ->
                if (sortMenu !== chooser || !anchor.isAttachedToWindow || !this@VocabularySortView.isAttachedToWindow) {
                    false
                } else {
                    val option = VocabularySort.entries.getOrNull(item.itemId)
                    retireSortMenu()
                    if (option != null) choose(option)
                    option != null
                }
            }
            chooser.show()
        }
    }

    private fun retireSortMenu() {
        val retired = sortMenu
        sortMenu = null
        retired?.dismiss()
    }

    override fun onDetachedFromWindow() {
        retireSortMenu()
        super.onDetachedFromWindow()
    }

    /** The currently displayed sort order; this still changes display order only. */
    var sort: VocabularySort = selected
        private set

    init {
        orientation = VERTICAL
        addView(orderButton, LayoutParams(-1, -2))
        render()
    }

    /**
     * Applies [value] to the strip and returns true when the selection actually changed. The
     * listener is notified only when [notify] is true and the value changed, so restoring the
     * caller's saved state cannot trigger an extra reload. The constructor never calls it.
     */
    fun setSort(value: VocabularySort, notify: Boolean = false): Boolean {
        retireSortMenu()
        val changed = sort != value
        sort = value
        render()
        if (changed && notify) onChanged(value)
        return changed
    }

    private fun choose(value: VocabularySort) = setSort(value, notify = true)

    private fun render() {
        orderButton.text = "${description(sort)} ▾"
        orderButton.contentDescription = "排序：${description(sort)}，点按选择排序方式"
        orderButton.setTextColor(Paper.green)
    }

    private fun description(sort: VocabularySort): String = when (sort) {
        VocabularySort.RECENT_FIRST -> "最近查询，晚到早"
        VocabularySort.OLDEST_FIRST -> "最早查询，早到晚"
        VocabularySort.MOST_QUERIED -> "查询次数从多到少"
        VocabularySort.LEAST_QUERIED -> "查询次数从少到多"
    }

    override fun onSaveInstanceState(): Parcelable =
        SavedState(super.onSaveInstanceState()).apply {
            sortName = this@VocabularySortView.sort.name
        }

    override fun onRestoreInstanceState(state: Parcelable?) {
        retireSortMenu()
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            sort = VocabularySort.entries.firstOrNull { it.name == state.sortName }
                ?: VocabularySort.RECENT_FIRST
            render()
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private class SavedState : View.BaseSavedState {
        var sortName: String = VocabularySort.RECENT_FIRST.name

        constructor(superState: Parcelable?) : super(superState)

        constructor(source: Parcel) : super(source) {
            sortName = source.readString() ?: VocabularySort.RECENT_FIRST.name
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeString(sortName)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }
}
