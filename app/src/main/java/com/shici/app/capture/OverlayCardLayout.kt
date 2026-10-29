package com.shici.app.capture

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shici.app.ui.Paper
import com.shici.app.ui.dp
import com.shici.app.ui.shape
import com.shici.app.ui.text

/**
 * One compact floating result card: a fixed header, an internally scrolling body and an
 * always-visible footer.
 *
 * The footer and then the header are measured first and never scroll, so a long definition, a
 * large font scale or a short screen can only take space away from the body. The definition is
 * never hard-truncated to fake a one-screen card: the body scrolls instead, while
 * "划词 / 手动输入 / 退出" stays mounted. The footer buttons are at least 48dp tall and the footer
 * itself is never placed inside the scroll area.
 *
 * When the hosting window measures the card with an exact height (which is what the service does
 * once it has mounted the card into a fixed-size overlay window), the scrolling body is given the
 * remaining height instead of hugging its content, so the footer stays at the bottom of the window
 * even for a short definition. With a bounded offer (AT_MOST) the card only grows as far as its
 * content and keeps the footer right below the body.
 *
 * This class only builds and measures views. It owns no capture, OCR, dictionary, page or save
 * admission; the service keeps every guard and callback exactly where it was.
 */
internal class OverlayCardLayout(context: Context) : LinearLayout(context) {
    /** Short title / core identity of the card (for example the looked-up headword). */
    val header: LinearLayout = LinearLayout(context).apply { orientation = VERTICAL }
    /** The only scrolling part of the card: long meanings, raw text and secondary actions. */
    val body: LinearLayout = LinearLayout(context).apply { orientation = VERTICAL }
    /** Fixed bottom row: "划词", "手动输入" and "退出", each at least 48dp tall. */
    val footer: LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
    }
    val scroll: ScrollView = ScrollView(context).apply {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        isVerticalScrollBarEnabled = true
        addView(body, ViewGroup.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val restart: Button
    val manual: Button
    val exit: Button
    private val titleView: TextView = context.text("", 20f, bold = true).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

    init {
        orientation = VERTICAL
        header.addView(titleView)
        addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        restart = footerAction("划词", filled = true)
        manual = footerAction("手动输入", filled = false)
        exit = footerAction("退出", filled = false)
        // Same row, each a third of the width and at least 48dp tall. "划词" is the filled primary
        // action; "手动输入" and "退出" are lighter secondary actions. Sharing the row equally with
        // wrapping labels keeps all three reachable on a narrow screen or at a large font scale
        // instead of letting "退出" be pushed out.
        footer.addView(restart, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(6) })
        footer.addView(manual, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(6) })
        footer.addView(exit, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        restart.contentDescription = "划词：收起这张词卡，开始新的一笔划词"
        manual.contentDescription = "手动输入：收起这张词卡，在当前页面输入英文单词查词（打开时不会自动查询）"
        exit.contentDescription = "退出当前词卡，返回悬浮条（不停止查词）"
    }

    fun setTitle(title: String) { titleView.text = title }

    /** Scrolls the body to its end, for example after opening an inline editor at the bottom. */
    fun scrollBodyToEnd() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun footerAction(label: String, filled: Boolean): Button = Button(context).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        minHeight = context.dp(48)
        minimumHeight = context.dp(48)
        // Two lines at most: at a large font scale or on a narrow card a longer label such as
        // "手动输入" wraps instead of shrinking its hit target or pushing a sibling out.
        maxLines = 2
        gravity = Gravity.CENTER
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (filled) android.graphics.Color.WHITE else Paper.green)
        background = context.shape(if (filled) Paper.green else Paper.lightGreen, 8)
        setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
    }

    /**
     * Reserves the footer and header before giving the rest of the window height to the body.
     *
     * [heightMeasureSpec] is the size the window can offer. With AT_MOST the card grows only as far
     * as its content; with EXACTLY (a mounted fixed-size window) the body takes the remainder so the
     * footer stays pinned to the bottom. When the offered height is so small that the header and
     * footer would not both fit, the footer is measured first and survives; the header is capped
     * next only enough to leave a scrollable slice (which can be zero on a tiny window), so the card
     * is still dismissible. The body is never hard-truncated to fit: the content stays mounted and
     * scrolls.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(
            (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0), MeasureSpec.EXACTLY)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val fixedHeight = heightMode == MeasureSpec.EXACTLY
        val offered = if (heightMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4
            else MeasureSpec.getSize(heightMeasureSpec)
        val outerPadding = paddingTop + paddingBottom
        val innerHeight = (offered - outerPadding).coerceAtLeast(0)
        // Priority order: the footer first (it is the only always-visible navigation), then the
        // header, then whatever is left for the scrolling body. Neither fixed part is placed inside
        // the scroll area, so a large font scale or a short screen can never hide
        // "划词 | 手动输入 | 退出".
        footer.measure(childWidth, MeasureSpec.makeMeasureSpec(innerHeight, MeasureSpec.AT_MOST))
        val afterFooter = (innerHeight - footer.measuredHeight).coerceAtLeast(0)
        // The header keeps its natural height unless the remaining room is genuinely short: then it
        // is capped only enough to leave a minimum slice for the scrolling body. A larger font scale
        // therefore never lets the title push the fixed footer out, but also never hides the pinned
        // save/undo status when there is room to show it.
        val bodyFloor = minOf(context.dp(48), afterFooter / 3)
        val headerCap = (afterFooter - bodyFloor).coerceAtLeast(0)
        header.measure(childWidth, MeasureSpec.makeMeasureSpec(headerCap, MeasureSpec.AT_MOST))
        val maxBody = (afterFooter - header.measuredHeight).coerceAtLeast(0)
        if (fixedHeight) {
            // Fill the mounted window so the footer sits on its bottom edge; the page-exclusion
            // rectangle then matches the card exactly instead of leaving uncovered window area.
            scroll.measure(childWidth, MeasureSpec.makeMeasureSpec(maxBody, MeasureSpec.EXACTLY))
            setMeasuredDimension(measuredWidth, offered)
        } else {
            // Wrap the content, but never past what the window can show: the body scrolls instead.
            scroll.measure(childWidth, MeasureSpec.makeMeasureSpec(maxBody, MeasureSpec.AT_MOST))
            val reserved = outerPadding + header.measuredHeight + footer.measuredHeight
            val height = (reserved + scroll.measuredHeight).coerceAtMost(offered).coerceAtLeast(1)
            setMeasuredDimension(measuredWidth, height)
        }
    }

    /**
     * Anchors the footer to the bottom of the card instead of trusting the children's sequential
     * heights. This is what makes "划词 | 手动输入 | 退出" survive a header that measured taller
     * than its offer (for example a very large font scale): the header and the scroll view are
     * clipped by the exact region left above the footer, and the footer keeps its full 48dp+ height.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = paddingLeft
        val right = (measuredWidth - paddingRight).coerceAtLeast(left)
        val contentTop = paddingTop
        val contentBottom = (measuredHeight - paddingBottom).coerceAtLeast(contentTop)
        val footerTop = if (footer.visibility == View.GONE) contentBottom
            else (contentBottom - footer.measuredHeight).coerceAtLeast(contentTop)
        if (footer.visibility != View.GONE) footer.layout(left, footerTop, right, contentBottom)
        val headerBottom = if (header.visibility == View.GONE) contentTop
            else (contentTop + header.measuredHeight).coerceAtMost(footerTop)
        if (header.visibility != View.GONE) header.layout(left, contentTop, right, headerBottom)
        if (scroll.visibility != View.GONE) {
            scroll.layout(left, headerBottom, right, footerTop.coerceAtLeast(headerBottom))
        }
    }
}
