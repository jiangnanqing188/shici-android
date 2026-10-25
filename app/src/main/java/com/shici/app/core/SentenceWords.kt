package com.shici.app.core

/**
 * One clickable fragment of the exact sentence shown by the translation page.
 *
 * [start] is inclusive and [end] exclusive, both UTF-16 indices into the caller's unchanged source.
 * [text] is exactly `source.substring(start, end)`, so case, internal apostrophes/hyphens and a
 * sentence-final stop are preserved. The scanner never rewrites, trims or re-joins the sentence.
 */
data class SentenceWord(val text: String, val start: Int, val end: Int)

/**
 * Finds the English word fragments a user may tap in the translation page.
 *
 * This is only a display/offset model: it performs no lookup, stemming, plural guessing, phrase
 * joining or form invention. The resulting fragment is passed unchanged to
 * [DictionaryResolver.lookup], so a real dotted headword still wins over its undotted fallback and
 * exactly one trailing sentence stop remains available to the existing resolver rule.
 *
 * Only Latin-script runs are returned. Chinese, emoji, punctuation-only runs and pure digit runs are
 * never candidates. Internal straight/curly apostrophes, hyphens and dots stay inside one fragment;
 * a single optional trailing dot is kept so the resolver can try the dotted form first. Adjacent
 * fragments (for example `one,two`) remain separate; the whole sentence is never one query.
 */
object SentenceWords {
    private val token = Regex("[\\p{IsLatin}]+(?:['’.\\-][\\p{IsLatin}]+)*\\.?")

    fun scan(source: String): List<SentenceWord> {
        if (source.isEmpty()) return emptyList()
        return token.findAll(source).mapNotNull { match ->
            // Do not carve a Latin fragment out of an alphanumeric run such as "2nd" or "abc2";
            // pure digit runs never match in the first place.
            val beforeIsDigit = source.getOrNull(match.range.first - 1)?.isDigit() == true
            val afterIsDigit = source.getOrNull(match.range.last + 1)?.isDigit() == true
            if (beforeIsDigit || afterIsDigit) return@mapNotNull null
            SentenceWord(match.value, match.range.first, match.range.last + 1)
        }.toList()
    }
}
