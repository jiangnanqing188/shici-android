package com.shici.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import com.shici.app.R

/** Presentation only. Route ids and every page-leave admission remain owned by the Activity. */
class MainNavigation(
    private val context: Context,
    private val wide: Boolean,
    private val items: List<Item>,
    private val settings: Item,
    selectedId: String,
    private val onSelect: (String) -> Unit
) {
    data class Item(val id: String, val label: String)

    private val buttons = LinkedHashMap<String, Button>()
    private var moreMenu: PopupMenu? = null
    private val primaryIds = listOf("LOOKUP", "VOCABULARY", "STUDY")
    private val moreId = "navigation_more"
    private val secondary get() = items.filter { it.id !in primaryIds } + settings
    val view: View = if (wide) wideView() else compactView()

    init { setSelected(selectedId) }

    /** No posted scrolling or deferred selection: a late navigation callback cannot select a tab. */
    fun setSelected(id: String) {
        retireMoreMenu()
        buttons.forEach { (buttonId, button) ->
            val selected = buttonId == id ||
                (buttonId == moreId && buttons[id] == null && secondary.any { it.id == id })
            button.background = context.shape(if (selected) Paper.lightGreen else Paper.background, 10)
            button.setTextColor(if (selected) Paper.green else Paper.secondary)
            button.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
            button.isSelected = selected
            button.compoundDrawables.filterNotNull().forEach { it.setTint(if (selected) Paper.green else Paper.secondary) }
            button.contentDescription = if (buttonId == moreId && selected)
                "更多，当前为${secondary.firstOrNull { it.id == id }?.label.orEmpty()}" else
                "${button.text}${if (selected) "，当前页面" else "，点按打开"}"
        }
    }

    private fun wideView(): View {
        val sidebar = context.column().apply {
            setPadding(context.dp(12), context.dp(20), context.dp(12), context.dp(12))
            setBackgroundColor(Paper.background)
            layoutParams = LinearLayout.LayoutParams(context.dp(184), -1)
        }
        val brand = context.row().apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(36), context.dp(36)).apply { marginEnd = context.dp(10) })
        brand.addView(context.text("拾词", 25f, Paper.ink, true))
        sidebar.addView(brand)
        sidebar.space(24)
        val main = context.column()
        primaryIds.mapNotNull { id -> items.firstOrNull { it.id == id } }.forEach {
            main.addView(navButton(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(6) })
        }
        items.firstOrNull { it.id == "TRANSLATION" }?.let {
            main.space(8)
            main.addView(navButton(it), LinearLayout.LayoutParams(-1, -2))
        }
        main.space(8)
        val moreItems = items.filter { it.id !in primaryIds && it.id != "TRANSLATION" && it.id != "GUIDE" }
        main.addView(moreButton(moreItems), LinearLayout.LayoutParams(-1, -2))
        sidebar.addView(ScrollView(context).apply { addView(main) }, LinearLayout.LayoutParams(-1, 0, 1f))
        sidebar.divider()
        items.firstOrNull { it.id == "GUIDE" }?.let {
            sidebar.addView(navButton(it), LinearLayout.LayoutParams(-1, -2))
        }
        // Help is secondary; settings remains a separate, always-mounted lower-left entry.
        sidebar.addView(navButton(settings), LinearLayout.LayoutParams(-1, -2))
        return sidebar
    }

    private fun compactView(): View = context.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(4), context.dp(6), context.dp(4), context.dp(6))
        setBackgroundColor(Paper.background)
        primaryIds.mapNotNull { id -> items.firstOrNull { it.id == id } }.forEach {
            addView(navButton(it), LinearLayout.LayoutParams(0, -2, 1f))
        }
        // Exactly four fixed entries. Every other original route, including settings, is here.
        addView(moreButton(secondary), LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun retireMoreMenu() {
        val retired = moreMenu
        moreMenu = null // Retire the callback identity before dismiss can deliver anything.
        retired?.dismiss()
    }

    private fun moreButton(destinations: List<Item>): Button = navButton(Item(moreId, "更多")).apply {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { retireMoreMenu() }
        })
        setOnClickListener { anchor ->
            retireMoreMenu()
            if (!anchor.isAttachedToWindow) return@setOnClickListener
            val chooser = PopupMenu(context, anchor)
            moreMenu = chooser
            destinations.forEachIndexed { index, item -> chooser.menu.add(0, index, index, item.label) }
            chooser.setOnDismissListener { dismissed ->
                if (moreMenu === dismissed) moreMenu = null
            }
            chooser.setOnMenuItemClickListener { choice ->
                if (moreMenu !== chooser || !anchor.isAttachedToWindow) {
                    false
                } else {
                    val destination = destinations.getOrNull(choice.itemId)
                    retireMoreMenu()
                    if (destination != null) onSelect(destination.id)
                    destination != null
                }
            }
            chooser.show()
        }
    }

    private fun navButton(item: Item): Button = context.button(item.label) { onSelect(item.id) }.apply {
        textSize = if (wide) 15f else 13f
        minWidth = 0; minimumWidth = 0
        minHeight = context.dp(52)
        gravity = if (wide) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        setPadding(context.dp(if (wide) 10 else 2), context.dp(8), context.dp(if (wide) 10 else 2), context.dp(8))
        val icon = NavigationIcon(item.id).apply { setBounds(0, 0, context.dp(22), context.dp(22)) }
        compoundDrawablePadding = context.dp(8)
        if (wide) setCompoundDrawables(icon, null, null, null) else setCompoundDrawables(null, icon, null, null)
        buttons[item.id] = this
    }

    /** Small native line icons; no font glyph dependency, asset download or new UI framework. */
    private class NavigationIcon(private val id: String) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Paper.secondary; style = Paint.Style.STROKE; strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            when (id) {
                "LOOKUP" -> { canvas.drawCircle(10f, 10f, 6.5f, paint); canvas.drawLine(15f, 15f, 21f, 21f, paint) }
                "VOCABULARY" -> {
                    val path = Path().apply {
                        moveTo(12f, 5f); cubicTo(8f, 2f, 4f, 3f, 2f, 4f); lineTo(2f, 20f)
                        cubicTo(6f, 18f, 9f, 19f, 12f, 21f); cubicTo(15f, 19f, 18f, 18f, 22f, 20f)
                        lineTo(22f, 4f); cubicTo(18f, 2f, 15f, 3f, 12f, 5f); lineTo(12f, 21f)
                    }
                    canvas.drawPath(path, paint)
                }
                "STUDY" -> {
                    canvas.drawRoundRect(3f, 4f, 21f, 22f, 2f, 2f, paint)
                    canvas.drawLine(7f, 2f, 7f, 7f, paint); canvas.drawLine(17f, 2f, 17f, 7f, paint)
                    val path = Path().apply { moveTo(7f, 14f); lineTo(11f, 18f); lineTo(17f, 11f) }
                    canvas.drawPath(path, paint)
                }
                "SETTINGS" -> { canvas.drawCircle(12f, 12f, 7f, paint); canvas.drawCircle(12f, 12f, 2.5f, paint)
                    for (i in 0..7) { canvas.save(); canvas.rotate(i * 45f, 12f, 12f); canvas.drawLine(12f, 2f, 12f, 5f, paint); canvas.restore() } }
                "GUIDE" -> { canvas.drawCircle(12f, 12f, 9f, paint); canvas.drawLine(12f, 11f, 12f, 17f, paint); canvas.drawPoint(12f, 7f, paint) }
                "TRANSLATION" -> { canvas.drawRoundRect(2f, 2f, 16f, 16f, 2f, 2f, paint); canvas.drawRoundRect(9f, 9f, 22f, 22f, 2f, 2f, paint) }
                else -> for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) canvas.drawRoundRect(x, y, x + 7f, y + 7f, 1f, 1f, paint)
            }
            canvas.restoreToCount(saved)
        }
        override fun setTint(tintColor: Int) { paint.color = tintColor; invalidateSelf() }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
