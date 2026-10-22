package com.shici.app.core.u1.host
import com.shici.app.core.*
import com.shici.app.core.b4.host.*
abstract class SchemaBase {abstract fun onCreate(db:SQLiteDatabase)}
/** Original schema2 DDL verbatim from verified pre-U1 source; factory only, not a product replacement. */
class LegacyV2SchemaFactory:SchemaBase(){
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
}
