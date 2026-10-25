package com.shici.app.ui

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 设置页的独立正文，只负责分组骨架与静态说明。
 *
 * 职责边界：
 * - 不访问 repository / app / worker，不读取学习数据，不发起备份、恢复、权限申请或屏幕捕获；
 * - 不持有 Activity 的动态生命周期。备份按钮与状态、悬浮查词状态与停止按钮、词库校验状态
 *   都由 MainActivity 沿用原有字段创建后挂到本类的 host 上，仍由原异步回调与 statusListener 更新；
 * - 系统权限入口只回调调用方打开的按钮，本页不会自动申请或开启屏幕捕获投影。
 *
 * 分组按使用频率排列：权限与查词状态 → 备份与恢复 → 使用帮助 → 关于与隐私。词库来源、
 * 模型联网、未验证事项与内部版本默认折叠，避免把长说明堆在首屏。查词、补词等日常业务不再
 * 放在设置页，各自保留独立页面。
 *
 * 汇总接线提示：`MainActivity.settings()` 构造本组件后，把 [view] 加到 body；再将原有
 * `backupExportButton` / `restoreButton` 加入 [backupActionHost]，`backupStatusView` 加入
 * [backupStatusHost]，`floatingText` 加入 [captureStatusHost]，`stopCaptureButton` 加入
 * [captureActionHost]，`dictionaryStatus()` 加入 [dictionaryStatusHost]，最后调用原有
 * `updateBackupButtons()` 与 `statusListener(CaptureStatus.state)`。权限徽章在重新进入设置页
 * 或由调用方在回到前台时通过 [updatePermissionStatus] 刷新；本组件不自行读取系统权限。
 *
 * @param overlayStatus 悬浮窗权限的展示状态（由 Activity 读取系统状态后传入）。
 * @param notificationStatus 通知权限的展示状态；API 33 以下可传“无需单独授权”。
 * @param versionText 内部版本说明，由调用方从 BuildConfig 读取，避免本组件硬编码版本。
 * @param provenanceText 原始词库来源说明，由调用方沿用 RawKajwebDictionary.provenance 生成。
 * @param onOpenOverlayPermission 打开系统悬浮窗权限设置；不在此处申请捕获。
 * @param onOpenNotificationSettings 打开系统通知设置；不自动弹出运行时授权。
 * @param onOpenUsageGuide 打开完整使用说明页；尚未接线时传 null，本页仍显示简短步骤。
 */
