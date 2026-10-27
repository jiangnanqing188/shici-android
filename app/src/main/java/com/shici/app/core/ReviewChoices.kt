package com.shici.app.core

import java.util.Locale

/** One labelled option of a four-choice review question. Exactly one option per question is correct. */
data class ReviewOption(
    /** The entry key the label came from; the correct option uses the pending card's own key. */
    val entryKey: String,
    /** A concise, existing Chinese meaning. This generator never writes or rewrites meaning text. */
    val label: String,
    val correct: Boolean
)

/** Why a pending card cannot be shown as a four-choice question right now. */
enum class ReviewUnavailableReason {
    /** The validated original dictionary could not be loaded, so no legal distractor exists. */
    NO_READABLE_DICTIONARY,
    /** The pending entry itself has no usable meaning, so there is nothing to ask. */
    NO_TARGET_MEANING,
    /** Fewer than three distractors are left after the distinct/overlap filter. */
    NOT_ENOUGH_CANDIDATES
}

/** The outcome of trying to build one question for one pending card. */
sealed class ReviewQuestion {
    data class Ready(val targetKey: String, val options: List<ReviewOption>) : ReviewQuestion() {
        init {
            require(options.size == ReviewChoices.OPTION_COUNT) { "四选一必须恰有四个选项" }
            require(options.count { it.correct } == 1) { "正确选项必须恰有一个" }
        }

        val correctIndex: Int get() = options.indexOfFirst { it.correct }
    }

    data class Unavailable(val reason: ReviewUnavailableReason) : ReviewQuestion()
}

/**
 * Pure generator for the default four-choice review question: `英文词 + 四个中文释义`.
 *
 * Inputs are only [target] (the pending card's resolved snapshot, i.e. the correct meaning), the
 * already loaded, bounded [candidates] and the persisted card [token]. This object never reads the
 * database, the assets, a file or the network, never runs an AI model and never applies a meaning
 * revision: every label is an existing local meaning taken verbatim (only shortened at a clause
 * boundary). It cannot be called with a whole-library scan by itself; the UI loads a bounded list on
 * the application worker first.
 *
 * The question is deterministic. Distractor order is a stable FNV-1a/SplitMix64 shuffle of the card
 * token, so the same pending card redrawn after rotation, navigation or a process restart shows the
 * same options in the same places, while a freshly dealt card gets a new token and therefore a new,
 * not-fixed correct position. No [java.util.Random], wall clock or hash-map iteration order is used.
 *
 * Distractor rules:
 * - Labels are four distinct normalised strings; the correct label is exactly one of them.
 * - A candidate is dropped when any of its meaning senses is equal to, contains or is contained in
 *   any sense of the full correct meaning (all original senses plus the user-supplied section), so a
 *   duplicate or an obviously contained meaning can never be offered as a wrong answer.
 * - Comparison also ignores a leading ASCII part-of-speech tag and a trailing 的/地/得, and a small
 *   curated list of common Chinese near-synonym clusters is treated as the same sense. This removes
 *   obvious pairs such as 美丽的/漂亮的, 高兴/开心 and 快速/迅速 across every sense, including the
 *   user supplement, without special-casing any headword.
 * - Same part of speech and similar spelling (shared prefix, close length) are preferred; ties are
 *   broken by a stable hash of token + keys. Existing meaning text is the only label source.
 *
 * The synonym rule is deliberately finite and conservative, not a semantic model: it covers only
 * the listed common clusters plus the normalisation above, so two synonyms outside that list (or a
 * paraphrase) can still look different. When a candidate cannot be shown to be safely different it
 * is dropped, and when fewer than three safe distractors remain the question is
 * [ReviewQuestion.Unavailable] instead of being padded with a wrong or duplicate meaning. This
 * generator does **not** claim complete semantic disambiguation.
 */
object ReviewChoices {
    /** A four-choice question always shows exactly four options. */
    const val OPTION_COUNT = 4

    /** Longer meanings keep their first clause; a single too-long clause is cut with an ellipsis. */
    const val MAX_LABEL_CHARS = 28

    /** Senses shorter than this are only compared for exact equality, never for containment. */
    private const val MIN_CONTAINMENT_CHARS = 2

