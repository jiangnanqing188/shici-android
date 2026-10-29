package com.shici.app.core

/**
 * Minimal page-identity authority shared by the pixel-fingerprint path and text/direct-read
 * paths.
 *
 * A guard is created only after a page identity has been accepted (a clean entry/lift frame for
 * the pixel path, or an accessibility/direct-read snapshot for the text path). It is one-way:
 * once invalidated it stays invalid, so a queued callback from a retired lookup cannot reopen it.
 * It carries only an opaque [generation], the page [body] region it was proven for and the
 * validity bit; it never exposes pixels, OCR text or a bitmap.
 *
 * The pixel implementation is [LivePageGuard]. A text/direct-read guard is expected to implement
 * this interface with its own identity and invalidation subscription; it must not fabricate an
 * empty [LiveFrameFingerprint] merely to satisfy the pixel API, and callers must not require an
 * Android screenshot from it.
 */
interface LookupPageGuard {
    /** Monotonic identity of the lookup/page authority that owns this guard. */
    val generation: Long

    /** Page region the identity was proven for, in screenshot pixels. */
    val body: PixelBox

    /** False after the page changed, the lookup ended, or the guard's bounded lifetime expired. */
    val isValid: Boolean

    /**
     * Invalidates the guard exactly once. Returns true only for the caller that performed the
     * transition, so two racing observers can agree on who owns the resulting UI/cancel action.
     */
    fun invalidate(): Boolean
}

/**
 * Page identity proven by one direct-read snapshot of an external application window.
 *
 * This guard deliberately carries no pixels and no fabricated [LiveFrameFingerprint]; it is the
 * text-path implementation of [LookupPageGuard]. Its identity consists of the window, package and
 * content generation the snapshot was read from. [invalidatedBy] answers whether a later text-free
 * change notification disproves that same identity; the caller still owns the gate/UI retirement.
 *
 * [generation] is the same opaque page-counter used by [LivePageGuard] and only names the lookup
 * authority; [contentGeneration] is the snapshot's own generation and must not be replaced by it.
 */
class AccessiblePageGuard(
    override val generation: Long,
    override val body: PixelBox,
    val windowId: Int,
    val packageName: String,
    val contentGeneration: Long
) : LookupPageGuard {
    @Volatile private var valid = true

    override val isValid: Boolean get() = valid

    @Synchronized override fun invalidate(): Boolean {
        if (!valid) return false
        valid = false
        return true
    }

    /**
     * True when [change] makes the snapshot no longer provable: the service disconnected or was
     * switched off, the content/window generation advanced, or a different window/package now owns
     * the event. The in-app disable notification carries generation 0, so the kind is checked first.
     */
    fun invalidatedBy(change: AccessibleTextChange): Boolean {
        if (!valid) return false
        return when (change.kind) {
            AccessibleTextChange.Kind.DISCONNECT,
            AccessibleTextChange.Kind.DISABLED -> true
            else -> change.generation != contentGeneration ||
                (change.windowId > 0 && change.windowId != windowId) ||
                (change.packageName.isNotEmpty() && change.packageName != packageName)
        }
    }
}
