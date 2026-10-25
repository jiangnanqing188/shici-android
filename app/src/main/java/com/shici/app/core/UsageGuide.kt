package com.shici.app.core

/**
 * Pure content model for the in-app usage guide. This file deliberately has no Android, Activity,
 * repository or preferences dependency: it can feed the standalone help page, the HOME newcomer card
 * and the Settings help entry without owning any action.
 *
 * The guide only explains. Every button described by [GuideAction] is a navigation request that the
 * host Activity resolves; the model never enables capture, requests a permission, performs a lookup,
 * downloads a model, deletes anything, restores a backup or writes `guide_seen_v1`.
 */
object GuideRoutes {
    /** Stable page ids; they intentionally equal the Activity's private MainPage enum names. */
    const val HOME = "HOME"
    const val LOOKUP = "LOOKUP"
    const val TRANSLATION = "TRANSLATION"
    const val VOCABULARY = "VOCABULARY"
    const val STUDY = "STUDY"
    const val STATISTICS = "STATISTICS"
    const val PERSONAL_ENTRY = "PERSONAL_ENTRY"
    const val SETTINGS = "SETTINGS"
}

/** A user-initiated page hand-off. [route] is a stable string, never a private enum instance. */
enum class GuideAction(val route: String, val label: String) {
    OPEN_HOME(GuideRoutes.HOME, "去首页"),
    OPEN_LOOKUP(GuideRoutes.LOOKUP, "去查词"),
    OPEN_PERSONAL_ENTRY(GuideRoutes.PERSONAL_ENTRY, "去添加/补词"),
    OPEN_VOCABULARY(GuideRoutes.VOCABULARY, "去生词本"),
    OPEN_STUDY(GuideRoutes.STUDY, "去背词"),
    OPEN_SETTINGS(GuideRoutes.SETTINGS, "去设置"),
    OPEN_TRANSLATION(GuideRoutes.TRANSLATION, "去离线翻译")
}

/** One short, self-contained help topic. Steps are plain strings so the UI can number them. */
data class GuideTopic(
    val id: String,
    val title: String,
    val summary: String,
    val steps: List<String>,
    val notes: List<String> = emptyList(),
    val actions: List<GuideAction> = emptyList()
)

/** Copy for the non-blocking HOME newcomer card. The host decides when to show it. */
data class GuideHomeCard(
    val title: String,
    val message: String,
    val hint: String,
    val openLabel: String,
    val dismissLabel: String
)

object UsageGuide {
    /**
     * The one preference key the integrator may write, and only after an explicit "skip" or
     * "done" tap. This file never reads or writes SharedPreferences and never touches the learning
     * database or a backup.
     */
    const val SEEN_PREFERENCE_KEY = "guide_seen_v1"

    const val TITLE = "使用引导"
    const val INTRO =
        "下面每次只演示一个真实操作：看手指或笔尖怎么移动、什么时候抬笔、结果卡何时出现。" +
            "每步配一句提示；任何一步都可以跳过，这份引导可以随时重新打开。"

    // Demo controls. "跳过" and "完成" are the only actions that dismiss the first-run prompt;
    // opening this page, switching scenes or replaying a step never writes guide_seen_v1.
    const val DEMO_BADGE = "演示"
    const val DEMO_NOTE = "以上为本地示意：不会真实查询、写库、申请权限或下载。"
    const val STATIC_HINT = "系统已关闭动画：点按演示图可逐个查看关键阶段。"
    const val PREV_LABEL = "上一步"
    const val NEXT_LABEL = "下一步"
    const val REPLAY_LABEL = "重播"
    const val SKIP_LABEL = "跳过"
    const val DONE_LABEL = "完成"
    const val USE_LABEL_PREFIX = "去使用："
    const val LONG_HELP_TITLE = "完整说明与边界"
    const val LONG_HELP_DESCRIPTION = "逐页文字说明、权限边界和危险操作细节；默认收起，需要时再展开。"
    const val NO_PERMISSION_FALLBACK =
        "没有悬浮窗或屏幕捕获权限时，仍可打开「查词」页手动输入单词查询；手动查词不依赖投影。"
    const val NO_AUTOMATION =
        "引导只做说明，不会自动替你开启权限、发起查询、下载模型、删除记录或恢复备份。"
    const val BACK_LABEL = "返回"

    const val FIRST_RUN_TITLE = "第一次使用拾词？"
    const val FIRST_RUN_MESSAGE =
        "建议先看首页如何开启悬浮查词，再看划词与手动查词、补词和生词本的区别、背词、PDF 与备份、" +
            "可选离线翻译。非阻塞，可随时跳过；以后还能重新打开。"
    const val FIRST_RUN_SKIP_LABEL = "跳过引导"
    const val FIRST_RUN_DONE_LABEL = "看完了，不再自动提示"

