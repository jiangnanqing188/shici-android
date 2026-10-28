package com.shici.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.shici.app.core.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Explicit three-state results so callers never report a stale/no-op revoke or delete as success.
 * [DeleteOutcome.REJECTED] is only returned when the caller supplied an admission/`canCommit` that
 * refused before any transaction: nothing was deleted and nothing changed.
 */
enum class RevokeOutcome { REVOKED, NONE, STALE }
enum class DeleteOutcome { DELETED, NOT_FOUND, STALE, REJECTED }

/**
 * Outcome of one field-aware personal-detail save. [SAVED] means at least one field the user really
 * edited was written and published; [UNCHANGED] means no edited field needed a write (nothing was
 * edited, or the stored value already equals the requested target); [CONFLICT] means an edited field
 * changed since the click-time baseline and nothing was written; [STALE] means the word or its
 * deletion credential is no longer current; [REJECTED] means an owner check refused before BEGIN.
 */
enum class WordDetailSaveOutcome { SAVED, UNCHANGED, CONFLICT, STALE, REJECTED }

/** Result of preparing the exact event shown by the "仅撤销最近一次有效查询" flow. */
enum class UndoPrepareOutcome {
    /** A fixed event identity was read; pass [UndoPreparation.prepared] to `commitUndo`. */
    PREPARED,
    /** There is no active event for this word right now. */
    NONE,
    /** The word was deleted (or a full restore committed) after this detail's credential was taken. */
    STALE,
    /** The database changed while the candidate was being read; nothing may be shown, retry/reopen. */
    CHANGED
}

/** Result of committing one exact prepared undo. [REJECTED] means an owner check refused before writing. */
enum class ExactUndoOutcome { REVOKED, NONE, STALE, REJECTED }

/**
 * Immutable identity of the one event the confirmation dialog displayed. [revision] is the change
 * revision observed for the stable prepare read; [deletionCredential] binds the commit to the same
 * word generation as the detail that opened the flow.
 */
data class PreparedUndo(
    val entryKey: String,
    val eventId: String,
    val eventTime: Long,
    val revision: Long,
    val deletionCredential: Long
)

/** Outcome plus the exact prepared identity (non-null if and only if the outcome is PREPARED). */
data class UndoPreparation(val outcome: UndoPrepareOutcome, val prepared: PreparedUndo? = null) {
    init {
        require((outcome == UndoPrepareOutcome.PREPARED) == (prepared != null)) {
            "PREPARED 必须携带 exact 事件，其余结果不得携带"
        }
    }
}

/** A full overwrite is refused when the database changed since the pre-restore snapshot was taken. */
enum class RestoreOutcome { RESTORED, REVISION_CHANGED }

/**
 * Outcome of one study feedback submission. The card token is the retry key: the same token with the
 * same feedback is idempotent, the same token with a different feedback is rejected, a token that is
 * neither the current card nor an already-answered card (a discarded/old card) is rejected.
 */
enum class StudyCommitOutcome { COMMITTED, IDEMPOTENT, CONFLICT, STALE, REJECTED }

/** Raw two-table snapshot plus the committed revision it was read at; no derived cache. */
data class LearningBackupSnapshot(val data: LearningBackupData, val revision: Long)

/** Result of saving one user supplement; [changed] is false for an idempotent repeat save. */
data class UserEntrySaveResult(
    val saved: Boolean,
    val changed: Boolean,
    val entry: UserDictionaryEntry?,
    val message: String
)

/** What one book's study round is allowed to draw from: ordered members and a snapshot resolver. */
class StudyMembership(
    val book: StudyBook,
    val orderedKeys: List<String>,
    val snapshot: (String) -> DictionaryEntry?
)