class SettingsScreen(
    context: Context,
    overlayStatus: PermissionStatus,
    notificationStatus: PermissionStatus,
    versionText: String,
    provenanceText: String,
    onOpenOverlayPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenUsageGuide: (() -> Unit)? = null
) {
    /** 权限徽章文案与配色状态；label 由调用方决定，便于区分“无需单独授权”。 */
    data class PermissionStatus(val label: String, val granted: Boolean)

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

    private lateinit var overlayBadge: TextView
    private lateinit var notificationBadge: TextView
    private lateinit var details: LinearLayout
    private lateinit var detailsToggle: Button

    init {
        val content = context.column()
        content.addView(context.text("设置", 28f, Paper.ink, true))
        content.addView(
            context.text(
                "只放权限状态、备份恢复与相关说明；查词、补词等日常操作在各自页面完成。",
                16f,
                Paper.secondary
            )
        )
        content.space(20)

        // 高频优先：先看到权限状态和正在进行的查词会话，再进入低频的备份与说明。
        val permission = section(
            context,
            "权限与查词状态",
            "这里只展示状态、进入系统设置或停止正在进行的查词会话，不会在本页申请或开启屏幕捕获投影。"
        )
        permission.addView(context.text("悬浮窗权限", 17f, Paper.ink, true))
        overlayBadge = permissionBadge(context, overlayStatus)
        permission.addView(overlayBadge, LinearLayout.LayoutParams(-2, -2))
        permission.addView(
            context.text(
                "仅用于在其他应用上显示查词浮层。这是权限状态展示，不是授权开关。",
                15f,
                Paper.secondary
            )
        )
        permission.addView(
            context.button("打开系统悬浮窗权限设置") { onOpenOverlayPermission() },
            LinearLayout.LayoutParams(-1, -2)
        )
        permission.divider()

        permission.addView(context.text("通知权限", 17f, Paper.ink, true))
        notificationBadge = permissionBadge(context, notificationStatus)
        permission.addView(notificationBadge, LinearLayout.LayoutParams(-2, -2))
        permission.addView(
            context.text(
                "用于悬浮查词期间的前台服务通知。未允许时，仍可从首页开关或系统前台服务入口停止。",
                15f,
                Paper.secondary
            )
        )
        permission.addView(
            context.button("打开系统通知设置") { onOpenNotificationSettings() },
            LinearLayout.LayoutParams(-1, -2)
        )
        permission.divider()

        permission.addView(context.text("查词会话", 17f, Paper.ink, true))
        captureStatusHost = context.column()
        permission.addView(captureStatusHost)
        permission.addView(
            context.text(
                "只在首页主动开启时申请悬浮窗、屏幕捕获及通知权限。全屏捕获可看到整个屏幕；" +
                    "划词时原页面始终显示，仅由透明笔迹层在这一次取词期间接收手势。",
                15f,
                Paper.secondary
            )
        )
        captureActionHost = context.column()
        permission.addView(captureActionHost)
        permission.addView(
            context.text(
                "关闭会释放捕获和浮层，不会撤销系统悬浮窗权限。若需撤销，请在系统设置中操作。",
                15f,
                Paper.secondary
            )
        )
        content.addView(permission)
        content.space(16)

        val backup = section(context, "备份与恢复")
        backup.addView(
            context.text(
                "把本机学习数据（全部词条与事件，含已撤销历史、笔记、掌握状态、统一背词进度与" +
                    "个人补充词库）导出为一个 .shicibackup 文件，或用备份完整覆盖当前数据。" +
                    "文件只通过系统文档选择器创建或读取，不联网、不上传。",
                15f,
                Paper.secondary
            )
        )
        backup.space(8)
        backupActionHost = context.column()
        backup.addView(backupActionHost)
        backup.space(12)
        backup.addView(
            context.text(
                "恢复是完整覆盖、不合并、不猜测冲突：备份里没有的词也会被移除。恢复前必须先把" +
                    "当前数据另存为一个安全备份文件，写回校验通过且数据未变化时才会覆盖。" +
                    "空备份可在确认后清空学习数据。备份是本地明文个人数据，未加密、不防恶意篡改，" +
                    "请妥善保存。",
                15f,
                Paper.warning
            )
        )
        backup.space(8)
        backupStatusHost = context.column()
        backup.addView(backupStatusHost)
        content.addView(backup)
        content.space(16)

        val help = section(context, "使用帮助")
        help.addView(
            context.text(
                "1. 在首页主动开启悬浮查词，按系统提示允许悬浮窗、屏幕捕获与通知。",
                15f,
                Paper.secondary
            )
        )
        help.addView(
            context.text(
                "2. 点悬浮「词」，在题目页面上划一笔，抬笔后自动查询；轻点、多指或取消不会查询。",
                15f,
                Paper.secondary
            )
        )
        help.addView(
            context.text(
                "3. 没有悬浮权限时，到「查词」页手动输入；缺词或补义到「添加/补词」页，" +
                    "背词与统计在各自页面。",
                15f,
                Paper.secondary
            )
        )
        help.addView(
            context.text(
                "4. 在本页或通知中停止查词，会释放捕获与浮层。",
                15f,
                Paper.secondary
            )
        )
        val openGuide = onOpenUsageGuide
        if (openGuide != null) {
            help.space(8)
            help.addView(
                context.button("查看完整使用说明", true) { openGuide() },
                LinearLayout.LayoutParams(-1, -2)
            )
        }
        content.addView(help)
        content.space(16)

        val about = section(context, "关于与隐私")
        about.addView(context.text("词库来源", 17f, Paper.ink, true))
        val identity = context.column().apply {
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            background = context.shape(Paper.lightGreen, 8)
            addView(context.text("原始离线词库 · 未逐条核对", 16f, Paper.green, true))
        }
        dictionaryStatusHost = context.column()
        identity.addView(dictionaryStatusHost)
        about.addView(identity)
        about.addView(
            context.text(
                "仅查原词头，不猜词形，不回退演示词库；未应用核验或候选修订。" +
                    "展开可查看来源、联网边界、未验证事项与内部版本。",
                15f,
                Paper.secondary
            )
        )

        details = context.column().apply { visibility = View.GONE }
        detailsToggle = context.button("展开详细信息") { toggleDetails() }
        about.addView(detailsToggle)
        about.addView(details)

        details.addView(context.text("词库来源", 16f, Paper.ink, true))
        details.addView(context.text(provenanceText, 15f, Paper.secondary))
        details.addView(
            context.text(
                "考研 / 四级 / 六级 / 专四 / 专八五本原始词书合并；未应用核验或候选修订。" +
                    "仅查原词头，不猜词形，不回退演示词库。",
                15f,
                Paper.warning
            )
        )
        details.divider()

        details.addView(context.text("数据保存在本机", 16f, Paper.ink, true))
        details.addView(
            context.text(
                "屏幕画面仅在内存中短暂读取并本机识别，不写截图文件、不上传；一笔抬起自动查询，" +
                    "轻点/多指/取消不查询，多个词时选择其中一个词查询，也可在多词原文卡中选择" +
                    "“翻译整句”。查询记录、释义快照、个人笔记、收藏与掌握状态保存在本机数据库。" +
                    "PDF 写入你选择的位置。",
                15f,
                Paper.secondary
            )
        )
        details.divider()

        details.addView(context.text("离线 AI 翻译组件与网络", 16f, Paper.ink, true))
        details.addView(
            context.text(
                "“离线 AI 翻译”是可选组件，不是查词、补词、生词本、PDF 或背词的前置条件。" +
                    "初装不下载任何模型；未下载时该页只显示组件说明与“下载并启用（仅 Wi-Fi）”入口，" +
                    "原文只读且草稿/选择位置保留，不开放编辑、翻译或复制。只有你明确确认后才由 " +
                    "Google ML Kit 下载英中离线模型（约 30 MB 只是估计，实际以系统为准），" +
                    "并采用 Wi-Fi 条件；不伪造下载进度，失败可主动重试，不会自动重下或改用移动数据。" +
                    "离开/关闭页面不会取消系统下载，系统下载可能继续，再次进入只重新检查本地状态。" +
                    "英文由 Google ML Kit 在设备本机翻译，本应用不上传原文或译文、不使用在线翻译" +
                    "或收费服务。ML Kit 可能为更新模型、诊断和统计而与 Google 通信并发送性能/使用指标，" +
                    "因此本应用并非“完全不联网”；清单中的网络权限仅用于该 SDK。设备需支持 " +
                    "Google Play 服务；模型能否下载、真实译文质量与兼容性仍需实测。",
                15f,
                Paper.secondary
            )
        )
        details.divider()

        details.addView(context.text("原型限制与未验证事项", 16f, Paper.ink, true))
        details.addView(
            context.text(
                "PDF 导出已接入 A4 复习版与自测版＋答案页；支持按首次收录日期（含首尾）、" +
                    "最近查询/查询次数排序、仅收藏，以及“当前筛选全部”或手动勾选当前结果。" +
                    "导出选项只保存在本次导出中，不是全局设置；PDF 不是备份。正式词典、发音、" +
                    "例句仍未实现；本地备份仅为完整覆盖恢复，不提供合并或云同步。",
                15f,
                Paper.secondary
            )
        )
        details.addView(
            context.text(
                "本页为开发阶段说明：离线编译只检查代码可编译，不能代替独立逻辑验收，也不等于" +
                    "功能通过。当前版本与限定验收进度以项目交付记录为准；已完成的限定范围验收" +
                    "不代表整包、真机或平板通过。真实 SDK 模型下载与推理质量、Google Play 兼容性、" +
                    "平板内存与速度、原生窗口、大字模式、海文物理笔及长期功耗仍未验证。",
                15f,
                Paper.warning
            )
        )
        details.divider()

        details.addView(context.text("内部版本", 16f, Paper.ink, true))
        details.addView(context.text(versionText, 15f, Paper.secondary))
        details.addView(
            context.text(
                "内部版本号仅用于本轮集成识别，不代表已测试、已安装或正式发布。",
                15f,
                Paper.secondary
            )
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

    private fun toggleDetails() {
        val expand = details.visibility != View.VISIBLE
        details.visibility = if (expand) View.VISIBLE else View.GONE
        detailsToggle.text = if (expand) "收起详细信息" else "展开详细信息"
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

    private fun section(context: Context, title: String, intro: String? = null): LinearLayout =
        context.column().apply {
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            background = context.shape(Paper.surface, border = true)
            addView(context.text(title, 20f, Paper.ink, true))
            if (intro != null) addView(context.text(intro, 15f, Paper.secondary))
            space(8)
        }
}
