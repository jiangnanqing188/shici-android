package com.shici.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.shici.app.core.AccessibleTextChange
import com.shici.app.core.AccessibleTextResult
import com.shici.app.core.AccessibleTextSnapshot
import com.shici.app.core.FloatBox
import com.shici.app.core.HighlighterWord
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cancellation handle for one direct-read request.
 *
 * [cancel] may be called from any thread and is idempotent. Cancelling before the request reaches
 * the service prevents it from starting; cancelling while it runs makes the service drop the job,
 * close its root references and never deliver a usable snapshot. A cancelled request also never
 * revives through a queued callback from an older window generation.
 */
class AccessibleTextRequest internal constructor() {
    private val lock = Any()
    private var cancelled = false
    private var onCancel: (() -> Unit)? = null

    val isCancelled: Boolean get() = synchronized(lock) { cancelled }

    fun cancel() {
        val action = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            val pending = onCancel
            onCancel = null
            pending
        }
        action?.invoke()
    }

    internal fun attach(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (cancelled) true else {
                onCancel = action
                false
            }
        }
        if (runNow) action()
    }
}

/** Hard bounds for one direct-read request. Exceeding any bound fails honestly, never partially. */
object AccessibleTextLimits {
    const val MAX_NODES = 512
    const val MAX_TEXT_CHARS = 24_000
    const val MAX_NODE_REQUEST_CHARS = 512
    const val MAX_WORDS = 512
    const val MAX_WORD_CHARS = 64
    const val READ_NODE_BATCH = 64
    const val MAX_QUEUED_NODES = 1024
    const val REQUEST_TIMEOUT_MS = 5_000L
}

/**
 * Optional, user-authorised direct text reader.
 *
 * Contract:
 * - The system service may be enabled by the user in Android accessibility settings, but it reads
 *   nothing unless the in-app switch stored by [AccessibleTextBridge] is also on.
 * - Text is only read while [AccessibleTextBridge.requestSnapshot] is in flight (one explicit
 *   floating-highlighter request). Events received while idle only bump the content generation and
 *   invalidate the short token; they never read, cache or log page text.
 * - It never performs gestures, never enables touch exploration, never writes to disk and never
 *   uploads. Password nodes and API-flagged sensitive nodes are skipped with their subtree.
 * - Multi-word nodes without per-character rectangles never get a divided or fabricated rectangle:
 *   the whole read reports Unavailable so the caller can fall back to OCR.
 */
class AccessibleTextService : AccessibilityService() {
    companion object {
        private val SINGLE_WORD = Regex("[A-Za-z\\u00C0-\\u024F]+(?:['’-][A-Za-z\\u00C0-\\u024F]+)*")

        @Volatile private var instanceRef: AccessibleTextService? = null
        internal fun connectedInstance(): AccessibleTextService? = instanceRef
    }

    /**
     * Current text-change generation of this connected service. Main-thread only. The capture
     * service compares it with the generation carried by an Available snapshot immediately before
     * mounting the text page guard, so an event delivered between snapshot completion and the
     * guard mount cannot be missed by the listener that was not yet subscribed to that guard.
     */
    internal fun currentGeneration(): Long = changeSequence

    private val main = Handler(Looper.getMainLooper())
    private var changeSequence = 0L
    private var pending: ReadJob? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = this
        changeSequence += 1
        AccessibleTextBridge.dispatchChange(
            AccessibleTextChange(changeSequence, AccessibleTextChange.Kind.CONNECT, -1, "")
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event ?: return
        val eventPackage = source.packageName?.toString().orEmpty()
        // Our own bubble/card windows must not invalidate a read of the external page.
        if (eventPackage == packageName) return
        changeSequence += 1
        pending?.let { finish(it, unavailable(AccessibleTextResult.Reason.CHANGED)) }
        AccessibleTextBridge.dispatchChange(
            AccessibleTextChange(changeSequence, kindOf(source.eventType), source.windowId, eventPackage)
        )
    }

