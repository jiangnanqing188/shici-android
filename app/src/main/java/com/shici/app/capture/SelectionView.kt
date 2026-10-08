package com.shici.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.shici.app.core.FloatBox
import com.shici.app.core.PixelBox
import com.shici.app.core.SelectionGeometry
import com.shici.app.ui.Paper

// Only constructed with a live session bitmap, never inflated from XML or a layout editor.
@SuppressLint("ViewConstructor")
class SelectionView(context: Context, private val image: Bitmap, private val onSelected: (PixelBox) -> Unit,
                    private val onInteractive: () -> Unit = {}) : View(context) {
    private val imageRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startX = 0f
    private var startY = 0f
    private var reportedInteractive = false
    private var selection: FloatBox? = null
    init { contentDescription = "临时截图；拖动框选一个英文单词"; isFocusable = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val box = SelectionGeometry.imageBox(image.width, image.height, w, h)
        imageRect.set(box.left, box.top, box.right, box.bottom)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(30, 38, 35))
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        canvas.drawBitmap(image, null, imageRect, paint)
        if (!reportedInteractive) { reportedInteractive = true; onInteractive() }
        selection?.let {
            paint.style = Paint.Style.STROKE; paint.color = Paper.green; paint.strokeWidth = 3 * resources.displayMetrics.density
            canvas.drawRect(minOf(it.left, it.right), minOf(it.top, it.bottom), maxOf(it.left, it.right), maxOf(it.top, it.bottom), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; selection = FloatBox(startX, startY, startX, startY) }
            MotionEvent.ACTION_MOVE -> selection = FloatBox(startX, startY, event.x, event.y)
            MotionEvent.ACTION_UP -> {
                val area = FloatBox(startX, startY, event.x, event.y)
                selection = area
                SelectionGeometry.mapSelection(area, image.width, image.height, width, height)?.let(onSelected)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> selection = null
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
