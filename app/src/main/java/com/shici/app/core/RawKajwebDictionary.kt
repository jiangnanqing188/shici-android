package com.shici.app.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Only the original kajweb asset. No reviewed overrides, demo fallback or inferred word forms. */
class RawKajwebDictionary private constructor(private val entries: Map<String, DictionaryEntry>) {
    val entryCount get() = entries.size
    val aliasCount get() = 0

    data class Manifest(val assetSha256: String, val entryCount: Int, val aliasCount: Int,
                        val source: String = SOURCE, val version: String = VERSION)

    fun lookup(raw: String): LookupResult {
        val form = normalize(raw) ?: return LookupResult.Invalid
        entries[form]?.let { return LookupResult.Match(form, listOf(it)) }
        // Exact dotted headwords win; only an unmatched final sentence stop is removed.
        if (form.endsWith('.')) {
            val withoutStop = form.dropLast(1)
            entries[withoutStop]?.let { return LookupResult.Match(withoutStop, listOf(it)) }
        }
        return LookupResult.Missing(form)
    }

    companion object {
        const val FORMAT = "SHICI_KAJWEB"
        const val FORMAT_VERSION = 1
        const val SOURCE = "kajweb/dict · 有道来源备考词书"
        const val VERSION = "3992bcb94c800a2fd38a9fd6ff95b2353e755363"
        const val EXPECTED_ENTRY_COUNT = 12652
        const val NOTICE = "原始词库，未逐条核对；不是官方大纲全覆盖词库，释义可能有误。"
        const val ASSET = "dictionary/kajweb.tsv.bin"
        const val MANIFEST_ASSET = "dictionary/kajweb-manifest.json"
        private val whitespace = Regex("[\\s\\p{Z}]+")
        private val headword = Regex("[\\p{L}\\p{M}]+(?:['.-][\\p{L}\\p{M}]+)*\\.?(?: [\\p{L}\\p{M}]+(?:['.-][\\p{L}\\p{M}]+)*\\.?)*")

        private fun key(raw: String) = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
            .replace(whitespace, " ").trim()

        fun normalize(raw: String): String? = key(raw)
            .trim { it in " \"'“”()[]{}<>,;:!?«»" }.takeIf { headword.matches(it) }

        fun label(source: String, version: String): String = when {
            source == DemoDictionary.SOURCE && version == DemoDictionary.VERSION -> "自编演示词库（旧记录）"
            source == SOURCE -> "原始词库 · 未逐条核对"
            else -> "历史词源 · 核对状态未确认"
        }

        fun reviewNotice(source: String, version: String): String = when {
            source == DemoDictionary.SOURCE && version == DemoDictionary.VERSION -> "自编演示数据，仅用于原型流程验证；不是正式考试词典。"
            source == SOURCE -> NOTICE
            else -> "历史词源记录，核对状态未确认；不代表官方大纲全覆盖。"
        }

        fun provenance(source: String, version: String) =
            "来源：$source\n版本：$version\n${reviewNotice(source, version)}"

        /** Hash covers the compressed bytes. Gzip CRC, strict UTF-8 and all rows are checked before use. */
        fun load(input: InputStream, manifest: Manifest): RawKajwebDictionary = input.use { stream ->
            require(manifest.source == SOURCE && manifest.version == VERSION) { "词库来源或版本不匹配" }
            require(manifest.assetSha256.matches(Regex("[0-9a-f]{64}"))) { "词库 SHA256 格式无效" }
            require(manifest.entryCount > 0 && manifest.aliasCount == 0) { "原始词库数量或别名声明无效" }
            val compressed = stream.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(compressed)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(hash == manifest.assetSha256) { "词库 SHA256 不匹配" }
            val payload = GZIPInputStream(compressed.inputStream()).use { it.readBytes() }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(payload)).toString()
            require('\r' !in text && '\u0000' !in text) { "词库包含非法控制字符" }
            val lines = text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
            require(lines.isNotEmpty()) { "词库 Header 缺失" }
            val header = lines.first().split('\t')
            require(header.size == 4 && header[0] == FORMAT && header[1] == FORMAT_VERSION.toString() &&
                header[2] == manifest.version && unescape(header[3]) == manifest.source) { "词库 Header 不匹配" }
            val entries = LinkedHashMap<String, DictionaryEntry>(manifest.entryCount)
            lines.drop(1).forEachIndexed { index, line ->
                val fields = line.split('\t')
                require(fields.size == 4 && fields[0] == "E") { "词库第 ${index + 2} 行字段无效" }
                val head = unescape(fields[1])
                val pos = unescape(fields[2])
                val meaning = unescape(fields[3])
                require(head == key(head) && headword.matches(head) && meaning.isNotBlank()) { "词库第 ${index + 2} 行词头或释义无效" }
                require(entries.put(head, DictionaryEntry(head, pos, meaning, manifest.source, manifest.version)) == null) {
                    "词库存在重复词头：$head"
                }
            }
            require(entries.size == manifest.entryCount) { "词库词头数量不匹配" }
            RawKajwebDictionary(entries)
        }

        private fun unescape(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                val character = value[index++]
                if (character != '\\') append(character)
                else {
                    require(index < value.length) { "词库转义不完整" }
                    append(when (value[index++]) {
                        '\\' -> '\\'
                        't' -> '\t'
                        'n' -> '\n'
                        else -> throw IllegalArgumentException("词库转义无效")
                    })
                }
            }
        }
    }
}
