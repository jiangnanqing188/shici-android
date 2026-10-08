package com.shici.app.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class FloatBox(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object SelectionGeometry {
    fun imageBox(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int): FloatBox {
        require(imageWidth > 0 && imageHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val scale = min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        val left = (viewWidth - width) / 2f
        val top = (viewHeight - height) / 2f
        return FloatBox(left, top, left + width, top + height)
    }

    fun mapSelection(selection: FloatBox, imageWidth: Int, imageHeight: Int,
                     viewWidth: Int, viewHeight: Int, padding: Int = 4): PixelBox? {
        require(padding >= 0)
        val box = imageBox(imageWidth, imageHeight, viewWidth, viewHeight)
        val left = max(min(selection.left, selection.right), box.left)
        val right = min(max(selection.left, selection.right), box.right)
        val top = max(min(selection.top, selection.bottom), box.top)
        val bottom = min(max(selection.top, selection.bottom), box.bottom)
        if (right - left < 3 || bottom - top < 3) return null
        val scale = (box.right - box.left) / imageWidth
        return PixelBox(
            (floor((left - box.left) / scale).toInt() - padding).coerceIn(0, imageWidth),
            (floor((top - box.top) / scale).toInt() - padding).coerceIn(0, imageHeight),
            (ceil((right - box.left) / scale).toInt() + padding).coerceIn(0, imageWidth),
            (ceil((bottom - box.top) / scale).toInt() + padding).coerceIn(0, imageHeight)
        ).takeIf { it.width > 0 && it.height > 0 }
    }
}
