package com.shici.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

object Paper {
    val background = Color.rgb(246, 245, 240)
    val surface = Color.WHITE
    val ink = Color.rgb(36, 47, 44)
    val secondary = Color.rgb(104, 115, 109)
    val green = Color.rgb(39, 101, 82)
    val lightGreen = Color.rgb(231, 240, 235)
    val line = Color.rgb(223, 228, 220)
    val warning = Color.rgb(138, 89, 35)
    val error = Color.rgb(173, 62, 54)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.text(value: String, size: Float = 16f, color: Int = Paper.ink, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
fun Context.shape(color: Int, radius: Int = 16, border: Boolean = false) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat()
    if (border) setStroke(dp(1), Paper.line)
}
fun Context.button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
    text = label; textSize = 16f; isAllCaps = false; minHeight = dp(48)
    setTextColor(if (primary) Color.WHITE else Paper.green)
    background = shape(if (primary) Paper.green else Paper.lightGreen, 8)
    setPadding(dp(12), dp(8), dp(12), dp(8))
    setOnClickListener { action() }
}
fun LinearLayout.space(height: Int = 16) { addView(View(context), LinearLayout.LayoutParams(1, context.dp(height))) }
fun LinearLayout.divider() { addView(View(context).apply { setBackgroundColor(Paper.line) },
    LinearLayout.LayoutParams(-1, context.dp(1)).apply { topMargin = context.dp(12); bottomMargin = context.dp(12) }) }

fun Context.badge(label: String): TextView = text(label, 16f, Paper.green).apply {
    setPadding(dp(12), dp(4), dp(12), dp(4)); background = shape(Paper.lightGreen, 8)
}
fun Context.inputField(hintText: String, singleLine: Boolean = false): EditText = EditText(this).apply {
    hint = hintText; textSize = 16f; setTextColor(Paper.ink); setHintTextColor(Paper.secondary)
    setSingleLine(singleLine); minHeight = dp(48)
    setPadding(dp(12), dp(12), dp(12), dp(12)); background = shape(Paper.surface, 8, true)
}
fun Context.pair(first: View, second: View, horizontal: Boolean, gap: Int = 16): LinearLayout =
    (if (horizontal) row() else column()).apply {
        addView(first, if (horizontal) LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(gap) }
            else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(gap) })
        addView(second, if (horizontal) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
    }

/**
 * Collapsed, purely presentational help block.
 *
 * The returned vertical container shows a >=48dp toggle followed by [buildContent]'s static views,
 * which start GONE. Tapping the toggle only flips visibility and the arrow/state description; it
 * never starts capture, opens a permission page, queries, downloads or writes anything. The host is
 * responsible for keeping inputs, primary actions, live status and required consent outside this
 * helper. No fixed height is set, so long text wraps and the block grows with the font scale.
 *
 * [buildContent] runs synchronously during construction with the hidden body as receiver, so it may
 * only add static views; it must not depend on an Activity or start asynchronous work.
 */
fun Context.helpDisclosure(title: String, buildContent: LinearLayout.() -> Unit): View {
    val container = column()
    val body = column().apply { visibility = View.GONE }
    body.buildContent()

    val toggle = Button(this).apply {
        isAllCaps = false
        textSize = 16f
        minHeight = dp(48)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setTextColor(Paper.green)
        background = shape(Paper.lightGreen, 8)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        text = "$title ▾"
        contentDescription = "$title，当前已收起，点按展开"
        setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            body.visibility = if (expand) View.VISIBLE else View.GONE
            text = "$title ${if (expand) "▴" else "▾"}"
            contentDescription = "$title，当前${if (expand) "已展开，点按收起" else "已收起，点按展开"}"
        }
    }

    container.addView(toggle, LinearLayout.LayoutParams(-1, -2))
    container.addView(body, LinearLayout.LayoutParams(-1, -2))
    return container
}
