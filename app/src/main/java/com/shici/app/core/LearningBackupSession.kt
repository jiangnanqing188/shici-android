package com.shici.app.core

import java.io.File

/** The current owner alone may hand off this app-private snapshot or delete it. */
internal data class FrozenLearningBackupFile(val file: File, val fingerprint: BackupFileFingerprint, val revision: Long) {
    fun discard() { file.delete() }
}

/**
 * Main-thread export session. It holds one frozen private file while the picker is open.
 * A late or duplicate result cannot consume it again. Request codes share the PDF allocator.
 */
class LearningBackupExportSession {
    private enum class Stage { IDLE, LOADING, PICKER, WRITING }
    private var stage = Stage.IDLE
    private var requestId: Int? = null
    private var bytes: ByteArray? = null
    private var file: FrozenLearningBackupFile? = null

    val busy get() = stage != Stage.IDLE
    val fileRequestCode: Int? get() = if (stage == Stage.PICKER) requestId else null
    internal val preparingRequestCode: Int? get() = if (stage == Stage.LOADING) requestId else null

    fun begin(): Int? {
        if (busy) return null
        return LearningExportSession.allocateRequestCode().also { requestId = it; stage = Stage.LOADING }
    }
    /** Legacy in-memory API for small fixtures; the Activity hands off a file instead. */
    fun freeze(id: Int, data: ByteArray): Boolean {
        if (!freezePicker(id)) return false
        bytes = data
        return true
    }
    internal fun freezeFile(id: Int, snapshot: FrozenLearningBackupFile): Boolean {
        if (!freezePicker(id)) return false
        file = snapshot
        return true
    }
    private fun freezePicker(id: Int): Boolean {
        if (requestId != id || stage != Stage.LOADING) return false
        stage = Stage.PICKER
        return true
    }
    fun takeResult(code: Int): ByteArray? {
        if (fileRequestCode != code || bytes == null) return null
        stage = Stage.WRITING
        return bytes.also { bytes = null }
    }
    /** A matching picker consumes the session's ownership exactly once. */
    internal fun takeFileResult(code: Int): FrozenLearningBackupFile? {
        if (fileRequestCode != code || file == null) return null
        stage = Stage.WRITING
        return file.also { file = null }
    }
    fun cancel(code: Int): Boolean {
        if (requestId != code) return false
        clear(); return true
    }
    /** Cancels a preparation that never reached the picker; a live picker is preserved. */
    fun cancelPreparation(code: Int? = null): Boolean {
        if (code != null && requestId != code) return false
        if (stage != Stage.LOADING) return false
        clear(); return true
    }
    fun clear() {
        // One private unlink only. The session never deletes a file already handed off to a worker.
        file?.discard(); file = null
        requestId = null; bytes = null; stage = Stage.IDLE
    }
}

/**
 * Main-thread restore session. It owns a validated private import file and walks: pick import file →
 * read/validate → preview → pick safety-backup location → apply. The on-screen file pickers stay
 * valid while [Stage.AWAIT_IMPORT]/[Stage.AWAIT_SAFETY]; only the non-picker preparation stages are
 * cancelled on stop, so an ACTION_OPEN/CREATE_DOCUMENT that stops the activity is never dropped.
 */
class LearningRestoreSession {
    enum class Stage { IDLE, AWAIT_IMPORT, READING_IMPORT, PREVIEW, AWAIT_SAFETY, APPLYING }
    private var stage = Stage.IDLE
    private var importId: Int? = null
    private var safetyId: Int? = null
    private var importUri: String? = null
    private var frozen: LearningBackup? = null // Small-payload compatibility API only.
    private var staged: LearningBackupCodec.StagedImport? = null
    private var apply: ApplyHandle? = null

    val busy get() = stage != Stage.IDLE
    val importRequestCode: Int? get() = if (stage == Stage.AWAIT_IMPORT) importId else null
    val safetyRequestCode: Int? get() = if (stage == Stage.AWAIT_SAFETY) safetyId else null
    internal val readingRequestCode: Int? get() = if (stage == Stage.READING_IMPORT) importId else null
    val pendingImportUri: String? get() = importUri
    /** Cancellation identity of the in-flight apply, safe to read from the worker thread; null when idle. */
    val applyHandle: ApplyHandle? get() = apply