/** Queries are serialized by ShiciApplication.worker; all mutations are transactional. */
class LearningRepository(context: Context, name: String = "learning.db") : SQLiteOpenHelper(context, name, null, 4) {
    val changes = LearningChanges()
    val deletions = DeletionGate()

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE words (
            entry_key TEXT PRIMARY KEY, headword TEXT NOT NULL, pos TEXT NOT NULL,
            meaning TEXT NOT NULL, source TEXT NOT NULL, version TEXT NOT NULL,
            mastered INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '',
            favorite INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0,1)))""")
        db.execSQL("""CREATE TABLE events (
            id TEXT PRIMARY KEY, entry_key TEXT NOT NULL REFERENCES words(entry_key),
            raw_text TEXT NOT NULL, final_form TEXT NOT NULL, time INTEGER NOT NULL,
            revoked INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX events_word_time ON events(entry_key, time)")
        createStudyTables(db)
        insertDefaultSettings(db)
        createUserDictionaryTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Non-destructive v1/v2/v3 -> v4. v1 gets the study tables/settings; v1/v2 then get the
        // independent user dictionary and the unified study scope; v1–v3 all gain the `favorite`
        // column with DEFAULT 0. Words, events, notes, mastery, study states/reviews, due dates,
        // feedback seq and the pending card snapshot are untouched; nothing is dropped or rebuilt.
        if (oldVersion !in 1..3 || newVersion != 4) {
            error("尚未提供从 $oldVersion 到 $newVersion 的迁移，保留原库而不删除数据")
        }
        if (oldVersion < 2) {
            createStudyTables(db)
            insertDefaultSettings(db)
        }
        if (oldVersion < 3) {
            createUserDictionaryTable(db)
            // A single unified scope is the only current setting; old book ids remain valid source
            // values on rows. The pending card keeps its token, reveal phase and snapshot, but joining
            // the unified round is recorded explicitly instead of pretending the old book still applies.
            db.execSQL("UPDATE study_settings SET selected_book=?", arrayOf(StudyBook.ALL.id))
            db.execSQL("UPDATE study_session SET book=?", arrayOf(StudyBook.ALL.id))
        }
        if (oldVersion < 4) {
            // ADD COLUMN only: every existing row keeps all prior fields and gets the non-favourite
            // default. The CHECK mirrors a freshly created schema-4 database.
            db.execSQL("ALTER TABLE words ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0,1))")
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("不支持数据库降级（$oldVersion → $newVersion）；回退请使用与版本匹配的备份，不要安装旧版直接读取新库")
    }

    private fun createStudyTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE study_settings (
            id INTEGER PRIMARY KEY CHECK (id = 0), selected_book TEXT NOT NULL,
            daily_new_goal INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE study_states (
            entry_key TEXT PRIMARY KEY REFERENCES words(entry_key), book TEXT NOT NULL,
            stage INTEGER NOT NULL, due_at INTEGER NOT NULL, introduced_at INTEGER NOT NULL,
            last_review_at INTEGER NOT NULL, last_review_seq INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX study_states_due ON study_states(due_at, entry_key)")
        db.execSQL("""CREATE TABLE study_reviews (
            token TEXT PRIMARY KEY, entry_key TEXT NOT NULL REFERENCES words(entry_key),
            feedback TEXT NOT NULL, stage_before INTEGER NOT NULL, stage_after INTEGER NOT NULL,
            due_after INTEGER NOT NULL, reviewed_at INTEGER NOT NULL, day_key TEXT NOT NULL,
            zone_id TEXT NOT NULL, new_word INTEGER NOT NULL, seq INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX study_reviews_day ON study_reviews(day_key, entry_key)")
        // No FK on the pending card: a brand-new card has no words row until its first real feedback.
        db.execSQL("""CREATE TABLE study_session (
            id INTEGER PRIMARY KEY CHECK (id = 0), token TEXT NOT NULL, entry_key TEXT NOT NULL,
            book TEXT NOT NULL, stage_before INTEGER NOT NULL, new_word INTEGER NOT NULL,
            answer_revealed INTEGER NOT NULL, headword TEXT NOT NULL, pos TEXT NOT NULL,
            meaning TEXT NOT NULL, source TEXT NOT NULL, version TEXT NOT NULL,
            day_key TEXT NOT NULL, created_at INTEGER NOT NULL)""")
    }

    private fun insertDefaultSettings(db: SQLiteDatabase) {
        db.insertWithOnConflict("study_settings", null, ContentValues().apply {
            put("id", 0); put("selected_book", StudyBook.default.id)
            put("daily_new_goal", StudyRules.DEFAULT_DAILY_NEW_GOAL)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * The user supplement dictionary is independent from `words`: a new headword may be saved with no
     * QueryEvent, no personal vocabulary row and no study state. No foreign key is declared on purpose.
     */
    private fun createUserDictionaryTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE user_entries (
            entry_key TEXT PRIMARY KEY, pos TEXT NOT NULL, meaning TEXT NOT NULL,
            updated_at INTEGER NOT NULL)""")
    }

    fun record(event: QueryEvent, entry: DictionaryEntry, replaces: String? = null,
               deletionCredential: Long = deletions.capture(),
               canCommit: () -> Boolean = { true }): Boolean {
        require(event.entryKey == entry.key && !event.revoked)
        // The display entry may already carry the marked user section. Never bake that derived text
        // into `words`: the independent user row remains the single source of user content.
        val stored = PersonalDictionary.storedEntry(entry)
        if (!canCommit()) return false
        if (deletions.isStale(entry.key, deletionCredential)) return false
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
                    // If the original event was already deleted, never insert a replacement event.
                    if (eventById(db, replaces) == null) return false
                    db.update("events", ContentValues().apply { put("revoked", 1) }, "id=?", arrayOf(replaces))
                }
                val dictionaryFields = ContentValues().apply {
                    put("headword", stored.headword); put("pos", stored.partOfSpeech); put("meaning", stored.meaning)
                    put("source", stored.source); put("version", stored.version)
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

    /**
     * Revokes the newest active event of [entryKey] only if this word was not deleted after
     * [deletionCredential] was taken. Returns STALE before resolving the latest event, so an old
     * confirm dialog can never revoke an event of a word that was deleted and re-collected.
     */
    fun revokeLatest(entryKey: String, deletionCredential: Long): RevokeOutcome {
        if (deletions.isStale(entryKey, deletionCredential)) return RevokeOutcome.STALE
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        db.beginTransaction()
        val revoked: Boolean
        try {
            val id = latestEventId(db, entryKey)
            revoked = id != null && db.update("events", ContentValues().apply { put("revoked", 1) },
                "id=? AND revoked=0", arrayOf(id)) > 0
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (revoked) changes.publishCommitted()
        return if (revoked) RevokeOutcome.REVOKED else RevokeOutcome.NONE
    }

    /**
     * Revokes one exact active event of [entryKey]. Unlike the raw [revoke], a deleted/replaced word
     * or an already-gone event is reported explicitly instead of being shown as a successful undo.
     */
    fun revokeEvent(id: String, entryKey: String, deletionCredential: Long): RevokeOutcome {
        if (deletions.isStale(entryKey, deletionCredential)) return RevokeOutcome.STALE
        var revoked = false
        change { db ->
            val affected = db.update("events", ContentValues().apply { put("revoked", 1) },
                "id=? AND entry_key=? AND revoked=0", arrayOf(id, entryKey))
            revoked = affected > 0
            affected
        }
        return if (revoked) RevokeOutcome.REVOKED else RevokeOutcome.NONE
    }

    /**
     * Prepares "撤销最近一次有效查询" as an exact, immutable target. The latest active event is read
     * once under the caller's original [deletionCredential]; the deletion generation and the change
     * revision are checked on both sides of that read, so the event the confirmation will display is
     * known not to have moved while it was read.
     *
     * The result distinguishes a deleted/re-collected word ([UndoPrepareOutcome.STALE]), a word with no
     * active event ([UndoPrepareOutcome.NONE]) and a database that changed during the read
     * ([UndoPrepareOutcome.CHANGED]) instead of silently substituting a different latest event.
     * This read never writes and never publishes.
     */
    fun prepareUndoLatest(entryKey: String, deletionCredential: Long): UndoPreparation {
        if (deletions.isStale(entryKey, deletionCredential)) return UndoPreparation(UndoPrepareOutcome.STALE)
        val db = readableDatabase
        check(!db.inTransaction()) { "只能读取已提交的撤销候选" }
        val revisionBefore = changes.version
        val exact = db.beginTransactionNonExclusive().let {
            try {
                val event = latestActiveEvent(db, entryKey)
                db.setTransactionSuccessful()
                event
            } finally { db.endTransaction() }
        }
        val revisionAfter = changes.version
        // Checked again after the read: a deletion that committed while the rows were read must never
        // present an event for a word that is already gone (or was re-collected under a new generation).
        if (deletions.isStale(entryKey, deletionCredential)) return UndoPreparation(UndoPrepareOutcome.STALE)
        if (revisionBefore != revisionAfter) return UndoPreparation(UndoPrepareOutcome.CHANGED)
        if (exact == null) return UndoPreparation(UndoPrepareOutcome.NONE)
        return UndoPreparation(UndoPrepareOutcome.PREPARED,
            PreparedUndo(entryKey, exact.id, exact.time, revisionAfter, deletionCredential))
    }

    /**
     * Commits exactly the event returned by [prepareUndoLatest]. The UPDATE is conditional on the shown
     * id, key and time plus `revoked=0`: "latest" is never resolved again, so an event collected while
     * the confirmation was open can never be the one this confirmation removes. An event that was
     * already revoked (or removed) returns [ExactUndoOutcome.NONE], never a false success.
     *
     * [deletionCredential] is re-checked before any write. [canCommit] and [admission] are evaluated
     * immediately before BEGIN, following the existing write semantics: once admitted, the transaction
     * finishes atomically even if the owner is invalidated afterwards. A word that is deleted after the
     * preparation but before this call is refused with [ExactUndoOutcome.STALE].
     */
    fun commitUndo(prepared: PreparedUndo, admission: StudyAdmission? = null,
                   canCommit: () -> Boolean = { true }): ExactUndoOutcome {
        if (deletions.isStale(prepared.entryKey, prepared.deletionCredential)) return ExactUndoOutcome.STALE
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        if (!canCommit()) return ExactUndoOutcome.REJECTED
        // Linearized immediately before BEGIN, exactly like change()/record()/commitStudyFeedback().
        if (!admit(admission)) return ExactUndoOutcome.REJECTED
        var revoked = false
        db.beginTransaction()
        try {
            revoked = db.update("events", ContentValues().apply { put("revoked", 1) },
                "id=? AND entry_key=? AND time=? AND revoked=0",
                arrayOf(prepared.eventId, prepared.entryKey, prepared.eventTime.toString())) > 0
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (revoked) changes.publishCommitted()
        return if (revoked) ExactUndoOutcome.REVOKED else ExactUndoOutcome.NONE
    }

    fun updateWord(key: String, mastered: Boolean, note: String,
                   deletionCredential: Long = deletions.capture()): Boolean {
        // A late detail save must never edit a word that was deleted after this detail was opened.
        if (deletions.isStale(key, deletionCredential)) return false
        val marked = if (mastered) 1 else 0
        change { db ->
            db.update("words", ContentValues().apply {
                put("mastered", marked); put("note", note)
            }, "entry_key=? AND (mastered<>? OR note<>?)", arrayOf(key, marked.toString(), note))
        }
        return true
    }

    /**
     * Saves only the personal fields the user actually edited in one open detail.
     *
     * [baselineMastered]/[baselineNote] are the last saved row values observed at the click, and
     * [mastered]/[note] are the control targets frozen at that same click. A field whose target
     * equals its baseline is not submitted at all, so an untouched field can never overwrite a newer
     * value (for example a note committed by another detail). For every edited field the current row
     * is read and compared inside the same transaction as the write: a value already equal to the
     * target is an idempotent success with no revision, a value different from both the baseline and
     * the target is a [WordDetailSaveOutcome.CONFLICT] and nothing is written, and only a value still
     * equal to the baseline is replaced. The checks are per key and per field, so unrelated words,
     * favorite changes and query-count changes never reject this save.
     *
     * [deletionCredential] is the detail's original credential and is never re-captured; a credential
     * that is already stale at entry returns [WordDetailSaveOutcome.STALE] without a transaction.
     * The same original credential is checked again inside the transaction, after the final owner
     * admission and immediately before the business UPDATE, so a delete/re-collect or full restore
     * that committed while `writableDatabase` was being acquired also returns
     * [WordDetailSaveOutcome.STALE] with no business write and no publish.
     *
     * Owner liveness is enforced at two points. The pre-transaction [canCommit]/[isActive] read is
     * only a cheap early rejection for an owner that is already gone. The authoritative final
     * admission — [canCommit] plus [StudyAdmission.admit] — runs inside the transaction, after the
     * current-row read and the conflict/idempotence decision. The original-credential re-check runs
     * after that final admission and immediately before the business UPDATE. A detail
     * closed/stopped/replaced while those reads were in progress therefore writes nothing and
     * publishes nothing; once admitted and still on the original credential, the transaction finishes
     * atomically.
     */
    fun saveWordDetail(key: String, mastered: Boolean, note: String,
                       baselineMastered: Boolean, baselineNote: String,
                       deletionCredential: Long,
                       admission: StudyAdmission? = null,
                       canCommit: () -> Boolean = { true }): WordDetailSaveOutcome {
        // A late detail save must never edit a word that was deleted after this detail was opened.
        if (deletions.isStale(key, deletionCredential)) return WordDetailSaveOutcome.STALE
        val editMastered = mastered != baselineMastered
        val editNote = note != baselineNote
        // Nothing the user edited: never touch (or conflict-check) an untouched field.
        if (!editMastered && !editNote) return WordDetailSaveOutcome.UNCHANGED
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        // Cheap early rejection only, not the admission point: the owner may still retire while the
        // transaction below reads, so the final check is repeated immediately before the UPDATE.
        if (!canCommit() || admission?.isActive == false) return WordDetailSaveOutcome.REJECTED
        var outcome: WordDetailSaveOutcome
        db.beginTransaction()
        try {
            val current = wordPersonalFields(db, key)
            if (current == null) {
                outcome = WordDetailSaveOutcome.STALE
            } else {
                val masteredConflict = editMastered &&
                    current.mastered != baselineMastered && current.mastered != mastered
                val noteConflict = editNote &&
                    current.note != baselineNote && current.note != note
                outcome = if (masteredConflict || noteConflict) {
                    // Never silently replace a value this click did not see; the caller keeps the draft.
                    WordDetailSaveOutcome.CONFLICT
                } else {
                    val writeMastered = editMastered && current.mastered != mastered
                    val writeNote = editNote && current.note != note
                    if (!writeMastered && !writeNote) {
                        WordDetailSaveOutcome.UNCHANGED
                    } else if (!canCommit() || !admit(admission)) {
                        // Final admission after every read needed to decide this write and before
                        // any business UPDATE: a retired owner writes and publishes nothing.
                        WordDetailSaveOutcome.REJECTED
                    } else if (deletions.isStale(key, deletionCredential)) {
                        // Re-check the detail's original credential after the final external gate
                        // and immediately before the business UPDATE. A real delete/re-collect or
                        // full restore that committed while writableDatabase was acquired must not
                        // be overwritten by this old detail; the credential is never re-captured.
                        WordDetailSaveOutcome.STALE
                    } else {
                        val values = ContentValues()
                        if (writeMastered) values.put("mastered", if (mastered) 1 else 0)
                        if (writeNote) values.put("note", note)
                        val affected = db.update("words", values, "entry_key=?", arrayOf(key))
                        // A row that disappeared between the in-transaction read and this UPDATE is
                        // not a success; report it stale so the UI freezes the old detail.
                        if (affected > 0) WordDetailSaveOutcome.SAVED else WordDetailSaveOutcome.STALE
                    }
                }
            }
            // A final-admission rejection never marks the transaction successful; nothing was
            // written, nothing is published, and the original detail state is left untouched.
            if (outcome != WordDetailSaveOutcome.REJECTED) db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (outcome == WordDetailSaveOutcome.SAVED) changes.publishCommitted()
        return outcome
    }

    /** The only two personal fields a detail save may touch; read inside that save's transaction. */
    private data class WordPersonalFields(val mastered: Boolean, val note: String)

    private fun wordPersonalFields(db: SQLiteDatabase, key: String): WordPersonalFields? =
        db.rawQuery("SELECT mastered, note FROM words WHERE entry_key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) WordPersonalFields(c.int("mastered") == 1, c.text("note")) else null
        }

    // ---- Personal vocabulary favourites (生词收藏星标) -------------------------------------------

    /**
     * Sets or clears one word's personal star. Favourites are a display/collection marker only: this
     * never writes an event, never changes [LearnedWord.mastered], the query count, notes, study
     * states/reviews, due dates or the daily goal, and it never collects a dictionary word that was
     * not already a learning word.
     *
     * Only a word that currently has at least one active (non-revoked) query can be starred: the
     * conditional UPDATE requires that active event to exist in the same statement, so a word whose
     * queries were all revoked (hidden from the vocabulary list) cannot be revived by starring it,
     * and a `user_entries`-only headword (no `words` row) is never collected. A stored marker on a
     * word that later loses all active queries stays raw in `words` but is not an active favourite;
     * a later real query shows it again exactly as it was left. Whole-word history deletion removes
     * the row (and the marker) entirely.
     *
     * [deletionCredential] must come from the snapshot the star was bound to; a word deleted or fully
     * restored since then is refused as stale. [admission] and [canCommit] are evaluated immediately
     * before BEGIN, like every other admitted write. Returns true if and only if a row actually
     * changed: an idempotent repeat, a rejection, an inactive word and a stale credential all return
     * false without publishing a change.
     */
    fun setFavorite(entryKey: String, favorite: Boolean, deletionCredential: Long,
                    admission: StudyAdmission? = null,
                    canCommit: () -> Boolean = { true }): Boolean {
        if (deletions.isStale(entryKey, deletionCredential)) return false
        val value = if (favorite) 1 else 0
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        if (!canCommit()) return false
        if (!admit(admission)) return false
        var changed = false
        db.beginTransaction()
        try {
            changed = db.update("words", ContentValues().apply { put("favorite", value) },
                """entry_key=? AND favorite<>? AND EXISTS (
                       SELECT 1 FROM events e WHERE e.entry_key=words.entry_key AND e.revoked=0)""",
                arrayOf(entryKey, value.toString())) > 0
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) changes.publishCommitted()
        return changed
    }

    /**
     * Keys of the words currently shown as favourited in the personal vocabulary list, in the same
     * recent-first order as [words]. A raw marker on a word with no active query is excluded, exactly
     * like that word is excluded from the list itself. Read-only; never changes statistics.
     */
    fun favoriteKeys(): List<String> = buildList {
        readableDatabase.rawQuery("""SELECT w.entry_key, MAX(e.time) AS last_at
            FROM words w JOIN events e ON e.entry_key=w.entry_key AND e.revoked=0
            WHERE w.favorite=1 GROUP BY w.entry_key ORDER BY last_at DESC, w.headword""", null).use { c ->
            while (c.moveToNext()) add(c.text("entry_key"))
        }
    }

    /** Number of active learning words shown as favourited (same predicate as [favoriteKeys]). */
    fun favoriteCount(): Int =
        readableDatabase.rawQuery("""SELECT COUNT(DISTINCT w.entry_key)
            FROM words w JOIN events e ON e.entry_key=w.entry_key AND e.revoked=0
            WHERE w.favorite=1""", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * Deletes one word's whole personal history: every event (revoked included), the words row with
     * its note/mastery, and this key's study state, feedback and any pending card, in a single
     * transaction by exact key. [deletionCredential] must come from the snapshot the caller's dialog
     * was bound to; a deletion request based on stale UI data (the word was deleted after that
     * snapshot) is rejected with STALE and never touches a record that was re-collected since.
     * NOT_FOUND means there was nothing to delete (safe no-op); DELETED means a word was actually
     * removed. Any failure rolls back fully, does not publish and does not invalidate in-flight
     * queries for this word.
     *
     * [admission] and [canCommit] are optional so existing callers keep the exact old two-argument
     * behavior (`null`/`{ true }` are always admitted). When supplied, they are evaluated immediately
     * before BEGIN, following the same semantics as every other admitted write; a refusal returns
     * [DeleteOutcome.REJECTED] without opening a transaction. Only this key's learning rows are
     * deleted, including its `favorite` marker because the whole `words` row goes away: the
     * independent `user_entries` row, the original dictionary asset, other words and exported PDFs
     * are untouched, exactly as before. A single revoke or a cleared star never uses this path and
     * therefore never removes learning data.
     */
    fun deleteWord(entryKey: String, deletionCredential: Long,
                   admission: StudyAdmission? = null,
                   canCommit: () -> Boolean = { true }): DeleteOutcome {
        if (deletions.isStale(entryKey, deletionCredential)) return DeleteOutcome.STALE
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        if (!canCommit()) return DeleteOutcome.REJECTED
        // Linearized immediately before BEGIN; an already-admitted delete may finish even if the owner
        // is invalidated afterwards. Only a refusal made before this point cancels the transaction.
        if (!admit(admission)) return DeleteOutcome.REJECTED
        db.beginTransaction()
        val deleted: Boolean
        try {
            db.delete("study_reviews", "entry_key=?", arrayOf(entryKey))
            db.delete("study_states", "entry_key=?", arrayOf(entryKey))
            db.delete("study_session", "entry_key=?", arrayOf(entryKey))
            db.delete("events", "entry_key=?", arrayOf(entryKey))
            deleted = db.delete("words", "entry_key=?", arrayOf(entryKey)) > 0
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (deleted) {
            deletions.recordDeletion(entryKey)
            changes.publishCommitted()
            return DeleteOutcome.DELETED
        }
        return DeleteOutcome.NOT_FOUND
    }

    /**
     * Reads every raw `words` row (including words whose only events are revoked) and every `events`
     * row (revoked included), plus study settings, study states, all real feedback and the pending
     * card, all in one read transaction. Never uses the active-events aggregate, so personal
     * notes/mastery, historical sources and revoked history are all preserved. Counts and dates are
     * re-aggregated later from these rows, never cached here.
     */
    fun backupSnapshot(): LearningBackupSnapshot {
        check(!readableDatabase.inTransaction()) { "只能读取已提交的学习备份快照" }
        val db = readableDatabase
        val revision = changes.version
        db.beginTransactionNonExclusive()
        try {
            val words = ArrayList<BackupWord>()
            db.rawQuery("SELECT entry_key, headword, pos, meaning, source, version, mastered, note, favorite FROM words ORDER BY entry_key", null).use { c ->
                while (c.moveToNext()) words += BackupWord(c.text("entry_key"), c.text("headword"), c.text("pos"),
                    c.text("meaning"), c.text("source"), c.text("version"), c.int("mastered") == 1, c.text("note"),
                    c.int("favorite") == 1)
            }
            val events = ArrayList<BackupEvent>()
            db.rawQuery("SELECT id, entry_key, raw_text, final_form, time, revoked FROM events ORDER BY time, id", null).use { c ->
                while (c.moveToNext()) events += BackupEvent(c.text("id"), c.text("entry_key"), c.text("raw_text"),
                    c.text("final_form"), c.long("time"), c.int("revoked") == 1)
            }
            val settings = readSettings(db)
            val states = ArrayList<BackupStudyState>()
            db.rawQuery("SELECT entry_key, book, stage, due_at, introduced_at, last_review_at, last_review_seq FROM study_states ORDER BY entry_key", null).use { c ->
                while (c.moveToNext()) states += BackupStudyState(c.text("entry_key"), c.text("book"), c.int("stage"),
                    c.long("due_at"), c.long("introduced_at"), c.long("last_review_at"), c.long("last_review_seq"))
            }
            val reviews = ArrayList<BackupStudyReview>()
            // Ordered by the durable commit sequence, not by wall clock: a legal clock rollback makes a
            // later feedback's reviewed_at smaller, so reviewed_at must never define the export order.
            db.rawQuery("""SELECT token, entry_key, feedback, stage_before, stage_after, due_after,
                reviewed_at, day_key, zone_id, new_word, seq FROM study_reviews ORDER BY seq, token""", null).use { c ->
                while (c.moveToNext()) reviews += BackupStudyReview(c.text("token"), c.text("entry_key"),
                    c.text("feedback"), c.int("stage_before"), c.int("stage_after"), c.long("due_after"),
                    c.long("reviewed_at"), c.text("day_key"), c.text("zone_id"), c.int("new_word") == 1,
                    c.long("seq"))
            }
            val session = readPending(db, mergeUser = false)?.let {
                BackupStudySession(it.token, it.entryKey, it.book.id, it.stageBefore, it.newWord, it.answerRevealed,
                    it.entry.headword, it.entry.partOfSpeech, it.entry.meaning, it.entry.source, it.entry.version,
                    it.dayKey, it.createdAt)
            }
            // Stored independently of `words`, so a new headword saved without any query event is still
            // part of the full v4 backup.
            val userEntries = readUserEntries(db).map {
                BackupUserEntry(it.entryKey, it.partOfSpeech, it.meaning, it.updatedAt)
            }
            db.setTransactionSuccessful()
            return LearningBackupSnapshot(
                LearningBackupData(words, events,
                    BackupStudySettings(settings.selectedBook.id, settings.dailyNewGoal),
                    states, reviews, session, userEntries), revision)
        } finally { db.endTransaction() }
    }

    /**
     * Replaces the whole learning database with [data] in one transaction: child rows first, then
     * [data.words] before every table that references it. Any failure rolls back completely: no
     * partial import, no success signal and no invalidation. [expectedRevision] is checked before the
     * transaction; if the database changed since the pre-restore snapshot was read the overwrite is
     * refused (no data written) so newer queries are never silently lost. Only after
     * `endTransaction` returns successfully does the global restore barrier advance and a single
     * change notification publish, invalidating every credential captured before the restore.
     */
    fun restoreBackup(data: LearningBackupData, expectedRevision: Long): RestoreOutcome {
        check(!writableDatabase.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        if (changes.version != expectedRevision) return RestoreOutcome.REVISION_CHANGED
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("study_reviews", null, null)
            db.delete("study_states", null, null)
            db.delete("study_session", null, null)
            db.delete("user_entries", null, null)
            db.delete("events", null, null)
            db.delete("words", null, null)
            data.words.forEach { word ->
                db.insertOrThrow("words", null, ContentValues().apply {
                    put("entry_key", word.entryKey); put("headword", word.headword); put("pos", word.partOfSpeech)
                    put("meaning", word.meaning); put("source", word.source); put("version", word.version)
                    put("mastered", if (word.mastered) 1 else 0); put("note", word.note)
                    // v1–v3 payloads decode every word with favorite=false, so restoring an old file
                    // intentionally clears the current local stars; a v4 file restores them exactly.
                    put("favorite", if (word.favorite) 1 else 0)
                })
            }
            data.userEntries.forEach { user ->
                PersonalDictionary.requireStoredKey(user.entryKey)
                require(PersonalDictionary.isValidStoredPos(user.partOfSpeech)) { "用户词性未规范化" }
                require(PersonalDictionary.isValidStoredMeaning(user.meaning)) { "用户释义未规范化" }
                db.insertOrThrow("user_entries", null, ContentValues().apply {
                    put("entry_key", user.entryKey); put("pos", user.partOfSpeech)
                    put("meaning", user.meaning); put("updated_at", user.updatedAt)
                })
            }
            data.events.forEach { event ->
                db.insertOrThrow("events", null, ContentValues().apply {
                    put("id", event.id); put("entry_key", event.entryKey); put("raw_text", event.rawText)
                    put("final_form", event.finalForm); put("time", event.time)
                    put("revoked", if (event.revoked) 1 else 0)
                })
            }
            db.insertWithOnConflict("study_settings", null, ContentValues().apply {
                put("id", 0)
                // Old v1/v2 backups may name one legacy book. Restoring keeps all study rows and due
                // dates but joins the current unified scope; no switch-book UI remains.
                put("selected_book", StudyBook.ALL.id)
                put("daily_new_goal", data.settings.dailyNewGoal)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            data.states.forEach { state ->
                db.insertOrThrow("study_states", null, ContentValues().apply {
                    put("entry_key", state.entryKey); put("book", state.book); put("stage", state.stage)
                    put("due_at", state.dueAt); put("introduced_at", state.introducedAt)
                    put("last_review_at", state.lastReviewAt); put("last_review_seq", state.lastReviewSeq)
                })
            }
            data.reviews.forEach { review ->
                db.insertOrThrow("study_reviews", null, ContentValues().apply {
                    put("token", review.token); put("entry_key", review.entryKey); put("feedback", review.feedback)
                    put("stage_before", review.stageBefore); put("stage_after", review.stageAfter)
                    put("due_after", review.dueAfter); put("reviewed_at", review.reviewedAt)
                    put("day_key", review.dayKey); put("zone_id", review.zoneId)
                    put("new_word", if (review.newWord) 1 else 0); put("seq", review.seq)
                })
            }
            data.session?.let { session ->
                db.insertOrThrow("study_session", null, ContentValues().apply {
                    put("id", 0); put("token", session.token); put("entry_key", session.entryKey)
                    // Token, reveal phase and the real snapshot are preserved; only the scope label
                    // joins the unified round, exactly like the v2->v3 migration.
                    put("book", StudyBook.ALL.id); put("stage_before", session.stageBefore)
                    put("new_word", if (session.newWord) 1 else 0)
                    put("answer_revealed", if (session.answerRevealed) 1 else 0)
                    put("headword", session.headword); put("pos", session.partOfSpeech)
                    put("meaning", session.meaning); put("source", session.source); put("version", session.version)
                    put("day_key", session.dayKey); put("created_at", session.createdAt)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        deletions.recordRestore()
        changes.publishCommitted()
        return RestoreOutcome.RESTORED
    }

    // ---- User supplement dictionary ---------------------------------------------------------------

    /** The current supplement for one normalized key, without merging it onto the original entry. */
    fun userEntry(entryKey: String): UserDictionaryEntry? =
        readUserEntry(readableDatabase, entryKey)

    /** All saved supplements, stable by key, including new headwords with no query/study record. */
    fun userEntries(): List<UserDictionaryEntry> = readUserEntries(readableDatabase)

    fun userEntryCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM user_entries", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * Creates or replaces exactly one user row. Saving is not a query and not a study event: no
     * event, query count, personal vocabulary row, state, feedback, note, mastery or due date is
     * written. An empty/invalid meaning creates nothing; an identical repeat save is an explicit
     * no-op. A real change clears only the pending card for this word so an older card cannot keep
     * showing/reveal-commit a stale meaning; committed progress and review history are untouched.
     */
    fun saveUserEntry(entryKey: String, partOfSpeech: String, meaning: String,
                      now: Long = System.currentTimeMillis(),
                      expectedUpdatedAt: Long? = null,
                      newHeadword: Boolean = false,
                      admission: StudyAdmission? = null): UserEntrySaveResult {
        val key = try { PersonalDictionary.requireStoredKey(entryKey) } catch (e: IllegalArgumentException) {
            return UserEntrySaveResult(false, false, null, "保存失败：${e.message ?: "词头无效"}")
        }
        if (newHeadword && PersonalDictionary.normalizeNewKey(key) != key) {
            return UserEntrySaveResult(false, false, null, "保存失败：新词头只支持单个英文单词，不能是短语或多词")
        }
        val pos = try { PersonalDictionary.sanitizePos(partOfSpeech) } catch (e: IllegalArgumentException) {
            return UserEntrySaveResult(false, false, null, "保存失败：${e.message ?: "词性无效"}")
        }
        val text = try { PersonalDictionary.sanitizeMeaning(meaning) } catch (e: IllegalArgumentException) {
            return UserEntrySaveResult(false, false, null, "保存失败：${e.message ?: "释义无效"}")
        }
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        if (!admit(admission)) return UserEntrySaveResult(false, false, null, "操作已失效，未保存")

        var changed = false
        var conflict = false
        var exhausted = false
        var saved: UserDictionaryEntry? = null
        var existingEntry: UserDictionaryEntry? = null
        try {
            db.beginTransaction()
            try {
                val existing = readUserEntry(db, key)
                existingEntry = existing
                // Two supplement editors for the same word: the older one must not overwrite the
                // newer saved meaning. 0 means "opened with no user row"; null means no expectation.
                if (expectedUpdatedAt != null &&
                    (existing?.updatedAt ?: 0L) != expectedUpdatedAt) {
                    conflict = true
                } else if (existing != null && existing.partOfSpeech == pos && existing.meaning == text) {
                    // A repeat of the current content keeps the row's existing conflict identity.
                    saved = existing
                } else {
                    val nextUpdatedAt = nextUserEntryConflictIdentity(existing?.updatedAt, now)
                    if (nextUpdatedAt == null) {
                        // The row identity is already Long.MAX_VALUE: a real change cannot move
                        // strictly forward without wrapping/reusing the old identity, so reject it
                        // before writing anything. The stored row, revision and notifications stay.
                        exhausted = true
                    } else {
                        val next = UserDictionaryEntry(key, pos, text, nextUpdatedAt)
                        db.insertWithOnConflict("user_entries", null, ContentValues().apply {
                            put("entry_key", next.entryKey); put("pos", next.partOfSpeech)
                            put("meaning", next.meaning); put("updated_at", next.updatedAt)
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                        // A card dealt before this supplement change must not be revealed/committed
                        // with the old snapshot. Removing just this key's pending card makes its
                        // token stale; the next view deals a fresh token while all real
                        // state/review rows stay intact.
                        db.delete("study_session", "id=0 AND entry_key=?", arrayOf(key))
                        saved = next
                        changed = true
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (e: Exception) {
            return UserEntrySaveResult(false, false, null, "保存失败：${e.message ?: "本地数据库错误"}")
        }
        if (conflict) {
            return UserEntrySaveResult(false, false, existingEntry, "词义已被更新，请重新打开后再保存")
        }
        if (exhausted) {
            return UserEntrySaveResult(false, false, existingEntry,
                "词义版本已无法推进，请重新打开后再保存")
        }
        if (changed) changes.publishCommitted()
        return UserEntrySaveResult(true, changed, saved,
            if (changed) "已保存到本机用户词库" else "词义未变化，未重复保存")
    }

    /**
     * Conflict identity stored in `user_entries.updated_at`. 0 is reserved for "the editor opened
     * with no user row", so a real row must always carry a non-zero identity. Every real content
     * change must move strictly past that row's previous identity: the wall clock alone is not
     * enough, because two saves can land in the same millisecond (or the clock can move backwards)
     * and then a stale editor's expectation would still match the newer row. A legitimate future
     * [now] wins; otherwise advance by one. Returns null only when the previous identity is already
     * [Long.MAX_VALUE] and cannot advance, in which case the caller must reject without writing.
     */
    private fun nextUserEntryConflictIdentity(previous: Long?, now: Long): Long? {
        if (previous == null) return if (now > 0L) now else 1L
        if (now > previous && now != 0L) return now
        if (previous == Long.MAX_VALUE) return null
        val next = previous + 1L
        return if (next != 0L) next else 1L
    }

    // ---- Study ------------------------------------------------------------------------------------

    fun studySettings(): StudySettings = readSettings(readableDatabase)

    /** Persists the selected book and daily new-word goal (clamped to 1–200). Not a destructive edit. */
    fun updateStudySettings(book: StudyBook, dailyNewGoal: Int,
                            admission: StudyAdmission? = null): Boolean {
        val goal = StudyRules.clampGoal(dailyNewGoal)
        var changed = false
        change(admission) { db ->
            changed = db.update("study_settings", ContentValues().apply {
                put("selected_book", book.id); put("daily_new_goal", goal)
            }, "id=0", null) > 0
            if (changed) 1 else 0
        }
        return changed
    }

    /** The personal book's members: the real active query set, in the vocabulary's recent-first order. */
    fun personalMembers(): List<String> = words().map { it.entry.key }

    /**
     * Read-only study statistic: the entry keys that already have a persisted study state. It is used
     * to describe how many personal words are still unlearned for the visible plan and to keep those
     * learned keys out of the plan's new-word streams; it is bounded by the number of introduced
     * words and writes nothing. [studyView] remains the only place that caps and deals cards.
     */
    fun studiedKeys(): Set<String> =
        readableDatabase.rawQuery("SELECT entry_key FROM study_states", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.text("entry_key")) }
        }

    /**
     * Read-only count of new words really introduced in [now]'s local day bucket. It reuses the same
     * [StudyRules.dayKey] bucket and the same `countIntroduced` statement that [studyView] and the
     * feedback commit already use, so the plan's personal/original-book rotation parity is driven by
     * committed `new_word` feedback rows, never by a UI-side counter. This writes no row, deals no
     * card and publishes no change; existing study/backup/delete statements are untouched.
     */
    fun introducedNewCount(now: Long, zone: ZoneId): Int =
        countIntroduced(readableDatabase, StudyRules.dayKey(now, zone))

    /**
     * Reads the current study round for [plan] and, when no card is pending for today and this book,
     * deals exactly one card (persisted with its immutable snapshot). Dealing is not a committed
     * user-data change, so it publishes nothing; only a real feedback commit publishes. Due words come
     * first (stable by due time then key), then new words in the order the caller's [plan] supplies,
     * capped by the remaining daily new-word quota. The UI passes the daily mixed order, so this method
     * itself never chooses between the personal and original-book streams. A card only due in ten
     * minutes is not dealt now and does not block new words.
     */
    fun studyView(plan: StudyMembership, now: Long, zone: ZoneId,
                  admission: StudyAdmission? = null): StudyView {
        check(!readableDatabase.inTransaction()) { "只能读取已提交的学习视图" }
        val db = writableDatabase
        val today = StudyRules.dayKey(now, zone)
        val memberSet = plan.orderedKeys.toHashSet()
        // The unified scope keeps every real existing state in its due pool, even a word that no
        // longer appears in the new-candidate order. Only new candidates come from plan.orderedKeys.
        val allScope = plan.book == StudyBook.ALL

        val reads = db.beginTransactionNonExclusive().let {
            try {
                val settings = readSettings(db)
                val states = readStates(db)
                val existing = readPending(db)
                val introducedToday = countIntroduced(db, today)
                val todayReviewed = countDistinctReviewed(db, today)
                val todayAnswers = countAnswers(db, today)
                db.setTransactionSuccessful()
                StudyRead(settings, states, existing, introducedToday, todayReviewed, todayAnswers)
            } finally { db.endTransaction() }
        }

        val due = reads.states.values.filter {
            (allScope || it.entryKey in memberSet) && it.dueAt <= now
        }
        var card = reads.existing
        // A pending card is only valid for today, for the selected scope, and while its word is still
        // a member (or, in the unified scope, already has a real state that is due/reviewable): a
        // personal word removed from the live vocabulary set must not stay pending. A pending *new*
        // word is also over quota once today's introductions have reached (or passed) a since-lowered
        // goal: the commit path already refuses it, so the round is replanned away instead of staying
        // stuck on a card that can never be answered. That replan only clears the pending card (and
        // then re-deals the true due review, if any) — it never touches committed states or reviews.
        val replan = card != null &&
            (card.dayKey != today || card.book != plan.book ||
                !(card.entryKey in memberSet || (allScope && card.entryKey in reads.states)) ||
                (card.newWord && reads.introducedToday >= reads.settings.dailyNewGoal))
        if (replan) {
            clearPending(db, admission)
            card = null
        }
        val quota = maxOf(0, reads.settings.dailyNewGoal - reads.introducedToday)
        val newCandidates = plan.orderedKeys.filter { it !in reads.states }
        if (card == null) {
            val candidate = due.minWithOrNull(compareBy<StudyState> { it.dueAt }.thenBy { it.entryKey })?.entryKey
                // A new word is dealt only while today's new-word quota is not exhausted; an exhausted
                // quota must not silently hand out one more new card.
                ?: if (quota > 0) newCandidates.firstOrNull() else null
            if (candidate != null) {
                val state = reads.states[candidate]
                val entry = displayEntry(db, candidate, plan)
                if (entry != null) {
                    val newWord = state == null
                    val dealt = StudyCard(UUID.randomUUID().toString(), candidate, plan.book,
                        if (newWord) StudyRules.NEW_STAGE_BEFORE else state!!.stage,
                        newWord, false, entry, today, now, noteOf(db, candidate))
                    // The real admission for dealing is right before this insert's transaction: after
                    // every read and member load above, an invalidated surface must not persist a card,
                    // while a card already admitted is allowed to finish.
                    if (insertPending(db, dealt, admission)) card = dealt
                }
            }
            val dueNow = due.size
            val nextDueAt = reads.states.values.filter {
                (allScope || it.entryKey in memberSet) && it.dueAt > now
            }.minOfOrNull { it.dueAt }
            return StudyView(reads.settings, card, StudyProgress(
                dueNow = dueNow,
                newRemaining = minOf(quota, newCandidates.size),
                introducedToday = reads.introducedToday,
                todayReviewed = reads.todayReviewed,
                todayAnswers = reads.todayAnswers,
                nextDueAt = nextDueAt,
                totalIntroduced = reads.states.size), deletions.capture())
        }

        val dueNow = due.size
        val nextDueAt = reads.states.values.filter {
            (allScope || it.entryKey in memberSet) && it.dueAt > now
        }.minOfOrNull { it.dueAt }
        return StudyView(reads.settings, card, StudyProgress(
            dueNow = dueNow,
            newRemaining = minOf(quota, newCandidates.size),
            introducedToday = reads.introducedToday,
            todayReviewed = reads.todayReviewed,
            todayAnswers = reads.todayAnswers,
            nextDueAt = nextDueAt,
            totalIntroduced = reads.states.size), deletions.capture())
    }

    /** Persists the answer-revealed phase of the current card so rotation/restart keeps the same card. */
    fun revealStudyCard(token: String, deletionCredential: Long = deletions.capture(),
                        admission: StudyAdmission? = null): Boolean {
        val pending = readPending(readableDatabase)
        if (pending == null || pending.token != token) return false
        if (deletions.isStale(pending.entryKey, deletionCredential)) return false
        return change(admission) { db ->
            db.update("study_session", ContentValues().apply { put("answer_revealed", 1) },
                "id=0 AND token=? AND answer_revealed=0", arrayOf(token))
        } > 0
    }

    /**
     * Commits one real feedback for the current card. The token is the idempotency key: retrying the
     * same token with the same feedback advances nothing, a different feedback for the same token is
     * rejected, and a token that is neither the pending card nor an already-answered one is rejected.
     *
     * Before anything is written the pending card is re-admitted against the current state: it must be
     * the answer-revealed phase, for the selected book, still inside today's fixed day bucket, and (for
     * a new word) still inside today's remaining new-word quota, and (for an existing word) still at the
     * exact stage it was dealt at, and (for 个人生词) still an active personal member. Any mismatch is
     * rejected without writing, so a card left over from another day, another book, a lowered goal, a
     * revoked personal word or a stale snapshot can never be committed. The word snapshot, study state,
     * feedback row, and pending-card removal all commit in one transaction; only then does a single
     * change publish. A stale credential (word deleted, or a full restore committed, after the card was
     * dealt) is rejected before any write.
     *
     * [canCommit] is the caller's world gate and [admission] is the owner's cross-thread gate. Both are
     * taken together at the true linearization point: after every read that shapes the verdict (the
     * pending card, feedback lookup, settings, personal members, study state and durable sequence) and
     * immediately before the first business write, inside the commit transaction. The earlier
     * `canCommit` call stays only as a cheap rejection after the first reads; it is not the barrier.
     * A committed change that lands during the later long reads (for example a supplement save that
     * rewrites a distractor meaning while this card is still pending) refuses at the final point with
     * no row written, no pending card cleared and no change published. An invalidation that happens
     * first likewise refuses there; an admission that happens first lets this atomic commit finish
     * even if the owner is destroyed afterwards.
     */
    fun commitStudyFeedback(token: String, feedback: FeedbackKind, now: Long, zone: ZoneId,
                            deletionCredential: Long = deletions.capture(),
                            admission: StudyAdmission? = null,
                            canCommit: () -> Boolean = { true }): StudyCommitOutcome {
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        val pending = readPending(db)
        val existing = reviewByToken(db, token)
        val knownKey = when {
            pending != null && pending.token == token -> pending.entryKey
            existing != null -> existing.entryKey
            else -> null
        } ?: return StudyCommitOutcome.REJECTED
        if (deletions.isStale(knownKey, deletionCredential)) return StudyCommitOutcome.STALE
        if (existing != null) {
            return if (existing.feedback == feedback) StudyCommitOutcome.IDEMPOTENT else StudyCommitOutcome.CONFLICT
        }
        if (pending == null || pending.token != token) return StudyCommitOutcome.REJECTED
        // Cheap early rejection only: the final `canCommit` is re-taken inside the transaction after
        // every read that shapes the verdict. This first check cannot close the read-to-write window.
        if (!canCommit()) return StudyCommitOutcome.REJECTED
        if (!admitsFeedback(db, pending, now, zone)) return StudyCommitOutcome.REJECTED

        var committed = false
        db.beginTransaction()
        try {
            val current = readPending(db)
            if (current == null || current.token != token) {
                db.setTransactionSuccessful()
            } else {
                val state = stateByKey(db, current.entryKey)
                val newWord = state == null
                val stageBefore = if (newWord) StudyRules.NEW_STAGE_BEFORE else state!!.stage
                val stageAfter = StudyRules.nextStage(stageBefore, feedback)
                val dueAfter = StudyRules.dueAt(now, stageBefore, feedback)
                // The durable commit sequence is taken inside the transaction, so it is the true order of
                // real commits even when two feedbacks share the same wall-clock millisecond.
                val seq = nextReviewSeq(db)
                // Final admission: after every read that decides this verdict (pending card, study
                // state, durable sequence) and immediately before the first business write. The early
                // `canCommit` above is only a fast rejection; a committed change can land during the
                // reads in between (for example a supplement save rewriting a distractor while this
                // card is still pending). Refusing here writes no row, does not mark the transaction
                // successful, publishes nothing and leaves the pending card untouched. `admit` is
                // linearized at this same point: invalidation first refuses, admission first lets the
                // atomic commit finish even if the owner is destroyed afterwards.
                if (canCommit() && admit(admission)) {
                    if (newWord) {
                        // The pending card may display a merged supplement; persist only the original or
                        // user-only snapshot. The user row stays the single source and is merged on read.
                        val stored = PersonalDictionary.storedEntry(current.entry)
                        db.insertWithOnConflict("words", null, ContentValues().apply {
                            put("entry_key", current.entryKey); put("headword", stored.headword)
                            put("pos", stored.partOfSpeech); put("meaning", stored.meaning)
                            put("source", stored.source); put("version", stored.version)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    db.insertWithOnConflict("study_states", null, ContentValues().apply {
                        put("entry_key", current.entryKey); put("book", current.book.id); put("stage", stageAfter)
                        put("due_at", dueAfter); put("introduced_at", state?.introducedAt ?: now)
                        put("last_review_at", now); put("last_review_seq", seq)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                    db.insertOrThrow("study_reviews", null, ContentValues().apply {
                        put("token", token); put("entry_key", current.entryKey); put("feedback", feedback.id)
                        put("stage_before", stageBefore); put("stage_after", stageAfter); put("due_after", dueAfter)
                        put("reviewed_at", now); put("day_key", StudyRules.dayKey(now, zone))
                        put("zone_id", zone.id); put("new_word", if (newWord) 1 else 0); put("seq", seq)
                    })
                    db.delete("study_session", "id=0", null)
                    db.setTransactionSuccessful()
                    committed = true
                }
            }
        } finally { db.endTransaction() }
        if (committed) changes.publishCommitted()
        return if (committed) StudyCommitOutcome.COMMITTED else StudyCommitOutcome.REJECTED
    }

    /**
     * Every condition the pending card must still satisfy at commit time. Checked immediately before the
     * commit transaction starts; each mismatch returns false so the card is rejected (never written).
     */
    private fun admitsFeedback(db: SQLiteDatabase, card: StudyCard, now: Long, zone: ZoneId): Boolean {
        if (!card.answerRevealed) return false
        val settings = readSettings(db)
        if (settings.selectedBook != card.book) return false
        val today = StudyRules.dayKey(now, zone)
        if (today != card.dayKey) return false
        val state = stateByKey(db, card.entryKey)
        if (card.newWord) {
            // A brand-new word must still be unintroduced and inside today's remaining new-word quota.
            if (state != null) return false
            if (countIntroduced(db, today) >= settings.dailyNewGoal) return false
        } else {
            // A review card is only valid for the exact stage the word is currently persisted at.
            if (state == null || state.stage != card.stageBefore) return false
        }
        // 个人生词 membership is a live set; a card for a word that is no longer an active member is stale.
        if (card.book == StudyBook.PERSONAL && card.entryKey !in personalMembers()) return false
        return true
    }

    private data class StudyRead(
        val settings: StudySettings,
        val states: Map<String, StudyState>,
        val existing: StudyCard?,
        val introducedToday: Int,
        val todayReviewed: Int,
        val todayAnswers: Int
    )

    private fun readSettings(db: SQLiteDatabase): StudySettings =
        db.rawQuery("SELECT selected_book, daily_new_goal FROM study_settings WHERE id=0", null).use { c ->
            if (c.moveToFirst()) StudySettings(StudyBook.fromId(c.text("selected_book")) ?: StudyBook.default,
                StudyRules.clampGoal(c.int("daily_new_goal"))) else StudySettings()
        }

    private fun readStates(db: SQLiteDatabase): Map<String, StudyState> = buildMap {
        db.rawQuery("SELECT entry_key, book, stage, due_at, introduced_at, last_review_at, last_review_seq FROM study_states", null).use { c ->
            while (c.moveToNext()) put(c.text("entry_key"), StudyState(c.text("entry_key"),
                StudyBook.fromId(c.text("book")) ?: StudyBook.default, c.int("stage"), c.long("due_at"),
                c.long("introduced_at"), c.long("last_review_at"), c.long("last_review_seq")))
        }
    }

    private fun stateByKey(db: SQLiteDatabase, key: String): StudyState? =
        db.rawQuery("SELECT entry_key, book, stage, due_at, introduced_at, last_review_at, last_review_seq FROM study_states WHERE entry_key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) StudyState(c.text("entry_key"), StudyBook.fromId(c.text("book")) ?: StudyBook.default,
                c.int("stage"), c.long("due_at"), c.long("introduced_at"), c.long("last_review_at"),
                c.long("last_review_seq")) else null
        }

    private fun readPending(db: SQLiteDatabase, mergeUser: Boolean = true): StudyCard? {
        // The personal note is not part of the immutable card snapshot: it is read live from `words`
        // (empty for a brand-new card that has no words row yet) so a note saved after dealing is shown.
        val base = db.rawQuery("""SELECT s.token, s.entry_key, s.book, s.stage_before, s.new_word, s.answer_revealed,
            s.headword, s.pos, s.meaning, s.source, s.version, s.day_key, s.created_at, w.note AS note
            FROM study_session s LEFT JOIN words w ON w.entry_key = s.entry_key WHERE s.id=0""", null).use { c ->
            if (c.moveToFirst()) StudyCard(c.text("token"), c.text("entry_key"),
                StudyBook.fromId(c.text("book")) ?: StudyBook.default, c.int("stage_before"),
                c.int("new_word") == 1, c.int("answer_revealed") == 1,
                DictionaryEntry(c.text("headword"), c.text("pos"), c.text("meaning"), c.text("source"), c.text("version")),
                c.text("day_key"), c.long("created_at"), c.textOrEmpty("note")) else null
        }
        if (base == null || !mergeUser) return base
        // Display-only refresh: the token, stage, day, reveal phase, note and every committed row are
        // unchanged. Backup export reads mergeUser=false and therefore keeps the original snapshot.
        val user = readUserEntry(db, base.entryKey) ?: return base
        return base.copy(entry = PersonalDictionary.merge(base.entry, user) ?: base.entry)
    }

    /** The word's current personal note, or empty when there is no words row (a brand-new card). */
    private fun noteOf(db: SQLiteDatabase, key: String): String =
        db.rawQuery("SELECT note FROM words WHERE entry_key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.textOrEmpty("note") else ""
        }

    private fun insertPending(db: SQLiteDatabase, card: StudyCard,
                              admission: StudyAdmission? = null): Boolean {
        // Admission is linearized here, immediately before BEGIN: an invalidated surface writes nothing.
        if (!admit(admission)) return false
        // The card shown by studyView may be a merged display (original + current user section). Store
        // only the untouched original/user-only snapshot, exactly like record(); re-reading, backup and
        // first feedback then merge the separate user row once instead of treating derived text as raw.
        val stored = PersonalDictionary.storedEntry(card.entry)
        db.beginTransaction()
        try {
            db.delete("study_session", "id=0", null)
            db.insertOrThrow("study_session", null, ContentValues().apply {
                put("id", 0); put("token", card.token); put("entry_key", card.entryKey)
                put("book", card.book.id); put("stage_before", card.stageBefore)
                put("new_word", if (card.newWord) 1 else 0)
                put("answer_revealed", if (card.answerRevealed) 1 else 0)
                put("headword", stored.headword); put("pos", stored.partOfSpeech)
                put("meaning", stored.meaning); put("source", stored.source)
                put("version", stored.version); put("day_key", card.dayKey)
                put("created_at", card.createdAt)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    private fun clearPending(db: SQLiteDatabase, admission: StudyAdmission? = null) =
        change(admission) { it.delete("study_session", "id=0", null) }

    /** True when this write is admitted: no handle (direct repository callers) is always admitted. */
    private fun admit(admission: StudyAdmission?): Boolean = admission == null || admission.admit()

    /** Durable, strictly increasing commit sequence for the next real feedback, taken inside its own tx. */
    private fun nextReviewSeq(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM study_reviews", null).use { c ->
            val max = if (c.moveToFirst()) c.getLong(0) else 0L
            // Never reuse a sequence: a restored row may legally be the current maximum. Refusing the
            // (practically unreachable) exhausted range is safer than silently duplicating the last seq.
            check(max < Long.MAX_VALUE) { "学习反馈提交序号已用尽" }
            max + 1
        }

    private fun wordEntry(db: SQLiteDatabase, key: String): DictionaryEntry? =
        db.rawQuery("SELECT headword, pos, meaning, source, version FROM words WHERE entry_key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) DictionaryEntry(c.text("headword"), c.text("pos"), c.text("meaning"),
                c.text("source"), c.text("version")) else null
        }

    /** Raw saved original snapshot for [key], without the user section; unified resolution merges it. */
    fun storedEntry(key: String): DictionaryEntry? =
        wordEntry(readableDatabase, key)?.let { PersonalDictionary.storedEntry(it) }

    /**
     * The entry shown/dealt for study: the saved original snapshot (which preserves historical source
     * labels) or the plan's resolver, followed by the *current* user supplement. This is why an older
     * word snapshot can never swallow a newer supplement edit.
     */
    private fun displayEntry(db: SQLiteDatabase, key: String, plan: StudyMembership): DictionaryEntry? =
        PersonalDictionary.merge(plan.snapshot(key) ?: wordEntry(db, key), readUserEntry(db, key))

    private fun readUserEntry(db: SQLiteDatabase, key: String): UserDictionaryEntry? =
        db.rawQuery("SELECT entry_key, pos, meaning, updated_at FROM user_entries WHERE entry_key=?",
            arrayOf(key)).use { c ->
            if (c.moveToFirst()) UserDictionaryEntry(c.text("entry_key"), c.text("pos"),
                c.text("meaning"), c.long("updated_at")) else null
        }

    private fun readUserEntries(db: SQLiteDatabase): List<UserDictionaryEntry> = buildList {
        db.rawQuery("SELECT entry_key, pos, meaning, updated_at FROM user_entries ORDER BY entry_key", null).use { c ->
            while (c.moveToNext()) add(UserDictionaryEntry(c.text("entry_key"), c.text("pos"),
                c.text("meaning"), c.long("updated_at")))
        }
    }

    private fun reviewByToken(db: SQLiteDatabase, token: String): StudyReview? =
        db.rawQuery("""SELECT token, entry_key, feedback, stage_before, stage_after, due_after, reviewed_at,
            day_key, zone_id, new_word, seq FROM study_reviews WHERE token=?""", arrayOf(token)).use { c ->
            if (c.moveToFirst()) StudyReview(c.text("token"), c.text("entry_key"),
                FeedbackKind.fromId(c.text("feedback")) ?: FeedbackKind.KNOW, c.int("stage_before"),
                c.int("stage_after"), c.long("due_after"), c.long("reviewed_at"), c.text("day_key"),
                c.text("zone_id"), c.int("new_word") == 1, c.long("seq")) else null
        }

    private fun countIntroduced(db: SQLiteDatabase, dayKey: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM study_reviews WHERE new_word=1 AND day_key=?", arrayOf(dayKey)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun countDistinctReviewed(db: SQLiteDatabase, dayKey: String): Int =
        db.rawQuery("SELECT COUNT(DISTINCT entry_key) FROM study_reviews WHERE day_key=?", arrayOf(dayKey)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun countAnswers(db: SQLiteDatabase, dayKey: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM study_reviews WHERE day_key=?", arrayOf(dayKey)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    private fun change(admission: StudyAdmission? = null, write: (SQLiteDatabase) -> Int): Int {
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
        // The cross-thread linearization for every study write: an invalidation that happened first
        // makes this return without a transaction at all; an admission that happened first lets the
        // write finish atomically even if the owner is destroyed afterwards.
        if (!admit(admission)) return 0
        db.beginTransaction()
        val affected: Int
        try {
            affected = write(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (affected > 0) changes.publishCommitted()
        return affected
    }

    fun snapshot(date: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): LearningSnapshot {
        val db = readableDatabase
        check(!db.inTransaction()) { "只能读取已提交的学习快照" }
        // Taken before the rows are read: if a word present here is deleted afterwards, the snapshot's
        // credential for that word becomes stale, so UI bound to this data can never edit a re-collected row.
        val credential = deletions.capture()
        db.beginTransactionNonExclusive()
        try {
            val words = words()
            val stats = LearningRules.summarize(words, events(), date, zone)
            db.setTransactionSuccessful()
            return LearningSnapshot(words, stats, changes.version, credential)
        } finally { db.endTransaction() }
    }

    fun exportSnapshot(search: String, date: LocalDate = LocalDate.now()): LearningExportSnapshot {
        check(!readableDatabase.inTransaction()) { "只能导出已提交的学习快照" }
        return LearningExportSnapshot(words(search), search, date)
    }

    fun words(search: String = ""): List<LearnedWord> {
        val words = mutableListOf<LearnedWord>()
        // Display-only merge: the `words` row keeps the original snapshot and the user row stays the
        // single source of supplement content. Personal fields, counts and source labels are unchanged.
        val users = readUserEntries(readableDatabase).associateBy { it.entryKey }
        readableDatabase.rawQuery("""SELECT w.*, COUNT(e.id) AS query_count,
            MIN(e.time) AS first_at, MAX(e.time) AS last_at,
            (SELECT e2.final_form FROM events e2 WHERE e2.entry_key=w.entry_key AND e2.revoked=0
             ORDER BY e2.time DESC, e2.id DESC LIMIT 1) AS last_form
            FROM words w JOIN events e ON e.entry_key=w.entry_key AND e.revoked=0
            GROUP BY w.entry_key ORDER BY last_at DESC, w.headword""", null).use { c ->
            while (c.moveToNext()) {
                val key = c.text("entry_key")
                val original = DictionaryEntry(c.text("headword"), c.text("pos"), c.text("meaning"),
                    c.text("source"), c.text("version"))
                val word = LearnedWord(
                    PersonalDictionary.merge(original, users[key]) ?: original,
                    c.text("source"), c.text("version"), c.text("last_form"), c.int("query_count"),
                    c.long("first_at"), c.long("last_at"), c.int("mastered") == 1, c.text("note"),
                    c.int("favorite") == 1
                )
                if (search.isBlank() || listOf(word.entry.headword, word.entry.meaning, word.lastForm, word.note)
                        .any { it.contains(search.trim(), ignoreCase = true) }) words += word
            }
        }
        return words
    }

    fun latestEventId(key: String): String? = latestEventId(readableDatabase, key)

    private fun latestEventId(db: SQLiteDatabase, key: String): String? = db.rawQuery(
        "SELECT id FROM events WHERE entry_key=? AND revoked=0 ORDER BY time DESC, id DESC LIMIT 1", arrayOf(key)
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** The exact id/time identity of the current latest active event, using the same ordering as [latestEventId]. */
    private data class ActiveEventIdentity(val id: String, val time: Long)

    private fun latestActiveEvent(db: SQLiteDatabase, key: String): ActiveEventIdentity? = db.rawQuery(
        "SELECT id, time FROM events WHERE entry_key=? AND revoked=0 ORDER BY time DESC, id DESC LIMIT 1",
        arrayOf(key)
    ).use { c -> if (c.moveToFirst()) ActiveEventIdentity(c.text("id"), c.long("time")) else null }

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
    /** Like [text], but a NULL column (e.g. the LEFT JOIN note of a card with no words row) reads as "". */
    private fun Cursor.textOrEmpty(key: String) = getString(getColumnIndexOrThrow(key)) ?: ""
    private fun Cursor.int(key: String) = getInt(getColumnIndexOrThrow(key))
    private fun Cursor.long(key: String) = getLong(getColumnIndexOrThrow(key))
}
