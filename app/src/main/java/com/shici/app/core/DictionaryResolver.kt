package com.shici.app.core

/**
 * The single resolution path for the pinned original dictionary plus the local user supplement
 * dictionary. Lookup entry points, study display and backup previews must all use this so a user
 * supplement is never hidden by an older original-only snapshot and never appears in one place but
 * not another.
 */
object DictionaryResolver {
    /**
     * Tries the normalized complete form, then the form without surrounding punctuation. Both
     * preserve real dotted headwords, including local entries, before falling back by one trailing
     * period. Word forms come only from the source index; there is no suffix guessing or recursion.
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
        val unquoted = form.trim { it in " \"'“”()[]{}<>,;:!?«»" }
        if (unquoted != form && unquoted.isNotEmpty()) candidates(unquoted)?.let { return it }
        if (unquoted.endsWith('.')) candidates(unquoted.dropLast(1).trimEnd())?.let { return it }
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
