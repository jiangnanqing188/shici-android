package com.shici.app.core

import kotlin.math.abs

/**
 * In-memory identity of one page body.
 *
 * The identity keeps one quantized colour per pixel in screen coordinates: RGB 5/6/5 in a
 * [ShortArray]. Keeping the pixels (rather than summed tiles or a sparse point grid) means that a
 * thin glyph row, a one-pixel horizontal/vertical move and an equal-brightness colour permutation
 * all remain observable. Two channel values are treated as equal when their quantized 5/6/5
 * buckets differ by at most [COLOR_BUCKET_TOLERANCE]; this absorbs ±1-level frame noise (white
 * 0xFF and 0xFE quantize identically) while real glyph contrast is far larger.
 *
 * Bounded resources:
 *  - at most [MAX_IDENTITY_PIXELS] pixels / [MAX_IDENTITY_BYTES] per identity; larger bodies fail
 *    closed at [sample] and are never retained;
 *  - at most two identities are alive during one lookup (entry + lift); the entry identity is
 *    dropped as soon as the lift frame is verified, and the guard keeps only the lift identity;
 *  - the guard's identity lives at most `PAGE_GUARD_TIMEOUT_MS` (60 s) and is released on save,
 *    cancel, dismissal, resize, stop or service destruction;
 *  - nothing is written to disk, uploaded, rendered or retained as a Bitmap/string.
 *
 * [matches] is intentionally strict: every non-excluded pixel must agree. A page that cannot be
 * proven to be the same page is treated as changed and the lookup is cancelled; it is better to
 * ask the user to select again than to OCR or save a word from a different page.
 */
