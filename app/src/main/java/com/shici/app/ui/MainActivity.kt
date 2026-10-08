package com.shici.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import com.shici.app.ShiciApplication
import com.shici.app.capture.*
import com.shici.app.core.*
import com.shici.app.export.PdfExporter
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : Activity() {
    companion object {
        private const val OVERLAY_PERMISSION = 10
        private const val CAPTURE_PERMISSION = 11
        private const val NOTIFICATION_PERMISSION = 12
        private const val EXPORT_FILE = 13
    }
    private val app get() = application as ShiciApplication
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var nav: LinearLayout
    private var currentTab = 0
    private var words = emptyList<LearnedWord>()
    private var stats = LearningStats(0, 0, 0, 0, 0)
    private var search = ""
    private var selectedKey: String? = null
    private var consentGeneration = -1L
    private var renderGeneration = 0
    private var pendingExport: List<LearnedWord>? = null
    private var exportDate: LocalDate? = null
    private var exporting = false
    private var floatingSwitch: Switch? = null
    private var floatingText: TextView? = null
    private var syncSwitch = false
    private var searchList: LinearLayout? = null
    private var detailPanel: LinearLayout? = null
    private val demoGate = QueryGate()
    private var activeDialog: AlertDialog? = null
    private val wide get() = resources.configuration.screenWidthDp >= 840
    private val statusListener: (CaptureState) -> Unit = { state ->
        syncSwitch = true
        floatingSwitch?.isEnabled = state.phase != CapturePhase.STOPPING
        floatingSwitch?.isChecked = state.phase == CapturePhase.ACTIVE || state.phase == CapturePhase.PREPARING
        syncSwitch = false
        floatingText?.apply {
            text = state.message
            setTextColor(if (state.phase == CapturePhase.ERROR) Paper.error else Paper.secondary)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentTab = savedInstanceState?.getInt("tab") ?: 0
        search = savedInstanceState?.getString("search") ?: ""
        consentGeneration = savedInstanceState?.getLong("consent", -1L) ?: -1L
        buildShell()
    }
    override fun onStart() { super.onStart(); CaptureStatus.observe(statusListener); refresh() }
    override fun onStop() { CaptureStatus.remove(statusListener); super.onStop() }
    override fun onDestroy() { demoGate.next(); activeDialog?.dismiss(); activeDialog = null; renderGeneration += 1; super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", currentTab); outState.putString("search", search)
        outState.putLong("consent", consentGeneration)
        super.onSaveInstanceState(outState)
    }

    private fun buildShell() {
        root = if (wide) row() else column()
        root.setBackgroundColor(Paper.background)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        body = column().apply { setPadding(dp(if (wide) 32 else 20), dp(24), dp(if (wide) 32 else 20), dp(12)) }
        nav = if (wide) column() else row()
        nav.setPadding(dp(12), dp(12), dp(12), dp(12))
        if (wide) {
            nav.addView(text("拾词", 32f, Paper.green, true)); nav.addView(text("专注阅读\n随手拾词", 16f, Paper.secondary)); nav.space(40)
            root.addView(nav, LinearLayout.LayoutParams(dp(188), -1))
            root.addView(body, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        }
        listOf("首页", "生词本", "统计", "设置").forEachIndexed { index, label ->
            nav.addView(button(label) { currentTab = index; render() },
                if (wide) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                else LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        setContentView(root)
        root.requestApplyInsets()
        render()
    }

    private fun refresh() {
        val revision = ++renderGeneration
        app.worker.execute {
            val result = runCatching {
                val list = app.repository.words()
                list to LearningRules.summarize(list, app.repository.events(), LocalDate.now(), ZoneId.systemDefault())
            }
            main.post {
                if (!isDestroyed && revision == renderGeneration) {
                    result.onSuccess { (list, summary) -> words = list; stats = summary; render() }
                        .onFailure { toast("读取失败：${it.message}") }
                }
            }
        }
    }

    private fun render() {
        floatingSwitch = null; floatingText = null; searchList = null; detailPanel = null
        body.removeAllViews()
        val navOffset = if (wide) 3 else 0
        repeat(4) { index ->
            val button = nav.getChildAt(index + navOffset) as Button
            button.background = shape(if (index == currentTab) Paper.lightGreen else Paper.background, 8)
            button.setTypeface(button.typeface, if (index == currentTab) Typeface.BOLD else Typeface.NORMAL)
            button.isSelected = index == currentTab
        }
        when (currentTab) { 0 -> home(); 1 -> vocabulary(); 2 -> statistics(); 3 -> settings() }
    }
    private fun scrolled(): LinearLayout {
        val content = column()
        body.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, -1))
        return content
    }
    private fun panel(): LinearLayout = column().apply {
        setPadding(dp(24), dp(24), dp(24), dp(24)); background = shape(Paper.surface, border = true)
    }
    private fun home() {
        val content = scrolled()
        content.addView(text("专注阅读，随手拾词", 16f, Paper.secondary))
        content.addView(text("让生词停在这里", if (wide) 36f else 28f, Paper.ink, true)); content.space(24)
        val session = panel()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(text("悬浮查词", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        floatingSwitch = Switch(this).apply {
            contentDescription = "开启或关闭悬浮查词"; minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                if (!syncSwitch) {
                    if (checked) beginCapture() else stopCapture()
                }
            }
        }
        heading.addView(floatingSwitch)
        session.addView(heading)
        floatingText = text(CaptureStatus.state.message, 16f, Paper.secondary)
        session.addView(floatingText)
        session.addView(text("开启后，可在题目页面框选查词", 16f, Paper.secondary))
        session.divider()
        val numbers = row()
        addMetric(numbers, "今日查询", stats.todayQueries); addMetric(numbers, "今日新增", stats.todayNew)
        session.addView(numbers)
        content.addView(session); content.space(24)
        content.addView(text("最近查到的单词", 22f, bold = true)); content.space(8)
        if (words.isEmpty()) {
            val empty = panel()
            empty.addView(text("查到的第一个单词，会留在这里", 18f))
            empty.addView(text("开启悬浮查词，或在设置中输入演示单词验证。", 16f, Paper.secondary))
            content.addView(empty)
        } else words.take(5).forEach { word -> content.addView(wordRow(word) { showWordDialog(word) }); content.divider() }
        content.space(24)
        val demo = column().apply {
            setPadding(dp(20), dp(16), dp(20), dp(16)); background = shape(Paper.lightGreen)
            addView(text("演示词库", 18f, Paper.green, true))
            addView(text("仅 ${DemoDictionary.entries.size} 个自编示例词条。未收录不生成，不是牛津或正式考研词库。", 16f, Paper.secondary))
        }
        content.addView(demo); content.space()
        content.addView(text("打开悬浮查词 → 回到题目 → 框选单词", 16f, Paper.secondary))
        statusListener(CaptureStatus.state)
    }
    private fun addMetric(row: LinearLayout, label: String, value: Int) {
        val content = column().apply { addView(text(label, 16f, Paper.secondary)); addView(text(value.toString(), 30f, bold = true)) }
        row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun vocabulary() {
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("生词本", 28f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("导出 PDF", true) { export() }.apply { isEnabled = filteredWords().isNotEmpty() && !exporting })
        body.addView(header); body.space(16)
        val input = EditText(this).apply {
            hint = "搜索单词、释义或笔记"; textSize = 16f; setSingleLine(true); setText(search)
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = shape(Paper.surface, 8, true)
        }
        body.addView(input); body.space(16)
        val container = if (wide) row() else column()
        searchList = column()
        container.addView(ScrollView(this).apply { addView(searchList) },
            if (wide) LinearLayout.LayoutParams(0, -1, 0.44f) else LinearLayout.LayoutParams(-1, -1))
        if (wide) {
            detailPanel = column().apply { setPadding(dp(24), 0, 0, 0) }
            container.addView(ScrollView(this).apply { addView(detailPanel) }, LinearLayout.LayoutParams(0, -1, 0.56f))
        }
        body.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                search = s.toString(); header.getChildAt(1).isEnabled = filteredWords().isNotEmpty() && !exporting
                renderWords()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderWords()
    }
    private fun filteredWords() = words.filter { word -> search.isBlank() ||
        listOf(word.entry.headword, word.entry.meaning, word.lastForm, word.note).any { it.contains(search.trim(), true) } }
    private fun renderWords() {
        val list = filteredWords()
        searchList?.removeAllViews()
        searchList?.addView(text("${list.size} 个词 · 最近查询优先", 14f, Paper.secondary))
        if (list.isEmpty()) searchList?.addView(text(if (words.isEmpty()) "还没有生词。成功查询后会自动收录。" else "没有匹配的生词。", 18f))
        list.forEach { word ->
            searchList?.addView(wordRow(word) {
                selectedKey = word.entry.key
                if (wide) renderWords() else showWordDialog(word)
            }.apply {
                if (wide && selectedKey == word.entry.key) background = shape(Paper.lightGreen, 8)
            })
            searchList?.divider()
        }
        detailPanel?.let { detail ->
            detail.removeAllViews()
            val selected = list.firstOrNull { it.entry.key == selectedKey } ?: list.firstOrNull()
            if (selected == null) detail.addView(text("选择一个生词，查看释义和记录", 18f, Paper.secondary))
            else { selectedKey = selected.entry.key; wordDetail(detail, selected) }
        }
    }
    private fun wordRow(word: LearnedWord, action: () -> Unit): View = column().apply {
        setPadding(dp(12), dp(8), dp(12), dp(8)); minimumHeight = dp(72)
        addView(text(word.entry.headword, 23f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        addView(text("${word.entry.partOfSpeech} ${word.entry.meaning}", 16f, Paper.secondary))
        addView(text("查询 ${word.queryCount} 次 · ${if (word.mastered) "已掌握" else "待复习"}", 14f, Paper.secondary))
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    private fun wordDetail(content: LinearLayout, word: LearnedWord, close: (() -> Unit)? = null) {
        content.addView(text(word.entry.headword, 30f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        content.addView(text("原词形：${word.lastForm} · ${word.entry.partOfSpeech}")); content.space()
        content.addView(text(word.entry.meaning, 18f)); content.space()
        content.addView(text("演示词库 · ${word.source}\n${word.version}", 14f, Paper.warning)); content.divider()
        content.addView(text("查询 ${word.queryCount} 次\n首次：${date(word.firstAt)}\n最近：${date(word.lastAt)}", 16f, Paper.secondary))
        val mastered = CheckBox(this).apply { text = "已掌握"; textSize = 16f; isChecked = word.mastered; minHeight = dp(48) }
        content.addView(mastered); content.space()
        content.addView(text("个人笔记", 18f, bold = true))
        val note = EditText(this).apply { setText(word.note); hint = "自己的记忆方法"; minLines = 3; gravity = Gravity.TOP; textSize = 16f }
        content.addView(note)
        content.addView(button("保存笔记与状态", true) {
            val marked = mastered.isChecked
            val noteText = note.text.toString()
            app.worker.execute {
                val error = runCatching { app.repository.updateWord(word.entry.key, marked, noteText) }.exceptionOrNull()
                main.post { if (!isDestroyed) { if (error == null) { toast("已保存"); close?.invoke(); refresh() } else toast("保存失败：${error.message}") } }
            }
        })
        content.space(8)
        content.addView(button("撤销最近一次查询") { revokeLast(word, close) })
    }
    private fun revokeLast(word: LearnedWord, close: (() -> Unit)?) {
        AlertDialog.Builder(this).setTitle("撤销最近一次查询？")
            .setMessage("只撤销 ${word.entry.headword} 最近的一次成功事件，之前的查询和笔记保留。")
            .setPositiveButton("撤销") { _, _ ->
                app.worker.execute {
                    val error = runCatching {
                        val id = app.repository.latestEventId(word.entry.key) ?: error("没有可撤销的查询")
                        app.repository.revoke(id)
                    }.exceptionOrNull()
                    main.post { if (!isDestroyed) {
                        if (error == null) { close?.invoke(); refresh(); toast("最近一次查询已撤销") }
                        else toast("撤销失败：${error.message}")
                    } }
                }
            }.setNegativeButton("取消", null).show()
    }
    private fun showWordDialog(word: LearnedWord) {
        val content = panel()
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).setNegativeButton("关闭", null).create()
        wordDetail(content, word) { dialog.dismiss() }
        activeDialog?.dismiss(); activeDialog = dialog; dialog.show()
    }
    private fun statistics() {
        val content = scrolled()
        content.addView(text("学习统计", 28f, bold = true)); content.space(16)
        content.addView(text("只统计未撤销的成功查询；次数不是考试频率。", 16f, Paper.secondary)); content.space(24)
        val counts = row(); addMetric(counts, "累计查询", stats.totalQueries); addMetric(counts, "生词总数", words.size); content.addView(counts)
        content.divider()
        val mastery = row(); addMetric(mastery, "待复习", stats.learning); addMetric(mastery, "已掌握", stats.mastered); content.addView(mastery)
        content.divider()
        val today = row(); addMetric(today, "今日查询", stats.todayQueries); addMetric(today, "今日新增", stats.todayNew); content.addView(today)
        content.space(24); content.addView(text("最常查询的词", 22f, bold = true))
        if (words.isEmpty()) content.addView(text("完成第一次查询后，这里才会有统计。", 16f, Paper.secondary))
        words.sortedByDescending { it.queryCount }.take(10).forEach { word -> content.addView(wordRow(word) { showWordDialog(word) }); content.divider() }
        content.addView(button("刷新统计") { refresh() })
    }
    private fun settings() {
        val content = scrolled()
        content.addView(text("设置", 28f, bold = true)); content.space(24)
        content.addView(text("词库来源", 22f, bold = true))
        content.addView(text("演示词库 · ${DemoDictionary.SOURCE}\n版本：${DemoDictionary.VERSION}\n${DemoDictionary.entries.size} 个自编示例，不是牛津或正式考研词典。只有显式收录的词形会映射；歧义必须人工确认。"))
        content.divider()
        content.addView(text("演示输入查词", 22f, bold = true))
        content.addView(text("不需要悬浮窗或屏幕捕获权限。成功后形成真实本地查询记录。", 16f, Paper.secondary))
        val input = EditText(this).apply { hint = "例如 evidence、studies、saw"; setSingleLine(true); textSize = 16f }
        content.addView(input)
        content.addView(button("查询演示词库", true) { demoLookup(input.text.toString()) }); content.space(8)
        content.addView(text("示例词头：${DemoDictionary.entries.joinToString { it.headword }}", 14f, Paper.secondary))
        content.divider()
        content.addView(text("权限与隐私", 22f, bold = true))
        content.addView(text("仅开启查词时申请悬浮窗、屏幕捕获和通知权限。捕获会话可看到整个屏幕；截图仅临时保存在内存，框选区域在本机识别，不写截图文件、不上传。静态取词画面不暂停题目计时。"))
        content.addView(text("悬浮窗权限：${if (Settings.canDrawOverlays(this)) "已允许" else "未允许"}", 16f, Paper.secondary))
        content.addView(button("关闭悬浮查词并释放捕获") { stopCapture(); toast("已请求停止查词") })
        content.divider()
        content.addView(text("原型限制", 22f, bold = true))
        content.addView(text("PDF 支持当前搜索结果的 A4 记忆版导出。尚未实现正式词典、发音、备份恢复、自测版或日期筛选。没有真机验证速度、离线首次 OCR、海文兼容性与长时间功耗。"))
    }

    private fun beginCapture() {
        if (CaptureStatus.state.phase == CapturePhase.ACTIVE || CaptureStatus.state.phase == CapturePhase.PREPARING ||
            CaptureStatus.state.phase == CapturePhase.STOPPING) { statusListener(CaptureStatus.state); return }
        consentGeneration = CaptureStatus.prepare()
        AlertDialog.Builder(this).setTitle("开启全屏悬浮查词")
            .setMessage("系统会授予本工具整个屏幕的捕获权限。截图仅临时保存在内存，在本机识别，不上传；框选时显示的静态画面不会暂停题目或视频。关闭开关或通知中的停止会释放捕获和浮层。")
            .setPositiveButton("继续") { _, _ ->
                if (consentIsCurrent()) {
                    if (!Settings.canDrawOverlays(this)) {
                        runCatching { startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), OVERLAY_PERMISSION) }
                            .onFailure { permissionError("无法打开悬浮窗授权：${it.message}") }
                    } else requestNotificationThenCapture()
                }
            }.setNegativeButton("取消") { _, _ -> stopCapture() }.setOnCancelListener { stopCapture() }.show()
    }
    private fun consentIsCurrent() = consentGeneration == CaptureStatus.generation && CaptureStatus.state.phase == CapturePhase.PREPARING
    private fun stopCapture() { consentGeneration = -1L; CaptureStatus.stop(this) }
    private fun requestNotificationThenCapture() {
        if (!consentIsCurrent()) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        } else requestCapture()
    }
    private fun requestCapture() {
        if (!consentIsCurrent()) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
        runCatching { startActivityForResult(intent, CAPTURE_PERMISSION) }.onFailure { permissionError("无法请求捕获授权：${it.message}") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION && consentIsCurrent()) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("通知未允许；仍可在首页开关或系统前台服务入口停止")
            requestCapture()
        }
    }
    @Deprecated("Platform result API retained to keep this native prototype dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION -> if (consentIsCurrent()) {
                if (Settings.canDrawOverlays(this)) requestNotificationThenCapture() else permissionError("未允许悬浮窗，查词未开启")
            }
            CAPTURE_PERMISSION -> if (consentIsCurrent()) {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT, resultCode).putExtra(CaptureService.EXTRA_DATA, data)
                        .putExtra(CaptureService.EXTRA_GENERATION, consentGeneration)
                    runCatching { startForegroundService(intent) }.onFailure { permissionError("无法启动捕获服务：${it.message}") }
                } else permissionError("已取消屏幕捕获，查词未开启")
            }
            EXPORT_FILE -> {
                val snapshot = pendingExport; pendingExport = null
                val frozenDate = exportDate ?: LocalDate.now(); exportDate = null
                if (resultCode == RESULT_OK && data?.data != null && snapshot != null) savePdf(data.data!!, snapshot, frozenDate)
                else { exporting = false; toast(if (snapshot == null && resultCode == RESULT_OK) "导出状态因重建丢失，请重新导出；没有写入文件" else "已取消导出"); render() }
            }
        }
    }
    private fun permissionError(message: String) { stopCapture(); CaptureStatus.update(CapturePhase.ERROR, message); toast(message) }

    private fun demoLookup(raw: String, replaces: String? = null, originalRaw: String = raw) {
        when (val result = DemoDictionary.lookup(raw)) {
            LookupResult.Invalid -> toast("请输入一个英文单词")
            is LookupResult.Missing -> toast("${result.form} 未收录于演示词库；未新增记录")
            is LookupResult.Match -> if (result.entries.size > 1) {
                AlertDialog.Builder(this).setTitle("词形有歧义，请选择词头")
                    .setItems(result.entries.map { "${it.headword} · ${it.meaning}" }.toTypedArray()) { _, index ->
                        showDemoDefinition(originalRaw, result.form, result.entries[index], replaces)
                    }.setNegativeButton("取消", null).show()
            } else showDemoDefinition(originalRaw, result.form, result.entries.first(), replaces)
        }
    }
    private fun showDemoDefinition(raw: String, form: String, entry: DictionaryEntry, replaces: String?) {
        val content = panel()
        content.addView(text(entry.headword, 28f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        content.addView(text("原词形：$form · ${entry.partOfSpeech}")); content.addView(text(entry.meaning, 18f))
        content.addView(text("演示词库 · ${DemoDictionary.SOURCE}", 14f, Paper.warning))
        val status = text("保存中…", 16f, Paper.secondary); content.addView(status)
        val undo = button("撤销本次收录") {}; undo.isEnabled = false; content.addView(undo); content.space(8)
        val correct = button("纠错") {}; correct.isEnabled = false; content.addView(correct); content.space(8)
        val retry = button("重试保存") {}; retry.visibility = View.GONE; content.addView(retry)
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).setNegativeButton("关闭", null).create()
        activeDialog?.dismiss(); activeDialog = dialog
        val token = demoGate.next()
        dialog.setOnDismissListener { if (demoGate.accepts(token)) demoGate.next() }
        dialog.show()
        val event = QueryEvent(UUID.randomUUID().toString(), entry.key, raw, form, System.currentTimeMillis())
        fun save() {
            retry.visibility = View.GONE; status.text = "保存中…"
            app.worker.execute {
                if (!demoGate.accepts(token)) return@execute
                val error = runCatching { app.repository.record(event, entry, replaces) { demoGate.accepts(token) } }.exceptionOrNull()
                main.post {
                    if (!isDestroyed && dialog.isShowing) {
                        status.text = if (error == null) "已收录" else "保存失败：${error.message}"
                        status.setTextColor(if (error == null) Paper.green else Paper.error)
                        undo.isEnabled = error == null; correct.isEnabled = error == null
                        retry.visibility = if (error == null) View.GONE else View.VISIBLE
                    }
                    if (!isDestroyed) refresh()
                }
            }
        }
        retry.setOnClickListener { save() }
        undo.setOnClickListener {
            undo.isEnabled = false; correct.isEnabled = false
            app.worker.execute {
                val error = runCatching { app.repository.revoke(event.id) }.exceptionOrNull()
                main.post {
                    if (!isDestroyed && dialog.isShowing) {
                        status.text = if (error == null) "本次已撤销，历史查询保留" else "撤销失败：${error.message}"
                        if (error == null) undo.text = "已撤销" else { undo.isEnabled = true; correct.isEnabled = true }
                    }
                    if (!isDestroyed) refresh()
                }
            }
        }
        correct.setOnClickListener {
            dialog.dismiss()
            val input = EditText(this).apply { setText(form); setSingleLine(true) }
            AlertDialog.Builder(this).setTitle("纠正词形").setView(input).setMessage("确认成功后撤销原事件，不重复累计。")
                .setPositiveButton("确认查询") { _, _ -> demoLookup(input.text.toString(), event.id, raw) }.setNegativeButton("取消", null).show()
        }
        save()
    }
    private fun export() {
        val snapshot = filteredWords().toList()
        if (snapshot.isEmpty()) { toast("没有可导出的生词"); return }
        if (exporting) return
        exporting = true; pendingExport = snapshot; exportDate = LocalDate.now()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf").putExtra(Intent.EXTRA_TITLE, "拾词-生词记忆单-${exportDate}.pdf")
        runCatching { startActivityForResult(intent, EXPORT_FILE) }.onFailure { pendingExport = null; exporting = false; toast("无法打开文件保存：${it.message}") }
        render()
    }
    private fun savePdf(uri: Uri, snapshot: List<LearnedWord>, date: LocalDate) {
        app.worker.execute {
            val error = runCatching {
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                output.use { PdfExporter.write(snapshot, it, date) }
            }.exceptionOrNull()
            main.post { if (!isDestroyed) { exporting = false; toast(if (error == null) "PDF 已写入所选位置" else "导出失败：${error.message}；目标文件可能不完整，请删除后重试"); render() } }
        }
    }
    private fun date(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
