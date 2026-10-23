package com.shici.app.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/** Raised for any malformed, truncated, oversized, future-version or inconsistent backup file. */
class BackupFormatException(message: String) : Exception(message)

/**
 * One raw `words` row; no derived counts or cache. `entryKey` equals `headword` in the real model.
 * [favorite] is the personal vocabulary star marker. It is raw persisted state, not mastery and not
 * a query count; a v1/v2/v3 file has no such column and decodes to `false`.
 */
data class BackupWord(
    val entryKey: String,
    val headword: String,
    val partOfSpeech: String,
    val meaning: String,
    val source: String,
    val version: String,
    val mastered: Boolean,
    val note: String,
    val favorite: Boolean = false
)

/** One raw `events` row, revoked events included. */
data class BackupEvent(
    val id: String,
    val entryKey: String,
    val rawText: String,
    val finalForm: String,
    val time: Long,
    val revoked: Boolean
)

/** Study settings; defaults reproduce a fresh v2 database and the v1 migration. */
data class BackupStudySettings(
    val selectedBook: String = StudyBook.default.id,
    val dailyNewGoal: Int = StudyRules.DEFAULT_DAILY_NEW_GOAL
)

/**
 * One `study_states` row (shared study progress for one entry key). [lastReviewSeq] is the commit
 * sequence of the feedback that produced this exact stage/due/last-review, so the true final commit
 * can be identified without assuming the wall clock is monotonic.
 */
data class BackupStudyState(
    val entryKey: String,
    val book: String,
    val stage: Int,
    val dueAt: Long,
    val introducedAt: Long,
    val lastReviewAt: Long,
    val lastReviewSeq: Long = 0
)

/**
 * One `study_reviews` row: a real feedback answer, never a QueryEvent. [seq] is a durable, strictly
 * increasing commit sequence assigned by the repository when the feedback commits; it is the stable
 * order of real commits for a word (never the wall clock, token text or list position).
 */
data class BackupStudyReview(
    val token: String,
    val entryKey: String,
    val feedback: String,
    val stageBefore: Int,
    val stageAfter: Int,
    val dueAfter: Long,
    val reviewedAt: Long,
    val dayKey: String,
    val zoneId: String,
    val newWord: Boolean,
    val seq: Long = 0
)

/** The single persisted pending card, including its immutable word snapshot and reveal phase. */
data class BackupStudySession(
    val token: String,
    val entryKey: String,
    val book: String,
    val stageBefore: Int,
    val newWord: Boolean,
    val answerRevealed: Boolean,
    val headword: String,
    val partOfSpeech: String,
    val meaning: String,
    val source: String,
    val version: String,
    val dayKey: String,
    val createdAt: Long
)

/**
 * One `user_entries` row: a locally saved supplement, independent from `words`, query events and the
 * study ledger. A new headword may have this row and no other record at all.
 */
data class BackupUserEntry(
    val entryKey: String,
    val partOfSpeech: String,
    val meaning: String,
    val updatedAt: Long
)

/**
 * Full raw payload across every persisted table; counts/dates are re-aggregated from these rows,
 * never cached here. The study fields default to an empty v2 database so a v1 file (or a synthetic
 * two-table payload) decodes to an empty review set with default settings. [userEntries] defaults to
 * empty so v1/v2 files remain readable; only a v3+ file carries the personal supplement dictionary.
 * Word favourites are carried per word inside [words] (format v4+; older files decode to `false`).
 */
data class LearningBackupData(
    val words: List<BackupWord>,
    val events: List<BackupEvent>,
    val settings: BackupStudySettings = BackupStudySettings(),
    val states: List<BackupStudyState> = emptyList(),
    val reviews: List<BackupStudyReview> = emptyList(),
    val session: BackupStudySession? = null,
    val userEntries: List<BackupUserEntry> = emptyList()
)

