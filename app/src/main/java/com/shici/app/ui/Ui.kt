package com.shici.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
