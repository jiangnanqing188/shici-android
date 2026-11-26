package com.shici.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * 设置页的独立正文，只负责分组骨架与静态说明。
 *
 * 职责边界：
 * - 不访问 repository / app / worker，不读取学习数据，不发起备份、恢复、权限申请或屏幕捕获；
 * - 不持有 Activity 的动态生命周期。查词状态与停止按钮、备份按钮与状态、词库校验状态
 *   都由 MainActivity 沿用原有字段创建后挂到本类的 host 上，仍由原异步回调与 statusListener 更新；
 * - 系统权限入口只回调调用方打开的按钮，本页不会自动申请或开启屏幕捕获投影。
 *
 * 首屏优先显示日常状态和动作：查词会话 → 权限入口 → 页面文字直读开关 → 备份/恢复 →
 * 使用帮助 → 关于。权限与捕获说明、备份校验/覆盖规则、词库来源、联网边界和隐私细节都放在
 * 默认收起的 [helpDisclosure] 中；恢复覆盖提醒与文字直读的短隐私提醒始终可见。
 *
 * 汇总接线提示：`MainActivity.settings()` 构造本组件后，把 [view] 加到 body；再将原有
 * `backupExportButton` / `restoreButton` 加入 [backupActionHost]，`backupStatusView` 加入
 * [backupStatusHost]，`floatingText` 加入 [captureStatusHost]，`stopCaptureButton` 加入
 * [captureActionHost]，`dictionaryStatus()` 加入 [dictionaryStatusHost]，最后调用原有
 * `updateBackupButtons()` 与 `statusListener(CaptureStatus.state)`。权限徽章在重新进入设置页
 * 或由调用方在回到前台时通过 [updatePermissionStatus] 刷新；文字直读开关与状态行由
 * [updateAccessibilityStatus] 按持久化/连接实况同步；本组件不自行读取系统权限或启用服务。
 *
 * @param overlayStatus 悬浮窗权限的展示状态（由 Activity 读取系统状态后传入）。
 * @param notificationStatus 通知权限的展示状态；API 33 以下可传“无需单独授权”。
 * @param accessibilityStatus 页面文字直读的开关与连接状态；label 由调用方按真实状态生成。
 * @param versionText 内部版本说明，由调用方从 BuildConfig 读取，避免本组件硬编码版本。
 * @param provenanceText 原始词库来源说明，由调用方沿用 RawKajwebDictionary.provenance 生成。
 * @param onOpenOverlayPermission 打开系统悬浮窗权限设置；不在此处申请捕获。
 * @param onOpenNotificationSettings 打开系统通知设置；不自动弹出运行时授权。
 * @param onOpenUsageGuide 打开完整使用说明页；尚未接线时传 null，本页不显示空的帮助入口。
 * @param onAccessibilityTextEnabledChange 用户拨动页面文字直读开关；Activity 负责隐私确认与持久化。
 * @param onOpenAccessibilitySettings 打开系统无障碍设置；本组件不会启用或关闭系统服务。
 */