/** A validated backup file: its identity metadata plus the frozen raw tables. */
data class LearningBackup(
    val appVersionName: String,
    val appVersionCode: Int,
    val schemaVersion: Int,
    val createdAt: Long,
    val data: LearningBackupData,
    val formatVersion: Int = LearningBackupCodec.FORMAT_VERSION
) {
    val wordCount get() = data.words.size
    val eventCount get() = data.events.size
    val stateCount get() = data.states.size
    val reviewCount get() = data.reviews.size
    val userEntryCount get() = data.userEntries.size
    /**
     * Favourites that will actually be visible in the vocabulary list after this restore: a star on
     * a word whose queries are all revoked is still persisted raw, but the list only shows words with
     * at least one active query, so it is not counted as an active favourite here.
     */
    val favoriteCount: Int
        get() {
            val activeKeys = data.events.asSequence().filterNot { it.revoked }.map { it.entryKey }.toHashSet()
            return data.words.count { it.favorite && it.entryKey in activeKeys }
        }
    /** A v1/schema1 file carries no study data; the preview must say so explicitly. */
    val legacyNoStudyData: Boolean get() = formatVersion < LearningBackupCodec.STUDY_FORMAT_VERSION
    /** A v1/v2 file carries no personal supplement dictionary; restoring it clears local supplements. */
    val noPersonalDictionary: Boolean get() = formatVersion < LearningBackupCodec.USER_DICTIONARY_FORMAT_VERSION
    /** True when the file carries the v4 `words.favorite` column (it may still contain zero stars). */
    val hasFavorites: Boolean get() = formatVersion >= LearningBackupCodec.FAVORITE_FORMAT_VERSION
    /** A v1/v2/v3 file has no favourite column at all; restoring it clears the current local stars. */
    val noFavorites: Boolean get() = !hasFavorites
}

/**
 * Versioned, length-prefixed local binary backup (`.shicibackup`). Pure Kotlin/JDK so the codec can
 * be exercised without Android; see README "本地备份格式" for the byte layout. The file is an
 * unencrypted, tamper-evident (SHA-256 only) personal-data file, not a secure vault.
 *
 * Format v2 adds study settings/states/reviews and the pending card after the v1 two tables. Format
 * v3 appends the independent user supplement dictionary. Format v4 appends one favourite boolean to
 * every word row and raises the database schema to 4. v1/v2/v3 files remain readable; v1 decodes to
 * default settings plus an empty review set, and v1/v2 decode with an empty personal dictionary.
 * A v1/v2/v3 word decodes with `favorite=false`, so restoring such a file clears local favourites.
 */
object LearningBackupCodec {
    const val MAGIC = "SHICIBKP"
    const val FORMAT_VERSION = 4
    /** First format that carries the independent user supplement dictionary (v3, schema 3). */
    const val USER_DICTIONARY_FORMAT_VERSION = 3
    /** First format that carries the `words.favorite` star marker (v4, schema 4). */
    const val FAVORITE_FORMAT_VERSION = 4
    const val STUDY_FORMAT_VERSION = 2
    const val LEGACY_FORMAT_VERSION = 1
    const val FILE_SUFFIX = ".shicibackup"
    const val MIME_TYPE = "application/octet-stream"
    const val APP_IDENTITY = "com.shici.app"
    const val DB_SCHEMA_VERSION = 4
    const val USER_DICTIONARY_DB_SCHEMA_VERSION = 3
    const val STUDY_DB_SCHEMA_VERSION = 2
    const val LEGACY_DB_SCHEMA_VERSION = 1
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_BYTES_LABEL = "64MiB"

    private const val DIGEST_BYTES = 32