    private const val FNV_PRIME = 0x100000001b3L
    private val FNV_OFFSET = 0xcbf29ce484222325uL.toLong()
    private val SPLIT_MIX_GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
    private val SPLIT_MIX_MUL_1 = 0xBF58476D1CE4E5B9uL.toLong()
    private val SPLIT_MIX_MUL_2 = 0x94D049BB133111EBuL.toLong()

    /** Punctuation and whitespace are ignored only when comparing labels for distinctness. */
    private val COMPARE_IGNORED = "，。、；：！？…（）()[]【】「」『』“”‘’·・,.;:!?\"'`~—–-　 ".toSet()

    private val MARKER_SENSE = normalize(PersonalDictionary.MARKER)

    /**
     * A leading ASCII part-of-speech tag (`[adj]`, `(n.)`, `【v】`, `adj.`) describes the same sense
     * as the bare meaning, so it is ignored for comparison. Only a short ASCII tag is removed; a
     * bracketed Chinese qualifier stays part of the meaning.
     */
    private val POS_TAG_PREFIX = Regex(
        "^\\s*[\\[(（【]\\s*(?:[A-Za-z][A-Za-z./ ]{0,14}|形容词|副词|名词|动词|介词|连词|代词|数词|量词|冠词|助词|感叹词|拟声词|短语)\\s*[\\])）】]\\s*"
    )

    /** A dotted or bare POS abbreviation at the very start of a sense (`adj.`, `vt`, `n.`). */
    private val POS_ABBREV_PREFIX = Regex(
        "^\\s*(?:adj|adv|prep|conj|pron|num|art|aux|int|vt|vi|ad|n|v|a)\\.?(?![A-Za-z])\\s*",
        RegexOption.IGNORE_CASE
    )

    /**
     * A deliberately small, curated list of common Chinese near-synonym clusters. Each cluster only
     * holds words that are interchangeable as a bare meaning in ordinary learner-dictionary usage;
     * the list is finite, so pairs outside it are still undetected. The list is not used to invent a
     * label or to rewrite meaning text: it only proves that an existing candidate label is too close
     * to the correct answer to be offered as a wrong option.
     */
    private val SYNONYM_CLUSTERS: List<Set<String>> = listOf(
        setOf("美丽", "漂亮", "好看", "美好", "秀丽", "俊美", "标致", "美观", "优美", "美貌", "俏丽", "美"),
        setOf("高兴", "开心", "快乐", "愉快", "喜悦", "欢乐", "欢快", "愉悦", "快活"),
        setOf("快速", "迅速", "飞快", "快捷", "急速", "飞速", "高速", "快"),
        setOf("安静", "宁静", "平静", "寂静", "幽静", "恬静", "清静"),
        setOf("冷静", "镇定", "沉着", "从容"),
        setOf("困难", "艰难", "艰苦", "艰巨"),
        setOf("容易", "简单", "轻易", "简易", "简便"),
        setOf("复杂", "繁杂"),
        setOf("聪明", "机智", "聪慧", "伶俐", "明智", "机灵"),
        setOf("愤怒", "生气", "恼怒", "气愤", "发怒", "愤慨"),
        setOf("害怕", "恐惧", "畏惧", "惊恐", "惧怕", "恐慌"),
        setOf("巨大", "庞大", "宏大", "硕大"),
        setOf("微小", "细小", "渺小", "细微"),
        setOf("寒冷", "冰冷", "严寒", "酷寒"),
        setOf("炎热", "酷热", "炽热", "火热"),
        setOf("重要", "关键", "重大", "要紧"),
        setOf("帮助", "协助", "帮忙", "援助", "辅助"),
        setOf("开始", "启动", "开端", "起初"),
        setOf("结束", "终止", "完毕", "完结", "终了"),
        setOf("购买", "买", "采购", "购置"),
        setOf("得到", "获得", "取得", "获取"),
        setOf("选择", "挑选", "选取", "抉择"),
        setOf("使用", "利用", "运用", "采用"),
        setOf("展示", "显示", "呈现", "展现"),
        setOf("改变", "更改", "变更", "改动"),
        setOf("允许", "准许", "许可"),
        setOf("禁止", "不准", "不许", "严禁"),
        setOf("回答", "答复", "回应", "解答"),
        setOf("询问", "问", "打听", "提问"),
        setOf("担心", "担忧", "忧虑", "发愁"),
        setOf("希望", "期望", "盼望", "渴望"),
        setOf("相信", "信任", "信赖"),
        setOf("看见", "看到", "瞧见", "目睹"),
        setOf("听见", "听到", "听闻"),
        setOf("认为", "觉得", "以为"),
        setOf("需要", "需求", "须要", "必需"),
        setOf("全部", "所有", "一切", "全都"),
        setOf("许多", "很多", "大量", "众多"),
        setOf("良好", "优秀", "优良", "出色", "杰出"),
        setOf("糟糕", "恶劣", "差劲", "不良"),
        setOf("新", "崭新", "全新"),
        setOf("旧", "陈旧", "古老", "老"),
        setOf("真实", "真正", "属实"),
        setOf("虚假", "伪造", "假"),
        setOf("正确", "准确", "无误"),
        setOf("错误", "错", "有误", "不正确"),
        setOf("疲倦", "疲劳", "疲惫", "劳累", "累"),
        setOf("生病", "患病", "有病"),
        setOf("强壮", "强大", "强健", "有力"),
        setOf("虚弱", "软弱", "薄弱", "无力"),
        setOf("喜爱", "热爱", "喜欢", "爱"),
        setOf("讨厌", "厌恶", "憎恨", "恨"),
        setOf("惊讶", "吃惊", "惊奇", "诧异"),
        setOf("著名", "有名", "出名", "闻名"),
        setOf("奇怪", "奇异", "古怪", "奇特"),
        setOf("危险", "凶险"),
        setOf("安全", "平安"),
        setOf("干净", "清洁", "洁净"),
        setOf("肮脏", "污秽", "脏"),
        setOf("明亮", "光亮", "光明"),
        setOf("黑暗", "昏暗", "漆黑"),
        setOf("潮湿", "湿润", "湿"),
        setOf("干燥", "干"),
        setOf("富有", "富裕", "有钱", "富"),
        setOf("贫穷", "贫困", "穷", "贫寒"),
        setOf("丑陋", "难看", "丑"),
        setOf("年轻", "年少"),
        setOf("缓慢", "迟缓", "慢"),
        setOf("清楚", "清晰", "明白", "明确"),
        setOf("模糊", "含糊", "朦胧")
    )