class LiveFrameFingerprint private constructor(
    private val width: Int,
    private val height: Int,
    private val body: PixelBox,
    private val encoded: ShortArray
) {
    /** Strict whole-body comparison used between the clean entry frame and the clean lift frame. */
    fun matches(other: LiveFrameFingerprint): Boolean = matches(other, emptyList())

    /**
     * Compares every pixel outside [excluded]. Exclusions are used only for this application's own
     * visible card/entry windows: masking is per pixel, so a rectangle that only touches a region
     * cannot hide the still-visible pixels around it.
     */
    fun matches(other: LiveFrameFingerprint, excluded: List<PixelBox>): Boolean {
        if (!sameShape(other.width, other.height, other.body)) return false
        if (encoded.size != other.encoded.size) return false
        val rowWidth = body.width
        for (row in 0 until body.height) {
            val screenY = body.top + row
            val base = row * rowWidth
            for (column in 0 until rowWidth) {
                if (isExcluded(body.left + column, screenY, excluded)) continue
                if (!closeEnough(encoded[base + column], other.encoded[base + column].toInt())) return false
            }
        }
        return true
    }

    /**
     * Compares this identity against a fresh RGBA_8888 plane without allocating a second identity.
     * Returns false when the region is invalid/too short, a read fails, or any visible pixel
     * differs; the caller treats every false result as "cannot prove same page" and cancels.
     * The buffer position is never advanced.
     */
    fun matchesPlane(width: Int, height: Int, body: PixelBox, rowStride: Int, pixelStride: Int,
                     buffer: java.nio.ByteBuffer, excluded: List<PixelBox>): Boolean {
        if (!sameShape(width, height, body)) return false
        if (!usablePlaneGeometry(width, height, body, rowStride, pixelStride, buffer.limit())) return false
        val rowWidth = body.width
        for (y in body.top until body.bottom) {
            var offset = y * rowStride + body.left * pixelStride
            val base = (y - body.top) * rowWidth
            for (column in 0 until rowWidth) {
                val red = buffer.get(offset).toInt() and 255
                val green = buffer.get(offset + 1).toInt() and 255
                val blue = buffer.get(offset + 2).toInt() and 255
                offset += pixelStride
                if (isExcluded(body.left + column, y, excluded)) continue
                if (!closeEnough(encoded[base + column], encode(red, green, blue))) return false
            }
        }
        return true
    }

    private fun sameShape(otherWidth: Int, otherHeight: Int, otherBody: PixelBox): Boolean =
        width == otherWidth && height == otherHeight && body == otherBody

    companion object {
        // 2 bytes per pixel. 8 Mi pixels (16 MiB) covers a 3000x2120 tablet page with headroom;
        // the capture layer already caps raw images at 16 Mi pixels, but anything above this
        // identity budget is rejected rather than retained.
        private const val MAX_IDENTITY_PIXELS = 8_388_608L
        const val MAX_IDENTITY_BYTES = MAX_IDENTITY_PIXELS * 2
        // One 5/6/5 bucket is 8 levels in red/blue and 4 in green. Allowing a one-bucket
        // difference absorbs ±1-level noise without missing real glyph contrast.
        private const val COLOR_BUCKET_TOLERANCE = 1

        /**
         * Samples the entire [body] once, row by row. No row buffer or bitmap is retained.
         * Invalid bodies and bodies above the identity budget fail closed before any row is read;
         * exceptions from [readRow] propagate.
         */
        fun sample(width: Int, height: Int, body: PixelBox,
                   readRow: (x: Int, y: Int, pixels: IntArray) -> Unit): LiveFrameFingerprint? =
            sampleRows(width, height, body) { y, row -> readRow(body.left, y, row) }

        /**
         * Samples straight from an RGBA_8888 image plane buffer, avoiding a full-screen bitmap
         * allocation for every observed ambient frame. Bytes are interpreted as R, G, B per pixel;
         * row padding ([rowStride]) and multi-byte pixels ([pixelStride]) are honoured. The caller
         * keeps ownership of the image and closes it after this call.
         */
        fun sample(width: Int, height: Int, body: PixelBox, rowStride: Int, pixelStride: Int,
                   buffer: java.nio.ByteBuffer): LiveFrameFingerprint? {
            if (!usablePlaneGeometry(width, height, body, rowStride, pixelStride, buffer.limit())) return null
            return sampleRows(width, height, body) { y, row ->
                var offset = y * rowStride + body.left * pixelStride
                for (i in row.indices) {
                    val red = buffer.get(offset).toInt() and 255
                    val green = buffer.get(offset + 1).toInt() and 255
                    val blue = buffer.get(offset + 2).toInt() and 255
                    row[i] = (red shl 16) or (green shl 8) or blue
                    offset += pixelStride
                }
            }
        }

        /**
         * Validates a real RGBA_8888 plane layout before any byte is read. A positive stride is
         * not enough: pixelStride 1 overlaps the three sampled channels of adjacent pixels, and a
         * rowStride shorter than one full row overlaps rows. Both would silently re-index a
         * changed glyph to a white prefix and wrongly prove "same page". Valid row padding,
         * pixelStride 4/8, non-zero body offsets, buffer slices/direct/read-only buffers and a
         * final pixel that supplies only RGB (no alpha byte) remain accepted.
         */
        private fun usablePlaneGeometry(width: Int, height: Int, body: PixelBox,
                                        rowStride: Int, pixelStride: Int, limit: Int): Boolean {
            if (width <= 0 || height <= 0 || body.left < 0 || body.top < 0 ||
                body.right > width || body.bottom > height || body.width <= 0 || body.height <= 0) return false
            if (pixelStride < 3) return false
            if (rowStride.toLong() < width.toLong() * pixelStride) return false
            val required = (body.bottom - 1).toLong() * rowStride +
                (body.right - 1).toLong() * pixelStride + 3
            return required <= limit
        }

        private fun sampleRows(width: Int, height: Int, body: PixelBox,
                               readRow: (y: Int, pixels: IntArray) -> Unit): LiveFrameFingerprint? {
            if (width <= 0 || height <= 0 || body.left < 0 || body.top < 0 ||
                body.right > width || body.bottom > height || body.width <= 0 || body.height <= 0) return null
            val pixelCount = body.width.toLong() * body.height.toLong()
            if (pixelCount > MAX_IDENTITY_PIXELS) return null
            val encoded = ShortArray(pixelCount.toInt())
            val rowPixels = IntArray(body.width)
            var out = 0
            for (y in body.top until body.bottom) {
                readRow(y, rowPixels)
                for (x in 0 until body.width) {
                    val pixel = rowPixels[x]
                    encoded[out++] = encode((pixel ushr 16) and 255,
                        (pixel ushr 8) and 255, pixel and 255).toShort()
                }
            }
            return LiveFrameFingerprint(width, height, body, encoded)
        }

        private fun encode(red: Int, green: Int, blue: Int): Int =
            ((red shr 3) shl 11) or ((green shr 2) shl 5) or (blue shr 3)

        private fun closeEnough(a: Short, b: Int): Boolean {
            val value = a.toInt() and 0xFFFF
            return abs(((value ushr 11) and 0x1F) - ((b ushr 11) and 0x1F)) <= COLOR_BUCKET_TOLERANCE &&
                abs(((value ushr 5) and 0x3F) - ((b ushr 5) and 0x3F)) <= COLOR_BUCKET_TOLERANCE &&
                abs((value and 0x1F) - (b and 0x1F)) <= COLOR_BUCKET_TOLERANCE
        }

        private fun isExcluded(x: Int, y: Int, excluded: List<PixelBox>): Boolean {
            for (box in excluded) {
                if (x >= box.left && x < box.right && y >= box.top && y < box.bottom) return true
            }
            return false
        }
    }
}

/**
 * Page identity for one query that has already passed the clean lift-frame check.
 *
 * The guard keeps the lift-frame identity (never pixels of a Bitmap) and is invalidated by an
 * observed page change, dismissal, save completion or its bounded lifetime. It is not persisted
 * and carries no OCR text.
 */
class LivePageGuard(
    val generation: Long,
    val body: PixelBox,
    val baseline: LiveFrameFingerprint
) {
    @Volatile private var valid = true

    val isValid: Boolean get() = valid

    /** Returns true only for the first invalidation, so old callbacks cannot win twice. */
    @Synchronized fun invalidate(): Boolean {
        if (!valid) return false
        valid = false
        return true
    }

    fun matches(frame: LiveFrameFingerprint, excluded: List<PixelBox>): Boolean =
        valid && baseline.matches(frame, excluded)
}

/**
 * One non-persistent live lookup attempt. The deletion credential is captured when the entry is
 * tapped and carried through the entry frame, the gesture, the lift verification frame and OCR.
 */
class LiveSelectionSession(
    val generation: Long,
    val entryToken: Long,
    val deletionCredential: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val body: PixelBox,
    val entryFingerprint: LiveFrameFingerprint
) {
    var verifyToken: Long = -1L
    var selection: HighlighterSelection? = null
}