    /** Encodes [data] with app identity/version/schema, creation time and a trailing SHA-256. */
    fun encode(data: LearningBackupData, appVersionName: String, appVersionCode: Int, createdAt: Long): ByteArray {
        val writer = BackupWriter(MAX_FILE_BYTES)
        writer.writeAscii(MAGIC)
        writer.writeInt(FORMAT_VERSION)
        writer.writeString(APP_IDENTITY)
        writer.writeString(appVersionName)
        writer.writeInt(appVersionCode)
        writer.writeInt(DB_SCHEMA_VERSION)
        writer.writeLong(createdAt)
        writer.writeInt(data.words.size)
        writer.writeInt(data.events.size)
        data.words.forEach { word ->
            writer.writeString(word.entryKey); writer.writeString(word.headword); writer.writeString(word.partOfSpeech)
            writer.writeString(word.meaning); writer.writeString(word.source); writer.writeString(word.version)
            writer.writeBoolean(word.mastered); writer.writeString(word.note); writer.writeBoolean(word.favorite)
        }
        data.events.forEach { event ->
            writer.writeString(event.id); writer.writeString(event.entryKey); writer.writeString(event.rawText)
            writer.writeString(event.finalForm); writer.writeLong(event.time); writer.writeBoolean(event.revoked)
        }
        writer.writeString(data.settings.selectedBook)
        writer.writeInt(data.settings.dailyNewGoal)
        writer.writeInt(data.states.size)
        data.states.forEach { state ->
            writer.writeString(state.entryKey); writer.writeString(state.book); writer.writeInt(state.stage)
            writer.writeLong(state.dueAt); writer.writeLong(state.introducedAt); writer.writeLong(state.lastReviewAt)
            writer.writeLong(state.lastReviewSeq)
        }
        writer.writeInt(data.reviews.size)
        data.reviews.forEach { review ->
            writer.writeString(review.token); writer.writeString(review.entryKey); writer.writeString(review.feedback)
            writer.writeInt(review.stageBefore); writer.writeInt(review.stageAfter); writer.writeLong(review.dueAfter)
            writer.writeLong(review.reviewedAt); writer.writeString(review.dayKey); writer.writeString(review.zoneId)
            writer.writeBoolean(review.newWord); writer.writeLong(review.seq)
        }
        val session = data.session
        writer.writeBoolean(session != null)
        session?.let {
            writer.writeString(it.token); writer.writeString(it.entryKey); writer.writeString(it.book)
            writer.writeInt(it.stageBefore); writer.writeBoolean(it.newWord); writer.writeBoolean(it.answerRevealed)
            writer.writeString(it.headword); writer.writeString(it.partOfSpeech); writer.writeString(it.meaning)
            writer.writeString(it.source); writer.writeString(it.version); writer.writeString(it.dayKey)
            writer.writeLong(it.createdAt)
        }
        writer.writeInt(data.userEntries.size)
        data.userEntries.forEach { user ->
            writer.writeString(user.entryKey); writer.writeString(user.partOfSpeech)
            writer.writeString(user.meaning); writer.writeLong(user.updatedAt)
        }
        val body = writer.toByteArray()
        if (body.size.toLong() + DIGEST_BYTES > MAX_FILE_BYTES) throw BackupFormatException("备份文件超过 $MAX_BYTES_LABEL 上限，未写出或读取")
        return body + sha256(body)
    }

