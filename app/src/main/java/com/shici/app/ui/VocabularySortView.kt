package com.shici.app.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.shici.app.core.VocabularySort

/**
 * One compact sort entry opens all four existing display orders. Constructing/restoring/opening
 * never notifies the host; only an explicit different selection uses onChanged. The host retains
 * search, filtered results and export-selection ownership. No repository or query writes occur.
 */
class VocabularySortView(
    context: Context,
    selected: VocabularySort,
    var onChanged: (VocabularySort) -> Unit
) : LinearLayout(context) {

    /** Selected order is host state; merely opening the chooser never changes it. */
    var sort: VocabularySort = selected
        private set

    private var sortDialog: AlertDialog? = null

    private val sortButton = context.button("") {
        if (!isAttachedToWindow || sortDialog?.isShowing == true) return@button
        retireSortDialog()
        val options = VocabularySort.entries
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(context)
            .setTitle("生词排序")
            .setSingleChoiceItems(options.map { description(it) }.toTypedArray(), options.indexOf(sort)) choice@{ source, index ->
                // An old callback cannot borrow a newly opened chooser's identity.
                if (source !== dialog || sortDialog !== dialog || !dialog.isShowing ||
                    !isAttachedToWindow) return@choice
                retireSortDialog()
                choose(options[index])
            }
            .setNegativeButton("取消", null)
            .create()
        sortDialog = dialog
        dialog.setOnDismissListener { if (sortDialog === dialog) sortDialog = null }
        dialog.show()
    }

    init {
        orientation = VERTICAL
        sortButton.textSize = 14f
        sortButton.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        addView(sortButton, LayoutParams(-1, -2))
        render()
    }

    /**
     * Applies [value] to the strip and returns true when the selection actually changed. The
     * listener is notified only when [notify] is true and the value changed, so restoring the
     * caller's saved state cannot trigger an extra reload. The constructor never calls it.
     */
    fun setSort(value: VocabularySort, notify: Boolean = false): Boolean {
        retireSortDialog()
        val changed = sort != value
        sort = value
        render()
        if (changed && notify) onChanged(value)
        return changed
    }

    private fun choose(value: VocabularySort) = setSort(value, notify = true)

    private fun render() {
        sortButton.text = "${description(sort)} ▾"
        sortButton.contentDescription = "排序：${description(sort)}，点按选择"
        sortButton.background = context.shape(Paper.background, 10)
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
        retireSortDialog()
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            sort = VocabularySort.entries.firstOrNull { it.name == state.sortName }
                ?: VocabularySort.RECENT_FIRST
            render()
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    override fun onDetachedFromWindow() {
        retireSortDialog()
        super.onDetachedFromWindow()
    }

    private fun retireSortDialog() {
        val previous = sortDialog
        sortDialog = null
        previous?.dismiss()
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