    override fun onInterrupt() {
        // Only the short token is dropped; no text was collected for an idle service.
        pending?.let { finish(it, unavailable(AccessibleTextResult.Reason.CANCELLED)) }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    /** Called by the bridge when the in-app switch is turned off. Clears every in-memory reference. */
    internal fun cancelReadingFromBridge() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancelReadingFromBridge() }
            return
        }
        pending?.let { finish(it, unavailable(AccessibleTextResult.Reason.CANCELLED)) }
    }

    private fun disconnect() {
        if (instanceRef === this) instanceRef = null
        pending?.let { finish(it, unavailable(AccessibleTextResult.Reason.CANCELLED)) }
        // Advance the generation too: a consumer that only compares generations must also see the
        // disconnect, not just listeners that inspect the kind.
        changeSequence += 1
        AccessibleTextBridge.dispatchChange(
            AccessibleTextChange(changeSequence, AccessibleTextChange.Kind.DISCONNECT, -1, "")
        )
    }

    /**
     * Starts one bounded read and returns an idempotent cancellation closure.
     *
     * The bridge calls this on the main thread, but the closure may run from any thread. A request
     * cancelled before this method reaches the main thread never starts; one cancelled after it
     * started is finished on the main thread and clears its node/text references immediately.
     */
    internal fun beginSnapshot(
        requestId: Long,
        isCancelled: () -> Boolean,
        callback: (AccessibleTextResult) -> Unit
    ): () -> Unit {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { beginSnapshot(requestId, isCancelled, callback) }
            return { main.post { cancelSnapshot(requestId) } }
        }
        // Pre-start cancellation must not read a node or start a timer.
        if (isCancelled()) {
            replyLater(callback, AccessibleTextResult.Reason.CANCELLED)
            return {}
        }
        // A newer request retires the older one before it can deliver a late Available.
        pending?.let { finish(it, unavailable(AccessibleTextResult.Reason.SUPERSEDED)) }
        if (instanceRef !== this) {
            replyLater(callback, AccessibleTextResult.Reason.NOT_CONNECTED)
            return {}
        }
        if (!AccessibleTextBridge.isEnabled(this)) {
            replyLater(callback, AccessibleTextResult.Reason.NOT_ENABLED)
            return {}
        }
        val target = selectTarget()
        if (target == null) {
            replyLater(callback, AccessibleTextResult.Reason.NO_TARGET)
            return {}
        }
        val job = ReadJob(
            requestId = requestId,
            startSequence = changeSequence,
            windowId = target.windowId,
            packageName = target.packageName,
            viewport = target.viewport,
            fromWindows = target.fromWindows,
            root = target.root,
            callback = callback
        )
        pending = job
        job.stack.addLast(target.root)
        val timeout = Runnable {
            if (pending === job) {
                finish(job, unavailable(AccessibleTextResult.Reason.TIMEOUT))
            }
        }
        job.timeout = timeout
        main.postDelayed(timeout, AccessibleTextLimits.REQUEST_TIMEOUT_MS)
        main.post { step(job) }
        return {
            if (Looper.myLooper() == Looper.getMainLooper()) cancelSnapshot(requestId)
            else main.post { cancelSnapshot(requestId) }
        }
    }

    /** Cancels only the job with this exact request id; a newer request is never touched. */
    private fun cancelSnapshot(requestId: Long) {
        val job = pending ?: return
        if (job.requestId != requestId || job.delivered) return
        finish(job, unavailable(AccessibleTextResult.Reason.CANCELLED))
    }

    private fun step(job: ReadJob) {
        if (pending !== job || job.delivered) return
        if (instanceRef !== this) {
            finish(job, unavailable(AccessibleTextResult.Reason.NOT_CONNECTED))
            return
        }
        if (!AccessibleTextBridge.isEnabled(this)) {
            finish(job, unavailable(AccessibleTextResult.Reason.NOT_ENABLED))
            return
        }
        // Events run between batches on the same main looper, so a window/content change during the
        // read is observed here and the whole read is refused instead of returning mixed generations.
        if (changeSequence != job.startSequence) {
            finish(job, unavailable(AccessibleTextResult.Reason.CHANGED))
            return
        }
        if (!windowStillValid(job)) {
            finish(job, unavailable(AccessibleTextResult.Reason.CHANGED))
            return
        }
        var processed = 0
        try {
            while (processed < AccessibleTextLimits.READ_NODE_BATCH && job.stack.isNotEmpty()) {
                val node = job.stack.removeLast()
                processed += 1
                job.nodesSeen += 1
                if (job.nodesSeen > AccessibleTextLimits.MAX_NODES) {
                    finish(job, unavailable(AccessibleTextResult.Reason.TOO_LARGE))
                    return
                }
                if (!node.isVisibleToUser) continue
                if (node.packageName?.toString().orEmpty() == packageName) continue
                if (node.isPassword || isDataSensitive(node)) continue
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) {
                    if (job.textChars + text.length > AccessibleTextLimits.MAX_TEXT_CHARS) {
                        finish(job, unavailable(AccessibleTextResult.Reason.TOO_LARGE))
                        return
                    }
                    job.textChars += text.length
                    if (!collectNode(job, node, text)) {
                        finish(
                            job,
                            unavailable(
                                if (job.words.size > AccessibleTextLimits.MAX_WORDS) {
                                    AccessibleTextResult.Reason.TOO_LARGE
                                } else {
                                    AccessibleTextResult.Reason.UNREADABLE
                                }
                            )
                        )
                        return
                    }
                }
                val childCount = node.childCount
                for (index in 0 until childCount) {
                    if (job.nodesSeen + job.stack.size >= AccessibleTextLimits.MAX_QUEUED_NODES) {
                        finish(job, unavailable(AccessibleTextResult.Reason.TOO_LARGE))
                        return
                    }
                    val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                    job.stack.addLast(child)
                }
            }
        } catch (exception: Exception) {
            finish(job, unavailable(AccessibleTextResult.Reason.ERROR))
            return
        }
        if (job.stack.isNotEmpty()) {
            main.post { step(job) }
            return
        }
        // Yield one main-loop turn before accepting the result so any accessibility event queued
        // while the last batch was running is dispatched first and invalidates the generation.
        main.post {
            try {
                complete(job)
            } catch (exception: Exception) {
                finish(job, unavailable(AccessibleTextResult.Reason.ERROR))
            }
        }
    }

    private fun complete(job: ReadJob) {
        // Final ownership/lifecycle checks: a cancelled, superseded, disconnected or switched-off
        // request must not be completed by a queued main-loop turn.
        if (pending !== job || job.delivered) return
        if (instanceRef !== this) {
            finish(job, unavailable(AccessibleTextResult.Reason.NOT_CONNECTED))
            return
        }
        if (!AccessibleTextBridge.isEnabled(this)) {
            finish(job, unavailable(AccessibleTextResult.Reason.NOT_ENABLED))
            return
        }
        if (changeSequence != job.startSequence || !windowStillValid(job)) {
            finish(job, unavailable(AccessibleTextResult.Reason.CHANGED))
            return
        }
        val ordered = orderWords(job.words)
        if (ordered.isEmpty()) {
            finish(job, unavailable(AccessibleTextResult.Reason.NO_WORDS))
            return
        }
        if (ordered.size > AccessibleTextLimits.MAX_WORDS) {
            finish(job, unavailable(AccessibleTextResult.Reason.TOO_LARGE))
            return
        }
        val snapshot = AccessibleTextSnapshot(
            windowId = job.windowId,
            packageName = job.packageName,
            generation = job.startSequence,
            fetchedAtMillis = System.currentTimeMillis(),
            viewport = job.viewport,
            words = ordered
        )
        finish(job, AccessibleTextResult.Available(snapshot))
    }

    private fun replyLater(
        callback: (AccessibleTextResult) -> Unit,
        reason: AccessibleTextResult.Reason
    ) {
        main.post { callback(unavailable(reason)) }
    }

    private fun finish(job: ReadJob, result: AccessibleTextResult) {
        if (job.delivered) return
        job.delivered = true
        if (pending === job) pending = null
        job.timeout?.let { main.removeCallbacks(it) }
        // Drop every node/text reference immediately. An Available result is an independent copy.
        job.stack.clear()
        job.words.clear()
        job.root = null
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { job.callback(result) }
        } else {
            job.callback(result)
        }
    }

    // ---- Target selection ---------------------------------------------------------------------

    private class Target(
        val windowId: Int,
        val packageName: String,
        val viewport: FloatBox,
        val root: AccessibilityNodeInfo,
        val fromWindows: Boolean
    )

    private fun selectTarget(): Target? {
        val own = packageName
        val imePackages = inputMethodPackages()
        // Only the interactive-window list is accepted. A bare rootInActiveWindow could point at a
        // background application behind this app's own card or an external sensitive overlay; the
        // safe answer in that case is "no target" (the caller falls back to OCR), never an
        // arbitrary background read.
        val interactive = runCatching { windows }.getOrNull() ?: return null
        if (interactive.isEmpty()) return null
        val ordered = interactive
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isActive }
                    .thenByDescending { it.isFocused }
            )
        for (window in ordered) {
            // The target must be the window the user is actually interacting with. A background
            // application behind a sensitive overlay or our own active window is not eligible.
            if (!window.isActive && !window.isFocused) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            if (!root.isVisibleToUser) continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == own) {
                // Our own foreground application window is not an external page. Returning null
                // also prevents reading whatever arbitrary app happens to be behind it.
                return null
            }
            if (!isReadablePackage(pkg, own, imePackages)) continue
            if (isDataSensitive(root)) continue
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            return Target(
                window.id,
                pkg,
                FloatBox(
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat()
                ),
                root,
                true
            )
        }
        return null
    }

    private fun isReadablePackage(pkg: String, own: String, imePackages: Set<String>): Boolean =
        pkg.isNotBlank() && pkg != own && pkg !in imePackages &&
            pkg != "com.android.systemui" &&
            pkg != "com.android.permissioncontroller" &&
            pkg != "com.google.android.permissioncontroller"

    private fun inputMethodPackages(): Set<String> {
        val manager = getSystemService(InputMethodManager::class.java) ?: return emptySet()
        return runCatching {
            manager.enabledInputMethodList.mapNotNull { it.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun windowStillValid(job: ReadJob): Boolean {
        if (job.fromWindows) {
            val interactive = runCatching { windows }.getOrNull() ?: return false
            val window = interactive.firstOrNull { it.id == job.windowId } ?: return false
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return false
            val root = runCatching { window.root }.getOrNull() ?: return false
            if (!root.isVisibleToUser) return false
            if (root.packageName?.toString() != job.packageName) return false
            if (isDataSensitive(root)) return false
            // The read may only continue while the same application window still owns the user's
            // interaction. Losing focus/activity means a different surface (sensitive overlay, our
            // own UI or IME) is now in front and the previously selected tree is no longer the
            // page the user explicitly highlighted.
            return window.isActive || window.isFocused
        }
        val root = job.root ?: return false
        return runCatching { root.refresh() }.getOrDefault(false) &&
            root.packageName?.toString() == job.packageName && !isDataSensitive(root)
    }

    // ---- Text gathering -----------------------------------------------------------------------

    private fun collectNode(job: ReadJob, node: AccessibilityNodeInfo, text: String): Boolean {
        var emitted = 0
        var candidates = 0
        var incomplete = false
        var start = 0
        while (start < text.length) {
            val end = chunkEnd(text, start)
            val rects = characterRects(node, start, end - start)
            if (rects == null) {
                incomplete = true
                candidates += countWordCandidates(text, start, end)
            } else {
                val result = extractWords(text, start, end, rects, job.words)
                emitted += result.emitted
                candidates += result.candidates
                if (result.incomplete > 0) incomplete = true
            }
            if (job.words.size > AccessibleTextLimits.MAX_WORDS) return false
            start = end
        }
        if (candidates == 0) return true
        if (emitted == 0) {
            // The one honest fallback that does not invent geometry: a node whose whole text is
            // exactly one word may use its exact node bounds. A multi-word node is never divided
            // into evenly split or otherwise fabricated rectangles.
            val single = singleWord(text)
            if (single != null) {
                val box = nodeBox(node)
                if (box != null) {
                    job.words += HighlighterWord(single, box, 0, box)
                    return job.words.size <= AccessibleTextLimits.MAX_WORDS
                }
            }
        }
        // A location failure or an incomplete per-character box for even one candidate word means
        // the node's positions are not trustworthy. Returning the other words would present a
        // partial tree as complete; refuse the whole read so the caller falls back to OCR.
        if (incomplete) return false
        return emitted > 0
    }

    private fun chunkEnd(text: String, start: Int): Int {
        val limit = minOf(start + AccessibleTextLimits.MAX_NODE_REQUEST_CHARS, text.length)
        if (limit >= text.length) return text.length
        var end = limit
        // Never split a word across two location requests.
        while (end > start && isWordLetter(text[end - 1]) && isWordLetter(text[end])) end -= 1
        return if (end > start) end else limit
    }

    private fun characterRects(node: AccessibilityNodeInfo, start: Int, length: Int): List<RectF?>? {
        if (length <= 0) return emptyList()
        return runCatching {
            val arguments = Bundle()
            arguments.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
            arguments.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
            val refreshed = node.refreshWithExtraData(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                arguments
            )
            if (!refreshed) return@runCatching null
            @Suppress("DEPRECATION")
            val array = node.extras
                ?.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
                ?: return@runCatching null
            if (array.size < length) return@runCatching null
            (0 until length).map { index -> array[index] as? RectF }
        }.getOrNull()
    }

    private data class WordScan(val emitted: Int, val candidates: Int, val incomplete: Int)

    private fun extractWords(
        text: String,
        start: Int,
        end: Int,
        rects: List<RectF?>,
        out: MutableList<HighlighterWord>
    ): WordScan {
        var candidates = 0
        var emitted = 0
        var incomplete = 0
        scanWordTokens(text, start, end) { wordStart, wordEnd ->
            candidates += 1
            var box: FloatBox? = null
            var complete = true
            for (index in wordStart until wordEnd) {
                val next = rectBox(rects.getOrNull(index - start))
                if (next == null) {
                    complete = false
                    break
                }
                box = box?.let { union(it, next) } ?: next
            }
            if (complete && box != null) {
                out += HighlighterWord(text.substring(wordStart, wordEnd), box, 0, box)
                emitted += 1
            } else {
                // Do not silently drop just this word and return the rest: one incomplete box
                // makes the node's whole position set untrustworthy.
                incomplete += 1
            }
        }
        return WordScan(emitted, candidates, incomplete)
    }

    private fun countWordCandidates(text: String, start: Int, end: Int): Int {
        var count = 0
        scanWordTokens(text, start, end) { _, _ -> count += 1 }
        return count
    }

    /** Scans only Latin-script word tokens; CJK/other scripts are not dictionary lookups here. */
    private fun scanWordTokens(text: String, start: Int, end: Int, onToken: (Int, Int) -> Unit) {
        var index = start
        while (index < end) {
            if (!isWordLetter(text[index])) {
                index += 1
                continue
            }
            var tokenEnd = index + 1
            while (tokenEnd < end) {
                val character = text[tokenEnd]
                if (isWordLetter(character)) {
                    tokenEnd += 1
                    continue
                }
                if (isWordJoiner(character) && tokenEnd + 1 < end && isWordLetter(text[tokenEnd + 1])) {
                    tokenEnd += 1
                    continue
                }
                break
            }
            if (tokenEnd - index <= AccessibleTextLimits.MAX_WORD_CHARS) onToken(index, tokenEnd)
            index = tokenEnd
        }
    }

    private fun isWordLetter(character: Char): Boolean =
        character.isLetter() && character.code <= 0x024F

    private fun isWordJoiner(character: Char): Boolean =
        character == '\'' || character == '’' || character == '-'

    private fun singleWord(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length !in 1..AccessibleTextLimits.MAX_WORD_CHARS) return null
        return trimmed.takeIf { SINGLE_WORD.matches(it) }
    }

    private fun rectBox(rect: RectF?): FloatBox? {
        if (rect == null) return null
        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        if (right - left <= 0f || bottom - top <= 0f) return null
        return FloatBox(left, top, right, bottom)
    }

    private fun nodeBox(node: AccessibilityNodeInfo): FloatBox? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return FloatBox(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat()
        )
    }

    private fun union(first: FloatBox, second: FloatBox): FloatBox = FloatBox(
        min(first.left, second.left),
        min(first.top, second.top),
        max(first.right, second.right),
        max(first.bottom, second.bottom)
    )

    private fun orderWords(words: List<HighlighterWord>): List<HighlighterWord> {
        if (words.isEmpty()) return emptyList()
        // A container and its child can expose the same text; keep one box per text/box pair so the
        // hit test never sees a duplicate candidate.
        val unique = words.distinctBy { it.text to it.bounds }
        val sorted = unique.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val lines = mutableListOf<MutableList<HighlighterWord>>()
        var lineBox: FloatBox? = null
        for (word in sorted) {
            val current = lines.lastOrNull()
            val box = lineBox
            if (current != null && box != null && sameLine(box, word.bounds)) {
                current += word
                lineBox = union(box, word.bounds)
            } else {
                lines += mutableListOf(word)
                lineBox = word.bounds
            }
        }
        val ordered = ArrayList<HighlighterWord>(unique.size)
        lines.forEachIndexed { lineIndex, line ->
            val lineWords = line.sortedWith(compareBy({ it.bounds.left }, { it.bounds.top }))
            val bounds = lineWords.map { it.bounds }.reduce { first, second -> union(first, second) }
            lineWords.forEach { ordered += HighlighterWord(it.text, it.bounds, lineIndex, bounds) }
        }
        return ordered
    }

    private fun sameLine(line: FloatBox, word: FloatBox): Boolean {
        val lineCenter = (line.top + line.bottom) / 2f
        val wordCenter = (word.top + word.bottom) / 2f
        val tolerance = max(line.bottom - line.top, word.bottom - word.top) * 0.5f
        return abs(wordCenter - lineCenter) <= tolerance
    }

    // ---- Platform facts -----------------------------------------------------------------------

    private fun isDataSensitive(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive

    private fun kindOf(eventType: Int): AccessibleTextChange.Kind = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> AccessibleTextChange.Kind.SCROLL
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> AccessibleTextChange.Kind.WINDOW
        else -> AccessibleTextChange.Kind.CONTENT
    }

    private fun unavailable(reason: AccessibleTextResult.Reason) =
        AccessibleTextResult.Unavailable(reason)

    /** Mutable state of one request. Dropped as a whole on completion/cancel/disconnect. */
    private class ReadJob(
        val requestId: Long,
        val startSequence: Long,
        val windowId: Int,
        val packageName: String,
        val viewport: FloatBox,
        val fromWindows: Boolean,
        /** Cleared by [AccessibleTextService.finish] so no node survives a completed request. */
        var root: AccessibilityNodeInfo?,
        val callback: (AccessibleTextResult) -> Unit
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        val words = ArrayList<HighlighterWord>()
        var textChars = 0
        var nodesSeen = 0
        var delivered = false
        var timeout: Runnable? = null
    }
}

