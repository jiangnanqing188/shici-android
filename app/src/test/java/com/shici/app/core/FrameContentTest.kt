package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class FrameContentTest {
    private val width = 120
    private val height = 80
    private val body = PixelBox(0, 6, width, height - 10)

    private fun black(pixels: IntArray, area: PixelBox = body): Boolean =
        FrameContent.isClearlyBlack(width, height, area) { x, y, row ->
            pixels.copyInto(row, 0, y * width + x, y * width + x + row.size)
        }

    @Test fun blackBodyIsRejectedEvenWithBrightStatusBarAndNavigationIcons() {
        val pixels = IntArray(width * height) { 0xff000000.toInt() }
        for (y in 0 until 6) for (x in 0 until width) pixels[y * width + x] = -1
        for (y in height - 10 until height) pixels[y * width + width / 2] = -1
        assertTrue(black(pixels))
    }

    @Test fun fullBlackAndTransparentBodiesAreRejected() {
        assertTrue(black(IntArray(width * height) { 0xff000000.toInt() }))
        assertTrue(black(IntArray(width * height)))
    }

    @Test fun darkThemeAndNearBlackBackgroundWithSparseTextAreAccepted() {
        assertFalse(black(IntArray(width * height) { 0xff101010.toInt() }))
        val pixels = IntArray(width * height) { 0xff020202.toInt() }
        pixels[body.top * width] = 0xffdedede.toInt()
        assertFalse(black(pixels))
    }

    @Test fun aSingleVisiblePixelAnywhereInTheBodyPreventsRejection() {
        for (y in body.top until body.bottom) for (x in body.left until body.right) {
            val pixels = IntArray(width * height) { 0xff000000.toInt() }
            pixels[y * width + x] = 0xff040000.toInt()
            assertFalse("Sparse content at $x,$y must not be lost to sampling", black(pixels))
        }
    }

    @Test fun brightPageAndLetterboxedContentAreAccepted() {
        assertFalse(black(IntArray(width * height) { -1 }))
        val pixels = IntArray(width * height) { 0xff000000.toInt() }
        for (y in 30..39) for (x in 50..59) pixels[y * width + x] = -1
        assertFalse(black(pixels))
    }

    @Test fun onlyReportedSystemInsetsAreIgnoredNotContentEdges() {
        val pixels = IntArray(width * height) { 0xff000000.toInt() }
        pixels[(body.bottom - 1) * width + width - 1] = -1
        assertFalse(black(pixels))
        val area = PixelBox(10, 6, width - 10, height - 10)
        pixels.fill(0xff000000.toInt())
        pixels[20 * width] = -1
        assertTrue(black(pixels, area))
        pixels[area.top * width + area.left] = -1
        assertFalse(black(pixels, area))
    }

    @Test fun uncertainOrEmptyBodyIsNotClassifiedAsClearlyBlack() {
        val pixels = IntArray(width * height)
        assertFalse(black(pixels, PixelBox(0, 10, 0, 20)))
        assertFalse(black(pixels, PixelBox(-1, 0, width, height)))
    }
}
