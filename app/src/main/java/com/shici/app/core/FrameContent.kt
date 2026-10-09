package com.shici.app.core

/** Reject only a uniformly near-black body, not a low average brightness or sparse text. */
object FrameContent {
    fun isClearlyBlack(width: Int, height: Int, body: PixelBox,
                       readRow: (x: Int, y: Int, pixels: IntArray) -> Unit): Boolean {
        if (body.left < 0 || body.top < 0 || body.right > width || body.bottom > height ||
            body.width <= 0 || body.height <= 0) return false
        val row = IntArray(body.width)
        for (y in body.top until body.bottom) {
            readRow(body.left, y, row)
            for (pixel in row) {
                if (pixel ushr 24 != 0 &&
                    (pixel shr 16 and 255 > 3 || pixel shr 8 and 255 > 3 || pixel and 255 > 3)) return false
            }
        }
        return true
    }
}
