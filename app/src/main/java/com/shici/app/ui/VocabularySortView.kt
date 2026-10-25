package com.shici.app.ui

import android.content.Context
import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
 * only a user tap on a different chip does. Tapping the already selected chip is a no-op. A
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

    private val chips = LinkedHashMap<VocabularySort, Button>()

    /** The currently displayed sort order. */
    var sort: VocabularySort = selected
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))

        val title = context.text("排序", 14f, Paper.secondary).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = context.dp(48)
        }
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = context.dp(4)
        })

        val options = VocabularySort.entries
        options.forEachIndexed { index, option ->
            val chip = context.button(shortLabel(option)) { choose(option) }.apply {
                textSize = 13f
                minWidth = 0
                setPadding(context.dp(2), context.dp(8), context.dp(2), context.dp(8))
            }
            chips[option] = chip
            addView(chip, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index != options.lastIndex) marginEnd = context.dp(4)
            })
        }
        render()
    }

    /**
     * Applies [value] to the strip and returns true when the selection actually changed. The
     * listener is notified only when [notify] is true and the value changed, so restoring the
     * caller's saved state cannot trigger an extra reload. The constructor never calls it.
     */
    fun setSort(value: VocabularySort, notify: Boolean = false): Boolean {
        val changed = sort != value
        sort = value
        render()
        if (changed && notify) onChanged(value)
        return changed
    }

    private fun choose(value: VocabularySort) = setSort(value, notify = true)

    private fun render() {
        chips.forEach { (option, chip) ->
            val selected = option == sort
            chip.isSelected = selected
            chip.setTextColor(if (selected) Color.WHITE else Paper.green)
            chip.background = context.shape(if (selected) Paper.green else Paper.lightGreen, 8)
            chip.contentDescription =
                "${description(option)}${if (selected) "，已选中" else ""}"
        }
    }

    private fun shortLabel(sort: VocabularySort): String = when (sort) {
        VocabularySort.RECENT_FIRST -> "最近"
        VocabularySort.OLDEST_FIRST -> "最早"
        VocabularySort.MOST_QUERIED -> "多→少"
        VocabularySort.LEAST_QUERIED -> "少→多"
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