    const val HOME_CARD_MESSAGE =
        "第一次用？几步弄清：首页开启悬浮查词 → 点悬浮「词」划一笔、抬笔取词 → " +
            "识别不到就换页面重试或手动查词。"
    const val HOME_CARD_OPEN_LABEL = "查看使用引导"
    const val HOME_CARD_DISMISS_LABEL = "跳过，不再提示"
    const val SETTINGS_ENTRY_TITLE = "使用引导"
    const val SETTINGS_ENTRY_DESCRIPTION =
        "随时重看查词、添加/补词、生词本、背词、PDF、备份恢复和可选离线翻译的步骤与边界。"

    /** The fixed HOME card copy. The host owns visibility and the `guide_seen_v1` preference. */
    val homeCard: GuideHomeCard = GuideHomeCard(
        title = "新手引导",
        message = HOME_CARD_MESSAGE,
        hint = NO_PERMISSION_FALLBACK,
        openLabel = HOME_CARD_OPEN_LABEL,
        dismissLabel = HOME_CARD_DISMISS_LABEL
    )

    /** All topics in reading order. Kept immutable so no screen can mutate shared content. */
    val topics: List<GuideTopic> = listOf(
        GuideTopic(
            id = "start_capture",
            title = "1. 首页：开启悬浮查词（一次同意）",
            summary = "开启入口只在首页；不点开关就不会申请权限，也不会开始查询。",
            steps = listOf(
                "在首页找到「悬浮查词」开关并打开。",
                "系统会按顺序请求悬浮窗、屏幕捕获（投影）和通知权限；按系统提示明确同意一次，本次开启期间生效；重新开启时系统可能再次询问。",
                "拒绝、取消或中途返回都会让查词保持关闭，不会偷偷重试。",
                "开启后点悬浮「词」，在原页面沿一个完整英文单词划一笔；松手（抬笔）即在本机识别并查询。",
                "划词时原页面始终显示，只有透明笔迹层在这次取词期间接收手势；关闭首页开关或通知里的「停止」会释放捕获和浮层。"
            ),
            notes = listOf(
                "关闭开关不会撤销系统悬浮窗权限；需要撤销时请到系统设置操作。",
                "屏幕画面只在内存中短暂读取并本机识别，不写截图文件、不上传。"
            ),
            actions = listOf(GuideAction.OPEN_HOME, GuideAction.OPEN_SETTINGS)
        ),
        GuideTopic(
            id = "capture_and_manual",
            title = "2. 划词取词与无权限手动查词",
            summary = "有权限用悬浮划词；没有权限或不开启时，用查词页手动输入。",
            steps = listOf(
                "点悬浮「词」→ 沿一个完整英文单词划一笔 → 松手自动查询；轻点、多指或取消不查询。",
                "一次划过多个词时，先选择要查的一个词；多词原文卡还可选择「翻译整句」，前提是可选离线翻译组件已经就绪。",
                "没有悬浮窗或屏幕捕获权限、或不想开启时：打开「查词」页输入单词，点「查询」或键盘搜索键提交。",
                "只有真实查询成功才形成本地记录；未识别、失败或词库未命中不会生成释义，也不会收录。"
            ),
            notes = listOf("手动查词是权限被拒或某些应用无法识别时的正式回退路径，不是降级开关。"),
            actions = listOf(GuideAction.OPEN_LOOKUP, GuideAction.OPEN_HOME)
        ),
        GuideTopic(
            id = "lookup_supplement_vocabulary",
            title = "3. 查词、添加/补词与生词本：三件不同的事",
            summary = "查词负责真实查询与收录；补词只维护本机词义；生词本只管理已真实查询收录的词。",
            steps = listOf(
                "「查词」页：输入并查询。成功查询会按现有规则形成真实的本地记录；未知词或未收录词形不生成释义，也不会凭空新增记录。",
                "「添加/补词」页：先读取词头，再编辑本机个人词性/释义。原始词库只读，补词只写本机个人补充，不覆盖原书。",
                "保存补词本身不计查询次数、不产生生词、不生成复习反馈；需要收录时，必须走真实查询（未收录词的结果卡提供「查询并收录」）。",
                "“加入生词本”（收录）不是补词操作的别名：只有真实查询成功才会收录为生词，并与查询记录绑定；补词保存的词义不会自动收藏或进入复习。",
                "生词本中看到的是已经真实查询收录的个人生词，不是整个原始词库。"
            ),
            notes = listOf("补词可以把词义改得更适合你，但不能替代一次真实查询，也不会伪造查询记录。"),
            actions = listOf(
                GuideAction.OPEN_LOOKUP,
                GuideAction.OPEN_PERSONAL_ENTRY,
                GuideAction.OPEN_VOCABULARY
            )
        ),
        GuideTopic(
            id = "vocabulary_scope",
            title = "4. 生词本：范围、移除与收藏",
            summary = "星标只是标记；移除学习记录必须先选清楚范围。",
            steps = listOf(
                "生词本只显示已经真实查询收录的个人生词；补词保存的词义不会自动变成生词。",
                "「☆ 收藏 / ★ 已收藏」只是个人标记。取消星标不删除任何查询、笔记、掌握状态或复习进度。",
                "「移除学习记录…」会先要求你明确选择范围：仅撤销最近一次有效查询（保留之前的查询与笔记），或删除该词的全部学习记录。",
                "整词删除包含全部查询历史（含已撤销）、我的记忆笔记与掌握状态、该词复习进度与反馈、待答卡片和收藏星标，删除后无法恢复。",
                "两种移除范围都不会删除独立保存的个人词义、原始词库、其他生词或已导出的 PDF。",
                "导出 PDF 前的勾选只决定导出范围，不改词库、查询次数或学习进度。"
            ),
            notes = listOf("危险范围没有默认选中项；需要两次明确确认才会执行。"),
            actions = listOf(GuideAction.OPEN_VOCABULARY)
        ),
        GuideTopic(
            id = "study",
            title = "5. 背词：统一范围、默认合计 20 个新词与到期复习",
            summary = "到期复习优先；个人生词与考研/六级原书两池混合抽取，共用一个队列和一份进度。",
            steps = listOf(
                "背词只有一个统一入口：先到期待复习，再学新词。每日新词默认合计 20 个，可在本页调整（范围 1–200）。",
                "两池都有词时，个人生词与当天原书抽样按约 1:1 交替加入同一队列，不再先把个人生词用完才轮到原书；某一池不足时由另一池补足。",
                "原书词按当天稳定抽样顺序出现：同一天、同一进度重新进入结果一致，换一天会抽到不同样本。",
                "没查过的原书词也能直接背，背词不以先查询为前提；查询收录的个人词只是其中一池。",
                "同一个词只保留一份进度，不需要切换词书；个人词和原书不会形成两套复习。",
                "每张卡只有「认识 / 模糊 / 忘记」三个真实反馈：认识按 1/3/7/14/30 天递增，模糊 1 天后回来，忘记 10 分钟后重新学。",
                "释义来自原始词书（未逐条核对）或本机用户补充；不联网、不自动生成。",
                "这是固定间隔复习，不是经过验证的记忆算法；进度只按你的真实反馈推进。"
            ),
            actions = listOf(GuideAction.OPEN_STUDY)
        ),
        GuideTopic(
            id = "pdf",
            title = "6. PDF 导出不是备份",
            summary = "PDF 用于阅读和打印复习，不能恢复学习数据。",
            steps = listOf(
                "在生词本可导出 A4 复习版或自测版＋答案页，PDF 写入你选择的系统位置。",
                "导出范围可以按当前筛选全部或手动勾选，并可叠加日期、排序和仅收藏；这些勾选只影响本次 PDF。",
                "PDF 不改动词库、查询次数或学习进度，也不是学习数据的备份。",
                "需要恢复数据时，只能使用设置里的「导出学习数据备份」与「从备份完整恢复」。"
            ),
            notes = listOf("导出的 PDF 与备份文件是两类文件，请不要把 PDF 当作恢复来源。"),
            actions = listOf(GuideAction.OPEN_VOCABULARY, GuideAction.OPEN_SETTINGS)
        ),
        GuideTopic(
            id = "backup",
            title = "7. 备份与恢复：本地明文、完整覆盖",
            summary = "备份只在本机通过系统文档选择器读写；恢复会完整替换，不合并。",
            steps = listOf(
                "设置 →「导出学习数据备份」：把本机学习数据（词条与事件、已撤销历史、笔记、掌握状态、背词进度和个人补充词库）写成一个 .shicibackup 文件。",
                "设置 →「从备份完整恢复」：先按提示把当前数据另存为安全备份，再选择要恢复的文件。",
                "只有安全备份写回并读回校验一致、且数据在此期间没有变化时，才会覆盖当前数据；否则本次不覆盖。",
                "恢复是完整覆盖，不合并、不猜测冲突：备份里没有的词、查询记录、收藏和补充词条也会被移除；空备份会在确认后清空学习数据。",
                "备份是本地明文个人数据，未加密、也不防恶意篡改，请自己妥善保存。",
                "没有云同步、没有合并恢复、也没有上传功能；文件只通过系统文档选择器创建或读取。"
            ),
            notes = listOf("恢复前先另存当前备份，是保护现有数据的必要步骤，不是可选项。"),
            actions = listOf(GuideAction.OPEN_SETTINGS)
        ),
        GuideTopic(
            id = "translation",
            title = "8. 离线 AI 翻译（可选组件）",
            summary = "初装不下载模型；只有你明确确认后才用 Google ML Kit 在本机下载和翻译。",
            steps = listOf(
                "它不是查词、补词、生词本、PDF 或背词的前置条件；其他功能完全不依赖它。",
                "初装不下载任何模型。未下载时该页只显示组件说明和「下载并启用（仅 Wi-Fi）」，原文只读，不开放编辑、翻译或复制。",
                "只有你明确点确认后，才由 Google ML Kit 下载英中离线模型（约 30 MB 只是估计，实际以系统为准），且采用 Wi-Fi 条件。",
                "不会自动改用移动数据，也不会改用在线翻译或收费服务；失败可主动重试，离开页面不会取消系统下载，再次进入只重新检查本地状态。",
                "英文由 Google ML Kit 在设备本机翻译，本应用不上传原文或译文。",
                "但 ML Kit 可能为模型更新、诊断和统计与 Google 通信并发送性能/使用指标，所以整个应用并非“完全不联网”；清单中的网络权限仅用于该 SDK。",
                "设备需支持 Google Play 服务；模型能否下载、真实译文质量与兼容性仍需实测，本引导不作保证。"
            ),
            notes = listOf("“离线”指翻译在本机完成，不等于整个应用从不联网，也不表示已实现上传词库。"),
            actions = listOf(GuideAction.OPEN_TRANSLATION)
        ),
        GuideTopic(
            id = "recognition_trouble",
            title = "9. 识别不到时：安全重试、换页面或手动查词",
            summary = "失败不会用旧截图凑结果；先看结果卡说明，再选择重试或手动查词。",
            steps = listOf(
                "看结果卡提示：采集异常、OCR 空结果、黑帧或页面变化都会明确说明“本次未查询、未收录”，不会复用旧截图。",
                "点「重试取词（重新划词）」，等页面稳定后沿一个完整英文单词重新划；锁屏或禁止截图的页面无法取词。",
                "换个页面、滚动到文字清晰处，或避开动画和弹窗后再试。",
                "仍识别不到时，直接去「查词」页手动输入单词查询；手动查词不依赖屏幕捕获。",
                "多词时先选择要查的单个词；不确定就把整句留给可选翻译，不要把一个短语当成一个单词提交。"
            ),
            notes = listOf(
                "不承诺所有第三方应用都能识别，也不承诺黑帧或安全页面会被修复；这些情况请走手动查词。",
                "重试只重新取词，不会自动删除或改写已有记录。"
            ),
            actions = listOf(GuideAction.OPEN_LOOKUP, GuideAction.OPEN_HOME)
        ),
        GuideTopic(
            id = "page_roles",
            title = "10. 每一页负责什么",
            summary = "按职责使用页面，避免把补词、查询、收藏和备份混在一起。",
            steps = listOf(
                "首页：唯一开启/关闭悬浮查词的入口；也是新手卡和主要功能入口。",
                "查词：手动输入与真实查询；失败时可重试或改用手动输入。",
                "添加/补词：只维护本机个人词性/释义，不收录、不计查询次数、不进入复习。",
                "生词本：查看、搜索、收藏、按明确范围移除，以及导出 PDF；不在这里新增词义。",
                "背词：统一复习队列与真实反馈；统计：本机查询与复习进度聚合。",
                "设置：权限状态说明、备份/恢复、可选离线翻译入口和其他本地设置；不承载查词输入。",
                "离线 AI 翻译：可选的本机整句翻译；不查词、不收录、不上传原文。"
            ),
            notes = listOf(
                "本应用没有把词库或学习数据上传到云端的功能；请勿把“导出到系统文档选择器”理解为上传。",
                NO_AUTOMATION
            ),
            actions = listOf(GuideAction.OPEN_HOME, GuideAction.OPEN_SETTINGS)
        )
    )

    /** Looks up one topic by id; unknown ids return null instead of falling back silently. */
    fun topic(id: String?): GuideTopic? =
        id?.let { wanted -> topics.firstOrNull { it.id == wanted } }
}
