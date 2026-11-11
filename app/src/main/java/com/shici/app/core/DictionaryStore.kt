package com.shici.app.core

import java.text.Normalizer
import java.util.Locale

/** Exact lookup and one-hop, source-declared word forms. Never stores learning data. */
interface DictionaryStore {
    val entryCount: Int
    val aliasCount: Int
    fun entry(normalizedKey: String): DictionaryEntry?
    fun lemmas(normalizedForm: String): List<String> = emptyList()
}

object DictionaryKeys {
    fun normalize(raw: String): String? {
        val value = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace('’', '\'').replace('‘', '\'').replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        return value.takeIf { it.isNotEmpty() && it.length <= 256 && it.any(Char::isLetter) &&
            it.none { c -> Character.getType(c) in listOf(Character.CONTROL.toInt(),
                Character.FORMAT.toInt(), Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
                Character.UNASSIGNED.toInt()) } }
    }
}

object EcdictInfo {
    const val SOURCE = "ECDICT · skywind3000/ECDICT"
    const val VERSION = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b"
    const val NOTICE = "完整 ECDICT 大词库；释义与词形关系未逐条核对，不代表官方考试大纲。"
}
