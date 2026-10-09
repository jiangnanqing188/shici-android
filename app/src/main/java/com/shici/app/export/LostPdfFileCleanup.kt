package com.shici.app.export

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.shici.app.core.PdfPlaceholderCleanup

/** Call on the worker. Query/delete only this picker result using its existing URI grant. */
object LostPdfFileCleanup {
    fun clean(resolver: ContentResolver, uri: Uri?): PdfPlaceholderCleanup.Result =
        PdfPlaceholderCleanup.clean(uri, { inspect(resolver, it) }, { DocumentsContract.deleteDocument(resolver, it) })

    private fun inspect(resolver: ContentResolver, uri: Uri): PdfPlaceholderCleanup.Metadata {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && !uri.authority.isNullOrEmpty())
        val path = uri.pathSegments
        require((path.size == 2 && path[0] == "document") ||
            (path.size == 4 && path[0] == "tree" && path[2] == "document"))
        require(DocumentsContract.getDocumentId(uri).isNotEmpty())
        val cursor = resolver.query(uri, arrayOf(Document.COLUMN_SIZE, Document.COLUMN_FLAGS,
            Document.COLUMN_MIME_TYPE), null, null, null) ?: return PdfPlaceholderCleanup.Metadata(null, false)
        cursor.use {
            if (it.count != 1 || !it.moveToFirst()) return PdfPlaceholderCleanup.Metadata(null, false)
            val sizeIndex = it.getColumnIndex(Document.COLUMN_SIZE)
            val size = if (sizeIndex >= 0 && it.getType(sizeIndex) == Cursor.FIELD_TYPE_INTEGER) it.getLong(sizeIndex) else null
            val flagsIndex = it.getColumnIndex(Document.COLUMN_FLAGS)
            val flags = if (flagsIndex >= 0 && it.getType(flagsIndex) == Cursor.FIELD_TYPE_INTEGER) it.getInt(flagsIndex) else 0
            val mimeIndex = it.getColumnIndex(Document.COLUMN_MIME_TYPE)
            val isFile = mimeIndex >= 0 && !it.isNull(mimeIndex) && it.getString(mimeIndex) != Document.MIME_TYPE_DIR
            return PdfPlaceholderCleanup.Metadata(size, isFile && (flags and Document.FLAG_SUPPORTS_DELETE) != 0)
        }
    }
}
