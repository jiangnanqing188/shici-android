package com.shici.app.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.DigestOutputStream
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

/** A counted, synchronous row traversal. Repository instances must not outlive their read transaction. */
internal class BackupRows<T>(val count: () -> Int, val visit: ((T) -> Unit) -> Unit) {
    fun toList(): List<T> = buildList { visit { add(it) } }
}

internal class LearningBackupRows(
    val words: BackupRows<BackupWord>,
    val events: BackupRows<BackupEvent>,
    val settings: BackupStudySettings,
    val states: BackupRows<BackupStudyState>,
    val reviews: BackupRows<BackupStudyReview>,
    val session: BackupStudySession?,
    val userEntries: BackupRows<BackupUserEntry>
)

/** Length and SHA-256 of the entire file, including the v4 checksum trailer. */
data class BackupFileFingerprint(val byteCount: Long, val sha256: String)

/** Validated header and exact preview counts, with no decoded table or pending-card payload. */
internal data class LearningBackupPreview(
    val appVersionName: String,
    val appVersionCode: Int,
    val schemaVersion: Int,
    val createdAt: Long,
    val formatVersion: Int,
    val wordCount: Int,
    val eventCount: Int,
    val favoriteCount: Int,
    val stateCount: Int = 0,
    val reviewCount: Int = 0,
    val hasSession: Boolean = false,
    val userEntryCount: Int = 0
) {
    val legacyNoStudyData get() = formatVersion < LearningBackupCodec.STUDY_FORMAT_VERSION
    val noPersonalDictionary get() = formatVersion < LearningBackupCodec.USER_DICTIONARY_FORMAT_VERSION
    val hasFavorites get() = formatVersion >= LearningBackupCodec.FAVORITE_FORMAT_VERSION
    val noFavorites get() = !hasFavorites
}

