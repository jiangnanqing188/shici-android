package com.shici.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.shici.app.core.*
import java.time.LocalDate
import java.time.ZoneId

/** Queries are serialized by ShiciApplication.worker; all mutations are transactional. */
class LearningRepository(context: Context, name: String = "learning.db") : SQLiteOpenHelper(context, name, null, 1) {
    val changes = LearningChanges()

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE words (
            entry_key TEXT PRIMARY KEY, headword TEXT NOT NULL, pos TEXT NOT NULL,
            meaning TEXT NOT NULL, source TEXT NOT NULL, version TEXT NOT NULL,
            mastered INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '')""")
        db.execSQL("""CREATE TABLE events (
            id TEXT PRIMARY KEY, entry_key TEXT NOT NULL REFERENCES words(entry_key),
            raw_text TEXT NOT NULL, final_form TEXT NOT NULL, time INTEGER NOT NULL,
            revoked INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX events_word_time ON events(entry_key, time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("尚未提供从 $oldVersion 到 $newVersion 的迁移，保留原库而不删除数据")
    }

    fun record(event: QueryEvent, entry: DictionaryEntry, replaces: String? = null,
               canCommit: () -> Boolean = { true }): Boolean {
        require(event.entryKey == entry.key && !event.revoked)
        if (!canCommit()) return false
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        db.beginTransaction()
        var changed = false
        try {
            val old = eventById(db, event.id)
            changed = !LearningRules.isDuplicate(old, event)
            if (changed) {
                if (replaces != null) {
                    require(replaces != event.id)
                    require(eventById(db, replaces) != null) { "原查询已不存在" }
                    db.update("events", ContentValues().apply { put("revoked", 1) }, "id=?", arrayOf(replaces))
                }
                val dictionaryFields = ContentValues().apply {
                    put("headword", entry.headword); put("pos", entry.partOfSpeech); put("meaning", entry.meaning)
                    put("source", entry.source); put("version", entry.version)
                }
                db.insertWithOnConflict("words", null, ContentValues(dictionaryFields).apply {
                    put("entry_key", entry.key)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                // Only a new successful event refreshes the dictionary snapshot. Preserve personal fields.
                db.update("words", dictionaryFields, "entry_key=?", arrayOf(entry.key))
                db.insertOrThrow("events", null, ContentValues().apply {
                    put("id", event.id); put("entry_key", event.entryKey); put("raw_text", event.rawText)
                    put("final_form", event.finalForm); put("time", event.time); put("revoked", 0)
                })
            }
            if (!canCommit()) return false
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) changes.publishCommitted()
        return true
    }

    fun revoke(id: String) = change { db ->
        db.update("events", ContentValues().apply { put("revoked", 1) }, "id=? AND revoked=0", arrayOf(id))
    }

    fun updateWord(key: String, mastered: Boolean, note: String) = change { db ->
        val marked = if (mastered) 1 else 0
        db.update("words", ContentValues().apply {
            put("mastered", marked); put("note", note)
        }, "entry_key=? AND (mastered<>? OR note<>?)", arrayOf(key, marked.toString(), note))
    }

    private fun change(write: (SQLiteDatabase) -> Int) {
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        db.beginTransaction()
        val affected: Int
        try {
            affected = write(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (affected > 0) changes.publishCommitted()
    }

    fun snapshot(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): LearningSnapshot {
        val db = readableDatabase
        check(!db.inTransaction()) { "只能读取已提交的学习快照" }
        db.beginTransactionNonExclusive()
        try {
            val words = words()
            val stats = LearningRules.summarize(words, events(), date, zone)
            db.setTransactionSuccessful()
            return LearningSnapshot(words, stats, changes.version)
        } finally { db.endTransaction() }
    }

    fun exportSnapshot(search: String, date: LocalDate = LocalDate.now()): LearningExportSnapshot {
        check(!readableDatabase.inTransaction()) { "只能导出已提交的学习快照" }
        return LearningExportSnapshot(words(search), search, date)
    }

    fun words(search: String = ""): List<LearnedWord> {
        val words = mutableListOf<LearnedWord>()
        readableDatabase.rawQuery("""SELECT w.*, COUNT(e.id) AS query_count,
            MIN(e.time) AS first_at, MAX(e.time) AS last_at,
            (SELECT e2.final_form FROM events e2 WHERE e2.entry_key=w.entry_key AND e2.revoked=0
             ORDER BY e2.time DESC, e2.id DESC LIMIT 1) AS last_form
            FROM words w JOIN events e ON e.entry_key=w.entry_key AND e.revoked=0
            GROUP BY w.entry_key ORDER BY last_at DESC, w.headword""", null).use { c ->
            while (c.moveToNext()) {
                val word = LearnedWord(
                    DictionaryEntry(c.text("headword"), c.text("pos"), c.text("meaning"), c.text("source"), c.text("version")),
                    c.text("source"), c.text("version"), c.text("last_form"), c.int("query_count"),
                    c.long("first_at"), c.long("last_at"), c.int("mastered") == 1, c.text("note")
                )
                if (search.isBlank() || listOf(word.entry.headword, word.entry.meaning, word.lastForm, word.note)
                        .any { it.contains(search.trim(), ignoreCase = true) }) words += word
            }
        }
        return words
    }

    fun latestEventId(key: String): String? = readableDatabase.rawQuery(
        "SELECT id FROM events WHERE entry_key=? AND revoked=0 ORDER BY time DESC, id DESC LIMIT 1", arrayOf(key)
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun events(): List<QueryEvent> = buildList {
        readableDatabase.rawQuery("SELECT * FROM events", null).use { c ->
            while (c.moveToNext()) add(readEvent(c))
        }
    }

    private fun eventById(db: SQLiteDatabase, id: String): QueryEvent? =
        db.rawQuery("SELECT * FROM events WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) readEvent(c) else null
        }

    private fun readEvent(c: Cursor) = QueryEvent(c.text("id"), c.text("entry_key"),
        c.text("raw_text"), c.text("final_form"), c.long("time"), c.int("revoked") == 1)
    private fun Cursor.text(key: String) = getString(getColumnIndexOrThrow(key))
    private fun Cursor.int(key: String) = getInt(getColumnIndexOrThrow(key))
    private fun Cursor.long(key: String) = getLong(getColumnIndexOrThrow(key))
}
