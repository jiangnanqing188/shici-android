package com.shici.app.core

/**
 * The single resolution path for the pinned original dictionary plus the local user supplement
 * dictionary. Lookup entry points, study display and backup previews must all use this so a user
 * supplement is never hidden by an older original-only snapshot and never appears in one place but
 * not another.
 */
object DictionaryResolver {
    /**
     * Resolves a raw manual/floating query through one strict priority: the normalized complete
     * form is matched against the original exact headword and the current user supplement together,
     * so a real dotted headword (including a user-only one) never loses to an original undotted
     * fallback. Only when both sources miss that complete form may exactly one trailing sentence
     * stop be removed, and the same original+user exact rule is applied once to the stripped form.
     * Word forms come only from the source index; there is no suffix guessing or recursive joining.
     */
    fun lookup(
        dictionary: DictionaryStore,
        raw: String,
        userFor: (String) -> UserDictionaryEntry?
    ): LookupResult = lookupWithForms(dictionary, raw, true, userFor)

    /** Supplement editing binds to the typed key, never silently to an inflection's lemma. */
    fun lookupExact(dictionary: DictionaryStore, raw: String,
                    userFor: (String) -> UserDictionaryEntry?): LookupResult =
        lookupWithForms(dictionary, raw, false, userFor)

    private fun lookupWithForms(dictionary: DictionaryStore, raw: String, includeForms: Boolean,
                                userFor: (String) -> UserDictionaryEntry?): LookupResult {
        val form = DictionaryKeys.normalize(raw) ?: return LookupResult.Invalid
        fun candidates(key: String): LookupResult.Match? {
            val entries = linkedMapOf<String, DictionaryEntry>()
            exact(key, dictionary, userFor)?.entries?.forEach { entries[it.key] = it }
            for (lemma in if (includeForms) dictionary.lemmas(key) else emptyList()) {
                exact(lemma, dictionary, userFor)?.entries?.forEach { entries[it.key] = it }
            }
            return entries.values.toList().takeIf { it.isNotEmpty() }?.let { LookupResult.Match(key, it) }
        }
        candidates(form)?.let { return it }
        // Full dotted headwords win, including a personal-only exact entry.
        if (form.endsWith('.')) candidates(form.dropLast(1))?.let { return it }
        val unquoted = form.trim { it in " \"'“”()[]{}<>,;:!?«»" }
        if (unquoted != form && unquoted.isNotEmpty()) candidates(unquoted)?.let { return it }
        return LookupResult.Missing(form)
    }

    /**
     * Resolves one normalized entry key used by study/backup. [original] is the optional saved or
     * pinned original entry; [userFor] reads the current local supplement. The latest user row is
     * always merged on top, so editing a supplement instantly affects the next study display.
     */
    fun resolve(
        dictionary: DictionaryStore?,
        original: DictionaryEntry?,
        key: String,
        userFor: (String) -> UserDictionaryEntry?
    ): DictionaryEntry? {
        val normalized = DictionaryKeys.normalize(key) ?: key
        // The pinned original asset wins for a current headword; the saved row is the fallback for
        // historical entries that no longer exist in the current asset. This keeps lookup and study
        // resolution on the same original meaning.
        val base = dictionary?.entry(normalized) ?: original
        return PersonalDictionary.merge(base, userFor(normalized))
    }

    /**
     * One exact level for [form]. The original asset and the latest user row are the only sources:
     * a shared key keeps the original as base and merges the user supplement once; an original miss
     * with a user exact returns that user-only headword. Null means both sources missed, so the
     * caller may try the single sentence-stop fallback. [LookupResult.Match.form] stays the real
     * normalized key that hit, and the merged entry keeps that same entry key.
     */
    private fun exact(
        form: String,
        dictionary: DictionaryStore,
        userFor: (String) -> UserDictionaryEntry?
    ): LookupResult.Match? {
        val original = dictionary.entry(form)
        val user = userFor(form)
        val entry = when {
            original != null -> PersonalDictionary.merge(original, user) ?: original
            user != null -> PersonalDictionary.userOnly(user)
            else -> return null
        }
        return LookupResult.Match(form, listOf(entry))
    }
}