/** Receives validated rows synchronously in file order. No effect may commit before replay returns. */
internal interface BackupRestoreSink {
    fun word(row: BackupWord)
    fun event(row: BackupEvent)
    fun settings(row: BackupStudySettings)
    fun state(row: BackupStudyState)
    fun review(row: BackupStudyReview)
    fun session(row: BackupStudySession)
    fun userEntry(row: BackupUserEntry)
}

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
    // Compatibility limits for the ByteArray entry points, not the streaming file limit.
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_BYTES_LABEL = "64MiB"
    const val MAX_STREAM_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_STREAM_BYTES_LABEL = "256MiB"

    private const val DIGEST_BYTES = 32
    private const val BUFFER_BYTES = 64 * 1024
    // The old whole-file limit already implied this per-field ceiling. No existing file loses support.
    private const val MAX_STRING_BYTES = MAX_FILE_BYTES.toInt()

    /** Small-file compatibility wrapper. Use [encodeTo] or the repository writer for a large file. */
    fun encode(data: LearningBackupData, appVersionName: String, appVersionCode: Int, createdAt: Long): ByteArray {
        val output = ByteArrayOutputStream()
        writeBackup(output, rowsOf(data), appVersionName, appVersionCode, createdAt, MAX_FILE_BYTES)
        return output.toByteArray()
    }

    /**
     * Writes the unchanged v4 wire format and flushes, but does not close [output]. Failure may
     * leave partial bytes; publish only after write/close succeeds. No whole-file byte array is built.
     */
    fun encodeTo(output: OutputStream, data: LearningBackupData, appVersionName: String,
                 appVersionCode: Int, createdAt: Long): BackupFileFingerprint =
        encodeRows(output, rowsOf(data), appVersionName, appVersionCode, createdAt)

    internal fun encodeRows(output: OutputStream, data: LearningBackupRows, appVersionName: String,
                            appVersionCode: Int, createdAt: Long): BackupFileFingerprint =
        writeBackup(output, data, appVersionName, appVersionCode, createdAt, MAX_STREAM_FILE_BYTES)

    private fun rowsOf(data: LearningBackupData): LearningBackupRows {
        fun <T> rows(list: List<T>): BackupRows<T> = BackupRows({ list.size }) { visit -> list.forEach(visit) }
        return LearningBackupRows(rows(data.words), rows(data.events), data.settings, rows(data.states),
            rows(data.reviews), data.session, rows(data.userEntries))
    }

    private fun writeBackup(output: OutputStream, data: LearningBackupRows, appVersionName: String,
                            appVersionCode: Int, createdAt: Long, limit: Long): BackupFileFingerprint {
        val writer = BackupWriter(output, limit)
        writer.writeAscii(MAGIC)
        writer.writeInt(FORMAT_VERSION)
        writer.writeString(APP_IDENTITY)
        writer.writeString(appVersionName)
        writer.writeInt(appVersionCode)
        writer.writeInt(DB_SCHEMA_VERSION)
        writer.writeLong(createdAt)
        val wordCount = data.words.count()
        val eventCount = data.events.count()
        writer.writeInt(wordCount)
        writer.writeInt(eventCount)
        writer.writeRows(data.words, wordCount) { word ->
            writer.writeString(word.entryKey); writer.writeString(word.headword); writer.writeString(word.partOfSpeech)
            writer.writeString(word.meaning); writer.writeString(word.source); writer.writeString(word.version)
            writer.writeBoolean(word.mastered); writer.writeString(word.note); writer.writeBoolean(word.favorite)
        }
        writer.writeRows(data.events, eventCount) { event ->
            writer.writeString(event.id); writer.writeString(event.entryKey); writer.writeString(event.rawText)
            writer.writeString(event.finalForm); writer.writeLong(event.time); writer.writeBoolean(event.revoked)
        }
        writer.writeString(data.settings.selectedBook)
        writer.writeInt(data.settings.dailyNewGoal)
        val stateCount = data.states.count()
        writer.writeInt(stateCount)
        writer.writeRows(data.states, stateCount) { state ->
            writer.writeString(state.entryKey); writer.writeString(state.book); writer.writeInt(state.stage)
            writer.writeLong(state.dueAt); writer.writeLong(state.introducedAt); writer.writeLong(state.lastReviewAt)
            writer.writeLong(state.lastReviewSeq)
        }
        val reviewCount = data.reviews.count()
        writer.writeInt(reviewCount)
        writer.writeRows(data.reviews, reviewCount) { review ->
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
        val userEntryCount = data.userEntries.count()
        writer.writeInt(userEntryCount)
        writer.writeRows(data.userEntries, userEntryCount) { user ->
            writer.writeString(user.entryKey); writer.writeString(user.partOfSpeech)
            writer.writeString(user.meaning); writer.writeLong(user.updatedAt)
        }
        return writer.finish()
    }

    /**
     * Fully validates [source] (size, checksum, magic, versions, every length/UTF-8/boolean, duplicate
     * keys, orphan events and trailing bytes) and returns the frozen payload. A version newer than
     * [FORMAT_VERSION] is rejected rather than guessed as the current format; v1 files are read into
     * default settings with an empty review set.
     */
    fun decode(source: ByteArray): LearningBackup {
        if (source.size > MAX_FILE_BYTES) throw sizeLimitExceeded(MAX_FILE_BYTES)
        if (source.size <= DIGEST_BYTES) throw BackupFormatException("备份文件过小或不完整")
        val bodySize = source.size - DIGEST_BYTES
        val actual = newDigest().apply { update(source, 0, bodySize) }.digest()
        requireChecksum(actual, source.copyOfRange(bodySize, source.size))
        return collectBackup { sink ->
            readBackup(BackupReader(ByteArrayInputStream(source, 0, bodySize), bodySize.toLong()), sink)
        }
    }

    /**
     * Stages one v1–v4 stream in a unique file in the existing app-private [privateDirectory]. The
     * stream must reach EOF within the byte cap and its checksum must pass before allocating payload
     * records. The temporary file is deleted when this call returns or unwinds; [source] is not closed.
     * Run on the file I/O worker. Disk usage is up to one encoded file; decoded rows and validation
     * indexes still require heap proportional to their contents, not to the scratch buffer size.
     */
    fun decodeFrom(source: InputStream, privateDirectory: File): LearningBackup {
        val temporary = File.createTempFile("shici-backup-read-", ".tmp", privateDirectory)
        return Closeable { Files.deleteIfExists(temporary.toPath()) }.use {
            val fingerprint = copyToPrivate(source, temporary) {}
            collectBackup { sink -> validatePrivate(temporary, fingerprint, sink) {} }
        }
    }

    /**
     * The document worker owns the stream from [openSource] through close. Only then, after the exact
     * private copy passes checksum, EOF and the shared parser, does the caller receive ownership.
     * Preview validation retains identity indexes and the study ledger, not the decoded main tables.
     */
    internal fun stageImport(privateDirectory: File, checkpoint: () -> Unit = {},
                             openSource: () -> InputStream): StagedImport {
        checkpoint()
        val temporary = File.createTempFile("shici-backup-read-", ".tmp", privateDirectory)
        var transferred = false
        try {
            val fingerprint = openSource().use { copyToPrivate(it, temporary, checkpoint) }
            checkpoint()
            val preview = validatePrivate(temporary, fingerprint, null, checkpoint)
            checkpoint()
            return StagedImport(temporary, fingerprint, preview).also { transferred = true }
        } finally {
            if (!transferred) temporary.delete()
        }
    }

    /** Exclusive app-private source. The preview owns it until admission transfers it to the worker. */
    internal class StagedImport internal constructor(
        private val file: File,
        val fingerprint: BackupFileFingerprint,
        val preview: LearningBackupPreview
    ) : Closeable {
        /** Replay in one pass and verify the exact bytes consumed before commit. */
        fun replay(sink: BackupRestoreSink) = file.inputStream().use {
            readPrivate(it, fingerprint, sink) {}
        }

        override fun close() { file.delete() }
    }

    private fun copyToPrivate(source: InputStream, temporary: File, checkpoint: () -> Unit): BackupFileFingerprint {
        val buffer = ByteArray(BUFFER_BYTES)
        val digest = newDigest()
        var total = 0L
        temporary.outputStream().buffered(BUFFER_BYTES).use { output ->
            while (true) {
                // Read at most the remaining cap plus one sentinel byte, even for a long stream.
                val requested = minOf(buffer.size.toLong(), MAX_STREAM_FILE_BYTES - total + 1).toInt()
                checkpoint()
                val read = readChunk(source, buffer, requested)
                checkpoint()
                if (read < 0) break
                if (read > MAX_STREAM_FILE_BYTES - total) throw sizeLimitExceeded(MAX_STREAM_FILE_BYTES)
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                total += read
            }
        }
        if (total <= DIGEST_BYTES) throw BackupFormatException("备份文件过小或不完整")
        return BackupFileFingerprint(total, digest.digest().hex())
    }

    private fun validatePrivate(temporary: File, fingerprint: BackupFileFingerprint, sink: BackupRestoreSink?,
                                checkpoint: () -> Unit): LearningBackupPreview = temporary.inputStream().use { file ->
        val bodySize = fingerprint.byteCount - DIGEST_BYTES
        val digest = newDigest()
        if (!digestExactly(file, bodySize, ByteArray(BUFFER_BYTES), digest, checkpoint)) throw EOFException()
        val trailer = ByteArray(DIGEST_BYTES)
        DataInputStream(file).readFully(trailer)
        if (file.read() != -1) throw BackupFormatException("备份在声明内容之后存在多余字节")
        requireChecksum(digest.digest(), trailer)
        file.channel.position(0)
        readPrivate(file, fingerprint, sink, checkpoint)
    }

    private fun readPrivate(file: FileInputStream, fingerprint: BackupFileFingerprint, sink: BackupRestoreSink?,
                            checkpoint: () -> Unit): LearningBackupPreview {
        val digest = newDigest()
        // Hash what the parser actually consumes (not a separate check then an unverified read).
        val input = DigestInputStream(file.buffered(BUFFER_BYTES), digest)
        val bodySize = fingerprint.byteCount - DIGEST_BYTES
        val preview = readBackup(BackupReader(input, bodySize, checkpoint), sink)
        DataInputStream(input).readFully(ByteArray(DIGEST_BYTES))
        if (input.read() != -1 || digest.digest().hex() != fingerprint.sha256) {
            throw BackupFormatException("暂存的备份内容已改变，未恢复学习数据")
        }
        return preview
    }

    /** Call on a fresh, reopened read-back stream after successful write/flush/close. Does not close it. */
    fun verifyExactBytes(source: InputStream, expected: BackupFileFingerprint): Boolean {
        if (expected.byteCount !in 1L..MAX_STREAM_FILE_BYTES) return false
        val digest = newDigest()
        return digestExactly(source, expected.byteCount, ByteArray(BUFFER_BYTES), digest) &&
            source.read() == -1 && digest.digest().hex() == expected.sha256
    }

    private fun collectBackup(read: (BackupRestoreSink) -> LearningBackupPreview): LearningBackup {
        val words = ArrayList<BackupWord>()
        val events = ArrayList<BackupEvent>()
        val states = ArrayList<BackupStudyState>()
        val reviews = ArrayList<BackupStudyReview>()
        val userEntries = ArrayList<BackupUserEntry>()
        var settings = BackupStudySettings()
        var session: BackupStudySession? = null
        val preview = read(object : BackupRestoreSink {
            override fun word(row: BackupWord) { words += row }
            override fun event(row: BackupEvent) { events += row }
            override fun settings(row: BackupStudySettings) { settings = row }
            override fun state(row: BackupStudyState) { states += row }
            override fun review(row: BackupStudyReview) { reviews += row }
            override fun session(row: BackupStudySession) { session = row }
            override fun userEntry(row: BackupUserEntry) { userEntries += row }
        })
        return LearningBackup(preview.appVersionName, preview.appVersionCode, preview.schemaVersion, preview.createdAt,
            LearningBackupData(words, events, settings, states, reviews, session, userEntries), preview.formatVersion)
    }

    private fun readBackup(reader: BackupReader, sink: BackupRestoreSink?): LearningBackupPreview = try {
        readPayload(reader, sink)
    } catch (_: EOFException) {
        throw BackupFormatException("备份文件被截断或不完整")
    }

    private fun readPayload(reader: BackupReader, sink: BackupRestoreSink?): LearningBackupPreview {
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
        // Lower bounds include fixed fields and string prefixes, not string contents.
        val minWordBytes = if (hasFavorites) 30L else 29L
        reader.requireRows(wordCount * minWordBytes + eventCount * 25L)

        val keys = HashSet<String>()
        val unseenFavorites = HashSet<String>()
        repeat(wordCount) {
            val entryKey = reader.readString("词条键")
            val headword = reader.readString("词头")
            val partOfSpeech = reader.readString("词性", materialize = sink != null)
            val meaning = reader.readString("释义", materialize = sink != null)
            val source = reader.readString("来源", materialize = sink != null)
            val version = reader.readString("来源版本", materialize = sink != null)
            val mastered = reader.readBoolean("掌握状态")
            val note = reader.readString("笔记", materialize = sink != null)
            // v4 appends the star marker after the note; v1–v3 have no such byte and default to false.
            val favorite = if (hasFavorites) reader.readBoolean("收藏状态") else false
            if (entryKey.isEmpty() || headword.isEmpty()) throw BackupFormatException("备份存在空词条键或空词头")
            if (entryKey != headword) throw BackupFormatException("备份词条键与词头不一致（$entryKey / $headword）")
            if (!keys.add(entryKey)) throw BackupFormatException("备份存在重复词条键：$entryKey")
            if (favorite) unseenFavorites.add(entryKey)
            sink?.word(BackupWord(entryKey, headword, partOfSpeech, meaning, source, version, mastered, note, favorite))
        }

        var favoriteCount = 0
        val ids = HashSet<String>()
        repeat(eventCount) {
            val id = reader.readString("事件 id")
            val entryKey = reader.readString("事件词条键")
            val rawText = reader.readString("原词形文本", materialize = sink != null)
            val finalForm = reader.readString("最终词形", materialize = sink != null)
            val time = reader.readLong()
            val revoked = reader.readBoolean("撤销状态")
            if (id.isEmpty()) throw BackupFormatException("备份存在空事件 id")
            if (!ids.add(id)) throw BackupFormatException("备份存在重复事件 id：$id")
            if (entryKey !in keys) throw BackupFormatException("备份存在无对应词条的事件（$entryKey）")
            if (!revoked && unseenFavorites.remove(entryKey)) favoriteCount++
            sink?.event(BackupEvent(id, entryKey, rawText, finalForm, time, revoked))
        }

        val preview = LearningBackupPreview(appVersionName, appVersionCode, schema, createdAt, format,
            wordCount, eventCount, favoriteCount)
        if (!hasStudy) {
            reader.requireEnd()
            sink?.settings(BackupStudySettings())
            return preview
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

        sink?.settings(settings)

        // Historical files may order reviews arbitrarily; retain just the ledger needed for the
        // exact cross-record checks, never the decoded words, events, or user dictionary.
        val states = ArrayList<BackupStudyState>()
        val stateKeys = HashSet<String>()
        repeat(reader.readInt().also {
            if (it < 0) throw BackupFormatException("学习状态数量无效")
            reader.requireRows(it * 44L)
        }) {
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
            val row = BackupStudyState(entryKey, book, stage, dueAt, introducedAt, lastReviewAt, lastReviewSeq)
            states += row
            sink?.state(row)
        }

        val reviews = ArrayList<BackupStudyReview>()
        val tokens = HashSet<String>()
        repeat(reader.readInt().also {
            if (it < 0) throw BackupFormatException("学习反馈数量无效")
            reader.requireRows(it * 53L)
        }) {
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
            val row = BackupStudyReview(token, entryKey, feedback, stageBefore, stageAfter, dueAfter, reviewedAt, dayKey, zoneId, newWord, seq)
            reviews += row
            sink?.review(row)
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
                partOfSpeech = reader.readString("待答卡词性", materialize = sink != null),
                meaning = reader.readString("待答卡释义", materialize = sink != null),
                source = reader.readString("待答卡来源", materialize = sink != null),
                version = reader.readString("待答卡来源版本", materialize = sink != null),
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

        session?.let { sink?.session(it) }

        val userEntryCount = if (hasUserDictionary) {
            val count = reader.readInt()
            if (count < 0) throw BackupFormatException("用户词典数量无效")
            reader.requireRows(count * 20L)
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
                sink?.userEntry(BackupUserEntry(entryKey, partOfSpeech, meaning, updatedAt))
            }
            count
        } else 0

        reader.requireEnd()
        validateStudyCrossRecords(states, reviews, session, reader::checkCurrent)
        return preview.copy(stateCount = states.size, reviewCount = reviews.size, hasSession = session != null,
            userEntryCount = userEntryCount)
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
        session: BackupStudySession?,
        checkpoint: () -> Unit
    ) {
        val stateByKey = states.associateBy { it.entryKey }
        // Repository exports are already in seq order. Historical input may be out of order;
        // keep accepting it without reordering the returned rows. Only that case needs a sort copy.
        val inSequence = (1 until reviews.size).all { reviews[it - 1].seq <= reviews[it].seq }
        val ordered = if (inSequence) reviews else reviews.sortedBy { it.seq }
        val lastByKey = HashMap<String, BackupStudyReview>()
        var lastSeq: Long? = null
        for (review in ordered) {
            checkpoint()
            if (lastSeq == review.seq) throw BackupFormatException("备份存在重复提交序号：${review.seq}")
            lastSeq = review.seq
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
            val key = review.entryKey
            val state = stateByKey[key] ?: throw BackupFormatException("真实反馈缺少对应的学习状态：$key")
            val previous = lastByKey.put(key, review)
            if (previous == null) {
                if (!review.newWord || review.stageBefore != StudyRules.NEW_STAGE_BEFORE) {
                    throw BackupFormatException("同一词条最早的反馈必须是从未引入档引入：$key")
                }
                if (state.introducedAt != review.reviewedAt) {
                    throw BackupFormatException("学习状态引入时间与首次反馈不一致：$key")
                }
            } else {
                if (review.newWord) throw BackupFormatException("同一词条的首次反馈必须唯一：$key")
                if (review.stageBefore != previous.stageAfter) {
                    throw BackupFormatException("真实反馈阶段转移链不连续：$key")
                }
            }
        }

        stateByKey.forEach { (key, state) ->
            checkpoint()
            val last = lastByKey[key] ?: throw BackupFormatException("学习状态缺少对应的真实反馈：$key")
            if (state.lastReviewSeq != last.seq) throw BackupFormatException("学习状态末尾提交序号与最后反馈不一致：$key")
            if (last.stageAfter != state.stage || last.dueAfter != state.dueAt || last.reviewedAt != state.lastReviewAt) {
                throw BackupFormatException("学习状态与最后真实反馈不一致：$key")
            }
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

    private fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun digestExactly(input: InputStream, length: Long, buffer: ByteArray, digest: MessageDigest,
                              checkpoint: () -> Unit = {}): Boolean {
        var remaining = length
        while (remaining > 0) {
            checkpoint()
            val count = readChunk(input, buffer, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) return false
            digest.update(buffer, 0, count)
            remaining -= count
        }
        return true
    }

    private fun readChunk(input: InputStream, buffer: ByteArray, length: Int): Int {
        val count = input.read(buffer, 0, length)
        if (count != 0) return count
        val byte = input.read()
        if (byte < 0) return -1
        buffer[0] = byte.toByte()
        return 1
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun requireChecksum(actual: ByteArray, expected: ByteArray) {
        if (!MessageDigest.isEqual(actual, expected)) {
            throw BackupFormatException("备份校验和失败，文件可能损坏或被修改")
        }
    }

    private fun sizeLimitExceeded(limit: Long): BackupFormatException {
        val label = if (limit == MAX_FILE_BYTES) MAX_BYTES_LABEL else MAX_STREAM_BYTES_LABEL
        return BackupFormatException("备份文件超过 $label 上限，未写出或读取")
    }

    private class BackupWriter(output: OutputStream, private val limit: Long) {
        private val bodyDigest = newDigest()
        private val fileDigest = newDigest()
        private val digestOutput = DigestOutputStream(DigestOutputStream(BufferedOutputStream(output, BUFFER_BYTES),
            fileDigest), bodyDigest)
        private val out = DataOutputStream(digestOutput)
        private val textBuffer = ByteBuffer.allocate(BUFFER_BYTES)
        private val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        private var bodyBytes = 0L

        fun writeAscii(value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            reserve(bytes.size)
            out.write(bytes)
        }
        fun writeInt(value: Int) { reserve(4); out.writeInt(value) }
        fun writeLong(value: Long) { reserve(8); out.writeLong(value) }
        fun writeBoolean(value: Boolean) { reserve(1); out.writeBoolean(value) }

        fun <T> writeRows(rows: BackupRows<T>, count: Int, writeRow: (T) -> Unit) {
            if (count < 0) throw BackupFormatException("备份记录数量无效")
            var remaining = count
            rows.visit { row ->
                if (remaining <= 0) throw BackupFormatException("备份快照记录数不一致，未写出")
                writeRow(row)
                remaining--
            }
            if (remaining != 0) throw BackupFormatException("备份快照记录数不一致，未写出")
        }

        fun writeString(value: String) {
            // The prefix needs a byte count, not the UTF-16 length. Count and validate before writing;
            // encode a second pass into one reusable buffer rather than creating a full field array.
            val length = utf8Length(value)
            writeInt(length)
            reserve(length)
            if (length == 0) return
            val chars = CharBuffer.wrap(value)
            encoder.reset()
            try {
                do {
                    textBuffer.clear()
                    val result = encoder.encode(chars, textBuffer, true)
                    if (result.isError) result.throwException()
                    out.write(textBuffer.array(), 0, textBuffer.position())
                } while (result.isOverflow)
                do {
                    textBuffer.clear()
                    val result = encoder.flush(textBuffer)
                    if (result.isError) result.throwException()
                    out.write(textBuffer.array(), 0, textBuffer.position())
                } while (result.isOverflow)
            } catch (_: CharacterCodingException) {
                throw BackupFormatException("文本包含无法按 UTF-8 编码的字符，未写出")
            }
        }

        fun finish(): BackupFileFingerprint {
            val trailer = bodyDigest.digest()
            digestOutput.on(false)
            out.write(trailer)
            out.flush()
            return BackupFileFingerprint(bodyBytes + DIGEST_BYTES, fileDigest.digest().hex())
        }

        private fun reserve(count: Int) {
            if (count > limit - DIGEST_BYTES - bodyBytes) throw sizeLimitExceeded(limit)
            bodyBytes += count
        }

        private fun utf8Length(value: String): Int {
            var index = 0
            var bytes = 0
            while (index < value.length) {
                val char = value[index++]
                bytes += when {
                    char.code <= 0x7f -> 1
                    char.code <= 0x7ff -> 2
                    !Character.isSurrogate(char) -> 3
                    Character.isHighSurrogate(char) && index < value.length &&
                        Character.isLowSurrogate(value[index]) -> { index++; 4 }
                    else -> throw BackupFormatException("文本包含无法按 UTF-8 编码的字符，未写出")
                }
                if (bytes > limit - DIGEST_BYTES - bodyBytes - 4) throw sizeLimitExceeded(limit)
                if (bytes > MAX_STRING_BYTES) throw BackupFormatException("单个备份文本超过 $MAX_BYTES_LABEL 上限，未写出")
            }
            return bytes
        }
    }

    /** Reads the declared body without letting field reads consume the next field or checksum. */
    private class BackupReader(source: InputStream, private var remaining: Long,
                               private val checkpoint: () -> Unit = {}) {
        private val input = DataInputStream(source)
        private val textBytes = ByteBuffer.allocate(8192)
        private val chars = CharBuffer.allocate(4096)
        private val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)

        fun readAscii(length: Int): String = String(readBytes(length), Charsets.US_ASCII)
        fun readBoolean(label: String): Boolean {
            take(1)
            return when (val value = input.readUnsignedByte()) {
                0 -> false; 1 -> true; else -> throw BackupFormatException("$label 的布尔值无效（$value）")
            }
        }
        fun readInt(): Int { take(4); return input.readInt() }
        fun readLong(): Long { take(8); return input.readLong() }

        fun requireRows(minimumBytes: Long) {
            if (minimumBytes > remaining) throw BackupFormatException("备份记录数量超过剩余文件容量")
        }

        // A validation-only field still goes through the same decoder and length/UTF-8 checks.
        // Its empty return is unused; only materialized fields are used to build emitted rows.
        fun readString(label: String, materialize: Boolean = true): String {
            checkCurrent()
            val length = readInt()
            if (length < 0) throw BackupFormatException("$label 的长度前缀无效")
            if (length > MAX_STRING_BYTES) throw BackupFormatException("$label 超过单个备份文本 $MAX_BYTES_LABEL 上限")
            if (length == 0) return ""
            take(length)
            // Read only the declared field, reusing scratch space and growing the decoded text
            // only as bytes arrive. Partial UTF-8 sequences remain in the buffer across chunks.
            decoder.reset()
            textBytes.clear().limit(0)
            var left = length
            var value: StringBuilder? = null
            try {
                while (true) {
                    checkCurrent()
                    textBytes.compact()
                    val count = minOf(left, textBytes.remaining())
                    input.readFully(textBytes.array(), textBytes.position(), count)
                    textBytes.position(textBytes.position() + count).flip()
                    left -= count
                    while (true) {
                        chars.clear()
                        val result = decoder.decode(textBytes, chars, left == 0)
                        if (result.isError) result.throwException()
                        if (result.isUnderflow && left == 0) {
                            // UTF-8 emits no extra characters when flushed.
                            decoder.flush(chars)
                            if (!materialize) return ""
                            val prefix = value ?: return String(chars.array(), 0, chars.position())
                            return prefix.append(chars.array(), 0, chars.position()).toString()
                        }
                        if (materialize) {
                            val prefix = value ?: StringBuilder().also { value = it }
                            prefix.append(chars.array(), 0, chars.position())
                        }
                        if (result.isUnderflow) break
                    }
                }
            } catch (_: CharacterCodingException) {
                throw BackupFormatException("$label 不是合法 UTF-8 文本")
            }
        }

        fun checkCurrent() = checkpoint()

        fun requireEnd() {
            if (remaining != 0L) {
                throw BackupFormatException("备份在声明内容之后存在多余字节")
            }
        }

        private fun readBytes(length: Int): ByteArray {
            take(length)
            return ByteArray(length).also { input.readFully(it) }
        }

        private fun take(count: Int) {
            if (count > remaining) throw BackupFormatException("备份文件被截断或不完整")
            remaining -= count
        }
    }
}
