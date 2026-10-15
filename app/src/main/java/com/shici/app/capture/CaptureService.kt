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

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.shici.app.START_CAPTURE"
        const val ACTION_STOP = "com.shici.app.STOP_CAPTURE"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "capture"
    }
    private val main = Handler(Looper.getMainLooper())
    private val gate = QueryGate()
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
    private var screenshot: Bitmap? = null
    private var sessionGeneration = -1L
    // Captured once per capture session and carried through OCR, display, save and retry.
    private var deletionCredential = 0L
    private var pendingError: String? = null
    private var screenWidth = 1
    private var screenHeight = 1
    private var bubbleX = 20
    private var bubbleY = 160
    @Volatile private var stopped = false
    private val requestedFrame = AtomicReference<FreshFrameRequest?>(null)
    private var frameTimeout: Runnable? = null
    private val app get() = application as ShiciApplication
    // The visible "已收录" card; invalidated if its word is deleted while the card is still on screen.
    private class DefinitionGuard(
        val key: String, val credential: Long,
        val status: TextView, val undo: Button, val correct: Button, val retry: Button
    ) { @Volatile var invalid = false }
    @Volatile private var definitionGuard: DefinitionGuard? = null
    private val deletionListener: (Long) -> Unit = {
        val guard = definitionGuard
        if (guard != null && !guard.invalid && app.repository.deletions.isStale(guard.key, guard.credential)) {
            main.post { if (definitionGuard === guard) invalidateDefinition(guard) }
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
            if (showBubble()) CaptureStatus.update(CapturePhase.ACTIVE, "已开启 · 点击悬浮「词」开始划选")
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
            .setContentTitle("拾词 · 全屏查词已开启").setContentText("仅完成划选时识别；点停止释放捕获")
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
            val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null }
            if (image != null) {
                val request = requestedFrame.get()
                var bitmap: Bitmap? = null
                try {
                    if (request != null && source === reader && image.width == source.width && image.height == source.height &&
                        frameIsCurrent(request) &&
                        request.canInspect(image.timestamp, SystemClock.elapsedRealtime())) {
                        val captured = imageBitmap(image)
                        bitmap = captured
                        val black = FrameContent.isClearlyBlack(captured.width, captured.height, request.body) { x, y, row ->
                            captured.getPixels(row, 0, row.size, x, y, row.size, 1)
                        }
                        val firstBlack = black && !request.rejectedBlack
                        if (request.offer(image.timestamp, SystemClock.elapsedRealtime(), black)) {
                            if (main.post {
                                if (source === reader && frameIsCurrent(request) &&
                                    request.complete(SystemClock.elapsedRealtime()) && requestedFrame.compareAndSet(request, null)) {
                                    clearFrameTimeout()
                                    screenshot = captured
                                    showSelection(request.token, captured)
                                } else {
                                    captured.recycle()
                                    if (frameIsCurrent(request)) expireFrame(request)
                                }
                            }) bitmap = null // The main-thread callback now owns this bitmap.
                        } else if (firstBlack && request.rejectedBlack) {
                            main.post { if (frameIsCurrent(request)) perf.stage("frame_rejected", "black") }
                        }
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

    private fun clearFrameTimeout() {
        frameTimeout?.let { main.removeCallbacks(it) }
        frameTimeout = null
    }
    private fun expireFrame(request: FreshFrameRequest) {
        if (frameIsCurrent(request) && requestedFrame.compareAndSet(request, null)) {
            request.cancel(); gate.next(); clearFrameTimeout()
            perf.end("failed", if (request.rejectedBlack) "frame_black_timeout" else "frame_timeout")
            showMessage(if (request.rejectedBlack)
                "已丢弃主体黑帧，等待有效新画面超时。页面可能尚未就绪或禁止截图；不会复用旧截图或绕过保护。请返回做题后重试。"
                else "未收到新的有效屏幕画面。安全页面可能禁止截图；不会复用旧截图。请返回做题后重试。")
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

    private fun requestFrame() {
        if (!sessionIsActive() || requestedFrame.get() != null) return
        // New capture session: take one deletion credential reused until the next 划选.
        deletionCredential = app.repository.deletions.capture()
        val body = frameBody()
        dismissPanel()
        perf.click()
        val request = FreshFrameRequest(gate.next(), SystemClock.elapsedRealtime() + 4000, body)
        removeBubble()
        requestedFrame.set(request)
        // Observe compositor frames rather than sleeping a fixed number of milliseconds.
        Choreographer.getInstance().postFrameCallback {
            if (frameIsCurrent(request)) request.arm(System.nanoTime())
        }
        val timeout = Runnable { expireFrame(request) }
        frameTimeout = timeout
        main.postDelayed(timeout, (request.deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
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
        view.setOnClickListener { requestFrame() }
        return try { windows.addView(view, params); bubble = view; true }
        catch (e: Exception) { fail("悬浮窗不可用：${e.message}"); false }
    }
    private fun removeBubble() { bubble?.let { runCatching { windows.removeViewImmediate(it) } }; bubble = null }

    private fun addPanel(view: View, fullScreen: Boolean = false) {
        removePanelView()
        val params = WindowManager.LayoutParams(if (fullScreen) -1 else minOf(dp(440), screenWidth - dp(24)),
            if (fullScreen) -1 else -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (fullScreen) 0 else WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT).apply {
            gravity = if (fullScreen) Gravity.TOP or Gravity.START else Gravity.CENTER
            dimAmount = 0.18f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dismissPanel(); showBubble(); true } else false
        }
        try { windows.addView(view, params); panel = view; view.requestFocus() }
        catch (e: Exception) { fail("无法打开取词界面：${e.message}") }
    }
    private fun removePanelView() { definitionGuard = null; panel?.let { runCatching { windows.removeViewImmediate(it) } }; panel = null }
    private fun invalidateDefinition(guard: DefinitionGuard) {
        guard.invalid = true
        guard.status.text = "未收录：该词已删除或查询已失效"; guard.status.setTextColor(Paper.error)
        guard.undo.isEnabled = false; guard.correct.isEnabled = false; guard.retry.visibility = View.GONE
    }
    // A floating card is out of date once it is invalidated or its word was deleted after the query
    // credential was captured; both must win over any late success/failure result.
    private fun definitionCardStale(guard: DefinitionGuard): Boolean =
        guard.invalid || app.repository.deletions.isStale(guard.key, guard.credential)
    private fun dismissPanel() {
        perf.end("closed", "dismiss")
        gate.next(); requestedFrame.getAndSet(null)?.cancel(); clearFrameTimeout()
        removePanelView(); screenshot?.recycle(); screenshot = null
    }

    private fun showSelection(token: Long, bitmap: Bitmap) {
        val layout = column().apply { setBackgroundColor(Paper.background) }
        val selection = SelectionView(this, bitmap, { selected ->
            if (sessionIsActive() && gate.accepts(token)) recognize(bitmap, selected)
        }, { if (gate.accepts(token)) perf.selectionReady() })
        layout.addView(text("沿文字涂划 · 可多笔累积，跨行请抬笔\n静态截图不暂停题目；完成才识别，轻点不提交", 16f).apply {
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(8)) }
        header.addView(button("完成划选", true) {
            if (sessionIsActive() && gate.accepts(token) && !selection.submit()) toast("请沿文字划过至少 6dp，再完成划选；轻点不查询")
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        header.addView(button("重画") { selection.clearStrokes() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        header.addView(button("取消") { dismissPanel(); showBubble() }, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(header)
        layout.addView(selection, LinearLayout.LayoutParams(-1, 0, 1f))
        addPanel(layout, fullScreen = true)
    }

    private fun recognize(bitmap: Bitmap, selection: HighlighterSelection) {
        perf.selectionSubmit()
        val token = gate.next()
        val area = selection.crop
        val crop = try {
            val slice = Bitmap.createBitmap(bitmap, area.left, area.top, area.width, area.height)
            // OCR owns its bitmap until completion, even if cancellation recycles the screenshot.
            if (slice === bitmap) checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else slice
        } catch (e: Exception) {
            perf.stage("ocr", "failed"); showMessage("无法准备识别画面：${e.message}"); return
        }
        removePanelView()
        val pending = column().apply { setPadding(dp(24), dp(24), dp(24), dp(24)); background = shape(Paper.surface) }
        pending.addView(text("正在本地识别笔迹附近的文字…", 20f, bold = true))
        pending.addView(button("重新划选") { requestFrame() }); pending.space(8)
        pending.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(pending)
        if (!sessionIsActive() || !gate.accepts(token)) { crop.recycle(); return }
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
                    selected.size == 1 && RawKajwebDictionary.normalize(selected.first().text) != null -> lookup(selected.first().text, raw)
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

    private fun showSelectedText(raw: String, words: List<String>) {
        gate.next()
        val content = card("选中原文")
        content.addView(text(raw, 18f)); content.space()
        content.addView(text("译句未接入 · 请选择一个词查词；不会批量自动加入生词本。", 16f, Paper.warning)); content.space()
        words.filter { RawKajwebDictionary.normalize(it) != null }.distinct().forEach { word ->
            content.addView(button("查词：$word") { lookup(word, raw) }); content.space(8)
        }
        content.addView(button("纠正选中文字") { showTextCorrection(raw) }); content.space(8)
        content.addView(button("重新划选") { requestFrame() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }
    private fun showTextCorrection(raw: String) {
        gate.next()
        val content = card("纠正选中文字")
        content.addView(text("保留大小写、标点与换行；确认后仍需选择一个词查词。译句未接入。"))
        val input = inputField("选中的原文").apply { setText(raw) }
        content.addView(input)
        content.addView(button("确认文字", true) {
            val corrected = input.text.toString()
            if (corrected.isBlank()) toast("选中文字不能为空；不会查询或入库")
            else showSelectedText(corrected, corrected.split(Regex("\\s+")))
        }); content.space(8)
        content.addView(button("重新划选") { requestFrame() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    private fun lookup(form: String, raw: String, replaces: String? = null) {
        val token = gate.next()
        val credential = deletionCredential
        app.worker.execute {
            if (!sessionIsActive() || !gate.accepts(token)) return@execute
            val lookup = runCatching { app.dictionary.getOrThrow().lookup(form) }
            main.post {
                if (!sessionIsActive() || !gate.accepts(token)) return@post
                lookup.onSuccess { result ->
                    when (result) {
                        LookupResult.Invalid -> { perf.stage("dictionary", "invalid"); showMessage("请输入一个有效词头，不自动拼接多个词。", raw, replaces) }
                        is LookupResult.Missing -> { perf.stage("dictionary", "missing"); showMessage("「${result.form}」未收录于原始词库。没有生成释义，也没有新增记录。", raw, replaces) }
                        is LookupResult.Match -> showDefinition(result.form, raw, result.entries.single(), replaces, credential)
                    }
                }.onFailure {
                    perf.stage("dictionary", "failed")
                    showMessage("原始词库不可用：${it.message}。没有生成释义，也没有新增记录。", raw, replaces)
                }
            }
        }
    }
    private fun showMessage(message: String, raw: String = "", replaces: String? = null) {
        gate.next()
        val content = card("取词提示")
        content.addView(text(message, 16f, Paper.warning)); content.space()
        if (raw.isNotBlank()) { content.addView(text("选中原文：\n$raw")); content.space() }
        content.addView(button("手动纠错") { showCorrection(raw, replaces) }); content.space(8)
        content.addView(button("重新划选") { requestFrame() }); content.space(8)
        content.addView(button("返回做题") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }
    private fun showCorrection(raw: String, replaces: String?, originalRaw: String = raw) {
        gate.next()
        val content = card("确认正确词形")
        content.addView(text("只查询本地原始未逐条核对词库；确认成功后原事件撤销，不双计。"))
        val input = EditText(this).apply { setText(raw); hint = "一个英文单词"; setSingleLine(true) }
        content.addView(input)
        content.addView(button("确认查询", true) { lookup(input.text.toString(), originalRaw, replaces) }); content.space(8)
        content.addView(button("重新划选") { requestFrame() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    private fun showDefinition(form: String, raw: String, entry: DictionaryEntry, replaces: String?, credential: Long) {
        val token = gate.next()
        val event = QueryEvent(UUID.randomUUID().toString(), entry.key, raw, form, System.currentTimeMillis())
        val content = card(entry.headword)
        content.addView(text("原词形：$form · ${entry.partOfSpeech}"))
        content.addView(text("选中原文：\n$raw"))
        content.addView(text(entry.meaning, 18f)); content.space()
        content.addView(text(RawKajwebDictionary.provenance(entry.source, entry.version), 14f, Paper.warning))
        val status = text("保存中…", 16f, Paper.secondary)
        content.addView(status)
        val undo = button("撤销本次收录") {}
        val correct = button("纠错") {}
        val retry = button("重试保存") {}
        undo.isEnabled = false; correct.isEnabled = false; retry.visibility = View.GONE
        content.addView(undo); content.space(8); content.addView(correct); content.space(8); content.addView(retry); content.space(8)
        content.addView(button("重新划选") { requestFrame() }); content.space(8)
        content.addView(button("返回做题", true) { dismissPanel(); showBubble() })
        addPanel(scroll(content))
        val guard = DefinitionGuard(entry.key, credential, status, undo, correct, retry)
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
                            stale -> invalidateDefinition(guard)
                            error != null -> {
                                status.text = "保存失败：${error.message}"; status.setTextColor(Paper.error)
                                undo.isEnabled = false; correct.isEnabled = false; retry.visibility = View.VISIBLE
                            }
                            saved -> {
                                status.text = "已收录 · 可撤销本次查询"; status.setTextColor(Paper.green)
                                undo.isEnabled = true; correct.isEnabled = true; retry.visibility = View.GONE
                            }
                            else -> invalidateDefinition(guard)
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
