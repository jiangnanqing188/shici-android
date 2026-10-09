package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class PdfPlaceholderCleanupTest {
    private val target = "content://test.documents/document/new.pdf"
    private val other = "content://test.documents/document/existing.pdf"

    private fun assertKept(metadata: PdfPlaceholderCleanup.Metadata, expected: PdfPlaceholderCleanup.Result) {
        var deletes = 0
        val result = PdfPlaceholderCleanup.clean(target, { metadata }, { deletes++; true })
        assertEquals(expected, result)
        assertEquals(0, deletes)
    }

    @Test fun confirmedEmptyPlaceholderDeletesOnlyTheReturnedUri() {
        val files = mutableMapOf(target to 0L, other to 0L)
        val inspected = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val result = PdfPlaceholderCleanup.clean(target, {
            inspected += it
            PdfPlaceholderCleanup.Metadata(files.getValue(it), true)
        }, {
            deleted += it
            files.remove(it) != null
        })
        assertEquals(PdfPlaceholderCleanup.Result.DELETED_EMPTY, result)
        assertEquals(listOf(target), inspected)
        assertEquals(listOf(target), deleted)
        assertFalse(files.containsKey(target))
        assertTrue(files.containsKey(other))
    }

    @Test fun existingNonemptyFileIsNeverDeleted() {
        assertKept(PdfPlaceholderCleanup.Metadata(1L, true), PdfPlaceholderCleanup.Result.KEPT_NONEMPTY)
        assertKept(PdfPlaceholderCleanup.Metadata(Long.MAX_VALUE, true), PdfPlaceholderCleanup.Result.KEPT_NONEMPTY)
    }

    @Test fun unknownAndNegativeSizesAreNeverTreatedAsZero() {
        assertKept(PdfPlaceholderCleanup.Metadata(null, true), PdfPlaceholderCleanup.Result.KEPT_UNKNOWN_SIZE)
        assertKept(PdfPlaceholderCleanup.Metadata(-1L, true), PdfPlaceholderCleanup.Result.KEPT_UNKNOWN_SIZE)
    }

    @Test fun emptyFileWithoutDeleteSupportIsKept() {
        assertKept(PdfPlaceholderCleanup.Metadata(0L, false), PdfPlaceholderCleanup.Result.KEPT_UNSUPPORTED)
    }

    @Test fun inspectionErrorsNeverFallThroughToDeletion() {
        listOf(SecurityException("grant revoked"), UnsupportedOperationException(), IOException()).forEach { error ->
            var deletes = 0
            val result = PdfPlaceholderCleanup.clean(target, { throw error }, { deletes++; true })
            assertEquals(PdfPlaceholderCleanup.Result.CHECK_FAILED, result)
            assertEquals(0, deletes)
        }
    }

    @Test fun rejectedDeletionIsNotReportedAsSuccess() {
        var deletes = 0
        val result = PdfPlaceholderCleanup.clean(target,
            { PdfPlaceholderCleanup.Metadata(0L, true) }, { deletes++; false })
        assertEquals(PdfPlaceholderCleanup.Result.DELETE_FAILED, result)
        assertEquals(1, deletes)
    }

    @Test fun deletionExceptionDoesNotRetryOrClaimSuccess() {
        listOf(SecurityException("grant revoked"), UnsupportedOperationException(), IOException()).forEach { error ->
            var deletes = 0
            val result = PdfPlaceholderCleanup.clean(target,
                { PdfPlaceholderCleanup.Metadata(0L, true) }, { deletes++; throw error })
            assertEquals(PdfPlaceholderCleanup.Result.DELETE_FAILED, result)
            assertEquals(1, deletes)
        }
    }

    @Test fun missingUriNeverChecksOrDeletesAnyFile() {
        val result = PdfPlaceholderCleanup.clean<String>(null,
            { fail("must not inspect without a URI"); PdfPlaceholderCleanup.Metadata(0L, true) },
            { fail("must not delete without a URI"); true })
        assertEquals(PdfPlaceholderCleanup.Result.NO_URI, result)
    }

    @Test fun cleanupOfAbandonedUriCannotConsumeANewExportSnapshot() {
        val session = LearningExportSession()
        val request = session.begin("new search")!!
        val snapshot = LearningExportSnapshot(emptyList(), request.search, LocalDate.of(2026, 10, 9))
        assertTrue(session.freeze(request, snapshot))
        assertSame(snapshot, session.choose(request))
        PdfPlaceholderCleanup.clean(target, { PdfPlaceholderCleanup.Metadata(0L, true) }, { true })
        assertTrue(session.busy)
        assertEquals(request.id, session.fileRequestCode)
        assertSame(snapshot, session.takeResult(request.id))
    }

    @Test fun messagesExplainInvalidExportAndTheActualCleanupOutcome() {
        PdfPlaceholderCleanup.Result.entries.forEach { result ->
            assertTrue(result.message.contains("本次导出无效"))
            assertTrue(result.message.contains("重新导出"))
            assertFalse(result.message.contains("没有写入文件"))
        }
        assertTrue(PdfPlaceholderCleanup.Result.DELETED_EMPTY.message.contains("已删除"))
        assertTrue(PdfPlaceholderCleanup.Result.KEPT_NONEMPTY.message.contains("非空"))
        assertTrue(PdfPlaceholderCleanup.Result.KEPT_UNKNOWN_SIZE.message.contains("无法确认"))
        assertTrue(PdfPlaceholderCleanup.Result.KEPT_UNSUPPORTED.message.contains("不支持删除"))
        assertTrue(PdfPlaceholderCleanup.Result.CHECK_FAILED.message.contains("检查失败"))
        assertTrue(PdfPlaceholderCleanup.Result.DELETE_FAILED.message.contains("未能确认删除成功"))
    }
}
