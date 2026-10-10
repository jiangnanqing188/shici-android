package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Self-authored fixtures, not bundled dictionary meanings or claims about actual asset coverage. */
class RawKajwebDictionaryTest {
    private val header = "SHICI_KAJWEB\t1\t${RawKajwebDictionary.VERSION}\t${RawKajwebDictionary.SOURCE}\n"
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun manifest(bytes: ByteArray, count: Int) = RawKajwebDictionary.Manifest(sha(bytes), count, 0)
    private fun load(rows: String, count: Int = rows.trimEnd('\n').split('\n').size): RawKajwebDictionary {
        val bytes = gzip((header + rows).toByteArray())
        return RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, count))
    }
    private fun rejected(action: () -> Unit) { assertNotNull(runCatching(action).exceptionOrNull()) }
    private fun match(dictionary: RawKajwebDictionary, raw: String) = dictionary.lookup(raw) as LookupResult.Match

    @Test fun runtimeAssetAvoidsGzipSuffixThatAssetMergeAutomaticallyDecompresses() {
        assertEquals("dictionary/kajweb.tsv.bin", RawKajwebDictionary.ASSET)
        assertFalse(RawKajwebDictionary.ASSET.endsWith(".gz"))
    }

    @Test fun nonDemoHeadwordUsesOnlyOriginalAssetAndItsSourceVersion() {
        val dictionary = load("E\tquantum\tn\t夹具原义\n")
        assertTrue(DemoDictionary.lookup("quantum") is LookupResult.Missing)
        val entry = match(dictionary, "quantum").entries.single()
        assertEquals("夹具原义", entry.meaning)
        assertEquals(RawKajwebDictionary.SOURCE, entry.source)
        assertEquals(RawKajwebDictionary.VERSION, entry.version)
        assertEquals(1, dictionary.entryCount)
        assertEquals(0, dictionary.aliasCount)
    }
    @Test fun abbreviationFixtureWinsBeforeFinalSentenceStopAndNeverBecomesAnotherWord() {
        val dictionary = load("E\ta\t\t单字母夹具\nE\ta.\tabbr\t句点夹具\nE\tvs\t\t无句点夹具\nE\tvs.\tabbr\t对照夹具\n")
        assertEquals("a.", match(dictionary, "A.").entries.single().headword)
        assertEquals("vs.", match(dictionary, "“VS.,”").form)
        assertEquals("vs.", RawKajwebDictionary.normalize(" “VS.,” "))
        assertEquals("vs", match(dictionary, "vs").form)
        val dottedOnly = load("E\ta.\tabbr\t句点夹具\n")
        assertTrue(dottedOnly.lookup("a") is LookupResult.Missing)
    }
    @Test fun surroundingPunctuationCaseAndSentencePeriodAreHandledWithoutChangingMeaning() {
        val dictionary = load("E\tevidence\tn\t  夹具原义保留空格  \n")
        val result = match(dictionary, "  “EVIDENCE,” ")
        assertEquals("evidence", result.form)
        assertEquals("evidence", match(dictionary, "(Evidence.)").form)
        assertEquals("  夹具原义保留空格  ", result.entries.single().meaning)
    }
    @Test fun nfcCurlyApostropheWhitespacePhraseAndAccentFixturesMatchExactly() {
        val dictionary = load("E\tcan't\tv\t夹具\nE\tin spite of\tprep\t短语夹具\nE\trésumé\tn\t重音夹具\n")
        assertEquals("can't", match(dictionary, "CAN’T").form)
        assertEquals("in spite of", match(dictionary, " In\u00a0spite \t of ").form)
        assertEquals("résumé", match(dictionary, "re\u0301sume\u0301").form)
    }
    @Test fun unknownWordsNeverFallBackToDemoEcdictOrGuessedInflections() {
        val dictionary = load("E\tstudy\tn\t夹具\n")
        listOf("studies", "studied", "saw", "evidence", "unlistedfixture").forEach {
            assertTrue(it, dictionary.lookup(it) is LookupResult.Missing)
        }
        listOf("", "   ", "abc123", "foo/bar", "!!!").forEach {
            assertEquals(it, LookupResult.Invalid, dictionary.lookup(it))
        }
    }
    @Test fun backslashEscapesAndEmptyPosArePreserved() {
        val entry = match(load("E\tescape\t\t斜线\\\\制表\\t换行\\n原文\n"), "escape").entries.single()
        assertEquals("", entry.partOfSpeech)
        assertEquals("斜线\\制表\t换行\n原文", entry.meaning)
    }
    @Test fun compressedShaMismatchRejectsBeforeParsing() {
        val bytes = gzip((header + "E\tquantum\tn\t夹具\n").toByteArray())
        rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1).copy(assetSha256 = "0".repeat(64))) }
        rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1).copy(assetSha256 = "invalid")) }
    }
    @Test fun missingAndTruncatedGzipRejectEvenWithMatchingSha() {
        listOf(byteArrayOf(), (header + "E\tquantum\tn\t夹具\n").toByteArray(),
            gzip((header + "E\tquantum\tn\t夹具\n").toByteArray()).dropLast(5).toByteArray()).forEach { bytes ->
            rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1)) }
        }
    }
    @Test fun corruptGzipCrcRejectsEvenWithMatchingSha() {
        val bytes = gzip((header + "E\tquantum\tn\t夹具\n").toByteArray())
        bytes[bytes.size - 8] = (bytes[bytes.size - 8].toInt() xor 1).toByte()
        rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1)) }
    }
    @Test fun malformedUtf8DoesNotSilentlyReplaceCharacters() {
        val payload = (header + "E\tquantum\tn\t").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28, 0x0a)
        val bytes = gzip(payload)
        rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1)) }
    }
    @Test fun wrongOrAbsentHeaderAndSourceRevisionReject() {
        listOf("", header.replace("SHICI_KAJWEB", "SHICI_ECDICT"), header.replace("\t1\t", "\t2\t"),
            header.replace(RawKajwebDictionary.VERSION, "another-version"),
            header.replace(RawKajwebDictionary.SOURCE, "another-source")).forEach { altered ->
            val bytes = gzip((altered + "E\tquantum\tn\t夹具\n").toByteArray())
            rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1)) }
        }
        val bytes = gzip((header + "E\tquantum\tn\t夹具\n").toByteArray())
        rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1).copy(source = "another-source")) }
        rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1).copy(version = "another-version")) }
    }
    @Test fun countMismatchAndNonzeroAliasesReject() {
        rejected { load("E\tquantum\tn\t夹具\n", 2) }
        rejected { load("E\tquantum\tn\t夹具\n", 0) }
        val bytes = gzip((header + "E\tquantum\tn\t夹具\n").toByteArray())
        rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1).copy(aliasCount = 1)) }
    }
    @Test fun duplicateNoncanonicalOrEmptyHeadwordsAndMeaningsReject() {
        listOf("E\tquantum\tn\t夹具\nE\tquantum\tv\t重复\n", "E\tQuantum\tn\t夹具\n",
            "E\t quantum\tn\t夹具\n", "E\t\tn\t夹具\n", "E\tquantum\tn\t   \n",
            "E\tcan't\tv\t夹具\nE\tcan’t\tv\t非规范夹具\n").forEach { rows -> rejected { load(rows) } }
    }
    @Test fun invalidRowTypeExtraMissingFieldsBlankRowsAndEscapesReject() {
        listOf("A\tquantum\tn\t夹具\n", "E\tquantum\tn\n", "E\tquantum\tn\t夹具\textra\n",
            "E\tquantum\tn\t夹具\\q\n", "E\tquantum\tn\t夹具\\\n", "E\tquantum\tn\t夹具\n\n",
            "E\tquantum\tn\t夹具\r\n").forEach { rows -> rejected { load(rows) } }
    }
    @Test fun emptyPayloadRejectsExplicitly() {
        val bytes = gzip(byteArrayOf())
        rejected { RawKajwebDictionary.load(bytes.inputStream(), manifest(bytes, 1)) }
    }
    @Test fun noticeAlwaysIdentifiesRawAsUnreviewedAndOldDemoAsItsOwnSource() {
        val raw = RawKajwebDictionary.provenance(RawKajwebDictionary.SOURCE, RawKajwebDictionary.VERSION)
        assertTrue(raw.contains("未逐条核对"))
        assertTrue(raw.contains("不是官方大纲全覆盖"))
        assertTrue(raw.contains(RawKajwebDictionary.VERSION))
        assertFalse(raw.contains("自编演示"))
        val demo = RawKajwebDictionary.provenance(DemoDictionary.SOURCE, DemoDictionary.VERSION)
        assertTrue(demo.contains("自编演示数据"))
        assertTrue(demo.contains(DemoDictionary.VERSION))
        assertFalse(demo.contains(RawKajwebDictionary.VERSION))
        assertTrue(RawKajwebDictionary.reviewNotice("其他历史来源", "old").contains("未确认"))
    }
    @Test fun inputClosesEvenWhenManifestValidationFails() {
        var closed = false
        val input = object : java.io.ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed = true; super.close() }
        }
        rejected { RawKajwebDictionary.load(input, RawKajwebDictionary.Manifest("invalid", 1, 0)) }
        assertTrue(closed)
    }

}
