package com.shici.app.export

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.shici.app.core.PdfPlaceholderCleanup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory provider only: never registers a provider or touches the user's chosen files. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29) // ContentResolver.wrap is only used by this test fixture.
class LostPdfFileCleanupTest {
    private val target = DocumentsContract.buildDocumentUri("test.pdf.documents", "new.pdf")
    private val other = DocumentsContract.buildDocumentUri("test.pdf.documents", "existing.pdf")

    private inner class Provider : ContentProvider() {
        val files = mutableMapOf(target to byteArrayOf(), other to "%PDF-1.4 existing".toByteArray())
        val queried = mutableListOf<Uri>()
        val deleted = mutableListOf<Uri>()
        var size: Any? = 0L
        var flags: Any? = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        var mime: String? = "application/pdf"
        var columns = arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        var rows = 1
        var nullCursor = false
        var queryError: Exception? = null
        var deleteError: Exception? = null
        var cursor: Cursor? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            queried += uri
            queryError?.let { throw it }
            if (nullCursor) return null
            return MatrixCursor(columns).apply {
                if (files.containsKey(uri)) repeat(rows) {
                    addRow(columns.map { column -> when (column) {
                        DocumentsContract.Document.COLUMN_SIZE -> size
                        DocumentsContract.Document.COLUMN_FLAGS -> flags
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> mime
                        else -> error("unexpected column")
                    } }.toTypedArray())
                }
                cursor = this
            }
        }
        @Suppress("DEPRECATION")
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            // Observe the actual DocumentsContract protocol rather than using a delete shortcut.
            assertEquals("android:deleteDocument", method)
            val uri = extras!!.getParcelable<Uri>(extras.keySet().single())!!
            deleted += uri
            deleteError?.let { throw it }
            assertTrue(files.containsKey(uri))
            assertEquals(0, files.getValue(uri).size)
            files.remove(uri)
            return Bundle.EMPTY
        }
        override fun getType(uri: Uri) = mime
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
            throw AssertionError("cleanup must not open or truncate a file")
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw AssertionError("no insert")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            throw AssertionError("must not fall back to generic resolver.delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
            throw AssertionError("no update")
    }

    private fun clean(provider: Provider, uri: Uri? = target) = LostPdfFileCleanup.clean(ContentResolver.wrap(provider), uri)
    private fun assertKept(provider: Provider, expected: PdfPlaceholderCleanup.Result) {
        assertEquals(expected, clean(provider))
        assertTrue(provider.deleted.isEmpty())
        assertTrue(provider.files.containsKey(target))
        assertTrue(provider.files.containsKey(other))
        provider.cursor?.let { assertTrue(it.isClosed) }
    }

    @Test fun emptyResultUsesDocumentDeleteForOnlyThatUriAndClosesCursor() {
        val provider = Provider()
        assertEquals(PdfPlaceholderCleanup.Result.DELETED_EMPTY, clean(provider))
        assertEquals(listOf(target), provider.queried)
        assertEquals(listOf(target), provider.deleted)
        assertFalse(provider.files.containsKey(target))
        assertArrayEquals("%PDF-1.4 existing".toByteArray(), provider.files.getValue(other))
        assertTrue(provider.cursor!!.isClosed)
    }

    @Test fun nonemptyPdfIsPreservedWithoutOpeningIt() {
        val provider = Provider().apply { files[target] = "%PDF-1.4 contents".toByteArray(); size = files.getValue(target).size.toLong() }
        assertKept(provider, PdfPlaceholderCleanup.Result.KEPT_NONEMPTY)
        assertArrayEquals("%PDF-1.4 contents".toByteArray(), provider.files.getValue(target))
    }

    @Test fun nullMissingNegativeAndNonintegerSizesCannotAuthorizeDeletion() {
        listOf(null, -1L, "0", 0.0).forEach { size ->
            assertKept(Provider().apply { this.size = size }, PdfPlaceholderCleanup.Result.KEPT_UNKNOWN_SIZE)
        }
        assertKept(Provider().apply { columns = columns.drop(1).toTypedArray() }, PdfPlaceholderCleanup.Result.KEPT_UNKNOWN_SIZE)
    }

    @Test fun nullEmptyOrMultirowCursorCannotAuthorizeDeletion() {
        assertKept(Provider().apply { nullCursor = true }, PdfPlaceholderCleanup.Result.KEPT_UNKNOWN_SIZE)
        listOf(0, 2).forEach { rows ->
            assertKept(Provider().apply { this.rows = rows }, PdfPlaceholderCleanup.Result.KEPT_UNKNOWN_SIZE)
        }
    }

    @Test fun unsupportedMissingFlagsAndDirectoriesArePreserved() {
        listOf(null, 0, "4").forEach { flags ->
            assertKept(Provider().apply { this.flags = flags }, PdfPlaceholderCleanup.Result.KEPT_UNSUPPORTED)
        }
        assertKept(Provider().apply { columns = columns.filterNot { it == DocumentsContract.Document.COLUMN_FLAGS }.toTypedArray() },
            PdfPlaceholderCleanup.Result.KEPT_UNSUPPORTED)
        assertKept(Provider().apply { mime = DocumentsContract.Document.MIME_TYPE_DIR }, PdfPlaceholderCleanup.Result.KEPT_UNSUPPORTED)
    }

    @Test fun revokedGrantOrQueryExceptionDoesNotAttemptDelete() {
        assertKept(Provider().apply { queryError = SecurityException("grant revoked") }, PdfPlaceholderCleanup.Result.CHECK_FAILED)
        assertKept(Provider().apply { queryError = UnsupportedOperationException() }, PdfPlaceholderCleanup.Result.CHECK_FAILED)
    }

    @Test fun failedDocumentDeleteDoesNotClaimSuccessOrRetry() {
        val provider = Provider().apply { deleteError = SecurityException("write grant revoked") }
        assertEquals(PdfPlaceholderCleanup.Result.DELETE_FAILED, clean(provider))
        assertEquals(listOf(target), provider.deleted)
        assertTrue(provider.files.containsKey(target))
        assertTrue(provider.files.containsKey(other))
    }

    @Test fun missingOrNonDocumentUriNeverTouchesProvider() {
        val provider = Provider()
        assertEquals(PdfPlaceholderCleanup.Result.NO_URI, clean(provider, null))
        listOf("file:///tmp/new.pdf", "content://test.pdf.documents/root", "content://test.pdf.documents/document/new.pdf/children")
            .forEach { assertEquals(PdfPlaceholderCleanup.Result.CHECK_FAILED, clean(provider, Uri.parse(it))) }
        assertTrue(provider.queried.isEmpty())
        assertTrue(provider.deleted.isEmpty())
    }
}
