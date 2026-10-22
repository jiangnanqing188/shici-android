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
     * There is no recursion, stemming, plural, phrase or multi-word joining.
     */
    fun lookup(
        dictionary: RawKajwebDictionary,
        raw: String,
        userFor: (String) -> UserDictionaryEntry?
    ): LookupResult {
        val form = RawKajwebDictionary.normalize(raw) ?: return LookupResult.Invalid
        exact(form, dictionary, userFor)?.let { return it }
        if (form.endsWith('.')) {
            val withoutStop = form.dropLast(1)
            exact(withoutStop, dictionary, userFor)?.let { return it }
        }
        return LookupResult.Missing(form)
    }

    /**
     * Resolves one normalized entry key used by study/backup. [original] is the optional saved or
     * pinned original entry; [userFor] reads the current local supplement. The latest user row is
     * always merged on top, so editing a supplement instantly affects the next study display.
     */
    fun resolve(
        dictionary: RawKajwebDictionary?,
        original: DictionaryEntry?,
        key: String,
        userFor: (String) -> UserDictionaryEntry?
    ): DictionaryEntry? {
        val normalized = RawKajwebDictionary.normalize(key) ?: key
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
        dictionary: RawKajwebDictionary,
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