    /**
     * Fully validates [source] (size, checksum, magic, versions, every length/UTF-8/boolean, duplicate
     * keys, orphan events and trailing bytes) and returns the frozen payload. A version newer than
     * [FORMAT_VERSION] is rejected rather than guessed as the current format; v1 files are read into
     * default settings with an empty review set.
     */
    fun decode(source: ByteArray): LearningBackup {
        if (source.size.toLong() > MAX_FILE_BYTES) throw BackupFormatException("备份文件超过 $MAX_BYTES_LABEL 上限，未写出或读取")
        if (source.size <= DIGEST_BYTES) throw BackupFormatException("备份文件过小或不完整")
        val body = source.copyOfRange(0, source.size - DIGEST_BYTES)
        val digest = source.copyOfRange(source.size - DIGEST_BYTES, source.size)
        if (!sha256(body).contentEquals(digest)) throw BackupFormatException("备份校验和失败，文件可能损坏或被修改")

        val reader = BackupReader(body)
        if (reader.readAscii(8) != MAGIC) throw BackupFormatException("不是拾词备份文件（magic 不匹配）")
        val format = reader.readInt()
        if (format > FORMAT_VERSION) {
            throw BackupFormatException("备份格式版本 $format 高于当前支持的 $FORMAT_VERSION，请先升级应用")
        }
        if (format < LEGACY_FORMAT_VERSION) throw BackupFormatException("备份格式版本 $format 不受支持")
        // v1 has no study section; v1/v2 predate the independent personal supplement dictionary; v1–v3
        // predate the favourite column. Each boundary is a fixed format constant, never "current
        // FORMAT_VERSION", so raising the current format can never make a v3 file lose its dictionary.
        val hasStudy = format >= STUDY_FORMAT_VERSION
        val hasUserDictionary = format >= USER_DICTIONARY_FORMAT_VERSION
        val hasFavorites = format >= FAVORITE_FORMAT_VERSION
        val identity = reader.readString("应用标识")
        if (identity != APP_IDENTITY) throw BackupFormatException("备份来自其他应用（$identity），已拒绝恢复")
        val appVersionName = reader.readString("应用版本名")
        val appVersionCode = reader.readInt()
        val schema = reader.readInt()
        val expectedSchema = when (format) {
            LEGACY_FORMAT_VERSION -> LEGACY_DB_SCHEMA_VERSION
            STUDY_FORMAT_VERSION -> STUDY_DB_SCHEMA_VERSION
            USER_DICTIONARY_FORMAT_VERSION -> USER_DICTIONARY_DB_SCHEMA_VERSION
            FORMAT_VERSION -> DB_SCHEMA_VERSION
            else -> throw BackupFormatException("备份格式版本 $format 不受支持")
        }
        if (schema != expectedSchema) {
            throw BackupFormatException("备份数据库版本 $schema 与当前 $expectedSchema 不一致，已拒绝恢复")
        }
        val createdAt = reader.readLong()
        val wordCount = reader.readInt()
        val eventCount = reader.readInt()
        if (wordCount < 0 || eventCount < 0) throw BackupFormatException("备份记录数量无效")

        val words = ArrayList<BackupWord>()
        val keys = HashSet<String>()
        repeat(wordCount) {
            val entryKey = reader.readString("词条键")
            val headword = reader.readString("词头")
            val partOfSpeech = reader.readString("词性")
            val meaning = reader.readString("释义")
            val source = reader.readString("来源")
            val version = reader.readString("来源版本")
            val mastered = reader.readBoolean("掌握状态")
            val note = reader.readString("笔记")
            // v4 appends the star marker after the note; v1–v3 have no such byte and default to false.
            val favorite = if (hasFavorites) reader.readBoolean("收藏状态") else false
            if (entryKey.isEmpty() || headword.isEmpty()) throw BackupFormatException("备份存在空词条键或空词头")
            if (entryKey != headword) throw BackupFormatException("备份词条键与词头不一致（$entryKey / $headword）")
            if (!keys.add(entryKey)) throw BackupFormatException("备份存在重复词条键：$entryKey")
            words += BackupWord(entryKey, headword, partOfSpeech, meaning, source, version, mastered, note, favorite)
        }

        val events = ArrayList<BackupEvent>()
        val ids = HashSet<String>()
        repeat(eventCount) {
            val id = reader.readString("事件 id")
            val entryKey = reader.readString("事件词条键")
            val rawText = reader.readString("原词形文本")
            val finalForm = reader.readString("最终词形")
            val time = reader.readLong()
            val revoked = reader.readBoolean("撤销状态")
            if (id.isEmpty()) throw BackupFormatException("备份存在空事件 id")
            if (!ids.add(id)) throw BackupFormatException("备份存在重复事件 id：$id")
            if (entryKey !in keys) throw BackupFormatException("备份存在无对应词条的事件（$entryKey）")
            events += BackupEvent(id, entryKey, rawText, finalForm, time, revoked)
        }

        if (!hasStudy) {
            if (reader.remaining != 0) throw BackupFormatException("备份在声明内容之后存在多余字节")
            return LearningBackup(appVersionName, appVersionCode, schema, createdAt,
                LearningBackupData(words, events), format)
        }

        val settings = BackupStudySettings(
            selectedBook = reader.readString("所选词书").also {
                if (StudyBook.fromId(it) == null) throw BackupFormatException("备份所选词书无效：$it")
            },
            dailyNewGoal = reader.readInt()
        )
        if (settings.dailyNewGoal < StudyRules.MIN_DAILY_NEW_GOAL || settings.dailyNewGoal > StudyRules.MAX_DAILY_NEW_GOAL) {
            throw BackupFormatException("备份每日目标超出 ${StudyRules.MIN_DAILY_NEW_GOAL}–${StudyRules.MAX_DAILY_NEW_GOAL}")
        }

        val states = ArrayList<BackupStudyState>()
        val stateKeys = HashSet<String>()
        repeat(reader.readInt().also { if (it < 0) throw BackupFormatException("学习状态数量无效") }) {
            val entryKey = reader.readString("学习状态词条键")
            val book = reader.readString("学习状态书种")
            val stage = reader.readInt()
            val dueAt = reader.readLong()
            val introducedAt = reader.readLong()
            val lastReviewAt = reader.readLong()
            val lastReviewSeq = reader.readLong()
            if (StudyBook.fromId(book) == null) throw BackupFormatException("学习状态书种无效：$book")
            if (stage < StudyRules.NEW_STAGE_BEFORE || stage > StudyRules.MAX_STAGE) throw BackupFormatException("学习状态阶段越界：$stage")
            if (entryKey !in keys) throw BackupFormatException("学习状态无对应词条：$entryKey")
            if (!stateKeys.add(entryKey)) throw BackupFormatException("学习状态重复词条键：$entryKey")
            states += BackupStudyState(entryKey, book, stage, dueAt, introducedAt, lastReviewAt, lastReviewSeq)
        }

        val reviews = ArrayList<BackupStudyReview>()
        val tokens = HashSet<String>()
        repeat(reader.readInt().also { if (it < 0) throw BackupFormatException("学习反馈数量无效") }) {
            val token = reader.readString("反馈 token")
            val entryKey = reader.readString("反馈词条键")
            val feedback = reader.readString("反馈类型")
            val stageBefore = reader.readInt()
            val stageAfter = reader.readInt()
            val dueAfter = reader.readLong()
            val reviewedAt = reader.readLong()
            val dayKey = reader.readString("反馈日键")
            val zoneId = reader.readString("反馈时区")
            val newWord = reader.readBoolean("反馈新词标记")
            val seq = reader.readLong()
            if (token.isEmpty()) throw BackupFormatException("备份存在空反馈 token")
            if (!tokens.add(token)) throw BackupFormatException("备份存在重复反馈 token：$token")
            if (entryKey !in keys) throw BackupFormatException("反馈无对应词条：$entryKey")
            if (FeedbackKind.fromId(feedback) == null) throw BackupFormatException("反馈类型无效：$feedback")
            if (stageBefore < StudyRules.NEW_STAGE_BEFORE || stageBefore > StudyRules.MAX_STAGE ||
                stageAfter < StudyRules.NEW_STAGE_BEFORE || stageAfter > StudyRules.MAX_STAGE) throw BackupFormatException("反馈阶段越界")
            // A first real feedback always starts before stage 0; a later review may also start at
            // -1 when the word was reset to relearning by 忘记, so only the new-word flag is implied.
            if (newWord && stageBefore != StudyRules.NEW_STAGE_BEFORE) throw BackupFormatException("反馈新词标记与阶段不一致：$token")
            if (dayKey.isEmpty() || zoneId.isEmpty()) throw BackupFormatException("反馈日键或时区为空")
            reviews += BackupStudyReview(token, entryKey, feedback, stageBefore, stageAfter, dueAfter, reviewedAt, dayKey, zoneId, newWord, seq)
        }

        val session = if (reader.readBoolean("待答卡存在标记")) {
            BackupStudySession(
                token = reader.readString("待答卡 token"),
                entryKey = reader.readString("待答卡词条键"),
                book = reader.readString("待答卡书种"),
                stageBefore = reader.readInt(),
                newWord = reader.readBoolean("待答卡新词标记"),
                answerRevealed = reader.readBoolean("待答卡显答案标记"),
                headword = reader.readString("待答卡词头"),
                partOfSpeech = reader.readString("待答卡词性"),
                meaning = reader.readString("待答卡释义"),
                source = reader.readString("待答卡来源"),
                version = reader.readString("待答卡来源版本"),
                dayKey = reader.readString("待答卡日键"),
                createdAt = reader.readLong()
            ).also {
                if (it.token.isEmpty() || it.entryKey.isEmpty() || it.headword.isEmpty()) throw BackupFormatException("待答卡缺少 token 或词头")
                if (StudyBook.fromId(it.book) == null) throw BackupFormatException("待答卡书种无效：${it.book}")
                if (it.stageBefore < StudyRules.NEW_STAGE_BEFORE || it.stageBefore > StudyRules.MAX_STAGE) throw BackupFormatException("待答卡阶段越界")
                if (it.newWord && it.stageBefore != StudyRules.NEW_STAGE_BEFORE) throw BackupFormatException("待答卡新词标记与阶段不一致")
                if (it.dayKey.isEmpty()) throw BackupFormatException("待答卡日键为空")
                if (!tokens.add(it.token)) throw BackupFormatException("待答卡 token 与反馈 token 冲突：${it.token}")
            }
        } else null

        validateStudyCrossRecords(states, reviews, session)

        val userEntries = ArrayList<BackupUserEntry>()
        if (hasUserDictionary) {
            val count = reader.readInt()
            if (count < 0) throw BackupFormatException("用户词典数量无效")
            val userKeys = HashSet<String>()
            repeat(count) {
                val entryKey = reader.readString("用户词条键")
                val partOfSpeech = reader.readString("用户词性")
                val meaning = reader.readString("用户释义")
                val updatedAt = reader.readLong()
                try {
                    PersonalDictionary.requireStoredKey(entryKey)
                    if (!PersonalDictionary.isValidStoredPos(partOfSpeech)) {
                        throw BackupFormatException("用户词性未规范化：$entryKey")
                    }
                    if (!PersonalDictionary.isValidStoredMeaning(meaning)) {
                        throw BackupFormatException("用户释义未规范化：$entryKey")
                    }
                } catch (e: IllegalArgumentException) {
                    throw BackupFormatException("用户词条无效：${e.message ?: entryKey}")
                }
                if (!userKeys.add(entryKey)) throw BackupFormatException("用户词典存在重复词条键：$entryKey")
                userEntries += BackupUserEntry(entryKey, partOfSpeech, meaning, updatedAt)
            }
        }

        if (reader.remaining != 0) throw BackupFormatException("备份在声明内容之后存在多余字节")
        return LearningBackup(appVersionName, appVersionCode, schema, createdAt,
            LearningBackupData(words, events, settings, states, reviews, session, userEntries), format)
    }

