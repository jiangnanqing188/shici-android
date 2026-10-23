package com.shici.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * App-shell navigation with a fixed settings entry.
 *
 * The component owns only presentation: it never reads the repository, never gates a page and never
 * keeps page state. Stable ids are opaque strings supplied by the caller (page enum names); the
 * caller decides what a selection means. [setSelected] restyles the existing buttons in place, so
 * it cannot reset the scroll position of either navigation region.
 *
 * Wide layout: a 216dp sidebar whose six main entries scroll in the weighted region above a
 * divider; settings sits in its own fixed area below the divider. Narrow layout: a fixed bottom
 * bar whose main entries scroll horizontally, with settings on a separate full-width row that the
 * main row can never push off screen. Insets stay with the shell root; this view adds none.
 *
 * Ids must be unique across [items] and [settings]; the caller keeps one stable id per page.
 */
class MainNavigation(
    private val context: Context,
    wide: Boolean,
    items: List<Item>,
    settings: Item,
    selectedId: String,
    private val onSelect: (String) -> Unit
) {
    data class Item(val id: String, val label: String)

    private val buttons = LinkedHashMap<String, Button>()

    /** Root navigation view. It already carries wide/narrow layout params for a LinearLayout shell. */
    val view: View = if (wide) wideView(items, settings) else compactView(items, settings)

    init {
        setSelected(selectedId)
    }

    /** Updates every button's selected background/typeface without rebuilding the view tree. */
    fun setSelected(id: String) {
        buttons.forEach { (buttonId, entryButton) ->
            val selected = buttonId == id
            entryButton.background =
                context.shape(if (selected) Paper.lightGreen else Paper.background, 8)
            entryButton.setTypeface(
                entryButton.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL
            )
            entryButton.isSelected = selected
        }
    }

    private fun wideView(items: List<Item>, settings: Item): View {
        val sidebar = context.column().apply {
            setBackgroundColor(Paper.background)
            setPadding(context.dp(16), context.dp(20), context.dp(16), context.dp(16))
            layoutParams = LinearLayout.LayoutParams(context.dp(216), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        sidebar.addView(context.text("拾词", 30f, Paper.green, true))
        sidebar.addView(context.text("专注阅读\n随手拾词", 15f, Paper.secondary))
        sidebar.space(16)

        val main = context.column()
        items.forEach { item ->
            main.addView(navButton(item), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = context.dp(6)
            })
        }
        // Only the main entries scroll; settings is a separate child below this weighted region.
        sidebar.addView(ScrollView(context).apply {
            isFillViewport = false
            addView(main)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        sidebar.addView(separator(), LinearLayout.LayoutParams(-1, context.dp(1)).apply {
            topMargin = context.dp(20)
            bottomMargin = context.dp(16)
        })
        sidebar.addView(navButton(settings), LinearLayout.LayoutParams(-1, -2))
        return sidebar
    }

    private fun compactView(items: List<Item>, settings: Item): View {
        val wrapper = context.column().apply {
            setBackgroundColor(Paper.background)
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val rowHost = context.row()
        items.forEach { item ->
            rowHost.addView(navButton(item), LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = context.dp(6)
            })
        }
        wrapper.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(rowHost)
        }, LinearLayout.LayoutParams(-1, -2))

        wrapper.addView(separator(), LinearLayout.LayoutParams(-1, context.dp(1)).apply {
            topMargin = context.dp(8)
            bottomMargin = context.dp(6)
        })
        // Settings is a separate full-width row, always visible without horizontal scrolling.
        wrapper.addView(navButton(settings), LinearLayout.LayoutParams(-1, -2))
        return wrapper
    }

    private fun navButton(item: Item): Button = context.button(item.label) { onSelect(item.id) }.apply {
        textSize = 15f
        isAllCaps = false
        // Keep at least a 48dp touch target while still letting the label wrap and grow with the
        // user's font scale. Neither width nor height is fixed, so no label is clipped.
        minHeight = context.dp(48)
        minimumWidth = context.dp(64)
        setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        contentDescription = "打开${item.label}页"
        buttons[item.id] = this
    }

    private fun separator(): View = View(context).apply { setBackgroundColor(Paper.line) }
}