    fun beginImport(): Int? {
        if (busy) return null
        return LearningExportSession.allocateRequestCode().also { importId = it; stage = Stage.AWAIT_IMPORT }
    }
    fun beginReading(code: Int, uri: String): Boolean {
        if (importId != code || stage != Stage.AWAIT_IMPORT) return false
        importUri = uri; stage = Stage.READING_IMPORT
        return true
    }
    fun freezeImport(code: Int, backup: LearningBackup): Boolean {
        if (importId != code || stage != Stage.READING_IMPORT) return false
        frozen = backup; stage = Stage.PREVIEW
        return true
    }
    /** Transfers ownership only if this is still the matching import. */
    internal fun freezeStagedImport(code: Int, source: LearningBackupCodec.StagedImport): Boolean {
        if (importId != code || stage != Stage.READING_IMPORT) return false
        staged = source; stage = Stage.PREVIEW
        return true
    }
    fun cancelImport(code: Int): Boolean {
        if (importId != code) return false
        clear(); return true
    }
    /** Confirms the preview and allocates the safety-backup document request code. */
    fun confirm(): Int? {
        if (stage != Stage.PREVIEW) return null
        return LearningExportSession.allocateRequestCode().also { safetyId = it; stage = Stage.AWAIT_SAFETY }
    }
    fun cancelPreview(): Boolean {
        if (stage != Stage.PREVIEW) return false
        clear(); return true
    }
    fun cancelSafety(code: Int): Boolean {
        if (safetyId != code || stage != Stage.AWAIT_SAFETY) return false
        clear(); return true
    }
    /**
     * Consumes the safety-backup picker result and returns the frozen payload for the apply step,
     * opening a fresh [ApplyHandle] that [clear] (onDestroy / a replaced session) invalidates.
     */
    fun beginApply(code: Int): LearningBackup? {
        if (safetyId != code || stage != Stage.AWAIT_SAFETY) return null
        val data = frozen ?: return null
        stage = Stage.APPLYING
        apply = ApplyHandle()
        return data
    }
    /** Main keeps only the preview; the handle owns the file until it admits a worker. */
    internal fun beginStagedApply(code: Int): LearningBackupPreview? {
        if (safetyId != code || stage != Stage.AWAIT_SAFETY) return null
        val source = staged ?: return null
        stage = Stage.APPLYING
        apply = ApplyHandle(source)
        staged = null
        return source.preview
    }
    fun finish(code: Int? = null): Boolean {
        if (code != null && safetyId != code) return false
        if (stage != Stage.APPLYING) return false
        clear(); return true
    }
    /** Cancels only read/preview preparation; file pickers and an in-flight apply are preserved. */
    fun cancelPreparation(code: Int? = null): Boolean {
        if (code != null && importId != code) return false
        if (stage != Stage.READING_IMPORT && stage != Stage.PREVIEW) return false
        clear(); return true
    }
    fun clear() {
        apply?.invalidate(); apply = null
        staged?.close(); staged = null
        importId = null; safetyId = null; importUri = null; frozen = null; stage = Stage.IDLE
    }

    /**
     * One-shot, cross-thread cancellation identity for a single apply. The session/main thread calls
     * [invalidate] from [clear]; the worker thread reads [isActive] and calls [admit]. [admit] is the
     * single linearization point for entering the overwrite transaction: if it returns false the apply
     * was invalidated first and the worker must not touch the database; once it returns true a later
     * destroy/clear may no longer cancel the atomic commit. Only the short state changes below are
     * synchronized — no lock is held across the safety-backup I/O, so a destroy is never blocked.
     */
    class ApplyHandle internal constructor(private var source: LearningBackupCodec.StagedImport? = null) {
        @Volatile private var active = true
        @Volatile private var admitted = false
        /** True once [admit] has entered the overwrite; lets a callback report only its own completion. */
        val isAdmitted get() = admitted
        /** True while this apply has not been invalidated by a session clear/replacement. */
        fun isActive(): Boolean = active
        /** Atomically admits the overwrite, or returns false when the apply was invalidated first. */
        fun admit(): Boolean = admit { true }
        @Synchronized internal fun admit(isCurrent: () -> Boolean): Boolean =
            source == null && tryAdmit(isCurrent)

        /** Admission and file handoff are one operation; clear can never unlink an admitted source. */
        @Synchronized internal fun admitStaged(isCurrent: () -> Boolean): LearningBackupCodec.StagedImport? {
            val file = source ?: return null
            if (!tryAdmit(isCurrent)) return null
            source = null
            return file
        }

        private fun tryAdmit(isCurrent: () -> Boolean): Boolean {
            if (!active || admitted || !isCurrent()) return false
            admitted = true
            return true
        }
        /** Timeout may win before admission, but must never report a committed write as cancelled. */
        @Synchronized internal fun cancelBeforeAdmission(): Boolean {
            if (!active || admitted) return false
            active = false
            return true
        }
        internal fun invalidate() {
            val abandoned = synchronized(this) {
                active = false
                source.also { source = null }
            }
            abandoned?.close()
        }
    }
}