    /**
     * Re-derives every recorded study row with [StudyRules] instead of trusting the file: each feedback
     * must be the exact transition and due time the rules compute for its own (stageBefore, feedback),
     * its day bucket must be the bucket that reviewedAt really falls in for its own zone, and each word
     * must have exactly one introduction (so a duplicate cannot inflate the daily new-word quota).
     *
     * The real order of commits is the durable [BackupStudyReview.seq] — never the wall clock (a legal
     * clock rollback makes a later commit's reviewedAt smaller), never the token text and never the
     * list position. In that seq order every word's feedback must form one continuous stage chain that
     * starts at the single introduction, and the persisted state must name that word's greatest seq and
     * match exactly that last feedback's stage/due/reviewed time. [BackupStudyState.lastReviewSeq] must
     * equal the greatest seq and introducedAt must equal the unique introduction's time, so an older but
     * internally consistent state can no longer masquerade as the final one. Order between different
     * words is never asserted by wall clock; only each row's own consistency and its state's own commit
     * chain are checked, so an honest backup with an unusual (rolled-back) history is still accepted.
     */
    private fun validateStudyCrossRecords(
        states: List<BackupStudyState>,
        reviews: List<BackupStudyReview>,
        session: BackupStudySession?
    ) {
        val stateByKey = states.associateBy { it.entryKey }
        val reviewsByKey = reviews.groupBy { it.entryKey }

        val seqs = HashSet<Long>()
        for (review in reviews) {
            val feedback = FeedbackKind.fromId(review.feedback) ?: throw BackupFormatException("反馈类型无效：${review.feedback}")
            if (StudyRules.nextStage(review.stageBefore, feedback) != review.stageAfter) {
                throw BackupFormatException("反馈阶段推进与规则不符：${review.token}")
            }
            if (StudyRules.dueAt(review.reviewedAt, review.stageBefore, feedback) != review.dueAfter) {
                throw BackupFormatException("反馈到期时间与规则不符：${review.token}")
            }
            val zone = parseZone(review.zoneId) ?: throw BackupFormatException("反馈时区无效：${review.zoneId}")
            val day = parseDayKey(review.dayKey) ?: throw BackupFormatException("反馈日键不是合法日期：${review.dayKey}")
            if (StudyRules.dayKey(review.reviewedAt, zone) != day.toString()) {
                throw BackupFormatException("反馈日键与反馈时间或时区不一致：${review.token}")
            }
            if (!seqs.add(review.seq)) throw BackupFormatException("备份存在重复提交序号：${review.seq}")
        }

        // Exactly one introduction per word: a second new-word flag would consume a second daily slot,
        // and the seq-ordered chain must be continuous from that single introduction.
        reviewsByKey.forEach { (key, list) ->
            if (list.count { it.newWord } != 1) throw BackupFormatException("同一词条的首次反馈必须唯一：$key")
            val ordered = list.sortedBy { it.seq }
            val intro = ordered.first()
            if (!intro.newWord || intro.stageBefore != StudyRules.NEW_STAGE_BEFORE) {
                throw BackupFormatException("同一词条最早的反馈必须是从未引入档引入：$key")
            }
            for (index in 1 until ordered.size) {
                if (ordered[index].stageBefore != ordered[index - 1].stageAfter) {
                    throw BackupFormatException("真实反馈阶段转移链不连续：$key")
                }
            }
        }

        stateByKey.forEach { (key, state) ->
            val list = reviewsByKey[key] ?: throw BackupFormatException("学习状态缺少对应的真实反馈：$key")
            val intro = list.single { it.newWord }
            if (state.introducedAt != intro.reviewedAt) {
                throw BackupFormatException("学习状态引入时间与首次反馈不一致：$key")
            }
            // The final state must be the word's greatest commit sequence, matching exactly that feedback.
            val last = list.maxByOrNull { it.seq } ?: throw BackupFormatException("学习状态缺少对应的真实反馈：$key")
            if (state.lastReviewSeq != last.seq) throw BackupFormatException("学习状态末尾提交序号与最后反馈不一致：$key")
            if (last.stageAfter != state.stage || last.dueAfter != state.dueAt || last.reviewedAt != state.lastReviewAt) {
                throw BackupFormatException("学习状态与最后真实反馈不一致：$key")
            }
        }
        reviewsByKey.keys.forEach { key ->
            if (key !in stateByKey) throw BackupFormatException("真实反馈缺少对应的学习状态：$key")
        }

        if (session != null) {
            if (session.headword != session.entryKey) throw BackupFormatException("待答卡词头与词条键不一致")
            if (parseDayKey(session.dayKey) == null) throw BackupFormatException("待答卡日键不是合法日期：${session.dayKey}")
            val state = stateByKey[session.entryKey]
            if (session.newWord) {
                if (state != null) throw BackupFormatException("待答卡标记为新词但该词已有学习状态：${session.entryKey}")
            } else {
                if (state == null) throw BackupFormatException("待答卡缺少对应的学习状态：${session.entryKey}")
                if (state.stage != session.stageBefore) throw BackupFormatException("待答卡阶段与学习状态不一致：${session.entryKey}")
            }
        }
    }

