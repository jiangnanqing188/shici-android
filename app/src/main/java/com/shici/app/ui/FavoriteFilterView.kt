package com.shici.app.ui

import android.content.Context
import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/**
 * Small "全部 / 仅收藏" filter strip for the personal vocabulary list. It is deliberately only a
 * display/selection widget: it never reads or writes the database, and the caller decides how to
 * filter the snapshot it already has (`repository.words()` carries each word's `favorite` flag).
 *
 * Wiring example (final integrator):
 * ```
 * val filter = FavoriteFilterView(activity)
 * filter.onFilterChanged = { onlyFavorites -> renderList(words.filter { !onlyFavorites || it.favorite }) }
 * ```
 * The selection survives rotation/recreation through [onSaveInstanceState]; a programmatic
 * [setOnlyFavorites] call does not invoke the listener unless `notify = true`, so refreshing the UI
 * from a fresh snapshot can never loop back into another reload.
 */
class FavoriteFilterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val allButton: Button = context.button("全部") { choose(false) }
    private val favoritesButton: Button = context.button("仅收藏") { choose(true) }

    /** True when the list should show only starred words. */
    var onlyFavorites: Boolean = false
        private set

    /** Invoked only on a real user- or caller-requested selection change (`notify = true`). */
    var onFilterChanged: ((Boolean) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        contentDescription = "生词筛选：全部或仅收藏"
        addView(allButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = context.dp(8)
        })
        addView(favoritesButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        render()
    }

    /**
     * Applies [value] to the strip. Returns true when the selection actually changed. The listener is
     * notified only when [notify] is true and the value changed, so the caller can restore persisted
     * UI state or server-driven state without triggering another list reload.
     */
    fun setOnlyFavorites(value: Boolean, notify: Boolean = false): Boolean {
        val changed = onlyFavorites != value
        onlyFavorites = value
        render()
        if (changed && notify) onFilterChanged?.invoke(value)
        return changed
    }

    private fun choose(value: Boolean) = setOnlyFavorites(value, notify = true)

    private fun render() {
        style(allButton, selected = !onlyFavorites)
        style(favoritesButton, selected = onlyFavorites)
    }

    private fun style(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) Color.WHITE else Paper.green)
        button.background = context.shape(if (selected) Paper.green else Paper.lightGreen, 8)
        button.isSelected = selected
    }

    override fun onSaveInstanceState(): Parcelable =
        SavedState(super.onSaveInstanceState()).apply { onlyFavorites = this@FavoriteFilterView.onlyFavorites }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            onlyFavorites = state.onlyFavorites
            render()
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private class SavedState : View.BaseSavedState {
        var onlyFavorites: Boolean = false

        constructor(superState: Parcelable?) : super(superState)

        constructor(source: Parcel) : super(source) {
            onlyFavorites = source.readInt() == 1
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(if (onlyFavorites) 1 else 0)
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