class SettingsScreen(
    context: Context,
    overlayStatus: PermissionStatus,
    notificationStatus: PermissionStatus,
    accessibilityStatus: AccessibilityStatus,
    versionText: String,
    provenanceText: String,
    onOpenOverlayPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenUsageGuide: (() -> Unit)? = null,
    onAccessibilityTextEnabledChange: (Boolean) -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {}
) {
    /** 权限徽章文案与配色状态；label 由调用方决定，便于区分“无需单独授权”。 */
    data class PermissionStatus(val label: String, val granted: Boolean)

    /** 页面文字直读的展示状态：开关是否开启、系统服务是否已连接及诚实的短标签。 */
    data class AccessibilityStatus(
        val enabled: Boolean,
        val connected: Boolean,
        val label: String,
        val healthy: Boolean
    )

    /** 完整可滚动正文；加到页面 body 时使用 MATCH_PARENT 宽高。 */
    val view: View

    /** 词库校验状态行宿主，调用方把原 dictionaryStatus() 加入这里。 */
    val dictionaryStatusHost: LinearLayout

    /** 悬浮查词动态状态行宿主，调用方把原 floatingText 加入这里。 */
    val captureStatusHost: LinearLayout

    /** 停止查词按钮宿主，调用方把原 stopCaptureButton 加入这里。 */
    val captureActionHost: LinearLayout

    /** 备份/恢复按钮宿主，保持原 backupExportButton / restoreButton 所有权。 */
    val backupActionHost: LinearLayout

    /** 备份状态行宿主，保持原 backupStatusView 所有权。 */
    val backupStatusHost: LinearLayout

    /**
     * 更新状态与动作宿主，调用方把版本检查结果、手动检查/下载/安装按钮加入这里。
     * 本组件不联网、不发起检查、不持有下载状态；离开页面或返回系统设置后由调用方刷新。
     */
    val updateHost: LinearLayout

    private lateinit var overlayBadge: TextView
    private lateinit var notificationBadge: TextView
    private lateinit var accessibilitySwitch: Switch
    private lateinit var accessibilityStatusView: TextView
    // True only while the switch is synchronised from the real persisted state, so programmatic
    // updates never look like a user tap and never re-open the privacy confirmation.
    private var syncingAccessibility = false

    init {
        val content = context.column()
        content.addView(context.text("设置", 28f, Paper.ink, true))
        content.addView(context.text("权限与本机数据", 15f, Paper.secondary))
        content.space(12)

        // 高频优先：正在进行的查词会话和停止动作最先可见，其次才是两个权限入口。
        val session = section(context, "查词与权限")
        session.addView(context.text("查词会话", 17f, Paper.ink, true))
        captureStatusHost = context.column()
        session.addView(captureStatusHost)
        captureActionHost = context.column()
        session.addView(captureActionHost)
        session.space(12)
        session.divider()

        val overlayRow = context.row().apply { gravity = Gravity.CENTER_VERTICAL }
        overlayRow.addView(
            context.text("悬浮窗权限", 17f, Paper.ink, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        overlayBadge = permissionBadge(context, overlayStatus)
        overlayRow.addView(overlayBadge, LinearLayout.LayoutParams(-2, -2))
        session.addView(overlayRow)
        session.addView(
            context.button("打开系统悬浮窗权限设置") { onOpenOverlayPermission() },
            LinearLayout.LayoutParams(-1, -2)
        )
        session.divider()

        val notificationRow = context.row().apply { gravity = Gravity.CENTER_VERTICAL }
        notificationRow.addView(
            context.text("通知权限", 17f, Paper.ink, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        notificationBadge = permissionBadge(context, notificationStatus)
        notificationRow.addView(notificationBadge, LinearLayout.LayoutParams(-2, -2))
        session.addView(notificationRow)
        session.addView(
            context.button("打开系统通知设置") { onOpenNotificationSettings() },
            LinearLayout.LayoutParams(-1, -2)
        )
        session.space(12)
        session.divider()

        // Optional direct text reading. The switch is the in-app authorisation; the system service
        // itself can only be enabled by the user in Android accessibility settings.
        val directReadingRow = context.row().apply { gravity = Gravity.CENTER_VERTICAL }
        directReadingRow.addView(
            context.text("页面文字直读（可选）", 17f, Paper.ink, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        accessibilitySwitch = Switch(context).apply {
            isChecked = accessibilityStatus.enabled
            minHeight = context.dp(48)
            contentDescription = "页面文字直读开关"
            setOnCheckedChangeListener { _, checked ->
                if (!syncingAccessibility) onAccessibilityTextEnabledChange(checked)
            }
        }
        directReadingRow.addView(accessibilitySwitch, LinearLayout.LayoutParams(-2, -2))
        session.addView(directReadingRow)
        accessibilityStatusView = context.text(
            accessibilityStatus.label,
            15f,
            if (accessibilityStatus.healthy) Paper.green else Paper.secondary
        )
        session.addView(accessibilityStatusView)
        session.addView(
            context.text(
                "仅本机、仅在你勾画取词的短暂请求中读取当前页面文字与单词位置；只在内存，不写盘、不记录、不上传。默认关闭。",
                15f,
                Paper.secondary
            )
        )
        session.addView(
            context.button("打开系统无障碍设置") { onOpenAccessibilitySettings() },
            LinearLayout.LayoutParams(-1, -2)
        )
        session.space(12)

        session.addView(
            context.helpDisclosure("权限与捕获说明") {
                addView(
                    context.text(
                        "悬浮窗权限仅用于在其他应用上显示查词浮层；请在「划词与查词」页主动开启。",
                        15f,
                        Paper.secondary
                    )
                )
                addView(
                    context.text(
                        "通知权限用于悬浮查词期间的前台服务通知；未允许时，仍可在「划词与查词」页或系统前台服务入口停止。",
                        15f,
                        Paper.secondary
                    )
                )
                addView(
                    context.text(
                        "只在你主动开启悬浮划词时申请悬浮窗、屏幕捕获及通知权限。全屏捕获可看到整个屏幕；" +
                            "划词时原页面始终显示，仅由透明笔迹层在这一次取词期间接收手势。",
                        15f,
                        Paper.secondary
                    )
                )
                addView(
                    context.text(
                        "关闭会释放捕获和浮层，不会撤销系统悬浮窗权限。若需撤销，请在系统设置中操作。",
                        15f,
                        Paper.secondary
                    )
                )
            }
        )
        content.addView(session)
        content.space(16)

        val backup = section(context, "备份与恢复")
        backup.addView(context.text("恢复将覆盖当前数据，请先备份。", 16f, Paper.warning, true))
        backup.addView(
            context.text(
                "普通备份和恢复前的安全备份均为明文，未加密、不防恶意篡改。" +
                    "拾词不主动上传或同步备份。保存位置由你在系统文件选择器中选择；" +
                    "如果选了云盘，提供方可能上传和同步明文学习记录。敏感备份建议保存到本机。",
                15f,
                Paper.warning
            )
        )
        backup.space(8)
        backupActionHost = context.column()
        backup.addView(backupActionHost)
        backup.space(8)
        backupStatusHost = context.column()
        backup.addView(backupStatusHost)
        backup.space(8)
        backup.addView(
            context.helpDisclosure("备份与恢复详情") {
                addView(
                    context.text(
                        "导出会把本机学习数据（全部词条与事件，含已撤销历史、笔记、掌握状态、" +
                            "统一复习进度与个人补充词库）写成一个 .shicibackup 文件；" +
                            "恢复时通过系统文件选择器选取备份文件。",
                        15f,
                        Paper.secondary
                    )
                )
                addView(
                    context.text(
                        "恢复是完整覆盖、不合并、不猜测冲突：备份里没有的词、查询记录、收藏和" +
                            "补充词条也会被移除；空备份会在确认后清空学习数据。",
                        15f,
                        Paper.secondary
                    )
                )
                addView(
                    context.text(
                        "只有安全备份写回并读回校验一致、且数据在此期间没有变化时，才会覆盖" +
                            "当前数据；否则本次不覆盖。",
                        15f,
                        Paper.secondary
                    )
                )
            }
        )
        content.addView(backup)
        content.space(16)

        val openGuide = onOpenUsageGuide
        if (openGuide != null) {
            val help = section(context, "使用帮助")
            help.addView(
                context.button("查看使用引导", true) { openGuide() },
                LinearLayout.LayoutParams(-1, -2)
            )
            content.addView(help)
            content.space(16)
        }

        val about = section(context, "关于")
        // 首屏只保留词库状态一行和版本一行；来源、联网与隐私细节默认收起。
        dictionaryStatusHost = context.column()
        about.addView(dictionaryStatusHost)
        about.addView(context.text(versionText, 15f, Paper.secondary))
        updateHost = context.column()
        about.addView(updateHost)
        about.addView(
            context.helpDisclosure("词库来源、联网与隐私") {
                addView(context.text("词库来源", 16f, Paper.ink, true))
                addView(context.text(provenanceText, 15f, Paper.secondary))
                addView(
                    context.text(
                        "五本原始词书合并；仅查原词头，不猜词形，不回退演示词库；" +
                            "释义未逐条核对，未应用核验或候选修订。",
                        15f,
                        Paper.warning
                    )
                )
                divider()

                addView(context.text("数据保存在本机", 16f, Paper.ink, true))
                addView(
                    context.text(
                        "屏幕画面仅在内存中短暂读取并本机识别，不写截图文件、不上传；一笔抬起" +
                            "自动查询，轻点/多指/取消不查询，多个词时选择其中一个词查询，也可在" +
                            "多词原文卡中选择“翻译整句”。查询记录、释义快照、个人笔记、收藏与" +
                            "掌握状态保存在本机数据库。PDF 写入你选择的位置。",
                        15f,
                        Paper.secondary
                    )
                )
                divider()

                addView(context.text("离线 AI 翻译组件与网络", 16f, Paper.ink, true))
                addView(
                    context.text(
                        "“离线 AI 翻译”是可选组件，不是查词、补词、生词本、PDF 或复习的前置条件。" +
                            "初装不下载任何模型；未下载时该页只显示组件说明与“下载并启用（仅 Wi-Fi）”" +
                            "入口，原文只读且草稿/选择位置保留，不开放编辑、翻译或复制。只有你明确" +
                            "确认后才由 Google ML Kit 下载英中离线模型（约 30 MB 只是估计，实际以" +
                            "系统为准），并采用 Wi-Fi 条件；不伪造下载进度，失败可主动重试，不会" +
                            "自动重下或改用移动数据。离开/关闭页面不会取消系统下载，系统下载可能" +
                            "继续，再次进入只重新检查本地状态。英文由 Google ML Kit 在设备本机翻译，" +
                            "本应用不上传原文或译文、不使用在线翻译或收费服务。ML Kit 可能为更新" +
                            "模型、诊断和统计而与 Google 通信并发送性能/使用指标，因此本应用并非" +
                            "“完全不联网”；清单中的网络权限仅用于该 SDK。设备需支持 Google Play " +
                            "服务；实际译文质量、模型可用性与兼容性取决于设备和 Google Play 服务。",
                        15f,
                        Paper.secondary
                    )
                )
                divider()

                addView(context.text("功能边界", 16f, Paper.ink, true))
                addView(
                    context.text(
                        "PDF 导出已接入 A4 复习版与自测版＋答案页；支持按首次收录日期（含首尾）、" +
                            "最近查询/查询次数排序、仅收藏，以及“当前筛选全部”或手动勾选当前结果。" +
                            "导出选项只保存在本次导出中，不是全局设置；PDF 不是备份。正式词典、" +
                            "发音、例句仍未实现；备份只能用于完整覆盖恢复，不支持合并。",
                        15f,
                        Paper.secondary
                    )
                )
            }
        )
        content.addView(about)

        view = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * 只更新两个权限徽章的文案与配色，不重建页面，因此不会折叠已展开的说明，也不改变宿主里
     * 由 Activity 持有的按钮/状态视图。调用方在回到前台或重新进入设置页时刷新即可。
     */
    fun updatePermissionStatus(overlay: PermissionStatus, notification: PermissionStatus) {
        applyBadge(overlayBadge, overlay)
        applyBadge(notificationBadge, notification)
    }

    /**
     * Syncs the direct-reading switch and status line from the real persisted/connection state
     * without firing the change callback, so a cancelled privacy confirmation reverts the switch
     * and returning from system accessibility settings shows the true connection state.
     */
    fun updateAccessibilityStatus(status: AccessibilityStatus) {
        syncingAccessibility = true
        accessibilitySwitch.isChecked = status.enabled
        syncingAccessibility = false
        accessibilityStatusView.text = status.label
        accessibilityStatusView.setTextColor(
            if (status.healthy) Paper.green else Paper.secondary
        )
    }

    private fun applyBadge(view: TextView, status: PermissionStatus) {
        view.text = status.label
        view.setTextColor(if (status.granted) Paper.green else Paper.secondary)
        view.background = view.context.shape(
            if (status.granted) Paper.lightGreen else Paper.background,
            8
        )
    }

    private fun permissionBadge(context: Context, status: PermissionStatus): TextView =
        context.badge(status.label).apply {
            if (!status.granted) {
                setTextColor(Paper.secondary)
                background = context.shape(Paper.background, 8)
            }
        }

    private fun section(context: Context, title: String): LinearLayout =
        context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.surface, border = true)
            addView(context.text(title, 20f, Paper.ink, true))
            divider()
            space(8)
        }
}