/**
 * The only public entry point for the rest of the app. It never exposes the service instance,
 * never returns nodes or text synchronously and never retains a returned snapshot.
 *
 * Threading: [requestSnapshot], [addChangeListener] and [removeChangeListener] may be called from
 * any thread; callbacks are always delivered on the main thread. A snapshot request is answered
 * exactly once; a newer request supersedes the older one, and disable/disconnect/events deliver
 * [AccessibleTextResult.Unavailable] instead of a late usable snapshot.
 */
object AccessibleTextBridge {
    private const val PREFERENCES = "accessibility_text_prefs"
    private const val KEY_ENABLED = "enabled"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(AccessibleTextChange) -> Unit>()

    /** Cached application context only, set by the explicit enable/status calls from the app UI. */
    @Volatile private var applicationContext: Context? = null

    /** The in-app switch. Default off; enabling is only ever an explicit user action. */
    fun isEnabled(context: Context): Boolean {
        applicationContext = context.applicationContext
        return preferences(context).getBoolean(KEY_ENABLED, false)
    }

    /**
     * Persists the explicit in-app switch and, when disabling, cancels the in-flight read and drops
     * every service reference immediately. The user's system-level accessibility enablement is not
     * changed here; only Android accessibility settings can do that.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        applicationContext = app
        preferences(app).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            AccessibleTextService.connectedInstance()?.cancelReadingFromBridge()
            dispatchChange(AccessibleTextChange(0L, AccessibleTextChange.Kind.DISABLED, -1, ""))
        }
    }

    /** True only while the user-enabled accessibility service is actually bound in this process. */
    val isConnected: Boolean get() = AccessibleTextService.connectedInstance() != null

