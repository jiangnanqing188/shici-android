package com.shici.app.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The pinned ky/cet6 study-membership index. Members are the normalized entry keys of the two fixed
 * source books, in original book order, deduplicated by key; a key may belong to both books and then
 * shares one entry_key study state. Personal vocabulary is supplied at runtime, not by this index.
 * The index is a local, unreviewed candidate derived from the same ZIPs as the runtime dictionary.
 */
class StudyBooks private constructor(
    private val ordered: Map<StudyBook, List<String>>,
    val source: String,
    val version: String
) {
    data class Book(
        val id: String, val label: String, val file: String,
        val member: String, val sha256: String, val count: Int
    )

    data class Manifest(
        val assetSha256: String,
        val bookCounts: Map<String, Int>,
        val source: String = SOURCE,
        val version: String = VERSION
    )

    /** Fixed members of [book], or null for [StudyBook.PERSONAL] (a runtime set). */
    fun orderedMembers(book: StudyBook): List<String>? = ordered[book]
    fun count(book: StudyBook): Int = ordered[book]?.size ?: 0
    fun contains(book: StudyBook, key: String): Boolean = ordered[book]?.contains(key) == true
    val totalDistinctKeys: Int get() = ordered.values.flatMapTo(HashSet()) { it }.size

    companion object {
        const val FORMAT = "SHICI_STUDY_BOOKS"
        const val FORMAT_VERSION = 1
        const val SOURCE = "kajweb/dict · 有道来源备考词书"
        const val VERSION = "3992bcb94c800a2fd38a9fd6ff95b2353e755363"
        const val ASSET = "dictionary/study-books.tsv.bin"
        const val MANIFEST_ASSET = "dictionary/study-books-manifest.json"
        const val EXPECTED_KAOYAN = 4533
        const val EXPECTED_CET6 = 2078
        const val NOTICE = "考研 / 六级归属来自同一批原始来源词书；本地未逐条核对，再分发许可未确认。"
        private val HEX = Regex("[0-9a-f]{64}")

        /** Validates the manifest, gzip payload, header and every member before the index is used. */
        fun load(input: InputStream, manifest: Manifest): StudyBooks = input.use { stream ->
            require(manifest.source == SOURCE && manifest.version == VERSION) { "学习词书来源或版本不匹配" }
            require(manifest.assetSha256.matches(HEX)) { "学习词书 SHA256 格式无效" }
            require(manifest.bookCounts[StudyBook.KAOYAN.id] == EXPECTED_KAOYAN &&
                manifest.bookCounts[StudyBook.CET6.id] == EXPECTED_CET6) { "学习词书数量声明无效" }
            val compressed = stream.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(compressed)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(hash == manifest.assetSha256) { "学习词书 SHA256 不匹配" }
            val payload = GZIPInputStream(compressed.inputStream()).use { it.readBytes() }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(payload)).toString()
            require('\r' !in text && '\u0000' !in text) { "学习词书包含非法控制字符" }
            val lines = text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
            require(lines.isNotEmpty()) { "学习词书 Header 缺失" }
            val header = lines.first().split('\t')
            require(header.size == 4 && header[0] == FORMAT && header[1] == FORMAT_VERSION.toString() &&
                header[2] == manifest.version && unescape(header[3]) == manifest.source) { "学习词书 Header 不匹配" }
            val ordered = LinkedHashMap<StudyBook, MutableList<String>>()
            val declaredCounts = HashMap<StudyBook, Int>()
            val seen = HashMap<StudyBook, HashSet<String>>()
            var current: StudyBook? = null
            lines.drop(1).forEachIndexed { index, line ->
                val fields = line.split('\t')
                val row = index + 2
                when {
                    fields.size == 3 && fields[0] == "BOOK" -> {
                        val book = StudyBook.fromId(fields[1])?.takeIf { it != StudyBook.PERSONAL }
                            ?: throw IllegalArgumentException("学习词书第 $row 行书种无效")
                        require(!ordered.containsKey(book)) { "学习词书第 $row 行书种重复" }
                        val declared = fields[2].toIntOrNull()?.takeIf { it > 0 }
                            ?: throw IllegalArgumentException("学习词书第 $row 行数量无效")
                        require(declared == manifest.bookCounts[book.id]) { "学习词书第 $row 行数量与清单不符" }
                        ordered[book] = ArrayList()
                        declaredCounts[book] = declared
                        seen[book] = HashSet()
                        current = book
                    }
                    fields.size == 3 && fields[0] == "WORD" -> {
                        val book = current ?: throw IllegalArgumentException("学习词书第 $row 行缺少书种")
                        require(fields[1].toIntOrNull() != null) { "学习词书第 $row 行序号无效" }
                        val key = unescape(fields[2])
                        require(key.isNotEmpty() && RawKajwebDictionary.normalize(key) == key) {
                            "学习词书第 $row 行词条键无效"
                        }
                        require(seen.getValue(book).add(key)) { "学习词书第 $row 行词条重复：$key" }
                        ordered.getValue(book) += key
                    }
                    else -> throw IllegalArgumentException("学习词书第 $row 行字段无效")
                }
            }
            require(ordered.keys == setOf(StudyBook.KAOYAN, StudyBook.CET6)) { "学习词书缺考研或六级" }
            ordered.forEach { (book, keys) -> require(keys.size == declaredCounts.getValue(book)) { "学习词书 ${book.id} 数量不匹配" } }
            StudyBooks(ordered.mapValues { it.value.toList() }, manifest.source, manifest.version)
        }

        private fun unescape(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (character != '\\') append(character)
                else {
                    require(index < value.length) { "学习词书转义不完整" }
                    append(when (value[index++]) {
                        '\\' -> '\\'
                        't' -> '\t'
                        'n' -> '\n'
                        else -> throw IllegalArgumentException("学习词书转义无效")
                    })
                }
            }
        }
    }
}
