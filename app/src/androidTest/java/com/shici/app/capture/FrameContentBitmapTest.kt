package com.shici.app.capture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shici.app.core.FrameContent
import com.shici.app.core.FreshFrameRequest
import com.shici.app.core.PixelBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic pixels and an ImageReader round-trip, not MediaProjection/device bug reproduction. */
@RunWith(AndroidJUnit4::class)
class FrameContentBitmapTest {
    private fun black(bitmap: Bitmap, body: PixelBox) =
        FrameContent.isClearlyBlack(bitmap.width, bitmap.height, body) { x, y, row ->
            bitmap.getPixels(row, 0, row.size, x, y, row.size, 1)
        }

    @Test fun bitmapSystemBarsAreIgnoredButOneBodyTextPixelIsPreserved() {
        val bitmap = Bitmap.createBitmap(17, 11, Bitmap.Config.ARGB_8888)
        val body = PixelBox(0, 1, 17, 10)
        try {
            bitmap.eraseColor(Color.BLACK)
            for (x in 0 until 17) { bitmap.setPixel(x, 0, Color.WHITE); bitmap.setPixel(x, 10, Color.WHITE) }
            assertTrue(black(bitmap, body))
            bitmap.setPixel(16, 9, Color.WHITE)
            assertFalse(black(bitmap, body))
        } finally { bitmap.recycle() }
    }

    @Test fun repeatedReaderFramesAreClosedAndOnlyANewValidFrameIsOffered() {
        val reader = ImageReader.newInstance(17, 11, PixelFormat.RGBA_8888, 2)
        val writer = ImageWriter.newInstance(reader.surface, 2)
        val thread = HandlerThread("shici-frame-test").apply { start() }
        val service = CaptureService()
        val convert = CaptureService::class.java.getDeclaredMethod("imageBitmap", Image::class.java)
            .apply { isAccessible = true }
        val request = FreshFrameRequest(1, 4000, PixelBox(0, 1, 17, 10)).apply { arm(100) }
        try {
            // More frames than maxImages: a missing Image.close would stall this test.
            for (index in 0..2) {
                val available = CountDownLatch(1)
                reader.setOnImageAvailableListener({ available.countDown() }, Handler(thread.looper))
                val outgoing = writer.dequeueInputImage()
                var queued = false
                try {
                    outgoing.timestamp = 100L + index
                    val plane = outgoing.planes[0]
                    for (y in 0 until 11) for (x in 0 until 17) {
                        val offset = y * plane.rowStride + x * plane.pixelStride
                        val channel = if (y == 0 || y == 10 || index == 2 && x == 16 && y == 9) 255.toByte() else 0
                        plane.buffer.put(offset, channel)
                        plane.buffer.put(offset + 1, channel)
                        plane.buffer.put(offset + 2, channel)
                        plane.buffer.put(offset + 3, 255.toByte())
                    }
                    writer.queueInputImage(outgoing)
                    queued = true // queueInputImage transfers ownership to the writer.
                } finally { if (!queued) outgoing.close() }
                assertTrue("Reader must receive each new frame", available.await(3, TimeUnit.SECONDS))
                val image = checkNotNull(reader.acquireLatestImage())
                var bitmap: Bitmap? = null
                try {
                    val captured = convert.invoke(service, image) as Bitmap
                    bitmap = captured
                    assertEquals(17, captured.width)
                    assertEquals(11, captured.height)
                    assertEquals(index < 2, black(captured, request.body))
                    assertEquals(index == 2, request.offer(image.timestamp, 10L + index, black(captured, request.body)))
                } finally { bitmap?.recycle(); image.close() }
            }
            assertTrue(request.rejectedBlack)
            assertTrue(request.complete(20))
        } finally {
            reader.setOnImageAvailableListener(null, null)
            writer.close(); reader.close(); thread.quitSafely(); thread.join(3000)
        }
    }
}
