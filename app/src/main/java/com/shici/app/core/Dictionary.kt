package com.shici.app.core

import java.util.Locale

data class DictionaryEntry(val headword: String, val partOfSpeech: String, val meaning: String) {
    val key: String get() = headword
}

sealed class LookupResult {
    data class Match(val form: String, val entries: List<DictionaryEntry>) : LookupResult()
    data class Missing(val form: String) : LookupResult()
    data object Invalid : LookupResult()
}

/** Self-authored examples, not a licensed dictionary or an exam vocabulary list. */
object DemoDictionary {
    const val SOURCE = "自编演示词库"
    const val VERSION = "demo-2026.10.08"
    val entries = listOf(
        DictionaryEntry("abandon", "v.", "放弃；不再继续做某事。"),
        DictionaryEntry("ability", "n.", "完成某件事情的能力。"),
        DictionaryEntry("achieve", "v.", "通过努力达成目标。"),
        DictionaryEntry("approach", "n. / v.", "处理事情的方法；接近某人或某物。"),
        DictionaryEntry("benefit", "n. / v.", "带来的好处；从中得到好处。"),
        DictionaryEntry("challenge", "n. / v.", "需要努力克服的难题；向某个观点提出质疑。"),
        DictionaryEntry("consider", "v.", "认真思考或考虑某个选择。"),
        DictionaryEntry("develop", "v.", "逐步发展或形成。"),
        DictionaryEntry("evidence", "n.", "支持某种判断的事实或材料。"),
        DictionaryEntry("improve", "v.", "使情况或能力变得更好。"),
        DictionaryEntry("knowledge", "n.", "通过学习和经验获得的知识。"),
        DictionaryEntry("opportunity", "n.", "适合做某件事的机会。"),
        DictionaryEntry("require", "v.", "需要；要求某人做某事。"),
        DictionaryEntry("research", "n. / v.", "为了了解问题而进行的系统研究；研究。"),
        DictionaryEntry("significant", "adj.", "重要的；值得注意的。"),
        DictionaryEntry("study", "n. / v.", "学习；为了解一个问题而进行研究。"),
        DictionaryEntry("understand", "v.", "理解某个意思、原因或情况。"),
        DictionaryEntry("see", "v.", "看见；理解或意识到。"),
        DictionaryEntry("saw", "n. / v.", "锯子；用锯子切割。也可能是 see 的过去式，须确认。"),
        DictionaryEntry("leave", "v.", "离开；把某物留下。"),
        DictionaryEntry("left", "adj. / n.", "左边的；左侧。也可能是 leave 的过去式，须确认。"),
        DictionaryEntry("read", "v.", "阅读文字并理解内容。过去式拼写相同，此处不判定时态。")
    )
    private val byKey = entries.associateBy { it.key }
    private val forms = mapOf(
        "abandoned" to listOf("abandon"), "abilities" to listOf("ability"),
        "achieved" to listOf("achieve"), "benefits" to listOf("benefit"),
        "challenges" to listOf("challenge"), "considered" to listOf("consider"),
        "developed" to listOf("develop"), "improved" to listOf("improve"),
        "opportunities" to listOf("opportunity"), "required" to listOf("require"),
        "studies" to listOf("study"), "studied" to listOf("study"),
        "understood" to listOf("understand"), "seen" to listOf("see"),
        "saw" to listOf("see"), "left" to listOf("leave")
    )

    fun normalize(raw: String): String? {
        val form = raw.trim().trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }
            .lowercase(Locale.ROOT)
        return form.takeIf { it.matches(Regex("[a-z]+(?:['-][a-z]+)*")) }
    }

    fun lookup(raw: String): LookupResult {
        val form = normalize(raw) ?: return LookupResult.Invalid
        val keys = (listOf(form) + forms[form].orEmpty()).distinct()
        val matches = keys.mapNotNull(byKey::get)
        return if (matches.isEmpty()) LookupResult.Missing(form) else LookupResult.Match(form, matches)
    }

    fun ocrWords(raw: String): List<String> = raw.split(Regex("\\s+"))
        .mapNotNull(::normalize).distinct()
}
