package com.shici.app.core

/**
 * One locally stored user supplement. It is deliberately independent of the pinned dictionary, the
 * personal query history and the study ledger: saving it never creates a QueryEvent, a word row, a
 * study state or a feedback. A key may have exactly one user row; editing replaces only these fields.
 */
data class UserDictionaryEntry(
    val entryKey: String,
    val partOfSpeech: String,
    val meaning: String,
    val updatedAt: Long
)

/**
 * Rules for the user supplement dictionary.
 *
 * The original assets are never rewritten: [merge] appends a clearly marked user section to the
 * original entry (or builds a user-only entry when the original book has no such headword). The
 * marker makes the merged display explicit. The merged object also carries the untouched original
 * in [DictionaryEntry.originalSnapshot], which is the only source [merge] and [storedEntry] consult
 * when replacing or removing the user section. Marker-like text inside a raw original entry is
 * ordinary data and is never parsed, guessed at or truncated.
 */
object PersonalDictionary {
    const val SOURCE = "用户补充（本机）"
    const val VERSION = "local-1"
    const val MAX_KEY_LENGTH = 128
    const val MAX_POS_LENGTH = 64
    const val MAX_MEANING_LENGTH = 16_384
    const val MARKER = "【用户补充】"
    private const val POS_MARKER = "；用户补充："
    private const val MEANING_MARKER = "\n\n$MARKER\n"

    /**
     * Normalizes a manually confirmed new headword with the same English-word convention the
     * dictionary uses. Multi-word phrases are not auto-concatenated or accepted here.
     */
    fun normalizeNewKey(raw: String): String? {
        val key = RawKajwebDictionary.normalize(raw) ?: return null
        if (key.length > MAX_KEY_LENGTH) return null
        if (key.any { it.isWhitespace() }) return null
        return key.takeIf { validText(it, allowNewline = false) }
    }

    /** Validates an already normalized key (an existing dictionary key or a new user headword). */
    fun requireStoredKey(key: String): String {
        require(key.isNotBlank()) { "词头不能为空" }
        require(key.length <= MAX_KEY_LENGTH) { "词头超过 $MAX_KEY_LENGTH 字符" }
        require(validText(key, allowNewline = false)) { "词头包含非法控制字符" }
        require(RawKajwebDictionary.normalize(key) == key) { "词头不是规范化形式" }
        return key
    }

    /** Trims and validates an optional part of speech. Newlines are not allowed in this field. */
    fun sanitizePos(raw: String): String {
        val value = raw.trim()
        require(value.length <= MAX_POS_LENGTH) { "词性超过 $MAX_POS_LENGTH 字符" }
        require(validText(value, allowNewline = false)) { "词性包含非法控制字符" }
        return value
    }

    /**
     * Normalizes a non-empty meaning: CRLF/CR become LF and the existing whole-value trim
     * convention is kept. Everything inside is user data: interior legal line breaks, blank lines,
     * repeated lines/paragraphs, indentation and trailing spaces on a line are preserved. Lines are
     * never split apart, dropped or de-duplicated here, so a legal repeated body round-trips
     * unchanged instead of being rewritten to look idempotent. Whole-value whitespace is still
     * rejected, as are illegal control/replacement characters and values longer than
     * [MAX_MEANING_LENGTH]. The repository and v3 backup use this same definition through
     * [isValidStoredMeaning], so stored text is validated without deleting user content.
     */
    fun sanitizeMeaning(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        require(validText(normalized, allowNewline = true)) { "释义包含非法控制字符" }
        val value = normalized.trim()
        require(value.isNotEmpty()) { "释义不能为空" }
        require(value.length <= MAX_MEANING_LENGTH) { "释义超过 $MAX_MEANING_LENGTH 字符" }
        return value
    }

    fun isValidStoredKey(key: String): Boolean =
        runCatching { requireStoredKey(key) }.isSuccess

    fun isValidStoredPos(pos: String): Boolean =
        runCatching { sanitizePos(pos) == pos }.getOrDefault(false)

    fun isValidStoredMeaning(meaning: String): Boolean =
        runCatching { sanitizeMeaning(meaning) == meaning }.getOrDefault(false)

    /**
     * Merges one user row onto an original entry without touching the original meaning. The user
     * section is rebuilt from [DictionaryEntry.originalSnapshot] when the passed entry is already a
     * merged display, so re-merging is idempotent and a new supplement replaces the old user text
     * instead of stacking sections. A raw entry has no snapshot: every byte it contains is original
     * data, even when it literally looks like the user marker.
     */
    fun merge(original: DictionaryEntry?, user: UserDictionaryEntry?): DictionaryEntry? {
        if (original == null) return user?.toEntry()
        if (original.source == SOURCE && original.version == VERSION) {
            return if (user == null) original else user.toEntry()
        }
        // A display entry may already carry one or more user sections. Always rebuild from the true
        // first original snapshot rather than treating derived text as a new raw original.
        val base = original.originalBase()
        if (user == null) return base
        val basePos = base.partOfSpeech
        val pos = when {
            user.partOfSpeech.isBlank() -> basePos
            basePos.isBlank() -> user.partOfSpeech
            else -> "$basePos$POS_MARKER${user.partOfSpeech}"
        }
        val meaning = if (base.meaning.isBlank()) "$MARKER\n${user.meaning}"
        else "${base.meaning}$MEANING_MARKER${user.meaning}"
        return base.copy(partOfSpeech = pos, meaning = meaning, originalSnapshot = base)
    }

    /**
     * The original (non-user) part of an entry, used when persisting a query/study snapshot. A
     * merged display text is never baked into `words`: the separate user row stays the source of
     * truth and the current supplement is merged again at read time. An entry without an explicit
     * [DictionaryEntry.originalSnapshot] is raw and returned byte-for-byte, never trimmed by
     * searching for marker-like text.
     */
    fun storedEntry(entry: DictionaryEntry): DictionaryEntry {
        if (entry.source == SOURCE && entry.version == VERSION) return entry
        return entry.originalSnapshot?.originalBase() ?: entry
    }

    /** Follows only the explicit snapshot chain; no text scanning or identity cache is involved. */
    private fun DictionaryEntry.originalBase(): DictionaryEntry {
        var current = this
        while (true) {
            val parent = current.originalSnapshot ?: return current
            current = parent
        }
    }

    fun label(entry: DictionaryEntry): String =
        if (entry.source == SOURCE && entry.version == VERSION) "用户补充 · 仅本机"
        else RawKajwebDictionary.label(entry.source, entry.version)

    fun provenance(entry: DictionaryEntry): String =
        if (entry.source == SOURCE && entry.version == VERSION)
            "来源：用户补充（仅保存在本机）\n未联网、未自动生成或核验；原始词库义项未被覆盖。"
        else RawKajwebDictionary.provenance(entry.source, entry.version)

    fun userOnly(entry: UserDictionaryEntry): DictionaryEntry = entry.toEntry()

    private fun UserDictionaryEntry.toEntry(): DictionaryEntry =
        DictionaryEntry(entryKey, partOfSpeech, meaning, SOURCE, VERSION)

    private fun validText(value: String, allowNewline: Boolean): Boolean {
        for (ch in value) {
            if (ch == '\n') {
                if (!allowNewline) return false
                continue
            }
            if (Character.isISOControl(ch)) return false
            if (ch == '�') return false
        }
        return true
    }
}