    private val requestIds = AtomicLong()

    /**
     * Requests one short-lived direct read of the visible external page.
     *
     * [callback] receives at most one [AccessibleTextResult] on the main thread. If the switch is
     * off, the service is disconnected, or no readable window exists, it receives an honest
     * [AccessibleTextResult.Unavailable]; it never receives a stale or partial snapshot. The
     * returned handle can be cancelled from any thread; a cancelled request neither starts nor
     * delivers a usable snapshot.
     */
    fun requestSnapshot(callback: (AccessibleTextResult) -> Unit): AccessibleTextRequest {
        val request = AccessibleTextRequest()
        val once = OnceCallback(callback)
        val dispatch = { dispatchRequest(request, once) }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(dispatch)
        } else {
            dispatch()
        }
        return request
    }

    private fun dispatchRequest(request: AccessibleTextRequest, callback: OnceCallback) {
        if (request.isCancelled) return
        val context = applicationContext
        if (context != null && !preferences(context).getBoolean(KEY_ENABLED, false)) {
            main.post {
                if (!request.isCancelled) {
                    callback.deliver(
                        AccessibleTextResult.Unavailable(AccessibleTextResult.Reason.NOT_ENABLED)
                    )
                }
            }
            return
        }
        val service = AccessibleTextService.connectedInstance()
        if (service == null) {
            main.post {
                if (!request.isCancelled) {
                    callback.deliver(
                        AccessibleTextResult.Unavailable(AccessibleTextResult.Reason.NOT_CONNECTED)
                    )
                }
            }
            return
        }
        val requestId = requestIds.incrementAndGet()
        val cancel = service.beginSnapshot(requestId, { request.isCancelled }) { result ->
            if (!request.isCancelled) callback.deliver(result)
        }
        request.attach(cancel)
    }

    /** Registers a text-free change listener. It receives no body, nodes or word boxes. */
    fun addChangeListener(listener: (AccessibleTextChange) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { addChangeListener(listener) }
            return
        }
        listeners += listener
    }

    fun removeChangeListener(listener: (AccessibleTextChange) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { removeChangeListener(listener) }
            return
        }
        listeners -= listener
    }

    internal fun dispatchChange(change: AccessibleTextChange) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { dispatchChange(change) }
            return
        }
        listeners.toList().forEach { listener -> runCatching { listener(change) } }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private class OnceCallback(private val delegate: (AccessibleTextResult) -> Unit) {
        private var delivered = false
        fun deliver(result: AccessibleTextResult) {
            if (delivered) return
            delivered = true
            delegate(result)
        }
    }
}