    private fun parseDayKey(value: String): LocalDate? = try {
        LocalDate.parse(value)
    } catch (_: Exception) {
        null
    }

    private fun parseZone(value: String): ZoneId? = try {
        ZoneId.of(value)
    } catch (_: Exception) {
        null
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun encodeUtf8(value: String): ByteArray = try {
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        throw BackupFormatException("文本包含无法按 UTF-8 编码的字符，未写出")
    }

    /** Enforces the 64MiB cap while building, so a runaway note cannot allocate without bound. */
    private class BackupWriter(private val limit: Long) {
        private val out = ByteArrayOutputStream()
        fun writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
        fun writeInt(value: Int) = write(byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))
        fun writeLong(value: Long) = write(ByteArray(8).also {
            for (index in 0 until 8) it[index] = (value ushr (56 - 8 * index)).toByte()
        })
        fun writeBoolean(value: Boolean) = write(byteArrayOf(if (value) 1 else 0))
        fun writeString(value: String) { val bytes = encodeUtf8(value); writeInt(bytes.size); write(bytes) }
        fun write(bytes: ByteArray) {
            if (out.size().toLong() + bytes.size > limit) {
                throw BackupFormatException("备份文件超过 ${LearningBackupCodec.MAX_BYTES_LABEL} 上限，未写出或读取")
            }
            out.write(bytes)
        }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** Bounds-checked big-endian reader; every access validates the remaining length first. */
    private class BackupReader(private val bytes: ByteArray) {
        private var position = 0
        val remaining get() = bytes.size - position

        fun readAscii(length: Int): String {
            requireAvailable(length); val value = String(bytes, position, length, Charsets.US_ASCII); position += length
            return value
        }
        fun readByte(): Int { requireAvailable(1); return bytes[position++].toInt() and 0xff }
        fun readBoolean(label: String): Boolean = when (val value = readByte()) {
            0 -> false; 1 -> true; else -> throw BackupFormatException("$label 的布尔值无效（$value）")
        }
        fun readInt(): Int {
            requireAvailable(4)
            val value = ((bytes[position].toInt() and 0xff) shl 24) or
                ((bytes[position + 1].toInt() and 0xff) shl 16) or
                ((bytes[position + 2].toInt() and 0xff) shl 8) or
                (bytes[position + 3].toInt() and 0xff)
            position += 4
            return value
        }
        fun readLong(): Long {
            requireAvailable(8)
            var value = 0L
            for (index in 0 until 8) value = (value shl 8) or (bytes[position + index].toLong() and 0xff)
            position += 8
            return value
        }
        fun readString(label: String): String {
            val length = readInt()
            if (length < 0) throw BackupFormatException("$label 的长度前缀无效")
            requireAvailable(length)
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val value = try {
                decoder.decode(ByteBuffer.wrap(bytes, position, length)).toString()
            } catch (_: CharacterCodingException) {
                throw BackupFormatException("$label 不是合法 UTF-8 文本")
            }
            position += length
            return value
        }
        private fun requireAvailable(count: Int) {
            if (count < 0 || count > remaining) throw BackupFormatException("备份文件被截断或不完整")
        }
    }
}
