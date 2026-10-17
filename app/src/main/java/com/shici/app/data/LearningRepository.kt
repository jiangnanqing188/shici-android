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

/** Explicit three-state results so callers never report a stale/no-op revoke or delete as success. */
enum class RevokeOutcome { REVOKED, NONE, STALE }
enum class DeleteOutcome { DELETED, NOT_FOUND, STALE }

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

/** What one book's study round is allowed to draw from: ordered members and a snapshot resolver. */
class StudyMembership(
    val book: StudyBook,
    val orderedKeys: List<String>,
    val snapshot: (String) -> DictionaryEntry?
)

/** Queries are serialized by ShiciApplication.worker; all mutations are transactional. */
class LearningRepository(context: Context, name: String = "learning.db") : SQLiteOpenHelper(context, name, null, 2) {
    val changes = LearningChanges()
    val deletions = DeletionGate()

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
        createStudyTables(db)
        insertDefaultSettings(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2 only adds the study tables and the default settings row; the original words/events
        // rows, notes, mastery, sources and revoked history are left exactly as they are.
        if (oldVersion == 1 && newVersion == 2) { createStudyTables(db); insertDefaultSettings(db); return }
        error("尚未提供从 $oldVersion 到 $newVersion 的迁移，保留原库而不删除数据")
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

    fun record(event: QueryEvent, entry: DictionaryEntry, replaces: String? = null,
               deletionCredential: Long = deletions.capture(),
               canCommit: () -> Boolean = { true }): Boolean {
        require(event.entryKey == entry.key && !event.revoked)
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
     * Deletes one word's whole personal history: every event (revoked included), the words row with
     * its note/mastery, and this key's study state, feedback and any pending card, in a single
     * transaction by exact key. [deletionCredential] must come from the snapshot the caller's dialog
     * was bound to; a deletion request based on stale UI data (the word was deleted after that
     * snapshot) is rejected with STALE and never touches a record that was re-collected since.
     * NOT_FOUND means there was nothing to delete (safe no-op); DELETED means a word was actually
     * removed. Any failure rolls back fully, does not publish and does not invalidate in-flight
     * queries for this word.
     */
    fun deleteWord(entryKey: String, deletionCredential: Long): DeleteOutcome {
        if (deletions.isStale(entryKey, deletionCredential)) return DeleteOutcome.STALE
        val db = writableDatabase
        check(!db.inTransaction()) { "学习变更不能嵌套在未提交事务中" }
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
            db.rawQuery("SELECT entry_key, headword, pos, meaning, source, version, mastered, note FROM words ORDER BY entry_key", null).use { c ->
                while (c.moveToNext()) words += BackupWord(c.text("entry_key"), c.text("headword"), c.text("pos"),
                    c.text("meaning"), c.text("source"), c.text("version"), c.int("mastered") == 1, c.text("note"))
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
            val session = readPending(db)?.let {
                BackupStudySession(it.token, it.entryKey, it.book.id, it.stageBefore, it.newWord, it.answerRevealed,
                    it.entry.headword, it.entry.partOfSpeech, it.entry.meaning, it.entry.source, it.entry.version,
                    it.dayKey, it.createdAt)
            }
            db.setTransactionSuccessful()
            return LearningBackupSnapshot(
                LearningBackupData(words, events,
                    BackupStudySettings(settings.selectedBook.id, settings.dailyNewGoal),
                    states, reviews, session), revision)
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
            db.delete("events", null, null)
            db.delete("words", null, null)
            data.words.forEach { word ->
                db.insertOrThrow("words", null, ContentValues().apply {
                    put("entry_key", word.entryKey); put("headword", word.headword); put("pos", word.partOfSpeech)
                    put("meaning", word.meaning); put("source", word.source); put("version", word.version)
                    put("mastered", if (word.mastered) 1 else 0); put("note", word.note)
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
                put("id", 0); put("selected_book", data.settings.selectedBook)
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
                    put("book", session.book); put("stage_before", session.stageBefore)
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
     * Reads the current study round for [plan] and, when no card is pending for today and this book,
     * deals exactly one card (persisted with its immutable snapshot). Dealing is not a committed
     * user-data change, so it publishes nothing; only a real feedback commit publishes. Due words come
     * first (stable by due time then key), then new words in fixed book order, capped by the remaining
     * daily new-word quota. A card only due in ten minutes is not dealt now and does not block new words.
     */
    fun studyView(plan: StudyMembership, now: Long, zone: ZoneId,
                  admission: StudyAdmission? = null): StudyView {
        check(!readableDatabase.inTransaction()) { "只能读取已提交的学习视图" }
        val db = writableDatabase
        val today = StudyRules.dayKey(now, zone)
        val memberSet = plan.orderedKeys.toHashSet()

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

        val due = reads.states.values.filter { it.entryKey in memberSet && it.dueAt <= now }
        var card = reads.existing
        // A pending card is only valid for today, for the selected book, and while its word is still a
        // member of this book: a personal word removed from the live vocabulary set must not stay pending.
        // A pending *new* word is also over quota once today's introductions have reached (or passed) a
        // since-lowered goal: the commit path already refuses it, so the round is replanned away instead
        // of staying stuck on a card that can never be answered. That replan only clears the pending card
        // (and then re-deals the true due review, if any) — it never touches committed states or reviews.
        val replan = card != null &&
            (card.dayKey != today || card.book != plan.book || card.entryKey !in memberSet ||
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
                val entry = wordEntry(db, candidate) ?: plan.snapshot(candidate)
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
            val nextDueAt = reads.states.values.filter { it.entryKey in memberSet && it.dueAt > now }.minOfOrNull { it.dueAt }
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
        val nextDueAt = reads.states.values.filter { it.entryKey in memberSet && it.dueAt > now }.minOfOrNull { it.dueAt }
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
        if (!canCommit()) return StudyCommitOutcome.REJECTED
        if (!admitsFeedback(db, pending, now, zone)) return StudyCommitOutcome.REJECTED
        // The real admission, taken only now — after every long read (pending card, feedback lookup,
        // settings, personal-member load). An owner invalidated before this point must not write;
        // once this returns true a later destroy can no longer cancel the already-admitted transaction,
        // which is allowed to finish atomically.
        if (!admit(admission)) return StudyCommitOutcome.REJECTED

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
                if (newWord) {
                    db.insertWithOnConflict("words", null, ContentValues().apply {
                        put("entry_key", current.entryKey); put("headword", current.entry.headword)
                        put("pos", current.entry.partOfSpeech); put("meaning", current.entry.meaning)
                        put("source", current.entry.source); put("version", current.entry.version)
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

    private fun readPending(db: SQLiteDatabase): StudyCard? =
        // The personal note is not part of the immutable card snapshot: it is read live from `words`
        // (empty for a brand-new card that has no words row yet) so a note saved after dealing is shown.
        db.rawQuery("""SELECT s.token, s.entry_key, s.book, s.stage_before, s.new_word, s.answer_revealed,
            s.headword, s.pos, s.meaning, s.source, s.version, s.day_key, s.created_at, w.note AS note
            FROM study_session s LEFT JOIN words w ON w.entry_key = s.entry_key WHERE s.id=0""", null).use { c ->
            if (c.moveToFirst()) StudyCard(c.text("token"), c.text("entry_key"),
                StudyBook.fromId(c.text("book")) ?: StudyBook.default, c.int("stage_before"),
                c.int("new_word") == 1, c.int("answer_revealed") == 1,
                DictionaryEntry(c.text("headword"), c.text("pos"), c.text("meaning"), c.text("source"), c.text("version")),
                c.text("day_key"), c.long("created_at"), c.textOrEmpty("note")) else null
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
        db.beginTransaction()
        try {
            db.delete("study_session", "id=0", null)
            db.insertOrThrow("study_session", null, ContentValues().apply {
                put("id", 0); put("token", card.token); put("entry_key", card.entryKey)
                put("book", card.book.id); put("stage_before", card.stageBefore)
                put("new_word", if (card.newWord) 1 else 0)
                put("answer_revealed", if (card.answerRevealed) 1 else 0)
                put("headword", card.entry.headword); put("pos", card.entry.partOfSpeech)
                put("meaning", card.entry.meaning); put("source", card.entry.source)
                put("version", card.entry.version); put("day_key", card.dayKey)
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

    fun latestEventId(key: String): String? = latestEventId(readableDatabase, key)

    private fun latestEventId(db: SQLiteDatabase, key: String): String? = db.rawQuery(
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
    /** Like [text], but a NULL column (e.g. the LEFT JOIN note of a card with no words row) reads as "". */
    private fun Cursor.textOrEmpty(key: String) = getString(getColumnIndexOrThrow(key)) ?: ""
    private fun Cursor.int(key: String) = getInt(getColumnIndexOrThrow(key))
    private fun Cursor.long(key: String) = getLong(getColumnIndexOrThrow(key))
}
