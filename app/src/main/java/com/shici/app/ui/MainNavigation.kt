package com.shici.app.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.PopupMenu

/** Stable route ids belong to the host. Navigation only draws and forwards explicit selections. */
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
    private val compact = !wide
    private val primaryIds = listOf("LOOKUP", "VOCABULARY", "STUDY")
    private val moreId = "MORE"
    private var moreMenu: PopupMenu? = null
    private var selectedRoute = selectedId

    val view: View = if (wide) wideView(items, settings) else compactView(items, settings)

    init {
        setSelected(selectedId)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { retireMoreMenu() }
        })
    }

    /** No scrolling or posted reveal: an old SETTINGS selection cannot move a later HOME route. */
    fun setSelected(id: String) {
        if (selectedRoute != id) retireMoreMenu()
        selectedRoute = id
        val visibleId = if (compact && id !in primaryIds) moreId else id
        buttons.forEach { (buttonId, button) ->
            val selected = buttonId == visibleId
            button.background = context.shape(if (selected) Paper.lightGreen else Paper.background, 12)
            button.setTypeface(button.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            button.setTextColor(if (selected) Paper.green else Paper.secondary)
            button.isSelected = selected
        }
    }

    private fun compactView(items: List<Item>, settings: Item): View {
        val destinations = items + settings
        val secondary = destinations.filter { it.id !in primaryIds }
        val wrapper = context.column().apply { setBackgroundColor(Paper.background) }
        wrapper.addView(separator(), LinearLayout.LayoutParams(-1, context.dp(1)))
        val row = context.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
        }
        primaryIds.forEach { id ->
            destinations.firstOrNull { it.id == id }?.let { item ->
                row.addView(navButton(item), LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val more = navButton(Item(moreId, "更多")).apply {
            contentDescription = "更多：首页、添加补词、翻译、统计、使用引导、设置"
            setOnClickListener { anchor ->
                retireMoreMenu()
                if (!wrapper.isAttachedToWindow || !anchor.isAttachedToWindow) return@setOnClickListener
                val openingRoute = selectedRoute
                val menu = PopupMenu(context, anchor)
                moreMenu = menu
                secondary.forEachIndexed { index, item -> menu.menu.add(0, index, index, item.label) }
                menu.setOnDismissListener { if (moreMenu === it) moreMenu = null }
                menu.setOnMenuItemClickListener choice@{ item ->
                    if (moreMenu !== menu || !wrapper.isAttachedToWindow || !anchor.isAttachedToWindow ||
                        selectedRoute != openingRoute) return@choice false
                    val destination = secondary.getOrNull(item.itemId) ?: return@choice false
                    retireMoreMenu()
                    onSelect(destination.id)
                    true
                }
                menu.show()
            }
        }
        row.addView(more, LinearLayout.LayoutParams(0, -2, 1f))
        wrapper.addView(row, LinearLayout.LayoutParams(-1, -2))
        return wrapper
    }

    /** The existing real button; no guide owns a duplicate or an estimated hit area. */
    fun practiceTarget(id: String): View? = buttons[id]

    private fun retireMoreMenu() {
        val previous = moreMenu
        moreMenu = null
        previous?.dismiss()
    }

    private fun wideView(items: List<Item>, settings: Item): View {
        val sidebar = context.column().apply {
            setBackgroundColor(Paper.background)
            setPadding(context.dp(16), context.dp(20), context.dp(16), context.dp(16))
            layoutParams = LinearLayout.LayoutParams(context.dp(216), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        sidebar.addView(context.text("拾词", 28f, Paper.green, true))
        sidebar.addView(context.text("专注阅读 · 随手拾词", 14f, Paper.secondary))
        sidebar.space(16)
        val main = context.column()
        items.filter { it.id != "GUIDE" }.forEach { item ->
            main.addView(navButton(item), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = context.dp(6)
            })
        }
        sidebar.addView(ScrollView(context).apply { addView(main) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sidebar.addView(separator(), LinearLayout.LayoutParams(-1, context.dp(1)).apply {
            topMargin = context.dp(12); bottomMargin = context.dp(12)
        })
        items.firstOrNull { it.id == "GUIDE" }?.let {
            sidebar.addView(navButton(it), LinearLayout.LayoutParams(-1, -2))
        }
        sidebar.addView(navButton(settings), LinearLayout.LayoutParams(-1, -2))
        return sidebar
    }

    private fun navButton(item: Item): Button = context.button(item.label) { onSelect(item.id) }.apply {
        textSize = if (compact) 14f else 15f
        minWidth = 0; minimumWidth = 0
        minHeight = context.dp(52)
        setPadding(context.dp(4), context.dp(8), context.dp(4), context.dp(8))
        contentDescription = "打开${item.label}页"
        if (compact) {
            val icon = when (item.id) {
                "LOOKUP" -> android.R.drawable.ic_menu_search
                "VOCABULARY" -> android.R.drawable.ic_menu_agenda
                "STUDY" -> android.R.drawable.ic_menu_today
                else -> android.R.drawable.ic_menu_more
            }
            val drawable = context.getDrawable(icon)?.mutate()?.apply {
                setTint(Paper.green)
                setBounds(0, 0, context.dp(22), context.dp(22))
            }
            setCompoundDrawables(null, drawable, null, null)
            compoundDrawablePadding = context.dp(4)
        }
        buttons[item.id] = this
    }

    private fun separator(): View = View(context).apply { setBackgroundColor(Paper.line) }
}
