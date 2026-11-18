package com.shici.app.ui

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

internal data class PersonalEntryDraft(
    val headword: String, val pos: String, val meaning: String,
    val baseUpdatedAt: Long, val newHeadword: Boolean
)

internal data class ActivityDraftState(
    val search: String?, val lookup: String?, val translation: String?,
    val personalHeadword: String?, val personalDraft: PersonalEntryDraft?,
    val exportSelection: Set<String>?, val selectedKey: String?, val pageAnchor: String?
) {
    /** This is disposable UI state, not learning data. A Bundle holds only the owner/snapshot IDs. */
    class Store(context: Context, owner: String) {
        // One slot per logical Activity. A different save's ID cannot satisfy an older Bundle.
        private val file = AtomicFile(File(context.cacheDir, "activity-drafts-$owner"))

        fun write(id: String, state: ActivityDraftState) {
            val output = try { file.startWrite() } catch (_: IOException) { return }
            try {
                val data = DataOutputStream(output.buffered())
                data.writeUTF(id)
                data.writeDraft(state.search)
                data.writeDraft(state.lookup)
                data.writeDraft(state.translation)
                data.writeDraft(state.personalHeadword)
                val personal = state.personalDraft
                data.writeBoolean(personal != null)
                if (personal != null) {
                    data.writeDraft(personal.headword)
                    data.writeDraft(personal.pos)
                    data.writeDraft(personal.meaning)
                    data.writeLong(personal.baseUpdatedAt)
                    data.writeBoolean(personal.newHeadword)
                }
                val selection = state.exportSelection?.takeIf { keys ->
                    keys.size <= MAX_KEYS && keys.all { it.length <= MAX_DRAFT_CHARS } &&
                        keys.sumOf { it.length.toLong() + 2 } <= MAX_KEY_UNITS
                }
                data.writeInt(selection?.size ?: -1)
                selection?.forEach { data.writeDraft(it) }
                data.writeDraft(state.selectedKey)
                data.writeDraft(state.pageAnchor)
                data.flush()
                file.finishWrite(output)
            } catch (_: IOException) {
                file.failWrite(output)
            }
        }

        fun read(id: String): ActivityDraftState? = try {
            DataInputStream(file.openRead().buffered()).use { data ->
                if (data.readUTF() != id) return@use null
                val search = data.readDraft()
                val lookup = data.readDraft()
                val translation = data.readDraft()
                val headword = data.readDraft()
                val personal = if (data.readBoolean()) {
                    val word = data.readDraft()
                    val pos = data.readDraft()
                    val meaning = data.readDraft()
                    val base = data.readLong()
                    val new = data.readBoolean()
                    if (word != null && pos != null && meaning != null)
                        PersonalEntryDraft(word, pos, meaning, base, new) else null
                } else null
                val count = data.readInt()
                if (count !in -1..MAX_KEYS) throw IOException("Invalid selection size")
                var remaining = MAX_KEY_UNITS
                val selection = if (count == -1) null else buildSet<String> {
                    repeat(count) {
                        val key = data.readDraft(minOf(MAX_DRAFT_CHARS, remaining - 2))
                            ?: throw IOException("Missing selection key")
                        remaining -= key.length + 2
                        if (!add(key)) throw IOException("Duplicate selection key")
                    }
                }
                ActivityDraftState(search, lookup, translation, headword, personal, selection,
                    data.readDraft(), data.readDraft())
            }
        } catch (_: IOException) { null }

        fun delete() = file.delete()

        private fun DataOutputStream.writeDraft(value: String?) {
            if (value == null || value.length > MAX_DRAFT_CHARS) {
                writeInt(-1)
                return
            }
            writeInt(value.length)
            writeChars(value)
        }

        private fun DataInputStream.readDraft(maxChars: Int = MAX_DRAFT_CHARS): String? {
            val chars = readInt()
            if (chars == -1) return null
            if (chars !in 0..maxChars) throw IOException("Invalid draft length")
            return String(CharArray(chars) { readChar() })
        }

        companion object {
            private const val MAX_DRAFT_CHARS = 256 * 1024
            private const val MAX_KEYS = 200_000
            private const val MAX_KEY_UNITS = 4 * 1024 * 1024
        }
    }
}
