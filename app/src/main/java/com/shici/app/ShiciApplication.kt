package com.shici.app

import android.app.Application
import com.shici.app.data.LearningRepository
import com.shici.app.core.DictionaryEntry
import com.shici.app.core.DictionaryResolver
import com.shici.app.core.LookupResult
import com.shici.app.core.EcdictDictionary
import com.shici.app.core.DictionaryStore
import com.shici.app.core.RawKajwebDictionary
import com.shici.app.core.StudyBook
import com.shici.app.core.StudyBooks
import org.json.JSONObject
import java.util.concurrent.Executors

class ShiciApplication : Application() {
    val worker = Executors.newSingleThreadExecutor()
    val repository by lazy { LearningRepository(this) }
    // Access only from worker: cache both a fully validated dictionary and an explicit load failure.
    val lookupDictionary: Result<DictionaryStore> by lazy { runCatching { EcdictDictionary.open(this) } }
    // Original fixed exam-book entries are retained only for study membership fallback.
    val dictionary: Result<RawKajwebDictionary> by lazy {
        runCatching {
            val json = assets.open(RawKajwebDictionary.MANIFEST_ASSET).use {
                JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it.readBytes())).toString())
            }
            require(json.getString("format") == RawKajwebDictionary.FORMAT &&
                json.getInt("format_version") == RawKajwebDictionary.FORMAT_VERSION &&
                json.getString("content_review_status") == "unreviewed" &&
                !json.getBoolean("syllabus_verified")) { "词库格式或未核对声明不匹配" }
            val manifest = RawKajwebDictionary.Manifest(json.getString("asset_sha256"),
                json.getInt("entry_count"), json.getInt("alias_count"),
                json.getString("source"), json.getString("version"))
            require(manifest.entryCount == RawKajwebDictionary.EXPECTED_ENTRY_COUNT) { "词库不是固定原始词头集合" }
            RawKajwebDictionary.load(assets.open(RawKajwebDictionary.ASSET), manifest)
        }
    }
    // Same worker and same manifest-checking discipline as the dictionary: the pinned ky/cet6 membership
    // index is only used after the format, unreviewed status, counts and SHA-256 all validate.
    val studyBooks: Result<StudyBooks> by lazy {
        runCatching {
            val json = assets.open(StudyBooks.MANIFEST_ASSET).use {
                JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(it.readBytes())).toString())
            }
            require(json.getString("format") == StudyBooks.FORMAT &&
                json.getInt("format_version") == StudyBooks.FORMAT_VERSION &&
                json.getString("content_review_status") == "unreviewed" &&
                !json.getBoolean("syllabus_verified")) { "学习词书格式或未核对声明不匹配" }
            val counts = json.getJSONObject("book_counts")
            val manifest = StudyBooks.Manifest(json.getString("asset_sha256"),
                mapOf(StudyBook.KAOYAN.id to counts.getInt(StudyBook.KAOYAN.id),
                    StudyBook.CET6.id to counts.getInt(StudyBook.CET6.id)),
                json.getString("source"), json.getString("version"))
            require(manifest.bookCounts[StudyBook.KAOYAN.id] == StudyBooks.EXPECTED_KAOYAN &&
                manifest.bookCounts[StudyBook.CET6.id] == StudyBooks.EXPECTED_CET6) { "学习词书不是固定成员集合" }
            StudyBooks.load(assets.open(StudyBooks.ASSET), manifest)
        }
    }

    /**
     * Unified manual/floating lookup. Worker-only: it reads the local supplement dictionary from the
     * same repository worker and must not be called from the UI thread.
     */
    fun lookupWord(raw: String): LookupResult =
        DictionaryResolver.lookup(lookupDictionary.getOrThrow(), raw) { repository.userEntry(it) }

    fun lookupExactWord(raw: String): LookupResult =
        DictionaryResolver.lookupExact(lookupDictionary.getOrThrow(), raw) { repository.userEntry(it) }

    /**
     * Unified entry resolution used by study membership/display. The saved original snapshot keeps
     * historical source labels working for old records; the current user supplement is merged on top
     * so an older word snapshot never swallows a newer supplement. Worker-only.
     */
    fun resolveEntry(key: String): DictionaryEntry? =
        DictionaryResolver.resolve(lookupDictionary.getOrNull(), repository.storedEntry(key) ?: dictionary.getOrNull()?.entry(key), key) {
            repository.userEntry(it)
        }
}
