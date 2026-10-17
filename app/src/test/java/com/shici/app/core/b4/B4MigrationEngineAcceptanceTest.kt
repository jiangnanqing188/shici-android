package com.shici.app.core.b4
import com.shici.app.core.b4.host.*
import org.junit.Assert.*
import org.junit.Test
/** Exact v1 CREATE statements, actual production onUpgrade, stdlib SQLite engine via RPC.
 * Android SQLiteOpenHelper's framework transaction is explicitly modelled, not actually run. */
class B4MigrationEngineAcceptanceTest {
 private fun v1(db:SQLiteDatabase) {
  db.execSQL("""CREATE TABLE words (
            entry_key TEXT PRIMARY KEY, headword TEXT NOT NULL, pos TEXT NOT NULL,
            meaning TEXT NOT NULL, source TEXT NOT NULL, version TEXT NOT NULL,
            mastered INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '')""")
  db.execSQL("""CREATE TABLE events (
            id TEXT PRIMARY KEY, entry_key TEXT NOT NULL REFERENCES words(entry_key),
            raw_text TEXT NOT NULL, final_form TEXT NOT NULL, time INTEGER NOT NULL,
            revoked INTEGER NOT NULL DEFAULT 0)""")
  db.execSQL("CREATE INDEX events_word_time ON events(entry_key, time)")
  db.execSQL("INSERT INTO words VALUES ('a','a','n','old meaning','old-source','v0',1,'note😀')")
  db.execSQL("INSERT INTO events VALUES ('e','a','raw','a',123,1)")
 }
 private fun rows(db:SQLiteDatabase,t:String)=db.rawQuery("SELECT * FROM $t",null).use{it.rows}
 private fun schema(db:SQLiteDatabase)=db.rawQuery("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name",null).use{it.rows}
 @Test fun v1ToV2PreservesEveryOldColumnAndEmptyStudy(){LearningRepository(Context(),"unused").use{r->SQLiteDatabase().use{db->v1(db);val w=rows(db,"words");val e=rows(db,"events");db.beginTransaction();try{r.onUpgrade(db,1,2);db.setTransactionSuccessful()}finally{db.endTransaction()};assertEquals(w,rows(db,"words"));assertEquals(e,rows(db,"events"));assertEquals(emptyList<Any>(),rows(db,"study_states"));assertEquals(emptyList<Any>(),rows(db,"study_reviews"));assertEquals(listOf(listOf("0","ky","20")),rows(db,"study_settings"))}}}
 @Test fun onCreateAndUpgradeProduceIdenticalSchema(){LearningRepository(Context(),"unused").use{r->SQLiteDatabase().use{db->v1(db);r.onUpgrade(db,1,2);assertEquals(schema(r.writableDatabase),schema(db))}}}
 @Test fun migrationDdlFailureActuallyRollsBack(){LearningRepository(Context(),"unused").use{r->SQLiteDatabase().use{db->v1(db);val old=schema(db);val rows=rows(db,"words");db.failAt="CREATE INDEX study_states_due";db.beginTransaction();try{try{r.onUpgrade(db,1,2);fail("injected expected")}catch(e:IllegalStateException){}}finally{db.endTransaction()};assertEquals(old,schema(db));assertEquals(rows,rows(db,"words"))}}}
 @Test fun unknownMigrationAndDowngradeRefuseWithoutDestroying(){LearningRepository(Context(),"unused").use{r->SQLiteDatabase().use{db->v1(db);val old=schema(db);try{r.onUpgrade(db,1,3);fail()}catch(e:IllegalStateException){};try{r.onDowngrade(db,2,1);fail()}catch(e:IllegalStateException){};assertEquals(old,schema(db));assertEquals(1,rows(db,"words").size)}}}
}