    private val SYNONYM_CLUSTER_OF: Map<String, Int> = buildMap {
        SYNONYM_CLUSTERS.forEachIndexed { index, cluster ->
            cluster.forEach { word -> put(word, index) }
        }
    }

    /**
     * Builds one question or reports why it cannot be built. [candidates] may be any bounded list of
     * already resolved local entries; this function filters, ranks and picks exactly three distractors.
     */
    fun build(target: DictionaryEntry, candidates: List<DictionaryEntry>, token: String): ReviewQuestion {
        val correctLabel = conciseLabel(preferredMeaning(target))
        val correctNormalised = normalize(correctLabel)
        if (correctNormalised.isEmpty()) {
            return ReviewQuestion.Unavailable(ReviewUnavailableReason.NO_TARGET_MEANING)
        }
        val targetSenses = senses(target.meaning)
        val targetComparison = comparisonSenses(target.meaning).toSet()
        val usedLabels = hashSetOf(correctNormalised)
        val usedComparison = hashSetOf(comparisonIdentity(correctLabel))
        val distractors = ArrayList<DictionaryEntry>(OPTION_COUNT - 1)
        val ranked = candidates.asSequence()
            .filter { it.key != target.key && it.meaning.isNotBlank() }
            .map { Candidate(it, score(target, it), stableHash("$token\u0000${target.key}\u0000${it.key}")) }
            .filter { !overlapsCorrect(it.entry.meaning, targetSenses, targetComparison) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.tieBreak })
            .iterator()
        while (ranked.hasNext() && distractors.size < OPTION_COUNT - 1) {
            val entry = ranked.next().entry
            val label = conciseLabel(preferredMeaning(entry))
            val normalised = normalize(label)
            val comparison = comparisonIdentity(label)
            if (normalised.isEmpty() || !usedLabels.add(normalised) ||
                !usedComparison.add(comparison)) continue
            distractors += entry
        }
        if (distractors.size < OPTION_COUNT - 1) {
            return ReviewQuestion.Unavailable(ReviewUnavailableReason.NOT_ENOUGH_CANDIDATES)
        }
        val options = ArrayList<ReviewOption>(OPTION_COUNT)
        options += ReviewOption(target.key, correctLabel, true)
        distractors.forEach { options += ReviewOption(it.key, conciseLabel(preferredMeaning(it)), false) }
        val order = seededOrder(OPTION_COUNT, "$token\u0000${target.key}")
        return ReviewQuestion.Ready(target.key, order.map { options[it] })
    }

    /**
     * A bounded, deterministic window over an already ordered member list: the [count] keys whose
     * `seed + key` hash sorts first, in that hashed order (tied by the key). The same seed and list
     * always give the same window, so a card's candidate set is stable across restarts while a new
     * card token samples a different slice. Pure arithmetic; no Random, clock or map iteration order.
     */
    fun sampleWindow(orderedKeys: List<String>, seed: String, count: Int): List<String> {
        if (count <= 0 || orderedKeys.isEmpty()) return emptyList()
        return orderedKeys.asSequence()
            .map { it to stableHash("$seed\u0000$it") }
            .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
            .take(count)
            .map { it.first }
            .toList()
    }

    /**
     * The option label's source. A merged display entry (one that carries an explicit original
     * snapshot) is asked with the user's current supplement, because that is the meaning the owner
     * configured; a raw original entry is asked with its own text. The user section is located by
     * the base snapshot's own length instead of scanning for marker-like text, so a raw entry that
     * merely contains the marker is never truncated (matching [PersonalDictionary]).
     */
    private fun preferredMeaning(entry: DictionaryEntry): String {
        val base = entry.originalSnapshot ?: return entry.meaning
        val baseMeaning = base.meaning
        val marker = PersonalDictionary.MARKER
        // PersonalDictionary.merge builds `base.meaning + "\n\n" + MARKER + "\n" + user.meaning`.
        if (entry.meaning.length > baseMeaning.length && entry.meaning.startsWith(baseMeaning)) {
            val userSection = entry.meaning.substring(baseMeaning.length).trim()
                .removePrefix(marker).trim()
            if (userSection.isNotEmpty()) return userSection
        }
        // A blank base means merge emitted just the marker plus the user text.
        if (entry.meaning.startsWith(marker)) {
            val userSection = entry.meaning.removePrefix(marker).trim()
            if (userSection.isNotEmpty()) return userSection
        }
        return entry.meaning
    }

    /** First non-blank clause, bounded; never invented, only shortened. */
    private fun conciseLabel(meaning: String): String {
        val first = meaning.split('；', ';', '。', '\n', '\r')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        val trimmed = first.trimEnd('，', ',', '：', ':', ' ')
        if (trimmed.length <= MAX_LABEL_CHARS) return trimmed
        return trimmed.take(MAX_LABEL_CHARS - 1).trimEnd() + "…"
    }

    /** Raw non-empty sense clauses; the user-supplement marker line itself is not a sense. */
    private fun rawSenses(meaning: String): List<String> =
        meaning.split('；', ';', '。', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && normalize(it) != MARKER_SENSE }

    /** Normalised senses of one meaning, used for the lexical same/contains rule. */
    private fun senses(meaning: String): List<String> =
        rawSenses(meaning).map { normalize(it) }.filter { it.isNotEmpty() }

    /**
     * Comparison form of one sense: a leading ASCII POS tag is removed, punctuation/whitespace is
     * normalised, and a trailing structural particle (的/地/得) is ignored. This form is used only
     * for exact sense equality and the finite synonym clusters, never for substring matching, so a
     * word such as 目的 is not split apart by a broad containment test.
     */
    private fun comparisonKey(rawSense: String): String {
        var text = rawSense.trim()
        text = POS_TAG_PREFIX.replace(text, "")
        text = POS_ABBREV_PREFIX.replace(text, "")
        return normalize(text).trimEnd('的', '地', '得')
    }

    /**
     * Comparison forms of every sense, including the user-supplied section. Comma/enumeration
     * separated sub-meanings are split too (`美丽的，漂亮的` is two senses of one clause), so a
     * synonym hidden after a comma is still compared. This only makes the distractor filter more
     * conservative; it never becomes a displayed label.
     */
    private fun comparisonSenses(meaning: String): List<String> =
        rawSenses(meaning)
            .flatMap { it.split('，', ',', '、') }
            .map { comparisonKey(it) }
            .filter { it.isNotEmpty() }

    /** A cluster identity for synonyms, or the ordinary comparison key for every other label. */
    private fun comparisonIdentity(label: String): String {
        val key = comparisonKey(label)
        if (key.isEmpty()) return "n:${normalize(label)}"
        val cluster = SYNONYM_CLUSTER_OF[key]
        return if (cluster == null) "k:$key" else "c:$cluster"
    }

    /**
     * True when a candidate meaning overlaps the correct answer across every sense. This is a
     * conservative test: lexical same/containment after normalisation, exact equality after POS and
     * particle normalisation, and membership in a finite common-synonym cluster. It is not full
     * semantics; an unlisted paraphrase still cannot be detected.
     */
    private fun overlapsCorrect(candidateMeaning: String, targetSenses: List<String>,
                                targetComparison: Set<String>): Boolean {
        val candidateSenses = senses(candidateMeaning)
        if (candidateSenses.isEmpty()) return true
        for (candidate in candidateSenses) {
            for (target in targetSenses) {
                if (candidate == target) return true
                if (candidate.length >= MIN_CONTAINMENT_CHARS && target.length >= MIN_CONTAINMENT_CHARS &&
                    (candidate.contains(target) || target.contains(candidate))
                ) return true
            }
        }
        for (candidate in comparisonSenses(candidateMeaning)) {
            if (candidate in targetComparison) return true
            val cluster = SYNONYM_CLUSTER_OF[candidate] ?: continue
            if (targetComparison.any { SYNONYM_CLUSTER_OF[it] == cluster }) return true
        }
        return false
    }

    private fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (character in text.lowercase(Locale.ROOT)) {
            if (character.isWhitespace() || character in COMPARE_IGNORED) continue
            out.append(character)
        }
        return out.toString()
    }

    /** Same part of speech first, then a shared spelling prefix and a close word length. */
    private fun score(target: DictionaryEntry, candidate: DictionaryEntry): Int {
        var score = 0
        val targetPos = posHead(target.partOfSpeech)
        if (targetPos.isNotEmpty() && targetPos == posHead(candidate.partOfSpeech)) score += 4
        score += minOf(commonPrefix(target.key, candidate.key), 3)
        if (target.key.length - candidate.key.length in -1..1) score += 1
        return score
    }

    private fun posHead(pos: String): String {
        var end = 0
        while (end < pos.length && !pos[end].isWhitespace() && pos[end] != '/') end++
        return pos.substring(0, end).trim()
    }

    private fun commonPrefix(first: String, second: String): Int {
        val limit = minOf(first.length, second.length)
        var count = 0
        while (count < limit && first[count] == second[count]) count++
        return count
    }

    /**
     * A stable Fisher-Yates permutation. Every index gets its own SplitMix64 output; the result
     * depends only on [seed] and [size], so the same card token always yields the same option order.
     */
    private fun seededOrder(size: Int, seed: String): List<Int> {
        val order = MutableList(size) { it }
        var state = stableHash(seed)
        for (index in size - 1 downTo 1) {
            state = splitMix64(state)
            val other = ((state ushr 1) % (index + 1)).toInt()
            val swap = order[index]
            order[index] = order[other]
            order[other] = swap
        }
        return order
    }

    private fun splitMix64(value: Long): Long {
        var z = value + SPLIT_MIX_GOLDEN
        z = (z xor (z ushr 30)) * SPLIT_MIX_MUL_1
        z = (z xor (z ushr 27)) * SPLIT_MIX_MUL_2
        return z xor (z ushr 31)
    }

    private fun stableHash(text: String): Long {
        var hash = FNV_OFFSET
        for (character in text) hash = (hash xor character.code.toLong()) * FNV_PRIME
        return hash
    }

    private data class Candidate(val entry: DictionaryEntry, val score: Int, val tieBreak: Long)
}
