package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class DictionaryTest {
    @Test fun acceptsOneWordWithPunctuationAndCase() {
        val result = DemoDictionary.lookup("  “EVIDENCE,” ") as LookupResult.Match
        assertEquals("evidence", result.form)
        assertEquals("evidence", result.entries.single().headword)
    }
    @Test fun explicitInflectionMapsToHeadword() {
        val result = DemoDictionary.lookup("studies") as LookupResult.Match
        assertEquals("studies", result.form)
        assertEquals("study", result.entries.single().headword)
    }
    @Test fun ambiguityRetainsExactAndIrregularCandidates() {
        val saw = DemoDictionary.lookup("saw") as LookupResult.Match
        assertEquals(listOf("saw", "see"), saw.entries.map { it.headword })
        val left = DemoDictionary.lookup("left") as LookupResult.Match
        assertEquals(listOf("left", "leave"), left.entries.map { it.headword })
    }
    @Test fun unsupportedWordAndInflectionNeverFabricateDefinition() {
        assertTrue(DemoDictionary.lookup("quantum") is LookupResult.Missing)
        assertTrue(DemoDictionary.lookup("researching") is LookupResult.Missing)
    }
    @Test fun refusesMultipleWordsDigitsAndNonLatinLetters() {
        listOf("", "evidence improve", "abc123", "évidence", "证据", "foo/bar").forEach {
            assertEquals(it, LookupResult.Invalid, DemoDictionary.lookup(it))
        }
    }
    @Test fun ocrKeepsSeveralWordsSeparateAndDoesNotJoinLines() {
        assertEquals(listOf("evidence", "improve"), DemoDictionary.ocrWords("Evidence,\n improve evidence"))
    }
    @Test fun vocabularyHasUniqueKeysAndExplicitSource() {
        assertEquals(DemoDictionary.entries.size, DemoDictionary.entries.map { it.key }.distinct().size)
        assertTrue(DemoDictionary.SOURCE.contains("演示"))
    }
}
