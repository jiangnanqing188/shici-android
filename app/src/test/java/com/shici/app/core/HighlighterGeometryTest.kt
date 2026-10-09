package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class HighlighterGeometryTest {
    private fun stroke(vararg coordinates: Float) = HighlighterStroke(coordinates.toList().chunked(2).map {
        HighlighterPoint(it[0], it[1])
    })
    private fun word(text: String, left: Float, top: Float, right: Float, bottom: Float, line: Int = 0,
                     lineBox: FloatBox = FloatBox(0f, top, 400f, bottom)) =
        HighlighterWord(text, FloatBox(left, top, right, bottom), line, lineBox)
    private fun selection(vararg strokes: HighlighterStroke) = checkNotNull(
        HighlighterGeometry.selection(strokes.toList(), 400, 300, 400, 300, 1f))
    private fun text(selection: HighlighterSelection, vararg words: HighlighterWord) =
        HighlighterGeometry.selectedText(HighlighterGeometry.hitWords(selection, words.toList()))

    @Test fun adjacentWordsAreNotSelectedByContextCrop() {
        val selected = selection(stroke(35f, 30f, 45f, 30f))
        assertEquals("study", text(selected, word("study", 20f, 20f, 60f, 40f), word("evidence", 65f, 20f, 130f, 40f)))
        assertTrue(selected.crop.right > 65) // The adjacent word is in the OCR crop, not the selection.
    }
    @Test fun wideInkDoesNotSelectAnAdjacentLineWithoutCenterlineHit() {
        assertEquals("study", text(selection(stroke(10f, 30f, 90f, 30f)),
            word("study", 20f, 20f, 80f, 40f), word("below", 20f, 41f, 80f, 61f, 1)))
    }
    @Test fun overallBoundingBoxDoesNotIncludeUnpaintedInterior() {
        assertEquals("First\nLast", text(selection(stroke(10f, 20f, 110f, 20f, 110f, 100f)),
            word("First", 20f, 10f, 60f, 30f), word("untouched", 35f, 45f, 85f, 65f, 1),
            word("Last", 100f, 80f, 130f, 110f, 2)))
    }
    @Test fun reverseSwipeHasSameReadingOrderAndCaseAndPunctuation() {
        val words = listOf(word("Study,", 20f, 20f, 80f, 40f), word("evidence!", 100f, 20f, 170f, 40f))
        val forward = HighlighterGeometry.hitWords(selection(stroke(10f, 30f, 180f, 30f)), words)
        val reverse = HighlighterGeometry.hitWords(selection(stroke(180f, 30f, 10f, 30f)), words.reversed())
        assertEquals(forward, reverse)
        assertEquals("Study, evidence!", HighlighterGeometry.selectedText(reverse))
    }
    @Test fun twoPointFastSwipeHitsWordsBetweenSamples() {
        assertEquals("Study evidence", text(selection(stroke(0f, 30f, 200f, 30f)),
            word("Study", 20f, 20f, 80f, 40f), word("evidence", 100f, 20f, 170f, 40f)))
    }
    @Test fun intermediateHistoryPointPreservesACurvedStroke() {
        val top = word("top", 40f, 10f, 60f, 30f)
        assertEquals("top", text(selection(stroke(0f, 70f, 50f, 20f, 100f, 70f)), top))
        assertEquals("", text(selection(stroke(0f, 70f, 100f, 70f)), top))
    }
    @Test fun partialWordHitReturnsEntireElementWithoutNormalization() {
        assertEquals("Evidence,", text(selection(stroke(42f, 30f, 49f, 30f)), word("Evidence,", 20f, 20f, 100f, 40f)))
    }
    @Test fun separateStrokesNeverCreateLiftToNextLineBridge() {
        val selected = selection(stroke(20f, 30f, 50f, 30f), stroke(150f, 130f, 180f, 130f))
        assertEquals("Upper\nLower.", text(selected, word("Upper", 20f, 20f, 60f, 40f),
            word("bridge", 80f, 70f, 120f, 90f, 1), word("Lower.", 140f, 120f, 190f, 140f, 2)))
    }
    @Test fun lineOrderDoesNotFollowOrderOfStrokesOrOcrBlocks() {
        val selected = selection(stroke(180f, 100f, 10f, 100f), stroke(180f, 30f, 10f, 30f))
        assertEquals("Read this,\nthen this.", text(selected,
            word("this.", 100f, 90f, 170f, 110f, 8), word("this,", 100f, 20f, 170f, 40f, 4),
            word("then", 20f, 90f, 70f, 110f, 8), word("Read", 20f, 20f, 70f, 40f, 4)))
    }
    @Test fun wordsOnSameLineSortByXDespiteDifferentGlyphBoxTops() {
        val line = FloatBox(0f, 18f, 200f, 40f)
        assertEquals("Study evidence", text(selection(stroke(0f, 30f, 200f, 30f)),
            word("evidence", 100f, 18f, 170f, 40f, 5, line), word("Study", 20f, 22f, 80f, 40f, 5, line)))
    }
    @Test fun repeatedWordsAndSeparatePunctuationArePreserved() {
        assertEquals("Study, study!", text(selection(stroke(0f, 30f, 240f, 30f)),
            word("Study", 10f, 20f, 70f, 40f), word(",", 72f, 20f, 80f, 40f),
            word("study", 100f, 20f, 160f, 40f), word("!", 162f, 20f, 170f, 40f)))
    }
    @Test fun duplicateStrokesDoNotDuplicateAnOcrElement() {
        assertEquals("study", text(selection(stroke(0f, 30f, 100f, 30f), stroke(100f, 30f, 0f, 30f)),
            word("study", 20f, 20f, 80f, 40f)))
    }
    @Test fun verticalLetterboxingAndScaleAreMappedToScreenshotPixels() {
        val selected = checkNotNull(HighlighterGeometry.selection(listOf(stroke(100f, 600f, 300f, 600f)),
            1000, 500, 2000, 2000, 2f))
        assertEquals(listOf(HighlighterPoint(50f, 50f), HighlighterPoint(150f, 50f)), selected.strokes.single().points)
        assertEquals(PixelBox(0, 0, 214, 114), selected.crop)
        assertEquals("Study", text(selected, word("Study", 60f, 40f, 140f, 60f)))
    }
    @Test fun horizontalLetterboxingIsMappedWithoutHeaderOrSystemBarOffsets() {
        val selected = checkNotNull(HighlighterGeometry.selection(listOf(stroke(60f, 60f, 100f, 60f)),
            100, 200, 200, 200, 1f))
        assertEquals(listOf(HighlighterPoint(10f, 60f), HighlighterPoint(50f, 60f)), selected.strokes.single().points)
    }
    @Test fun cropOffsetAndOcrInputResizeRestoreWordBox() {
        assertEquals(FloatBox(120f, 60f, 180f, 90f), HighlighterGeometry.restoreBox(
            FloatBox(10f, 5f, 40f, 20f), PixelBox(100, 50, 300, 150), 100, 50))
        assertEquals(FloatBox(110f, 55f, 140f, 70f), HighlighterGeometry.restoreBox(
            FloatBox(10f, 5f, 40f, 20f), PixelBox(100, 50, 300, 150)))
    }
    @Test fun restoredCropBoxHitsInTheSameScreenshotCoordinatesAsStroke() {
        val selected = selection(stroke(150f, 90f, 160f, 90f))
        val cropBox = HighlighterGeometry.restoreBox(FloatBox(64f, 54f, 94f, 74f), selected.crop)
        assertEquals("evidence", text(selected, HighlighterWord("evidence", cropBox, 0, cropBox)))
    }
    @Test fun blankAreaAndBlankOcrTextDoNotBecomeQueries() {
        assertEquals("", text(selection(stroke(20f, 100f, 100f, 100f)), word("study", 20f, 20f, 80f, 40f)))
        assertEquals("", text(selection(stroke(20f, 30f, 100f, 30f)), word("  ", 20f, 20f, 80f, 40f)))
    }
    @Test fun tapAndShortStrokeCannotProduceSelection() {
        assertNull(HighlighterGeometry.selection(listOf(stroke(20f, 30f)), 400, 300, 400, 300, 1f))
        assertNull(HighlighterGeometry.selection(listOf(stroke(20f, 30f, 25f, 30f)), 400, 300, 400, 300, 1f))
        assertNull(HighlighterGeometry.selection(listOf(stroke(20f, 30f, 25f, 30f), stroke(20f, 80f, 25f, 80f)),
            400, 300, 400, 300, 1f))
    }
    @Test fun minLengthIsMeasuredInViewDpRatherThanScreenshotPixels() {
        assertNull(HighlighterGeometry.selection(listOf(stroke(20f, 30f, 31f, 30f)), 400, 300, 400, 300, 2f))
        assertNotNull(HighlighterGeometry.selection(listOf(stroke(20f, 30f, 32f, 30f)), 400, 300, 400, 300, 2f))
    }
    @Test fun shortStrokeIsDiscardedWhileOtherStrokesStayValid() {
        assertEquals("Study", text(selection(stroke(20f, 30f, 60f, 30f), stroke(130f, 30f, 134f, 30f)),
            word("Study", 20f, 20f, 80f, 40f), word("evidence", 100f, 20f, 170f, 40f)))
    }
    @Test fun letterboxDistanceDoesNotMakeATinyInImageGestureValid() {
        assertNull(HighlighterGeometry.selection(listOf(stroke(0f, 30f, 51f, 30f)), 100, 200, 200, 200, 1f))
        assertNull(HighlighterGeometry.selection(listOf(stroke(0f, 30f, 40f, 30f)), 100, 200, 200, 200, 1f))
    }
    @Test fun outOfImagePointsAreNotClampedIntoFakeEdgeStrokes() {
        val selected = checkNotNull(HighlighterGeometry.selection(listOf(stroke(60f, 10f, 20f, 10f, 20f, 190f, 60f, 190f)),
            100, 200, 200, 200, 1f))
        assertEquals("", text(selected, word("edge", 0f, 80f, 20f, 120f)))
    }
    @Test fun grazingWordEdgeOrInsetBoundaryIsExcluded() {
        val target = word("study", 20f, 20f, 80f, 40f)
        assertEquals("", text(selection(stroke(0f, 20f, 100f, 20f)), target))
        assertEquals("", text(selection(stroke(0f, 21.6f, 100f, 21.6f)), target))
        assertEquals("study", text(selection(stroke(0f, 22f, 100f, 22f)), target))
    }
    @Test fun invalidOrEmptyGeometryIsNotSelected() {
        assertNull(HighlighterGeometry.selection(emptyList(), 400, 300, 400, 300, 1f))
        assertNull(HighlighterGeometry.selection(listOf(stroke(Float.NaN, 10f, 50f, 10f)), 400, 300, 400, 300, 1f))
        assertEquals("", text(selection(stroke(0f, 30f, 100f, 30f)), word("study", 20f, 20f, 20f, 40f)))
    }
}
