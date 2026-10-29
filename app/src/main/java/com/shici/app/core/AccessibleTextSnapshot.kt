package com.shici.app.core

/**
 * One direct-read result: the visible external page text, already reduced to word boxes.
 *
 * Ownership and lifetime:
 * - In memory only. Never written to disk, preferences, logs, backups or the network; the
 *   accessibility service drops every reference when the request finishes, the short token is
 *   invalidated, the user closes the in-app switch or the system service disconnects.
 * - Only produced while an explicit floating-highlighter request is in flight. Ordinary
 *   accessibility events never collect or cache text; they only invalidate the short token and
 *   notify change listeners without a body.
 *
 * Coordinate basis:
 * - [words] bounds and [viewport] are Android screen coordinates in physical pixels, as returned by
 *   AccessibilityNodeInfo character locations / node bounds and AccessibilityWindowInfo bounds.
 *   They are not overlay-view dp and not screenshot-crop coordinates; the consumer must map them
 *   with the same screen/display basis the capture pipeline uses.
 *
 * Honesty:
 * - [words] only contains words backed by per-character location rectangles, or a node whose whole
 *   text is exactly one word and whose exact node bounds were used instead. A multi-word node
 *   whose character boxes are unavailable never gets a divided/fabricated rectangle; the whole
 *   read reports [AccessibleTextResult.Unavailable] so the capture path can fall back to OCR.
 */
data class AccessibleTextSnapshot(
    val windowId: Int,
    val packageName: String,
    /** Monotonic content/window generation observed for the whole read; a later change invalidates it. */
    val generation: Long,
    val fetchedAtMillis: Long,
    /** Target application window bounds in screen pixels; may extend beyond the display. */
    val viewport: FloatBox,
    val words: List<HighlighterWord>
)

/**
 * A text-free change notification for coordinate following and late-result rejection.
 *
 * It deliberately contains no body text, no node and no word list. Consumers keep their own copy of
 * the last [AccessibleTextSnapshot] and must invalidate it whenever any notification arrives whose
 * [generation] differs, whose window/package differs, or whose [kind] is [Kind.DISCONNECT]/[Kind.DISABLED].
 * [windowId] is -1 and [packageName] is empty for bridge-level events such as a switch disable.
 */
data class AccessibleTextChange(
    val generation: Long,
    val kind: Kind,
    val windowId: Int,
    val packageName: String
) {
    enum class Kind { WINDOW, CONTENT, SCROLL, CONNECT, DISCONNECT, DISABLED }
}

/**
 * Terminal outcome of one direct-read request. Every request is answered exactly once on the main
 * thread, either with [Available] or with an honest [Unavailable]; a cancelled, superseded, changed
 * or disconnected request never turns into a late [Available].
 */
sealed class AccessibleTextResult {
    data class Available(val snapshot: AccessibleTextSnapshot) : AccessibleTextResult()
    data class Unavailable(val reason: Reason) : AccessibleTextResult()

    enum class Reason {
        /** The in-app switch is off; nothing was read even if the system service is enabled. */
        NOT_ENABLED,
        /** The in-app switch is on but the accessibility service is not connected. */
        NOT_CONNECTED,
        /** No readable, visible external application window (own app, keyboard, system included). */
        NO_TARGET,
        /** The page exposed no English word with a trustworthy position. */
        NO_WORDS,
        /** Readable text existed but its character boxes could not be obtained honestly. */
        UNREADABLE,
        /** Window, package or content generation changed during the read. */
        CHANGED,
        /** The service or the in-app switch cancelled the in-flight read. */
        CANCELLED,
        /** A newer request replaced this read before it finished. */
        SUPERSEDED,
        /** The bounded read did not finish in its short lifetime. */
        TIMEOUT,
        /** Node/text/word budget was exceeded; a partial tree is never presented as complete. */
        TOO_LARGE,
        /** Unexpected platform failure. */
        ERROR
    }
}
