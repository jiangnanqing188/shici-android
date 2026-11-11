package com.shici.app.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Full pinned ECDICT. Indexed disk reads, not a 770,000-entry in-memory map. Worker-only. */
class EcdictDictionary private constructor(private val db: SQLiteDatabase,
    override val entryCount: Int, override val aliasCount: Int) : DictionaryStore {
    override fun entry(normalizedKey: String): DictionaryEntry? {
        val bodies = mutableListOf<String>()
        var pos = ""
        db.rawQuery("SELECT translation,definition,pos FROM records WHERE key=? ORDER BY id",
            arrayOf(normalizedKey)).use { rows ->
            while (rows.moveToNext()) {
                if (pos.isEmpty()) pos = rows.getString(2)
                val chinese = rows.getString(0).replace("\\n", "\n")
                val english = rows.getString(1).replace("\\n", "\n")
                bodies += when {
                    chinese.isNotBlank() -> chinese
                    english.isNotBlank() -> "中文释义未提供；原英文释义：\n$english"
                    else -> "原词库未提供释义；可在本机补充。"
                }
            }
        }
        if (bodies.isEmpty()) return null
        return DictionaryEntry(normalizedKey, pos, bodies.distinct().joinToString("\n\n"), SOURCE, VERSION)
    }

    override fun lemmas(normalizedForm: String): List<String> =
        db.rawQuery("SELECT lemma FROM forms WHERE form=? ORDER BY lemma", arrayOf(normalizedForm)).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }

    companion object {
        const val SOURCE = EcdictInfo.SOURCE
        const val VERSION = EcdictInfo.VERSION
        const val NOTICE = EcdictInfo.NOTICE
        private const val ASSET = "dictionary/ecdict.sqlite3"
        private const val MANIFEST = "dictionary/ecdict-manifest.json"

        private fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        fun open(context: Context): EcdictDictionary {
            val manifest = context.assets.open(MANIFEST).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            require(manifest.getString("format") == "SHICI_ECDICT_SQLITE" && manifest.getInt("format_version") == 1 &&
                manifest.getString("source") == SOURCE && manifest.getString("version") == VERSION &&
                manifest.getString("content_review_status") == "unreviewed" && !manifest.getBoolean("syllabus_verified") &&
                manifest.getInt("record_count") == 770611) { "大词库来源或格式不匹配" }
            val expected = manifest.getString("asset_sha256")
            require(expected.matches(Regex("[a-f0-9]{64}"))) { "大词库完整性声明无效" }
            // Never touches context.getDatabasePath(): that directory belongs to the learning DB.
            val directory = File(context.noBackupFilesDir, "dictionary").apply { check(isDirectory || mkdirs()) }
            val file = File(directory, "ecdict-$VERSION.sqlite3")
            val bytes = manifest.getLong("asset_bytes")
            if (!file.isFile || file.length() != bytes || hash(file) != expected) {
                val temporary = File.createTempFile("ecdict-", ".tmp", directory)
                try {
                    context.assets.open(ASSET).use { input -> FileOutputStream(temporary).use { output ->
                        input.copyTo(output, 64 * 1024); output.fd.sync()
                    } }
                    require(temporary.length() == bytes && hash(temporary) == expected) { "大词库复制不完整" }
                    // Validation failure must leave the previous usable file untouched. Close the
                    // read-only candidate handle before the same-directory atomic replacement.
                    openChecked(temporary).close()
                    check(temporary.renameTo(file)) { "大词库启用失败（请检查本机空间）" }
                } finally { temporary.delete() }
            }
            return EcdictDictionary(openChecked(file), manifest.getInt("entry_count"), manifest.getInt("alias_count"))
        }

        private fun openChecked(file: File): SQLiteDatabase {
            val db = SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") { "大词库损坏" } }
                db.rawQuery("SELECT value FROM metadata WHERE name='version'", null).use {
                    require(it.moveToFirst() && it.getString(0) == VERSION) { "大词库版本不匹配" }
                }
                // Also prove both query tables are usable before publishing the file.
                db.rawQuery("SELECT key,translation,definition,pos FROM records LIMIT 1", null).use {
                    require(it.moveToFirst()) { "大词库词条缺失" }
                }
                db.rawQuery("SELECT form,lemma FROM forms LIMIT 1", null).use {
                    require(it.moveToFirst()) { "大词库词形索引缺失" }
                }
                return db
            } catch (failure: Throwable) { db.close(); throw failure }
        }
    }
}
