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
 * revision: every label is an existing local meaning (only a leading known POS label is removed and
 * the first clause is shortened). It cannot be called with a whole-library scan by itself; the UI
 * loads a bounded list on the application worker first.
 *
 * The question is deterministic. Distractor order is a stable FNV-1a/SplitMix64 shuffle of the card
 * token, so the same pending card redrawn after rotation, navigation or a process restart shows the
 * same options in the same places, while a freshly dealt card gets a new token and therefore a new,
 * not-fixed correct position. No [java.util.Random], wall clock or hash-map iteration order is used.
 *
 * Displayed meaning and part of speech:
 * - A merged display entry (one carrying [DictionaryEntry.originalSnapshot]) is asked with the
 *   current user supplement, exactly like the lookup card. Its explicit part of speech is recovered
 *   from the structure [PersonalDictionary.merge] produces, never by scanning marker-like text in a
 *   raw original meaning. A blank user part of speech is unknown: the original part of speech is not
 *   borrowed and no part of speech is guessed from the Chinese text. User-only entries behave the
 *   same way.
 * - A raw original entry uses the explicit part of speech on its first displayed clause when there is
 *   one; otherwise it falls back to the original entry's own part of speech, and only when that field
 *   is itself a supported label. Only a short explicit whitelist (n/n./名词, v/vt/vi/动词,
 *   adj/a/形容词, adv/ad/副词, pron, prep, conj, num, art, int/interj, aux and their bracketed forms)
 *   is recognised. Multiple labels form an explicit set; any arbitrary string is unknown.
 * - When the target's displayed part of speech is known, a distractor must carry an explicitly
 *   compatible label (a non-empty intersection), still inside the caller's bounded candidate window.
 *   Fewer than three compatible distractors yields the ordinary
 *   [ReviewUnavailableReason.NOT_ENOUGH_CANDIDATES] result, so the existing UI widening path decides
 *   what to do. When it is unknown, no part-of-speech filter or reward is applied and the existing
 *   deterministic spelling/hash order is kept.
 * - All four labels have any leading *known* part-of-speech label removed uniformly; Chinese
 *   qualifiers and the original text are kept, and no label is invented or rewritten.
 *
 * Distractor rules:
 * - Labels are four distinct normalised strings; the correct label is exactly one of them.
 * - A candidate is dropped when any of its meaning senses is equal to, contains or is contained in
 *   any sense of the full correct meaning (all original senses plus the user-supplied section), so a
 *   duplicate or an obviously contained meaning can never be offered as a wrong answer.
 * - Comparison also ignores a leading part-of-speech label and a trailing 的/地/得, and a small
 *   curated list of common Chinese near-synonym clusters is treated as the same sense. This removes
 *   obvious pairs such as 美丽的/漂亮的, 高兴/开心 and 快速/迅速 across every sense, including the
 *   user supplement, without special-casing any headword. The intensity family 怒/愤怒/狂怒/暴怒 is
 *   included only as a further ambiguous-distractor guard; it does not claim those words are equal.
 * - Similar spelling (shared prefix, close length) is preferred; ties are broken by a stable hash of
 *   token + keys. Existing meaning text is the only label source.
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

    /** The separator [PersonalDictionary.merge] puts between the original and the user POS. */
    private const val USER_POS_SEPARATOR = "；用户补充："

    /**
     * The only part-of-speech categories this generator treats as known. Anything outside this
     * finite enum is unknown, so an arbitrary source string can never act as a filter or a bonus.
     */
    private enum class PosKind {
        NOUN, VERB, ADJECTIVE, ADVERB, PRONOUN, PREPOSITION, CONJUNCTION,
        NUMERAL, ARTICLE, INTERJECTION, AUXILIARY
    }

    /** Separators accepted inside one explicit POS field (`n. / v.`, `vt/vi`, `n.或v.`). */
    private val POS_SEPARATORS = Regex("[\\s/／,，、;&|或]+")

    /** A bare ASCII POS expression at the start of a clause (`adj.`, `n./v.`, `vt`). */
    private val BARE_ASCII_POS = Regex(
        "^((?:[A-Za-z]{1,8}\\.?)(?:\\s*[/,，、;&|或]\\s*[A-Za-z]{1,8}\\.?)*)(?=\\s|[\\u4e00-\\u9fff]|$)"
    )

    /** One or more bare supported Chinese POS labels at the start of a clause (`名词/动词`). */
    private val BARE_CHINESE_POS = Regex(
        "^((?:名词|动词|形容词|副词|代词|介词|连词|数词|冠词|感叹词|助动词)(?:\\s*[/,，、;&|或]\\s*(?:名词|动词|形容词|副词|代词|介词|连词|数词|冠词|感叹词|助动词))*)(?=\\s|[：:]|$)"
    )

    /**
     * A leading ASCII part-of-speech tag (`[adj]`, `(n.)`, `【v】`, `adj.`) describes the same sense
     * as the bare meaning, so it is ignored for comparison. This broad comparison-only normaliser is
     * separate from the finite POS whitelist above: inference and label stripping never treat an
     * arbitrary bracket as a known part of speech.
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
        setOf("模糊", "含糊", "朦胧"),
        // Anger-intensity family. This is only a guard against offering a different strength as a
        // wrong option; it deliberately does not claim 怒/愤怒/狂怒/暴怒 are interchangeable.
        setOf("怒", "愤怒", "狂怒", "暴怒")
    )

    /**
     * Every curated cluster a word belongs to. A word may belong to more than one family (愤怒 is
     * in both the existing anger cluster and the intensity family), so values are sets rather than
     * one index; sharing any cluster is enough to treat two labels as too close.
     */
    private val SYNONYM_CLUSTERS_OF: Map<String, Set<Int>> = buildMap {
        SYNONYM_CLUSTERS.forEachIndexed { index, cluster ->
            cluster.forEach { word ->
                put(word, (this[word] ?: emptySet<Int>()) + index)
            }
        }
    }

    /**
     * Builds one question or reports why it cannot be built. [candidates] may be any bounded list of
     * already resolved local entries; this function filters, ranks and picks exactly three distractors.
     */
    fun build(target: DictionaryEntry, candidates: List<DictionaryEntry>, token: String): ReviewQuestion {
        val targetDisplay = displayed(target)
        val correctLabel = displayedLabel(targetDisplay)
        val correctNormalised = normalize(correctLabel)
        if (correctNormalised.isEmpty()) {
            return ReviewQuestion.Unavailable(ReviewUnavailableReason.NO_TARGET_MEANING)
        }
        val targetSenses = senses(target.meaning)
        val targetComparison = comparisonSenses(target.meaning).toSet()
        val targetPos = targetDisplay.pos
        val usedLabels = hashSetOf(correctNormalised)
        val usedComparison = hashSetOf<String>().apply { addAll(comparisonIdentities(correctLabel)) }
        val distractors = ArrayList<DictionaryEntry>(OPTION_COUNT - 1)
        val ranked = candidates.asSequence()
            .filter { it.key != target.key && it.meaning.isNotBlank() }
            .map {
                Candidate(
                    it, displayed(it), score(target.key, it.key),
                    stableHash("$token\u0000${target.key}\u0000${it.key}")
                )
            }
            .filter { compatiblePos(targetPos, it.display.pos) }
            .filter { !overlapsCorrect(it.entry.meaning, targetSenses, targetComparison) }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.tieBreak })
            .iterator()
        while (ranked.hasNext() && distractors.size < OPTION_COUNT - 1) {
            val candidate = ranked.next()
            val label = displayedLabel(candidate.display)
            val normalised = normalize(label)
            val identities = comparisonIdentities(label)
            if (normalised.isEmpty() || !usedLabels.add(normalised) ||
                identities.isEmpty() || identities.any { it in usedComparison }) continue
            usedComparison.addAll(identities)
            distractors += candidate.entry
        }
        if (distractors.size < OPTION_COUNT - 1) {
            return ReviewQuestion.Unavailable(ReviewUnavailableReason.NOT_ENOUGH_CANDIDATES)
        }
        val options = ArrayList<ReviewOption>(OPTION_COUNT)
        options += ReviewOption(target.key, correctLabel, true)
        distractors.forEach { options += ReviewOption(it.key, displayedLabel(displayed(it)), false) }
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

    /** One displayed meaning plus the explicit POS labels that came from the same source. */
    private data class DisplayedMeaning(val meaning: String, val pos: Set<PosKind>?)

    /**
     * Resolves the source used for the label and the POS that belongs to *that* source.
     *
     * A merged display entry uses the user supplement and the user's own POS recovered from the
     * merge structure. A blank user POS is unknown and never falls back to the original POS. A
     * user-only entry is the same rule with its own field. Only a raw original entry may use its
     * first displayed clause, then its own POS field; marker-like text is never parsed out of the
     * meaning.
     */
    private fun displayed(entry: DictionaryEntry): DisplayedMeaning {
        val snapshot = entry.originalSnapshot
        if (snapshot != null) {
            return DisplayedMeaning(preferredMeaning(entry), mergedUserPos(entry, snapshot))
        }
        if (entry.source == PersonalDictionary.SOURCE && entry.version == PersonalDictionary.VERSION) {
            return DisplayedMeaning(entry.meaning, parsePosSet(entry.partOfSpeech))
        }
        val clausePos = leadingPosTags(firstClause(entry.meaning))
        return DisplayedMeaning(entry.meaning, clausePos ?: parsePosSet(entry.partOfSpeech))
    }

    /**
     * Recovers the user POS from the exact shape [PersonalDictionary.merge] writes:
     * `basePos + USER_POS_SEPARATOR + userPos`, with the field being just `userPos` when the base
     * has none, and just `basePos` when the user left it blank. A shape that does not match is
     * unknown rather than being parsed speculatively.
     */
    private fun mergedUserPos(entry: DictionaryEntry, snapshot: DictionaryEntry): Set<PosKind>? {
        val basePos = rawBase(snapshot).partOfSpeech
        val mergedPos = entry.partOfSpeech
        val userPos = when {
            basePos.isBlank() -> mergedPos
            mergedPos == basePos -> ""
            mergedPos.startsWith(basePos) -> {
                val rest = mergedPos.substring(basePos.length)
                if (rest.startsWith(USER_POS_SEPARATOR)) {
                    rest.substring(USER_POS_SEPARATOR.length).trim()
                } else ""
            }
            else -> ""
        }
        return parsePosSet(userPos)
    }

    /** Follows only the explicit snapshot chain; never scans text. */
    private fun rawBase(entry: DictionaryEntry): DictionaryEntry {
        var current = entry
        while (true) {
            current = current.originalSnapshot ?: return current
        }
    }

    /** First non-blank clause, before any shortening. */
    private fun firstClause(meaning: String): String =
        meaning.split('；', ';', '。', '\n', '\r')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

    /**
     * The displayed label: first clause, with a leading *known* POS label removed. Chinese
     * qualifiers and the rest of the original text stay untouched, and the 28-character bound is
     * applied only after stripping so a POS tag cannot use up the visible budget.
     */
    private fun displayedLabel(display: DisplayedMeaning): String {
        val clause = stripKnownPosPrefix(firstClause(display.meaning))
            .trimEnd('，', ',', '：', ':', ' ')
        if (clause.length <= MAX_LABEL_CHARS) return clause
        return clause.take(MAX_LABEL_CHARS - 1).trimEnd() + "…"
    }

    /** Removes up to three consecutive known POS labels from the start of a clause. */
    private fun stripKnownPosPrefix(text: String): String {
        var value = text.trim()
        var guard = 0
        while (guard++ < 3) {
            val tag = leadingPosTag(value) ?: break
            value = value.substring(tag.end).trim()
        }
        return value
    }

    /** Union of up to three consecutive known POS labels at the start of a clause, if any. */
    private fun leadingPosTags(text: String): Set<PosKind>? {
        var value = text.trim()
        var result: MutableSet<PosKind>? = null
        var guard = 0
        while (guard++ < 3) {
            val tag = leadingPosTag(value) ?: break
            result = (result ?: mutableSetOf()).apply { addAll(tag.pos) }
            value = value.substring(tag.end)
        }
        return result
    }

    private data class LeadingPos(val pos: Set<PosKind>, val end: Int)

    /**
     * Recognises one leading POS label from the finite whitelist. Bracketed content is accepted only
     * when every token is a supported label, so `[adj] 美丽的` is known while `[口] 你好` is not.
     * A bare ASCII label must have a dot or be followed by Chinese text (or be a multi-token
     * expression); a single uppercase letter without a dot is never mistaken for one.
     */
    private fun leadingPosTag(text: String): LeadingPos? {
        var start = 0
        while (start < text.length && text[start].isWhitespace()) start++
        if (start >= text.length) return null
        val closing = when (text[start]) {
            '[' -> ']'
            '(' -> ')'
            '（' -> '）'
            '【' -> '】'
            else -> null
        }
        if (closing != null) {
            val end = text.indexOf(closing, start + 1)
            if (end <= start + 1 || end - start - 1 > 32) return null
            val pos = parsePosSet(text.substring(start + 1, end)) ?: return null
            var after = end + 1
            while (after < text.length && text[after].isWhitespace()) after++
            if (after < text.length && (text[after] == '：' || text[after] == ':')) {
                after++
                while (after < text.length && text[after].isWhitespace()) after++
            }
            return LeadingPos(pos, after)
        }
        val remainder = text.substring(start)
        val chinese = BARE_CHINESE_POS.find(remainder)
        if (chinese != null) {
            val pos = parsePosSet(chinese.groupValues[1]) ?: return null
            var after = start + chinese.range.last + 1
            while (after < text.length && text[after].isWhitespace()) after++
            if (after < text.length && (text[after] == '：' || text[after] == ':')) {
                after++
                while (after < text.length && text[after].isWhitespace()) after++
            }
            return LeadingPos(pos, after)
        }
        val ascii = BARE_ASCII_POS.find(remainder) ?: return null
        val expression = ascii.groupValues[1]
        val pos = parsePosSet(expression) ?: return null
        var after = start + ascii.range.last + 1
        while (after < text.length && text[after].isWhitespace()) after++
        if (after < text.length && (text[after] == '：' || text[after] == ':')) {
            after++
            while (after < text.length && text[after].isWhitespace()) after++
        }
        if (!expression.contains('.') && expression.length == 1 && expression[0].isUpperCase()) {
            return null
        }
        if (!expression.contains('.') && after < text.length && !isCjk(text[after])) {
            return null
        }
        return LeadingPos(pos, after)
    }

    private fun isCjk(character: Char): Boolean = character.code in 0x4e00..0x9fff

    /**
     * Parses one explicit POS field or tag body into a set. Every token must be a supported label;
     * a partly unknown value such as `n. / 口语` is unknown rather than guessed. Multiple known
     * labels form a set and are compared by explicit intersection.
     */
    private fun parsePosSet(raw: String): Set<PosKind>? {
        val tokens = raw.split(POS_SEPARATORS).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val result = mutableSetOf<PosKind>()
        for (token in tokens) {
            val pos = canonicalPosToken(token) ?: return null
            result += pos
        }
        return result
    }

    /** Maps one token to the finite whitelist; trailing dots and wrapping brackets are ignored. */
    private fun canonicalPosToken(rawToken: String): PosKind? {
        var token = rawToken.trim().lowercase(Locale.ROOT)
        token = token.trim('[', ']', '(', ')', '（', '）', '【', '】', '{', '}')
        token = token.trimEnd('.', '．', '。').trim()
        if (token.isEmpty()) return null
        return when (token) {
            "n", "名词" -> PosKind.NOUN
            "v", "vt", "vi", "动词" -> PosKind.VERB
            "adj", "a", "形容词" -> PosKind.ADJECTIVE
            "adv", "ad", "副词" -> PosKind.ADVERB
            "pron", "代词" -> PosKind.PRONOUN
            "prep", "介词" -> PosKind.PREPOSITION
            "conj", "连词" -> PosKind.CONJUNCTION
            "num", "数词" -> PosKind.NUMERAL
            "art", "冠词" -> PosKind.ARTICLE
            "int", "interj", "感叹词" -> PosKind.INTERJECTION
            "aux", "助动词" -> PosKind.AUXILIARY
            else -> null
        }
    }

    /** A known target POS accepts only candidates with an explicit, non-empty intersection. */
    private fun compatiblePos(target: Set<PosKind>?, candidate: Set<PosKind>?): Boolean {
        if (target == null) return true
        if (candidate == null) return false
        return candidate.any { it in target }
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
     * Comparison form of one sense: leading known/ASCII POS labels are removed,
     * punctuation/whitespace is normalised, and a trailing structural particle (的/地/得) is
     * ignored. This form is used only for exact sense equality and the finite synonym clusters,
     * never for substring matching, so a word such as 目的 is not split apart by a broad
     * containment test.
     */
    private fun comparisonKey(rawSense: String): String {
        var text = stripKnownPosPrefix(rawSense.trim())
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

    /**
     * Identities used to keep option labels distinct. A word may belong to more than one curated
     * cluster (愤怒 is in both the existing anger cluster and the new intensity family), so every
     * cluster id is returned; sharing any one id means the labels are too close for two options.
     */
    private fun comparisonIdentities(label: String): List<String> {
        val key = comparisonKey(label)
        if (key.isEmpty()) return listOf("n:${normalize(label)}")
        val clusters = SYNONYM_CLUSTERS_OF[key]
        if (clusters.isNullOrEmpty()) return listOf("k:$key")
        return clusters.sorted().map { "c:$it" }
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
            val candidateClusters = SYNONYM_CLUSTERS_OF[candidate] ?: continue
            if (targetComparison.any { target ->
                    SYNONYM_CLUSTERS_OF[target]?.any { it in candidateClusters } == true
                }) return true
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

    /**
     * Similar spelling (shared prefix, close length) only. POS compatibility is a filter, not a
     * reward, so an unknown target never inherits a bonus from the original entry's old POS.
     */
    private fun score(targetKey: String, candidateKey: String): Int {
        var score = minOf(commonPrefix(targetKey, candidateKey), 3)
        if (targetKey.length - candidateKey.length in -1..1) score += 1
        return score
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

    private data class Candidate(val entry: DictionaryEntry, val display: DisplayedMeaning,
                                 val score: Int, val tieBreak: Long)
}
