package com.shici.app.core

/**
 * Main-thread backup export session. It reads the current database once, freezes the encoded bytes,
 * opens a document picker and writes exactly those bytes; a late or duplicate picker callback cannot
 * reuse an already-consumed payload. Request codes come from [LearningExportSession.allocateRequestCode]
 * so a backup never collides with a PDF export.
 */
class LearningBackupExportSession {
    private enum class Stage { IDLE, LOADING, PICKER, WRITING }
    private var stage = Stage.IDLE
    private var requestId: Int? = null
    private var bytes: ByteArray? = null

    val busy get() = stage != Stage.IDLE
    val fileRequestCode: Int? get() = if (stage == Stage.PICKER) requestId else null

    fun begin(): Int? {
        if (busy) return null
        return LearningExportSession.allocateRequestCode().also { requestId = it; stage = Stage.LOADING }
    }
    fun freeze(id: Int, data: ByteArray): Boolean {
        if (requestId != id || stage != Stage.LOADING) return false
        bytes = data; stage = Stage.PICKER
        return true
    }
    fun takeResult(code: Int): ByteArray? {
        if (requestId != code || stage != Stage.PICKER) return null
        stage = Stage.WRITING
        return bytes.also { bytes = null }
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
    fun clear() { requestId = null; bytes = null; stage = Stage.IDLE }
}

/**
 * Main-thread restore session. It freezes the validated import payload and walks: pick import file →
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
    private var frozen: LearningBackup? = null
    private var apply: ApplyHandle? = null

    val busy get() = stage != Stage.IDLE
    val importRequestCode: Int? get() = if (stage == Stage.AWAIT_IMPORT) importId else null
    val safetyRequestCode: Int? get() = if (stage == Stage.AWAIT_SAFETY) safetyId else null
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
        stage = Stage.APPLYING
        apply = ApplyHandle()
        return frozen
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
    class ApplyHandle internal constructor() {
        @Volatile private var active = true
        @Volatile private var admitted = false
        /** True once [admit] has entered the overwrite; lets a callback report only its own completion. */
        val isAdmitted get() = admitted
        /** True while this apply has not been invalidated by a session clear/replacement. */
        fun isActive(): Boolean = active
        /** Atomically admits the overwrite, or returns false when the apply was invalidated first. */
        @Synchronized fun admit(): Boolean {
            if (!active) return false
            admitted = true
            return true
        }
        @Synchronized internal fun invalidate() { active = false }
    }
}
