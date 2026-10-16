package com.shici.app.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Raised for any malformed, truncated, oversized, future-version or inconsistent backup file. */
class BackupFormatException(message: String) : Exception(message)

/** One raw `words` row; no derived counts or cache. `entryKey` equals `headword` in the real model. */
data class BackupWord(
    val entryKey: String,
    val headword: String,
    val partOfSpeech: String,
    val meaning: String,
    val source: String,
    val version: String,
    val mastered: Boolean,
    val note: String
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

/** Full raw two-table payload; counts/dates are re-aggregated from these rows, never cached here. */
data class LearningBackupData(val words: List<BackupWord>, val events: List<BackupEvent>)

/** A validated backup file: its identity metadata plus the frozen raw tables. */
data class LearningBackup(
    val appVersionName: String,
    val appVersionCode: Int,
    val schemaVersion: Int,
    val createdAt: Long,
    val data: LearningBackupData
) {
    val wordCount get() = data.words.size
    val eventCount get() = data.events.size
}

/**
 * Versioned, length-prefixed local binary backup (`.shicibackup`). Pure Kotlin/JDK so the codec can
 * be exercised without Android; see README "本地备份格式" for the byte layout. The file is an
 * unencrypted, tamper-evident (SHA-256 only) personal-data file, not a secure vault.
 */
object LearningBackupCodec {
    const val MAGIC = "SHICIBKP"
    const val FORMAT_VERSION = 1
    const val FILE_SUFFIX = ".shicibackup"
    const val MIME_TYPE = "application/octet-stream"
    const val APP_IDENTITY = "com.shici.app"
    const val DB_SCHEMA_VERSION = 1
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
            writer.writeBoolean(word.mastered); writer.writeString(word.note)
        }
        data.events.forEach { event ->
            writer.writeString(event.id); writer.writeString(event.entryKey); writer.writeString(event.rawText)
            writer.writeString(event.finalForm); writer.writeLong(event.time); writer.writeBoolean(event.revoked)
        }
        val body = writer.toByteArray()
        if (body.size.toLong() + DIGEST_BYTES > MAX_FILE_BYTES) throw BackupFormatException("备份文件超过 $MAX_BYTES_LABEL 上限，未写出或读取")
        return body + sha256(body)
    }

    /**
     * Fully validates [source] (size, checksum, magic, versions, every length/UTF-8/boolean, duplicate
     * keys, orphan events and trailing bytes) and returns the frozen payload. A version newer than
     * [FORMAT_VERSION] is rejected rather than guessed as the current format.
     */
    fun decode(source: ByteArray): LearningBackup {
        if (source.size.toLong() > MAX_FILE_BYTES) throw BackupFormatException("备份文件超过 $MAX_BYTES_LABEL 上限，未写出或读取")
        if (source.size <= DIGEST_BYTES) throw BackupFormatException("备份文件过小或不完整")
        val body = source.copyOfRange(0, source.size - DIGEST_BYTES)
        val digest = source.copyOfRange(source.size - DIGEST_BYTES, source.size)
        if (!sha256(body).contentEquals(digest)) throw BackupFormatException("备份校验和失败，文件可能损坏或被修改")

        val reader = BackupReader(body)
        if (reader.readAscii(8) != MAGIC) throw BackupFormatException("不是拾词备份文件（magic 不匹配）")
        when (val format = reader.readInt()) {
            FORMAT_VERSION -> Unit
            in (FORMAT_VERSION + 1)..Int.MAX_VALUE ->
                throw BackupFormatException("备份格式版本 $format 高于当前支持的 $FORMAT_VERSION，请先升级应用")
            else -> throw BackupFormatException("备份格式版本 $format 不受支持")
        }
        val identity = reader.readString("应用标识")
        if (identity != APP_IDENTITY) throw BackupFormatException("备份来自其他应用（$identity），已拒绝恢复")
        val appVersionName = reader.readString("应用版本名")
        val appVersionCode = reader.readInt()
        val schema = reader.readInt()
        if (schema != DB_SCHEMA_VERSION) {
            throw BackupFormatException("备份数据库版本 $schema 与当前 $DB_SCHEMA_VERSION 不一致，已拒绝恢复")
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
            if (entryKey.isEmpty() || headword.isEmpty()) throw BackupFormatException("备份存在空词条键或空词头")
            if (entryKey != headword) throw BackupFormatException("备份词条键与词头不一致（$entryKey / $headword）")
            if (!keys.add(entryKey)) throw BackupFormatException("备份存在重复词条键：$entryKey")
            words += BackupWord(entryKey, headword, partOfSpeech, meaning, source, version, mastered, note)
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

        if (reader.remaining != 0) throw BackupFormatException("备份在声明内容之后存在多余字节")
        return LearningBackup(appVersionName, appVersionCode, schema, createdAt, LearningBackupData(words, events))
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
