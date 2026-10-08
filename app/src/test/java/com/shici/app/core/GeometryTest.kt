package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun mapsLetterboxedImageWithoutSystemBarAssumptions() {
        // Image 1000x500 fits in square view; top/bottom letterboxes are each 250 px.
        assertEquals(PixelBox(100, 50, 300, 150), SelectionGeometry.mapSelection(
            FloatBox(100f, 300f, 300f, 400f), 1000, 500, 1000, 1000, 0))
    }
    @Test fun invertedDragWorksAndPaddingClampsToImage() {
        assertEquals(PixelBox(0, 0, 100, 50), SelectionGeometry.mapSelection(
            FloatBox(200f, 100f, 0f, 0f), 100, 50, 200, 100, 4))
    }
    @Test fun portraitImageInLandscapeViewHasHorizontalOffset() {
        assertEquals(PixelBox(0, 0, 100, 200), SelectionGeometry.mapSelection(
            FloatBox(50f, 0f, 150f, 200f), 100, 200, 200, 200, 0))
    }
    @Test fun blackBorderAndTinyGestureDoNotProduceRoi() {
        assertNull(SelectionGeometry.mapSelection(FloatBox(0f, 0f, 40f, 40f), 100, 200, 200, 200))
        assertNull(SelectionGeometry.mapSelection(FloatBox(60f, 30f, 61f, 31f), 100, 200, 200, 200))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidImageDimensionsFailClearly() {
        SelectionGeometry.imageBox(0, 100, 200, 200)
    }
}
