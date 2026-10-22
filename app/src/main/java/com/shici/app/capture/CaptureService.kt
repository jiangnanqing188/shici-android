package com.shici.app.capture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.shici.app.R
import com.shici.app.ShiciApplication
import com.shici.app.core.*
import com.shici.app.data.RevokeOutcome
import com.shici.app.ui.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * One dictionary read plus the committed revision observed on the worker immediately around it.
 * [stable] is false when a commit landed between the before/after reads, so the resolved entry and
 * the separately read user row may come from different snapshots. Delivery compares only the
 * in-memory revision (never SQLite on the main thread) and rejects an old read instead of relabelling
 * stale text with newer data.
 */
private data class LookupRead(
    val result: LookupResult,
    val user: UserDictionaryEntry?,
    val revision: Long,
    val stable: Boolean
) {
    fun isCurrent(now: Long): Boolean = stable && revision == now
}

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.shici.app.START_CAPTURE"
        const val ACTION_STOP = "com.shici.app.STOP_CAPTURE"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "capture"
        private const val ENTRY_FRAME_TIMEOUT_MS = 2_500L
        private const val VERIFY_FRAME_TIMEOUT_MS = 1_500L
        private const val LIVE_MODE_TIMEOUT_MS = 30_000L
        // A live query's page identity is kept only while its OCR/dictionary/save admission can
        // still run. The bound prevents an indefinitely observed card from becoming a full-frame
        // sampling loop; after it expires the old request is cancelled rather than allowed to write.
        private const val PAGE_GUARD_TIMEOUT_MS = 60_000L
    }
    /** The single pending one-shot frame is either the clean entry page or the clean lift page. */
    private enum class FramePurpose { ENTRY, VERIFY }
    private val main = Handler(Looper.getMainLooper())
    private val gate = QueryGate()
    // Serializes page-guard invalidation on the frame thread with starting a new one-shot request
    // on the main thread, so a page-change detection can never close the token of the new request.
    private val pageLock = Any()
    private val perf = DebugPerformance()
    private lateinit var windows: WindowManager
    private lateinit var framesThread: HandlerThread
    private lateinit var frames: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var recognizer: TextRecognizer? = null
    private var bubble: View? = null
    private var panel: View? = null
    // One admission lease per open supplement editor. Detach, panel close and service stop/destroy
    // close it; a closed lease cannot be revived by reattaching the old view. The repository still
    // owns the real pre-transaction admission, so a save admitted earlier commits atomically.
    private class SupplementEditorLease(val admission: StudyAdmission) {
        @Volatile var closed = false
    }
    private val supplementEditorLeases = mutableSetOf<SupplementEditorLease>()
    private fun closeSupplementEditors() {
        supplementEditorLeases.toList().forEach { lease ->
            lease.closed = true
            lease.admission.invalidate()
        }
        supplementEditorLeases.clear()
    }
    private var sessionGeneration = -1L
    // Captured once per live lookup and carried through the entry frame, gesture, lift verification,
    // OCR, display, save and retry.
    private var deletionCredential = 0L
    private var pendingError: String? = null
    private var screenWidth = 1
    private var screenHeight = 1
    private var bubbleX = 20
    private var bubbleY = 160
    @Volatile private var stopped = false
    private val requestedFrame = AtomicReference<FreshFrameRequest?>(null)
    @Volatile private var framePurpose = FramePurpose.ENTRY
    private var frameTimeout: Runnable? = null
    // Identity of the live lookup attempt that owns an asynchronous failure notification. The main
    // thread advances it after a replacement one-shot request is published, and when the current
    // attempt is cancelled or dismissed. The capture thread closes the old request/gate immediately
    // and carries the value current at that moment into its main-thread notification, so a queued
    // failure from an older attempt can never tear down a newer entry, stroke, guard or token.
    @Volatile private var lookupEpoch = 0L
    // Live in-place lookup: entry clean-frame identity plus the transparent gesture layer. Both are
    // removed on lift, cancel, resize, rotation, panel dismissal or service shutdown.
    private var live: LiveSelectionSession? = null
    private var liveView: LiveSelectionView? = null
    private var liveTimeout: Runnable? = null
    // After the clean lift frame has been accepted, this guard proves that later OCR/dictionary/
    // display/save/retry admissions still belong to the same page. It stores only the compact
    // fingerprint; the lift bitmap is still released as soon as the OCR crop is taken.
    @Volatile private var pageGuard: LivePageGuard? = null
    private var pageGuardCounter = 0L
    private var pageGuardTimeout: Runnable? = null
    // Lower bound for page identity: the accepted lift image's own source timestamp, not the later
    // moment at which the guard object is created. A frame captured after the lift but delivered
    // before/while the guard is mounted must still be judged against the lift baseline.
    @Volatile private var pageGuardSourceNanos = 0L
    // Screen regions actually covered by this application's current card(s). Masks are applied per
    // pixel, so a card rectangle never hides the visible page pixels that merely share a tile or
    // lie inside a fixed outward margin.
    @Volatile private var pageOverlayBoxes: List<PixelBox> = emptyList()
    // Set only between the accepted lift frame and the pending card being mounted, so the card's
    // own frame is already excluded before observation starts. The pending identity is published
    // to the frame thread so a genuine update in this window is not lost.
    @Volatile private var pendingPageBaseline: LiveFrameFingerprint? = null
    @Volatile private var pendingPageBody: PixelBox? = null
    @Volatile private var pendingPageSourceNanos = 0L
    // A readable frame compared against the pending lift identity already disproved the page.
    @Volatile private var pendingPageChanged = false
    private val app get() = application as ShiciApplication
    // The visible "已收录" card; invalidated if its word is deleted while the card is still on screen.
    private class DefinitionGuard(
        val key: String, val credential: Long, val page: LivePageGuard?, val manual: Boolean,
        val status: TextView, val undo: Button, val correct: Button, val retry: Button
    ) { @Volatile var invalid = false; @Volatile var saved = false }
    @Volatile private var definitionGuard: DefinitionGuard? = null
    // The visible floating "未收录" card. Like DefinitionGuard it carries the query-start credential
    // so an already-displayed card cannot open a writable editor after a restore/delete.
    private class MissingGuard(
        val key: String, val credential: Long,
        val add: Button?, val collect: Button, val status: TextView,
        val closeEditors: () -> Unit
    ) { @Volatile var invalid = false }
    @Volatile private var missingGuard: MissingGuard? = null
    private val deletionListener: (Long) -> Unit = {
        val guard = definitionGuard
        if (guard != null && !guard.invalid && app.repository.deletions.isStale(guard.key, guard.credential)) {
            main.post { if (definitionGuard === guard) { invalidateDefinition(guard); endPageGuard() } }
        }
        val missing = missingGuard
        if (missing != null && !missing.invalid && app.repository.deletions.isStale(missing.key, missing.credential)) {
            main.post { if (missingGuard === missing) invalidateMissing(missing) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        framesThread = HandlerThread("shici-capture").apply { start() }
        frames = Handler(framesThread.looper)
        app.repository.changes.observe(deletionListener)
    }
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) { CaptureStatus.stop(this); stopSelf(); return START_NOT_STICKY }
        if (projection != null || stopped) return START_NOT_STICKY
        if (intent.getLongExtra(EXTRA_GENERATION, -1) != CaptureStatus.generation ||
            CaptureStatus.state.phase != CapturePhase.PREPARING) { stopSelf(); return START_NOT_STICKY }
        sessionGeneration = intent.getLongExtra(EXTRA_GENERATION, -1)
        try {
            check(Settings.canDrawOverlays(this)) { "请先允许显示悬浮窗" }
            startCaptureForeground()
            val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_DATA)
            check(consent != null && intent.getIntExtra(EXTRA_RESULT, 0) == Activity.RESULT_OK) { "屏幕捕获授权无效" }
            CaptureStatus.update(CapturePhase.PREPARING, "准备中 · 初始化本地识别")
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, consent)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { if (!stopped) CaptureStatus.stop(this@CaptureService) }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (!stopped && width > 0 && height > 0 && (width != screenWidth || height != screenHeight)) resize(width, height)
                }
            }, main)
            val size = screenSize()
            screenWidth = size.first; screenHeight = size.second
            val imageReader = newReader(screenWidth, screenHeight)
            reader = imageReader
            display = projection!!.createVirtualDisplay("shici-screen", screenWidth, screenHeight,
                resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, main)
            if (showBubble()) CaptureStatus.update(CapturePhase.ACTIVE, "已开启 · 点悬浮「词」后直接在原页面划一笔")
        } catch (e: Exception) { fail("开启失败：${e.message ?: "系统拒绝捕获"}") }
        return START_NOT_STICKY
    }

    private fun startCaptureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "悬浮查词会话", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_shici)
            .setContentTitle("拾词 · 全屏查词已开启").setContentText("一笔抬起即在本机识别；点停止释放捕获")
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "停止查词", stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1001, notification)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        windows.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun newReader(width: Int, height: Int): ImageReader {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216L) { "捕获尺寸超出原型安全范围" }
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
        setOnImageAvailableListener({ source ->
            /**
             * The current source could not supply a trustworthy frame (a thrown acquisition or an
             * image whose geometry does not match the reader). That is not "no new image": the
             * active query's page authority can no longer be proven, so close it on this frame
             * thread before any worker can pass record admission, then notify the UI. A late frame
             * from a replaced reader or a different generation is ignored.
             */
            fun unusableSource(stage: String, message: String) {
                if (source !== reader || stopped || !sessionIsActive()) return
                val guard = pageGuard
                if (guard != null) {
                    val invalidated = synchronized(pageLock) {
                        // A published replacement request owns the gate now; an old guard must not
                        // advance it past that request's token. beginLiveSelection already closes
                        // any guard before publishing, under this same lock.
                        if (pageGuard !== guard || requestedFrame.get() != null) false
                        else if (guard.invalidate()) { gate.next(); true } else false
                    }
                    if (invalidated) main.post { invalidatePage(guard, message) }
                    return
                }
                val request = requestedFrame.get()
                if (request != null) {
                    // Stamp the failed attempt before its request is removed. The replacement cannot
                    // publish a new request before the ownership boundary in
                    // closeFailedCaptureRequest(), and if it already did, that boundary fails and no
                    // UI notification is posted at all.
                    val failedGeneration = sessionGeneration
                    val failedEpoch = lookupEpoch
                    if (closeFailedCaptureRequest(request)) {
                        main.post { failLiveLookupIfCurrent(failedGeneration, failedEpoch, message, stage) }
                    }
                    return
                }
                // The lift identity is published but the guard is not mounted yet: the pending
                // card mount will see this flag and invalidate before OCR/save can continue.
                synchronized(pageLock) {
                    if (pendingPageBaseline != null) pendingPageChanged = true
                }
            }
            // A plain null return means the source simply has no newer image and stays neutral.
            val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) {
                unusableSource("frame_acquire", "无法读取当前页面画面；本次查询已取消。")
                null
            }
            if (image != null) {
                val request = requestedFrame.get()
                var bitmap: Bitmap? = null
                try {
                    val current = source === reader &&
                        image.width == source.width && image.height == source.height
                    if (source === reader && !current) {
                        // Wrong geometry from the current reader cannot prove the accepted page.
                        unusableSource("frame_geometry", "画面尺寸异常；本次查询已取消。")
                    } else if (current && request != null && frameIsCurrent(request) &&
                        request.canInspect(image.timestamp, SystemClock.elapsedRealtime())) {
                        val purpose = framePurpose
                        val captured = imageBitmap(image)
                        bitmap = captured
                        val black = FrameContent.isClearlyBlack(captured.width, captured.height, request.body) { x, y, row ->
                            captured.getPixels(row, 0, row.size, x, y, row.size, 1)
                        }
                        val firstBlack = black && !request.rejectedBlack
                        // Retain the accepted image's identity on this frame thread, before the
                        // main-thread hand-off. A newer frame that arrives while the request is
                        // awaiting delivery can then be compared instead of silently dropped.
                        val acceptedIdentity = if (black) null else runCatching {
                            LiveFrameFingerprint.sample(captured.width, captured.height, request.body) { x, y, row ->
                                captured.getPixels(row, 0, row.size, x, y, row.size, 1)
                            }
                        }.getOrNull()
                        if (!black && acceptedIdentity == null) {
                            // A readable candidate whose identity cannot be retained could later
                            // be adopted without a proven page identity: reject it up front.
                            val failedGeneration = sessionGeneration
                            val failedEpoch = lookupEpoch
                            if (closeFailedCaptureRequest(request)) {
                                main.post {
                                    failLiveLookupIfCurrent(failedGeneration, failedEpoch,
                                        if (purpose == FramePurpose.VERIFY)
                                            "无法读取抬笔后的页面画面；本次未查询。请返回做题后重试。"
                                        else "无法读取当前页面画面；本次未查询。请返回做题后重试。",
                                        if (purpose == FramePurpose.VERIFY) "verify_frame" else "entry_frame")
                                }
                            }
                        } else if (request.offer(image.timestamp, SystemClock.elapsedRealtime(), black)) {
                            if (acceptedIdentity != null) {
                                synchronized(pageLock) {
                                    pendingPageBaseline = acceptedIdentity
                                    pendingPageBody = request.body
                                    pendingPageSourceNanos = image.timestamp
                                    pendingPageChanged = false
                                }
                            }
                            if (main.post {
                                if (source === reader && frameIsCurrent(request) &&
                                    request.complete(SystemClock.elapsedRealtime()) && requestedFrame.compareAndSet(request, null)) {
                                    clearFrameTimeout()
                                    when (purpose) {
                                        FramePurpose.ENTRY -> onEntryFrame(request, captured)
                                        FramePurpose.VERIFY -> onVerifyFrame(request, captured)
                                    }
                                } else {
                                    captured.recycle()
                                    if (frameIsCurrent(request)) expireFrame(request, purpose)
                                }
                            }) bitmap = null // The main-thread callback now owns this bitmap.
                        } else if (firstBlack && request.rejectedBlack) {
                            main.post { if (frameIsCurrent(request)) perf.stage("frame_rejected", "black") }
                        }
                    } else if (current) {
                        // This image was not taken as the one-shot entry/lift frame. It is still
                        // page evidence while an accepted frame awaits main delivery or while a
                        // pending/guard identity is published: observe it, never drop it here.
                        observeAmbientPage(image)
                    }
                } catch (e: Exception) {
                    main.post {
                        if (request != null && frameIsCurrent(request)) {
                            perf.end("failed", "frame_conversion")
                            dismissPanel()
                            showMessage("取词失败：${e.message}")
                        }
                    }
                } finally { bitmap?.recycle(); image.close() }
            }
        }, frames)
        }
    }

    private fun imageBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        var bitmap: Bitmap? = null
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            return bitmap
        } finally { if (bitmap !== padded) padded.recycle() }
    }

    /**
     * Observes a frame for which no one-shot request is pending and checks whether the page behind
     * the current OCR/dictionary/card is still the page accepted at lift. Only the bounded,
     * in-memory colour identity is retained; this call reads the image plane directly and
     * allocates neither a full-screen bitmap nor a second full identity.
     *
     * A frame that arrives but cannot be read (missing plane, truncated buffer, read exception) is
     * not "no evidence": the page can no longer be proven unchanged, so the guard and query gate
     * are invalidated on this capture thread before any worker can pass record admission. The
     * image itself is still closed by the reader's `finally`.
     *
     * Our own visible card windows are excluded per pixel; page changes elsewhere still count.
     */
    private fun observeAmbientPage(image: Image) {
        fun matchesImage(baseline: LiveFrameFingerprint, body: PixelBox): Boolean = try {
            val plane = image.planes.firstOrNull()
            plane != null && baseline.matchesPlane(image.width, image.height, body,
                plane.rowStride, plane.pixelStride, plane.buffer, pageOverlayExclusions())
        } catch (_: Exception) {
            false
        }

        fun timestampBelongsToGuard(candidate: LivePageGuard, timestampNanos: Long): Boolean =
            pageGuard === candidate &&
                (timestampNanos <= 0 || pageGuardSourceNanos <= 0 || timestampNanos >= pageGuardSourceNanos)

        fun invalidateOnFrameThread(guard: LivePageGuard) {
            val invalidated = synchronized(pageLock) {
                if (pageGuard !== guard || requestedFrame.get() != null) false
                else if (guard.invalidate()) {
                    // Close the gate on this frame thread before posting the UI update, so an
                    // OCR/dictionary/save worker racing on another thread cannot pass admission in
                    // the interval between observing the change and the main-thread card update.
                    gate.next()
                    true
                } else false
            }
            if (invalidated) {
                main.post { invalidatePage(guard, "页面已变化，本次查询已失效；已完成的提交不会自动撤销。") }
            }
        }

        val guard = pageGuard
        if (guard == null) {
            // The lift frame has been accepted but main delivery/guard mounting has not finished.
            // A frame captured at or after the accepted source timestamp is still page evidence;
            // compare it with the identity published at offer instead of dropping it in the
            // offer -> main-delivery -> guard-mount window.
            val pending = pendingPageBaseline ?: return
            val pendingBody = pendingPageBody ?: return
            if (stopped || !sessionIsActive() || liveView != null) return
            val awaiting = requestedFrame.get()
            // A request that has not accepted a frame yet is not covered by this pending identity;
            // an accepted request awaiting main delivery is, even though canInspect is now false.
            if (awaiting != null && !awaiting.isAwaitingDelivery()) return
            val sourceNanos = pendingPageSourceNanos
            if (sourceNanos > 0 && image.timestamp > 0 && image.timestamp < sourceNanos) return
            val same = matchesImage(pending, pendingBody)
            if (awaiting != null) {
                if (same) return
                // Invalidate the accepted-but-not-yet-delivered offer on this frame thread, so the
                // queued main callback can no longer adopt a stale lift frame. Nothing from it may
                // reach OCR/dictionary/record. The ownership check, gate advance, timeout detach and
                // pending-identity cleanup are one boundary with publishing a replacement request.
                val failedGeneration = sessionGeneration
                val failedEpoch = lookupEpoch
                if (closeFailedCaptureRequest(awaiting)) {
                    main.post { failLiveLookupIfCurrent(failedGeneration, failedEpoch,
                        "页面已变化，本次查询已取消。", "page_changed_gap") }
                    return
                }
                // Main delivery won the race; record the change for the pending guard below.
            }
            var raced: LivePageGuard? = null
            synchronized(pageLock) {
                val mounted = pageGuard
                if (mounted == null && !same && requestedFrame.get() == null) {
                    pendingPageChanged = true
                } else if (mounted != null && !same &&
                    timestampBelongsToGuard(mounted, image.timestamp) && mounted.invalidate()) {
                    // The guard was published while this frame was being compared. It is still a
                    // post-lift frame of this query, so close the gate before the worker can write.
                    gate.next()
                    raced = mounted
                }
            }
            val racedGuard = raced
            if (racedGuard != null) {
                main.post { invalidatePage(racedGuard, "页面已变化，本次查询已失效；已完成的提交不会自动撤销。") }
            }
            return
        }
        if (!guard.isValid || stopped || !sessionIsActive() || liveView != null ||
            requestedFrame.get() != null) return
        // Ignore a late image produced before the accepted lift frame. Zero timestamps keep the
        // old "no lower bound" safety semantics and are still checked, never silently trusted.
        if (!timestampBelongsToGuard(guard, image.timestamp)) return
        if (!matchesImage(guard.baseline, guard.body)) invalidateOnFrameThread(guard)
    }

    /** Screen rectangles covered by this application's own card; page changes elsewhere still count. */
    private fun pageOverlayExclusions(): List<PixelBox> {
        val bubbleBox = bubble?.let {
            PixelBox(bubbleX, bubbleY, bubbleX + dp(56), bubbleY + dp(56))
        }
        return if (bubbleBox == null) pageOverlayBoxes else pageOverlayBoxes + bubbleBox
    }

    private fun startPageGuard(baseline: LiveFrameFingerprint, body: PixelBox) {
        val previous = pageGuard
        pageGuard = null
        previous?.invalidate()
        pageGuardTimeout?.let { main.removeCallbacks(it) }
        // Keep the current card's exact exclusion: it was recorded by addPanel just before this call.
        val guard = LivePageGuard(++pageGuardCounter, body, baseline)
        var changedBeforeMount = false
        synchronized(pageLock) {
            // Publish the accepted lift image's own source timestamp as the page-identity lower
            // bound. The guard is then atomically visible to the frame thread; a frame that was
            // being compared against the pending identity either sets pendingPageChanged before
            // this lock or sees the mounted guard and invalidates it directly.
            pageGuardSourceNanos = pendingPageSourceNanos
            pageGuard = guard
            changedBeforeMount = pendingPageChanged
            pendingPageChanged = false
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
        }
        val timeout = Runnable { expirePageGuard(guard) }
        pageGuardTimeout = timeout
        main.postDelayed(timeout, PAGE_GUARD_TIMEOUT_MS)
        if (changedBeforeMount) {
            // A post-lift frame delivered before the card was mounted already disproved identity.
            invalidatePage(guard, "页面已变化，本次查询已失效；已完成的提交不会自动撤销。")
        }
    }

    /** Ends observation for a finished/dismissed request without invalidating any newer guard. */
    private fun endPageGuard() {
        synchronized(pageLock) {
            val previous = pageGuard
            pageGuard = null
            previous?.invalidate()
            pageGuardSourceNanos = 0L
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
        }
        pageOverlayBoxes = emptyList()
        pageGuardTimeout?.let { main.removeCallbacks(it) }
        pageGuardTimeout = null
    }

    /**
     * The observed page no longer matches the accepted lift page. All callbacks that still carry
     * the old QueryGate token are rejected at their next admission; an already committed save is
     * left in place and is not presented as rolled back.
     */
    private fun invalidatePage(guard: LivePageGuard, message: String) {
        synchronized(pageLock) {
            if (pageGuard !== guard) return
            // The ambient observer may already have invalidated this guard on the frame thread; the
            // identity check above is what makes this main-thread UI update run at most once.
            guard.invalidate()
            pageGuard = null
            pageGuardSourceNanos = 0L
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
        }
        pageOverlayBoxes = emptyList()
        pageGuardTimeout?.let { main.removeCallbacks(it) }
        pageGuardTimeout = null
        gate.next()
        perf.stage("page", "changed")
        val definition = definitionGuard
        if (definition == null) {
            removePanelView()
        } else if (!definition.invalid) {
            definition.invalid = true
            definition.status.text = "页面已变化，本次查询已失效；已完成的提交不会自动撤销"
            definition.status.setTextColor(Paper.error)
            definition.correct.isEnabled = false
            definition.retry.visibility = View.GONE
            // Undo remains available only for a commit this card already observed.
            definition.undo.isEnabled = definition.saved
        }
        toast(message)
        if (sessionIsActive() && bubble == null) showBubble()
    }

    private fun expirePageGuard(guard: LivePageGuard) {
        synchronized(pageLock) {
            if (pageGuard !== guard) return
            pageGuard = null
            pageGuardSourceNanos = 0L
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
        }
        pageOverlayBoxes = emptyList()
        pageGuardTimeout = null
        guard.invalidate()
        gate.next()
        perf.stage("page", "guard_expired")
        val definition = definitionGuard
        if (definition == null) {
            removePanelView()
            if (sessionIsActive()) {
                toast("页面状态已无法确认，本次查询已取消。")
                showBubble()
            }
        } else if (!definition.invalid && !definition.saved) {
            definition.invalid = true
            definition.status.text = "未收录：页面状态已无法确认，本次查询已取消"
            definition.status.setTextColor(Paper.error)
            definition.undo.isEnabled = false
            definition.correct.isEnabled = false
            definition.retry.visibility = View.GONE
            toast("页面状态已无法确认，本次查询已取消。")
        }
    }

    private fun resize(width: Int, height: Int) {
        var replacement: ImageReader? = null
        try {
            perf.end("cancelled", "resize")
            dismissPanel()
            replacement = newReader(width, height)
            val previous = reader
            display?.surface = replacement.surface
            display?.resize(width, height, resources.configuration.densityDpi)
            reader = replacement
            replacement = null
            screenWidth = width; screenHeight = height
            previous?.setOnImageAvailableListener(null, null)
            frames.post { previous?.close() }
            if (showBubble()) toast("画面尺寸已变化，请重新划选")
        } catch (e: Exception) {
            replacement?.close()
            fail("捕获尺寸变化失败：${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!stopped && projection != null) {
            try {
                val (width, height) = screenSize()
                if (width != screenWidth || height != screenHeight) resize(width, height)
            } catch (e: Exception) { fail("无法更新捕获尺寸：${e.message}") }
        }
    }

    private fun frameIsCurrent(request: FreshFrameRequest): Boolean =
        sessionIsActive() && requestedFrame.get() === request && gate.accepts(request.token)

    /**
     * Ends [request]'s capture-thread ownership in the same short [pageLock] boundary in which a
     * replacement request is published. Ownership check, removal, gate advance, timeout detach and
     * pending-identity cleanup are one step: either this callback wins and any later publish takes a
     * newer gate token, or a replacement/cancellation already won and this callback changes nothing.
     * The old request's synchronous cancel and callback removal run only after the lock is released,
     * so a blocked/frozen old request monitor can neither stall the main-thread publisher nor create
     * a lock inversion, and cleanup touches only the timeout runnable captured for this request.
     *
     * Returns true only for the caller that actually removed [request].
     */
    private fun closeFailedCaptureRequest(request: FreshFrameRequest): Boolean {
        var timeout: Runnable? = null
        val closed = synchronized(pageLock) {
            if (requestedFrame.get() !== request) return@synchronized false
            if (!requestedFrame.compareAndSet(request, null)) return@synchronized false
            // Close the old token before any new request can be published under the same lock.
            gate.next()
            // The timeout and pending identity here belong to the request just removed: a new
            // request cannot publish or publish a pending identity until this lock is released.
            timeout = frameTimeout
            frameTimeout = null
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
            true
        }
        if (!closed) return false
        request.cancel()
        timeout?.let { main.removeCallbacks(it) }
        return true
    }

    private fun clearFrameTimeout() {
        frameTimeout?.let { main.removeCallbacks(it) }
        frameTimeout = null
    }
    private fun expireFrame(request: FreshFrameRequest, purpose: FramePurpose) {
        if (!frameIsCurrent(request) || !requestedFrame.compareAndSet(request, null)) return
        request.cancel(); gate.next(); clearFrameTimeout()
        // A timeout ends the one-shot request; any identity sampled at offer is no longer evidence.
        synchronized(pageLock) {
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
        }
        val session = live
        removeLiveLayer()
        live = null
        session?.selection = null
        when (purpose) {
            FramePurpose.ENTRY -> {
                perf.end("failed", if (request.rejectedBlack) "frame_black_timeout" else "frame_timeout")
                showMessage(if (request.rejectedBlack)
                    "已丢弃主体黑帧，等待有效新画面超时。页面可能尚未就绪或禁止截图；不会复用旧截图或绕过保护。请返回做题后重试。"
                    else "未收到新的有效屏幕画面。安全页面可能禁止截图；不会复用旧截图。请返回做题后重试。")
            }
            FramePurpose.VERIFY -> {
                perf.end("failed", if (request.rejectedBlack) "verify_black_timeout" else "verify_timeout")
                showMessage(if (request.rejectedBlack)
                    "抬笔后只收到主体黑帧，本次未查询，也没有写入生词本。请返回做题后重试。"
                    else "抬笔后未收到新的有效画面，本次未查询，也没有写入生词本。请返回做题后重试。")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun frameBody(): PixelBox {
        val body = if (Build.VERSION.SDK_INT >= 30) {
            val insets = windows.maximumWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            PixelBox(insets.left, insets.top, screenWidth - insets.right, screenHeight - insets.bottom)
        } else {
            val insets = bubble?.rootWindowInsets
            PixelBox(insets?.systemWindowInsetLeft ?: 0, insets?.systemWindowInsetTop ?: 0,
                screenWidth - (insets?.systemWindowInsetRight ?: 0), screenHeight - (insets?.systemWindowInsetBottom ?: 0))
        }
        return body.takeIf { it.width > 0 && it.height > 0 } ?: PixelBox(0, 0, screenWidth, screenHeight)
    }

    /**
     * Arms one compositor-observed frame request; no fixed sleep and no reuse of an older frame.
     *
     * Token generation, request publication, lookup epoch and the frame timeout all move inside
     * the same [pageLock] boundary used by a capture-thread failure to close an old request. A
     * capture callback that already removed the previous request therefore either commits its gate
     * advance before this publish (this request gets a newer, valid token) or observes the published
     * request and leaves it untouched.
     */
    private fun armFrame(purpose: FramePurpose, body: PixelBox, deadlineMillis: Long): FreshFrameRequest {
        val request = synchronized(pageLock) {
            val published = FreshFrameRequest(gate.next(), deadlineMillis, body)
            framePurpose = purpose
            requestedFrame.set(published)
            // Advance only after the replacement is visible: a capture-thread failure that already
            // stamped the old epoch now sees the new request and cannot remove it.
            lookupEpoch++
            val timeout = Runnable { expireFrame(published, purpose) }
            frameTimeout = timeout
            main.postDelayed(timeout, (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            published
        }
        Choreographer.getInstance().postFrameCallback {
            if (frameIsCurrent(request)) request.arm(System.nanoTime())
        }
        return request
    }

    /**
     * Entry of one live lookup. The bubble is removed and a clean entry frame is requested before
     * the transparent gesture layer is mounted, so neither the entry chip nor ink can be captured;
     * a lookup is never performed from a stale mode-entry picture.
     */
    private fun beginLiveSelection() {
        if (stopped || !sessionIsActive() || requestedFrame.get() != null || live != null || liveView != null) return
        synchronized(pageLock) {
            dismissPanel()
            if (!sessionIsActive()) return
            perf.click()
            deletionCredential = app.repository.deletions.capture()
            val body = frameBody()
            removeBubble()
            armFrame(FramePurpose.ENTRY, body, SystemClock.elapsedRealtime() + ENTRY_FRAME_TIMEOUT_MS)
        }
    }

    private fun onEntryFrame(request: FreshFrameRequest, bitmap: Bitmap) {
        // Consume the identity sampled when this entry image was accepted. It is the same image,
        // and must not linger as page evidence for the later lift request.
        val published = synchronized(pageLock) {
            val identity = pendingPageBaseline
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
            identity
        }
        if (!sessionIsActive() || !gate.accepts(request.token)) { bitmap.recycle(); return }
        val fingerprint = published ?: runCatching {
            LiveFrameFingerprint.sample(bitmap.width, bitmap.height, request.body) { x, y, row ->
                bitmap.getPixels(row, 0, row.size, x, y, row.size, 1)
            }
        }.getOrNull()
        if (fingerprint == null) {
            bitmap.recycle()
            failLiveLookup("无法读取当前页面画面；本次未查询。请返回做题后重试。", "entry_frame")
            return
        }
        bitmap.recycle() // Only the compact fingerprint survives; the clean entry bitmap is released.
        val session = LiveSelectionSession(
            generation = CaptureStatus.generation,
            entryToken = request.token,
            deletionCredential = deletionCredential,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            body = request.body,
            entryFingerprint = fingerprint
        )
        live = session
        val view = LiveSelectionView(this, screenWidth, screenHeight,
            { stroke -> commitLiveStroke(session, stroke) },
            { toast("请沿完整单词一笔划过；轻点不查询") },
            { reason -> cancelLiveMode(reason) })
        if (!addLiveLayer(view)) return
        liveView = view
        scheduleLiveTimeout()
        // "可划" is reported only after the window is attached, laid out with a real size and
        // visible. addView alone merely registers the window. The checks reject a late callback
        // from a cancelled, superseded or stopped lookup.
        view.awaitWindowReady {
            if (liveView === view && live === session && sessionIsActive() &&
                session.generation == CaptureStatus.generation && gate.accepts(session.entryToken)) {
                view.arm()
                perf.selectionReady()
            }
        }
    }

    /**
     * One valid stroke was lifted. The full-screen layer is removed before the clean lift frame is
     * requested; no card or ink exists while that frame is captured. The gesture itself is never
     * forwarded or synthesised to the underlying app.
     */
    private fun commitLiveStroke(session: LiveSelectionSession, stroke: HighlighterStroke) {
        if (live !== session || liveView == null || !sessionIsActive() ||
            session.generation != CaptureStatus.generation || !gate.accepts(session.entryToken)) {
            cancelLiveMode(null)
            return
        }
        val selection = HighlighterGeometry.selection(listOf(stroke), session.screenWidth, session.screenHeight,
            session.screenWidth, session.screenHeight, resources.displayMetrics.density)
        if (selection == null) {
            toast("未形成有效划线；没有查询，也没有新增记录")
            return
        }
        removeLiveLayer()
        session.selection = selection
        perf.selectionSubmit()
        val request = armFrame(FramePurpose.VERIFY, session.body,
            SystemClock.elapsedRealtime() + VERIFY_FRAME_TIMEOUT_MS)
        session.verifyToken = request.token
    }

    /** Rejects a stroke whose page changed, went black or could not be read while it was drawn. */
    private fun onVerifyFrame(request: FreshFrameRequest, bitmap: Bitmap) {
        val session = live
        val selection = session?.selection
        if (session == null || selection == null || !sessionIsActive() ||
            session.generation != CaptureStatus.generation || session.verifyToken != request.token ||
            !gate.accepts(request.token)) {
            bitmap.recycle()
            return
        }
        val published = synchronized(pageLock) { pendingPageBaseline }
        val fingerprint = published ?: runCatching {
            LiveFrameFingerprint.sample(bitmap.width, bitmap.height, session.body) { x, y, row ->
                bitmap.getPixels(row, 0, row.size, x, y, row.size, 1)
            }
        }.getOrNull()
        if (fingerprint == null) {
            bitmap.recycle()
            failLiveLookup("无法读取抬笔后的页面画面；本次未查询。请返回做题后重试。", "verify_frame")
            return
        }
        if (!session.entryFingerprint.matches(fingerprint)) {
            bitmap.recycle()
            failLiveLookup("划选期间页面已变化，本次未查询。请在页面稳定后重新划选。", "page_changed")
            return
        }
        // Fresh lift frame, clean of bubble/ink/card, and materially the same page as the entry
        // frame: this is the only frame handed to OCR. Its colour identity becomes the page
        // baseline for the following OCR -> dictionary -> display/save/retry admissions, with the
        // image's own source timestamp as the lower bound for later observations.
        live = null
        session.selection = null
        // A differing frame observed between the accepted offer and this delivery is retained:
        // startPageGuard consumes it after mounting and invalidates before OCR/save can continue.
        synchronized(pageLock) {
            pendingPageSourceNanos = request.acceptedTimestampNanos
            pendingPageBody = session.body
            // Publish the identity last: a frame thread that sees it also sees source and body.
            pendingPageBaseline = fingerprint
        }
        perf.stage("frame", "verified")
        recognize(bitmap, selection, session.deletionCredential)
    }

    /**
     * Delivers a capture-thread failure notification only while the attempt that detected it is the
     * current one. The old request and gate token were already closed on the capture thread; if a
     * replacement entry/lift was armed, or the attempt was cancelled/dismissed, or the service is no
     * longer active, this callback must leave every current object untouched instead of calling
     * [failLiveLookup]. In particular it must not advance the gate again (which would invalidate the
     * replacement token), clear a live session/new stroke, end a new page guard, or rebuild UI after
     * stop. No old token is ever re-opened by this check.
     */
    private fun failLiveLookupIfCurrent(generation: Long, epoch: Long, message: String, stage: String) {
        if (stopped || generation != sessionGeneration || CaptureStatus.generation != generation) return
        if (epoch != lookupEpoch || !sessionIsActive()) return
        failLiveLookup(message, stage)
    }

    private fun failLiveLookup(message: String, stage: String) {
        removeLiveLayer()
        live = null
        endPageGuard()
        gate.next()
        perf.end("failed", stage)
        showMessage(message)
    }

    private fun addLiveLayer(view: LiveSelectionView): Boolean = try {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        windows.addView(view, params)
        true
    } catch (e: Exception) {
        failLiveLookup("无法打开透明划词层：${e.message}；本次未查询。", "live_layer")
        false
    }

    private fun removeLiveLayer() {
        liveTimeout?.let { main.removeCallbacks(it) }
        liveTimeout = null
        val view = liveView
        liveView = null
        view?.cancelWindowReadiness()
        view?.let { runCatching { windows.removeViewImmediate(it) } }
    }

    private fun scheduleLiveTimeout() {
        liveTimeout?.let { main.removeCallbacks(it) }
        val session = live ?: return
        val timeout = Runnable {
            if (live === session && liveView != null) cancelLiveMode("取词已超时，本次未查询")
        }
        liveTimeout = timeout
        main.postDelayed(timeout, LIVE_MODE_TIMEOUT_MS)
    }

    /** Leaves one-stroke mode without querying or writing anything. */
    private fun cancelLiveMode(reason: String?) {
        val hadLive = live != null || liveView != null
        removeLiveLayer()
        live?.selection = null
        live = null
        endPageGuard()
        gate.next()
        requestedFrame.getAndSet(null)?.cancel()
        clearFrameTimeout()
        // A later notification from the cancelled attempt must not reopen UI for it.
        lookupEpoch++
        if (reason != null) toast(reason)
        if (hadLive) perf.end("cancelled", "live_cancel")
        if (sessionIsActive() && bubble == null) showBubble()
    }

    private fun showBubble(): Boolean {
        if (stopped) return false
        if (bubble != null) return true
        val view = BubbleView(this)
        val params = WindowManager.LayoutParams(dp(56), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX.coerceIn(0, (screenWidth - dp(56)).coerceAtLeast(0))
            y = bubbleY.coerceIn(0, (screenHeight - dp(56)).coerceAtLeast(0))
        }
        var originalX = 0; var originalY = 0
        view.onDragStart = { originalX = params.x; originalY = params.y }
        view.onDrag = { dx, dy ->
            params.x = (originalX + dx.toInt()).coerceIn(0, (screenWidth - dp(56)).coerceAtLeast(0))
            params.y = (originalY + dy.toInt()).coerceIn(0, (screenHeight - dp(56)).coerceAtLeast(0))
            bubbleX = params.x; bubbleY = params.y
            if (bubble === view) windows.updateViewLayout(view, params)
        }
        view.setOnClickListener { beginLiveSelection() }
        return try { windows.addView(view, params); bubble = view; true }
        catch (e: Exception) { fail("悬浮窗不可用：${e.message}"); false }
    }
    private fun removeBubble() { bubble?.let { runCatching { windows.removeViewImmediate(it) } }; bubble = null }

    /**
     * A compact, non-full-screen result/pending card. FLAG_NOT_TOUCH_MODAL keeps everything outside
     * the card's own bounds going to the page below; there is no dim layer or full-screen backdrop.
     */
    private fun addPanel(view: View) {
        removePanelView()
        val width = minOf(dp(440), (screenWidth - dp(24)).coerceAtLeast(dp(200)))
        // A long definition scrolls inside a card that never grows into a full-screen surface.
        val maxHeight = (screenHeight * 3 / 4).coerceAtLeast(dp(240))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = view.measuredHeight.coerceIn(1, maxHeight)
        // Exclude only the exact centred window this card actually covers. There is deliberately
        // no fixed outward margin and no accumulated history: a word just above the card, or page
        // pixels left visible around it, must remain page evidence.
        val left = (screenWidth - width) / 2
        val top = (screenHeight - height) / 2
        pageOverlayBoxes = listOf(PixelBox(left, top,
            (left + width).coerceAtMost(screenWidth), (top + height).coerceAtMost(screenHeight)))
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dismissPanel(); showBubble(); true } else false
        }
        try { windows.addView(view, params); panel = view; view.requestFocus() }
        catch (e: Exception) { fail("无法打开取词界面：${e.message}") }
    }
    private fun removePanelView() { definitionGuard = null; missingGuard = null; panel?.let { runCatching { windows.removeViewImmediate(it) } }; panel = null }
    private fun invalidateDefinition(guard: DefinitionGuard) {
        guard.invalid = true
        guard.status.text = "未收录：该词已删除或查询已失效"; guard.status.setTextColor(Paper.error)
        guard.undo.isEnabled = false; guard.correct.isEnabled = false; guard.retry.visibility = View.GONE
    }
    // Retires an open uncollected card after a restore/delete: no new-headword editor and no explicit
    // collect can start from the old world.
    private fun invalidateMissing(guard: MissingGuard) {
        guard.invalid = true
        guard.add?.apply { isEnabled = false; text = "查询已失效" }
        guard.collect.isEnabled = false
        guard.status.text = "该词条已删除或数据已恢复，请重新查询"
        guard.status.setTextColor(Paper.error)
        guard.closeEditors()
    }
    // A floating card is out of date once it is invalidated or its word was deleted after the query
    // credential was captured; both must win over any late success/failure result.
    private fun definitionCardStale(guard: DefinitionGuard): Boolean =
        guard.invalid || app.repository.deletions.isStale(guard.key, guard.credential)
    private fun dismissPanel() {
        // Closing the page card ends every editor it owned, even before its view detaches.
        closeSupplementEditors()
        perf.end("closed", "dismiss")
        gate.next(); requestedFrame.getAndSet(null)?.cancel(); clearFrameTimeout()
        // The dismissed attempt is over; queued failure notifications for it must stay silent.
        lookupEpoch++
        removeLiveLayer()
        live?.selection = null
        live = null
        endPageGuard()
        removePanelView()
    }

    private fun recognize(bitmap: Bitmap, selection: HighlighterSelection, credential: Long) {
        val area = selection.crop
        val crop = try {
            val slice = Bitmap.createBitmap(bitmap, area.left, area.top, area.width, area.height)
            // OCR owns its bitmap until completion; never share the live frame after this point.
            if (slice === bitmap) checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else slice
        } catch (e: Exception) {
            bitmap.recycle()
            perf.stage("ocr", "failed"); showMessage("无法准备识别画面：${e.message}"); return
        }
        bitmap.recycle() // The only retained pixels are the necessary stroke-area crop.
        val token = gate.next()
        removePanelView()
        val pending = column().apply { setPadding(dp(24), dp(24), dp(24), dp(24)); background = shape(Paper.surface) }
        pending.addView(text("正在本地识别笔迹附近的文字…", 20f, bold = true))
        pending.addView(button("重新划选") { beginLiveSelection() }); pending.space(8)
        pending.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(pending)
        if (!sessionIsActive() || !gate.accepts(token)) {
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
            crop.recycle()
            return
        }
        // The pending card is now mounted and its exact exclusion recorded; observation can begin.
        // The pending identity is kept published until startPageGuard consumes it, so a frame that
        // arrives while the card is being mounted is still compared rather than lost.
        val baseline = pendingPageBaseline
        val body = pendingPageBody
        if (baseline != null && body != null) startPageGuard(baseline, body)
        if (!sessionIsActive() || !gate.accepts(token)) {
            // A post-lift frame already disproved identity while the card was mounted.
            crop.recycle()
            return
        }
        val task = try {
            checkNotNull(recognizer).process(InputImage.fromBitmap(crop, 0))
        } catch (e: Exception) {
            crop.recycle()
            if (sessionIsActive() && gate.accepts(token)) {
                perf.stage("ocr", "failed"); showMessage("识别失败：${e.message}")
            }
            return
        }
        task.addOnSuccessListener { result ->
            if (sessionIsActive() && gate.accepts(token)) {
                var lineIndex = 0
                val elements = buildList {
                    result.textBlocks.forEach { block -> block.lines.forEach { line ->
                        val index = lineIndex++
                        val lineRect = line.boundingBox
                        if (lineRect != null) {
                            val lineBox = HighlighterGeometry.restoreBox(FloatBox(lineRect.left.toFloat(), lineRect.top.toFloat(),
                                lineRect.right.toFloat(), lineRect.bottom.toFloat()), area)
                            line.elements.forEach { element ->
                                element.boundingBox?.let { box ->
                                    add(HighlighterWord(element.text, HighlighterGeometry.restoreBox(FloatBox(box.left.toFloat(),
                                        box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()), area), index, lineBox))
                                }
                            }
                        }
                    } }
                }
                val selected = HighlighterGeometry.hitWords(selection, elements)
                val raw = HighlighterGeometry.selectedText(selected)
                when {
                    selected.isEmpty() -> {
                        perf.stage("ocr_empty", "failed")
                        showMessage("笔迹没有命中可识别文字。没有查询或新增记录；请沿字形重新划选，或手动纠错。")
                    }
                    selected.size == 1 && RawKajwebDictionary.normalize(selected.first().text) != null ->
                        lookup(selected.first().text, raw, credentialOverride = credential)
                    else -> {
                        perf.stage("ocr_multiple", "needs_confirmation")
                        showSelectedText(raw, selected.map { it.text })
                    }
                }
            }
        }.addOnFailureListener { e ->
            if (sessionIsActive() && gate.accepts(token)) { perf.stage("ocr", "failed"); showMessage("识别失败：${e.message}") }
        }.addOnCompleteListener { crop.recycle() }
    }

    private fun showSelectedText(raw: String, words: List<String>, manual: Boolean = false) {
        gate.next()
        val content = card("选中原文")
        content.addView(text(raw, 18f)); content.space()
        content.addView(text("译句未接入 · 请选择一个词查词；不会批量自动加入生词本。", 16f, Paper.warning)); content.space()
        words.filter { RawKajwebDictionary.normalize(it) != null }.distinct().forEach { word ->
            content.addView(button("查词：$word") { lookup(word, raw, manual = manual) }); content.space(8)
        }
        content.addView(button("纠正选中文字") { showTextCorrection(raw) }); content.space(8)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }
    private fun showTextCorrection(raw: String) {
        // The user is explicitly correcting the previous OCR text; no automatic OCR/page evidence
        // is being admitted from here, so stop observing the old page.
        endPageGuard()
        gate.next()
        val content = card("纠正选中文字")
        content.addView(text("保留大小写、标点与换行；确认后仍需选择一个词查词。译句未接入。"))
        val input = inputField("选中的原文").apply { setText(raw) }
        content.addView(input)
        content.addView(button("确认文字", true) {
            val corrected = input.text.toString()
            if (corrected.isBlank()) toast("选中文字不能为空；不会查询或入库")
            else showSelectedText(corrected, corrected.split(Regex("\\s+")), manual = true)
        }); content.space(8)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    private fun lookup(form: String, raw: String, replaces: String? = null,
                       credentialOverride: Long? = null, manual: Boolean = false) {
        val token = gate.next()
        val credential = credentialOverride ?: deletionCredential
        app.worker.execute {
            if (!sessionIsActive() || !gate.accepts(token)) return@execute
            // One unified resolution for the pinned asset plus the local supplement dictionary.
            val lookup = runCatching {
                // Capture the committed revision immediately before and after the actual read. A
                // commit in between means the merged entry and the user row may be from different
                // snapshots; such a read is rejected rather than mixed or relabelled later.
                val before = app.repository.changes.version
                val result = app.lookupWord(form)
                val user = (result as? LookupResult.Match)?.let { app.repository.userEntry(it.form) }
                val after = app.repository.changes.version
                LookupRead(result, user, after, before == after)
            }
            main.post {
                if (!sessionIsActive() || !gate.accepts(token)) return@post
                lookup.onSuccess { read ->
                    val result = read.result
                    when (result) {
                        LookupResult.Invalid -> { perf.stage("dictionary", "invalid"); showMessage("请输入一个有效词头，不自动拼接多个词。", raw, replaces) }
                        is LookupResult.Missing -> {
                            perf.stage("dictionary", "missing")
                            // The result is only valid in the world the query started in. A restore or
                            // this key's deletion must reject it instead of showing a writable card.
                            if (app.repository.deletions.isStale(result.form, credential))
                                showMessage("查询已失效（词条已删除或数据已恢复），请重新查询。", raw, replaces)
                            else if (!read.isCurrent(app.repository.changes.version)) {
                                // A supplement (or any committed data) changed after this read: drawing
                                // the old result would hide the current user meaning, so no card is made.
                                perf.stage("dictionary", "stale")
                                showMessage("查询已失效（词条或本机补充已更新），请重新查询。", raw, replaces)
                            } else showMissing(result.form, raw, replaces, credential)
                        }
                        is LookupResult.Match -> {
                            val entry = result.entries.single()
                            if (app.repository.deletions.isStale(entry.key, credential))
                                showMessage("查询已失效（词条已删除或数据已恢复），请重新查询。", raw, replaces)
                            else if (!read.isCurrent(app.repository.changes.version)) {
                                perf.stage("dictionary", "stale")
                                showMessage("查询已失效（词条或本机补充已更新），请重新查询。", raw, replaces)
                            } else showDefinition(result.form, raw, entry, read.user, replaces, credential, manual)
                        }
                    }
                }.onFailure {
                    perf.stage("dictionary", "failed")
                    showMessage("词库不可用：${it.message}。没有生成释义，也没有新增记录。", raw, replaces)
                }
            }
        }
    }
    private fun showMessage(message: String, raw: String = "", replaces: String? = null) {
        // A terminal message has no save/retry admission left; stop observing its page.
        endPageGuard()
        gate.next()
        val content = card("取词提示")
        content.addView(text(message, 16f, Paper.warning)); content.space()
        if (raw.isNotBlank()) {
            content.addView(text("选中原文：\n$raw")); content.space()
            content.addView(button("手动纠错") { showCorrection(raw, replaces) }); content.space(8)
        }
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("返回做题") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    /**
     * The floating uncollected state. Adding a new user headword here is local-only: it creates no
     * QueryEvent, count, vocabulary row or study card, and ends this automatic page admission. Only
     * the explicit "查询并收录" button starts a normal (manual) query that may enter personal words.
     */
    private fun showMissing(form: String, raw: String, replaces: String?, credential: Long) {
        // Display-entry check: a stale query never creates a writable floating card.
        if (app.repository.deletions.isStale(form, credential)) {
            showMessage("查询已失效（词条已删除或数据已恢复），请重新查询。", raw, replaces)
            return
        }
        endPageGuard()
        gate.next()
        val content = card("未收录")
        content.addView(text("「$form」未收录。可以只在本机新增用户词条；保存不会产生查询记录或生词。", 16f, Paper.warning))
        if (raw.isNotBlank()) { content.addView(text("选中原文：\n$raw")); content.space() }
        val meaningView = text("", 18f)
        val supplementStatus = text("", 14f, Paper.secondary)
        val editorHost = column()
        lateinit var guard: MissingGuard
        val collect = button("查询并收录", true) {
            if (!sessionIsActive()) return@button
            // A card whose world was replaced must not start a new explicit query that could be
            // perceived as the old query's write path.
            if (app.repository.deletions.isStale(form, credential)) { invalidateMissing(guard); return@button }
            // Explicit user action in the card's still-current world: the only path that may create a
            // real query and personal vocabulary. It keeps the original query credential instead of
            // recapturing, so a later restore cannot be laundered into a fresh write identity.
            lookup(form, raw, replaces, credentialOverride = credential, manual = true)
        }.apply { visibility = View.GONE }
        var currentUser: UserDictionaryEntry? = null
        var editorRoot: LinearLayout? = null
        val canAdd = PersonalDictionary.normalizeNewKey(form) == form
        if (!canAdd) {
            content.addView(text("该结果不是单个规范英文词头（可能是短语或多词），不能作为新词头；请修改为一个英文单词。", 15f, Paper.warning, true))
        }
        lateinit var edit: Button
        if (canAdd) {
            edit = button("新增这个词", true) {
                if (!sessionIsActive()) return@button
                // Bind the editor to the query-start credential; opening it after a restore must not
                // capture the new world and write the old text there.
                if (app.repository.deletions.isStale(form, credential)) { invalidateMissing(guard); return@button }
                editorRoot?.let { editorHost.removeView(it) }
                editorRoot = addSupplementEditor(editorHost, form, form, null, currentUser, credential,
                    newHeadword = true) { saved, merged ->
                    currentUser = saved
                    meaningView.text = "${merged.partOfSpeech} ${merged.meaning}".trim()
                    supplementStatus.text = "已保存到本机用户词库（仅本机；尚未收录为生词）"
                    supplementStatus.setTextColor(Paper.green)
                    collect.visibility = View.VISIBLE
                    edit.text = "重新编辑词义"
                }
            }
            content.addView(edit); content.space(4)
        }
        content.addView(collect); content.space(4)
        content.addView(meaningView); content.addView(supplementStatus)
        content.addView(editorHost)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("返回做题") { dismissPanel(); showBubble() })
        guard = MissingGuard(form, credential, if (canAdd) edit else null, collect, supplementStatus) {
            closeSupplementEditors()
        }
        addPanel(scroll(content))
        missingGuard = guard
    }

    /**
     * Inline supplement editor. It edits only user-owned POS/meaning; the original asset is shown
     * read-only and never rewritten. A stale handle (restore, deletion or a newer supplement) is
     * rejected instead of overwriting newer data, and no query/study row is written by this save.
     */
    private fun addSupplementEditor(host: LinearLayout, entryKey: String, headword: String,
                                    original: DictionaryEntry?, currentUser: UserDictionaryEntry?,
                                    credential: Long,
                                    newHeadword: Boolean = false,
                                    onSaved: (UserDictionaryEntry, DictionaryEntry) -> Unit): LinearLayout {
        // Never open a writable editor for a query world that has already been replaced.
        if (app.repository.deletions.isStale(entryKey, credential)) {
            val stale = column().apply {
                space(8); divider()
                addView(text("该词条已删除或数据已恢复，请重新查询后再编辑", 15f, Paper.warning, true))
            }
            host.addView(stale)
            return stale
        }
        val root = column().apply {
            space(8); divider()
            addView(text("补充/编辑词义 · 仅本机", 18f, bold = true))
            addView(text("不会覆盖原始词库，也不会计入查询次数或学习进度", 13f, Paper.secondary))
        }
        original?.let {
            root.addView(text("原词库释义（只读）", 15f, bold = true))
            root.addView(text("${it.partOfSpeech} ${it.meaning}".trim(), 15f))
            root.addView(text(PersonalDictionary.provenance(it), 12f, Paper.warning))
        }
        val pos = inputField("词性（可留空），如 n. / v.", singleLine = true).apply {
            setText(currentUser?.partOfSpeech ?: "")
        }
        val meaning = inputField("用户补充释义（必填）", singleLine = false).apply {
            minLines = 3; gravity = Gravity.TOP
            setText(currentUser?.meaning ?: "")
        }
        root.addView(pos); root.space(4); root.addView(meaning)
        val status = text("", 14f, Paper.secondary); root.addView(status)
        val save = button("保存到本机", true) {}
        root.addView(save)
        host.addView(root)
        var alive = true
        var saving = false
        // Bound to the deletion/restore world of the originating query, passed in explicitly. Opening
        // the editor never captures a fresh credential, so an old card cannot rebind after a restore.
        val editorLease = SupplementEditorLease(StudyAdmission {
            !app.repository.deletions.isStale(entryKey, credential)
        })
        supplementEditorLeases += editorLease
        fun closeEditor() {
            editorLease.closed = true
            editorLease.admission.invalidate()
            supplementEditorLeases -= editorLease
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            // Detach closes the lease permanently; a later reattach does not make it writable again.
            override fun onViewAttachedToWindow(v: View) { alive = true }
            override fun onViewDetachedFromWindow(v: View) { alive = false; closeEditor() }
        })
        val expectedUpdatedAt = currentUser?.updatedAt ?: 0L
        save.setOnClickListener {
            if (!alive || editorLease.closed || saving || !sessionIsActive()) return@setOnClickListener
            if (meaning.text.toString().isBlank()) {
                status.text = "释义不能为空，未保存"; status.setTextColor(Paper.error); return@setOnClickListener
            }
            if (app.repository.deletions.isStale(entryKey, credential)) {
                status.text = "词条已删除或数据已恢复，请重新划选后再编辑"; status.setTextColor(Paper.error)
                return@setOnClickListener
            }
            saving = true; save.isEnabled = false; status.text = "保存中…"; status.setTextColor(Paper.secondary)
            app.worker.execute {
                val result = app.repository.saveUserEntry(entryKey, pos.text.toString(), meaning.text.toString(),
                    expectedUpdatedAt = expectedUpdatedAt, newHeadword = newHeadword,
                    admission = editorLease.admission)
                main.post {
                    // A closed/stopped owner suppresses its old UI, but never reports an already
                    // committed save as unwritten; result.saved is the actual admission outcome.
                    if (!alive || editorLease.closed) return@post
                    saving = false
                    if (!result.saved) {
                        save.isEnabled = true
                        status.text = result.message; status.setTextColor(Paper.error); return@post
                    }
                    if (app.repository.deletions.isStale(entryKey, credential)) {
                        // Committed before the deletion/restore; do not claim "未保存" or repaint.
                        save.isEnabled = false
                        status.text = "保存已提交，但该词随后被删除或数据已恢复"
                        status.setTextColor(Paper.warning); return@post
                    }
                    val saved = result.entry ?: return@post
                    val merged = PersonalDictionary.merge(original, saved) ?: PersonalDictionary.userOnly(saved)
                    onSaved(saved, merged)
                    status.text = if (result.changed) "已保存到本机用户词库" else "词义未变化，未重复保存"
                    status.setTextColor(Paper.green)
                }
            }
        }
        return root
    }

    private fun showCorrection(raw: String, replaces: String?, originalRaw: String = raw) {
        // A correction is an explicit user action, not a late automatic result from the old page.
        endPageGuard()
        gate.next()
        val content = card("确认正确词形")
        content.addView(text("只查询本地原始未逐条核对词库；确认成功后原事件撤销，不双计。"))
        val input = EditText(this).apply { setText(raw); hint = "一个英文单词"; setSingleLine(true) }
        content.addView(input)
        content.addView(button("确认查询", true) { lookup(input.text.toString(), originalRaw, replaces, manual = true) }); content.space(8)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    private fun showDefinition(form: String, raw: String, entry: DictionaryEntry,
                               initialUser: UserDictionaryEntry?, replaces: String?,
                               credential: Long, manual: Boolean = false) {
        val token = gate.next()
        val page = pageGuard
        val event = QueryEvent(UUID.randomUUID().toString(), entry.key, raw, form, System.currentTimeMillis())
        val content = card(entry.headword)
        val meta = text("原词形：$form · ${entry.partOfSpeech}")
        val meaningView = text(entry.meaning, 18f)
        val provenanceView = text(PersonalDictionary.provenance(entry), 14f, Paper.warning)
        content.addView(meta)
        content.addView(text("选中原文：\n$raw"))
        content.addView(meaningView); content.space()
        content.addView(text(PersonalDictionary.label(entry), 13f, Paper.secondary))
        content.addView(provenanceView)
        val status = text("保存中…", 16f, Paper.secondary)
        content.addView(status)
        val undo = button("撤销本次收录") {}
        val correct = button("纠错") {}
        val retry = button("重试保存") {}
        undo.isEnabled = false; correct.isEnabled = false; retry.visibility = View.GONE
        content.addView(undo); content.space(8); content.addView(correct); content.space(8); content.addView(retry); content.space(8)
        var currentUser = initialUser
        val supplementStatus = text("", 13f, Paper.secondary)
        val editorHost = column()
        var editorRoot: LinearLayout? = null
        content.addView(button("补充/编辑词义") {
            val active = definitionGuard
            if (active != null && definitionCardStale(active)) { invalidateDefinition(active); return@button }
            if (!sessionIsActive()) return@button
            editorRoot?.let { editorHost.removeView(it) }
            val original = if (entry.source == PersonalDictionary.SOURCE) null
            else PersonalDictionary.storedEntry(entry)
            editorRoot = addSupplementEditor(editorHost, entry.key, entry.headword, original, currentUser,
                credential) { saved, merged ->
                currentUser = saved
                meta.text = "原词形：$form · ${merged.partOfSpeech}"
                meaningView.text = merged.meaning
                provenanceView.text = PersonalDictionary.provenance(merged)
                supplementStatus.text = "用户补充已保存到本机；本次查询记录不受影响"
                supplementStatus.setTextColor(Paper.green)
            }
        }); content.space(4)
        content.addView(supplementStatus); content.addView(editorHost); content.space(4)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("返回做题", true) { dismissPanel(); showBubble() })
        addPanel(scroll(content))
        val guard = DefinitionGuard(entry.key, credential, page, manual, status, undo, correct, retry)
        definitionGuard = guard
        correct.setOnClickListener {
            // Reject an expired card at the entry: an old card must not start a new correction flow.
            if (definitionCardStale(guard)) { invalidateDefinition(guard); return@setOnClickListener }
            showCorrection(form, event.id, raw)
        }
        val drawListener = object : ViewTreeObserver.OnDrawListener {
            var reported = false
            override fun onDraw() {
                if (!reported && sessionIsActive() && gate.accepts(token)) {
                    reported = true; perf.definitionVisible()
                    content.post { if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnDrawListener(this) }
                }
            }
        }
        content.viewTreeObserver.addOnDrawListener(drawListener)
        fun save() {
            // A stale card (word deleted after this query started) must not start/retry a save.
            if (definitionCardStale(guard)) { invalidateDefinition(guard); return }
            // Retry/initial save from an automatic OCR lookup may only run while the page accepted
            // at lift is still provably current. A user-confirmed correction has its own explicit
            // word and is not an automatic admission from the old page.
            if (!guard.saved && !guard.manual && guard.page?.isValid != true) {
                invalidateDefinition(guard)
                endPageGuard()
                return
            }
            retry.visibility = View.GONE; status.text = "保存中…"
            app.worker.execute {
                if (!sessionIsActive() || !gate.accepts(token)) return@execute
                val result = runCatching { app.repository.record(event, entry, replaces, credential) { sessionIsActive() && gate.accepts(token) } }
                main.post {
                    if (sessionIsActive() && gate.accepts(token) && definitionGuard === guard && !guard.invalid) {
                        val error = result.exceptionOrNull()
                        val saved = result.getOrNull() == true
                        // A committed deletion (even before the invalidation message runs) wins over a
                        // late error/success: never re-show retry nor overwrite the invalid text.
                        val stale = definitionCardStale(guard)
                        perf.stage("save", if (saved && !stale) "ok" else "failed")
                        when {
                            stale -> { invalidateDefinition(guard); endPageGuard() }
                            error != null -> {
                                status.text = "保存失败：${error.message}"; status.setTextColor(Paper.error)
                                undo.isEnabled = false; correct.isEnabled = false; retry.visibility = View.VISIBLE
                            }
                            saved -> {
                                guard.saved = true
                                status.text = "已收录 · 可撤销本次查询"; status.setTextColor(Paper.green)
                                undo.isEnabled = true; correct.isEnabled = true; retry.visibility = View.GONE
                                // The record is committed; no further automatic admission depends on
                                // this page. Later page changes never pretend to roll it back.
                                endPageGuard()
                            }
                            else -> { invalidateDefinition(guard); endPageGuard() }
                        }
                    }
                }
            }
        }
        retry.setOnClickListener { save() }
        undo.setOnClickListener {
            // Reject an expired card at the entry too, not only after the worker returns.
            if (definitionCardStale(guard)) { invalidateDefinition(guard); return@setOnClickListener }
            undo.isEnabled = false; correct.isEnabled = false
            app.worker.execute {
                // A no-op revoke (word already deleted / event gone) must not be reported as success.
                val result = runCatching { app.repository.revokeEvent(event.id, entry.key, credential) }
                main.post {
                    if (sessionIsActive() && gate.accepts(token) && definitionGuard === guard) {
                        // Deletion wins over a late revoke result: never re-enable undo/correct nor report REVOKED.
                        if (definitionCardStale(guard)) invalidateDefinition(guard) else {
                            val outcome = result.getOrNull()
                            when {
                                result.isFailure -> {
                                    status.text = "撤销失败：${result.exceptionOrNull()?.message}"
                                    undo.isEnabled = true; correct.isEnabled = true
                                }
                                outcome == RevokeOutcome.REVOKED -> { status.text = "本次查询已撤销；之前的记录保留"; undo.text = "已撤销" }
                                else -> {
                                    status.text = "撤销失败：该记录已不存在"; status.setTextColor(Paper.error)
                                    undo.isEnabled = false; correct.isEnabled = false
                                }
                            }
                        }
                    }
                }
            }
        }
        save()
    }
    private fun card(title: String) = column().apply {
        setPadding(dp(24), dp(24), dp(24), dp(24)); background = shape(Paper.surface, border = true)
        addView(text(title, 26f, bold = true))
    }
    private fun scroll(content: LinearLayout) = ScrollView(this).apply { addView(content); isFillViewport = true }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun sessionIsActive(): Boolean = !stopped && CaptureStatus.generation == sessionGeneration &&
        CaptureStatus.state.phase == CapturePhase.ACTIVE
    private fun fail(message: String) {
        perf.end("failed", "session")
        pendingError = message
        CaptureStatus.update(CapturePhase.STOPPING, "$message · 正在释放")
        toast(message); stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { CaptureStatus.stop(this) }
    override fun onDestroy() {
        perf.end("stopped", "session_stop")
        stopped = true
        // A stopped service rejects every queued supplement save that has not reached its
        // transaction yet; an already-admitted save keeps its atomic commit.
        closeSupplementEditors()
        app.repository.changes.remove(deletionListener)
        dismissPanel(); removeBubble()
        display?.release(); display = null
        projection?.stop(); projection = null
        reader?.setOnImageAvailableListener(null, null)
        val old = reader; reader = null
        val closingGeneration = CaptureStatus.generation
        if (CaptureStatus.state.phase != CapturePhase.PREPARING)
            CaptureStatus.update(CapturePhase.STOPPING, "正在关闭 · 释放捕获与浮层")
        frames.post {
            old?.close(); framesThread.quitSafely()
            main.post {
                if (CaptureStatus.generation == closingGeneration && CaptureStatus.state.phase == CapturePhase.STOPPING)
                    CaptureStatus.update(if (pendingError == null) CapturePhase.CLOSED else CapturePhase.ERROR,
                        pendingError ?: "已关闭 · 捕获与浮层已释放")
            }
        }
        recognizer?.close(); recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
