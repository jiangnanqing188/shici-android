# 拾词 · Android 演示原型

独立 Kotlin 安卓 App，不是网站。用于验证“主界面开关 → 悬浮框选 → 本地 OCR → 本地释义 → 生词记录”的可行性。首个目标设备是 OPPO Pad 3，目标使用场景是海文考研英语题。2026-10-09 经用户授权，在 USB 名称为 OPPO Pad 3 Pro（OPD2401）、Android 16 的平板完成首轮安装与基础实测；不是完整海文兼容性验收。

## 2026-10-09 首轮真机测试

- 原 Debug APK 安装成功，7 项 SQLite 设备测试全部通过；未修改应用代码或重打包。
- 四栏导航、默认关闭、演示输入查词、词形映射、歧义取消、缺词不入库、笔记和掌握状态重启保留通过。
- 在拾词自己的测试画面框选 `study`、`evidence`，本地 OCR、释义、保存通过；撤销、取消及首页停止捕获通过。系统查询确认停止后无捕获会话、无服务、无本工具浮层。
- 横屏/竖屏基础布局及竖屏详情操作可达；单词样本 PDF 成功写入下载目录，A4、中文和笔记经电脑渲染检查。长文分页、实体打印和字体放大未验证。
- **已确认缺陷：悬浮 OCR 更新数据库后，仍在前台的主界面不立即刷新；随后导出可能冻结旧计数。** 实际 `evidence` 3 次，旧界面/测试 PDF 显示 2 次。统计页“刷新统计”、重启或旋转重建可重新读取数据库。本轮只测试并记录，尚未修复。
- 仅两个自备画面 OCR 成功样本，不代表海文准确率或 P95 达标；未测试断网初装、长时间功耗、锁屏/系统回收及捕获期间旋转。
- 完整证据及测试残留见 `工作记录/20261009-1-安卓安装包真机测试工作记录.md`；截图、测试库及 PDF 位于本地忽略目录 `artifacts/device-tests/20261009/`。

## 已实现的范围

- 原生 Views 的首页、生词本、统计、设置；暖纸白与深青绿，宽屏侧栏和生词双栏，窄屏底部导航。
- 初次启动不自动请求捕获，悬浮开关默认关。生词本、统计、输入查词、PDF 不要求捕获或悬浮权限。
- 全屏 MediaProjection 前台服务、可拖动「词」入口、临时截图框选、取消和返回做题。首页开关、通知停止、系统撤销、移除最近任务结束会话；非粘性服务，不保存或复用授权令牌。
- 打包版 ML Kit 拉丁文字 OCR（`com.google.mlkit:text-recognition:16.0.1`），只对框选区域识别；迟到结果受查询标识隔离。多个 OCR 词须选择，词形歧义须确认。
- **22 个自编演示词头**及少量显式词形映射。所有相关界面标注演示词库；没有牛津内容、正式考研词库或大模型生成释义。未收录只提示，不建立生词。`saw`、`left` 用于歧义测试。
- SQLite 保存查询事件、释义快照、来源版本、原词形、笔记及已掌握二态。事件 ID 防重；查询与词项更新同一事务；取消标识在入库前与提交前核对。计数来自未撤销事件，不维护第二份计数器。
- 自动保存仅在提交成功后显示“已收录”；失败可重试。卡片可撤销本次、纠错成功后原事件撤销。生词详情可撤销最近一次查询，保留同词之前的事件与个人笔记。
- 真实空态、单词/释义/笔记搜索、最近记录、累计/今日查询、今日新增、待复习/已掌握、查询最多的词。日期以设备当前时区分日。
- 当前搜索结果的 **A4 记忆版 PDF**，最近查询优先；选择保存位置前冻结内容。自动换行、分页、页码、来源声明；超长条目续页，不截断笔记。只有输出流完成才提示导出成功。

## 本轮二级 UI 补充

- 保留首页风格与功能，以及原有设计参考图；仅补充统计、设置、生词详情和 PDF 导出确认的原生界面。
- 统计分为今日查询 / 今日新增 / 累计查询、待复习 / 已掌握和最常查询列表；所有数量来自现有本地记录，空库显示真实空态。
- 设置分为词库来源、演示输入、权限与隐私、原型限制；权限状态只展示，不画无动作箭头。停止查词释放捕获与浮层，不撤销系统悬浮权限；查询与个人数据保存在本机。
- 详情呈现原词形、来源版本、查询次数和首次 / 最近时间；个人笔记不新增字数限制，掌握开关与笔记一起保存，保留撤销最近一次查询入口。
- 导出先显示自定义确认框，说明实际搜索结果词数、A4 记忆版和演示词库限制；确认后才打开系统保存位置。确认框与文件写入使用同一份冻结内容，未保存的笔记不导出；取消确认不打开文件选择、不写文件。
- 内容空间充足时采用双栏，窄窗口或大字号时上下排列；详情和确认框可滚动，新增输入与操作控件至少 48dp。
- 本轮先通过 UI 源码局部编译与结构检查，再由主线程冻结源码完成四项 Gradle 回归：26 项 JVM 测试通过，lint 0 错误 / 5 项建议警告，APK 与设备测试包构建成功。真机布局、字号放大、键盘和 PDF 仍待验证。

## 构建环境与命令

| 项目 | 固定值 |
| --- | --- |
| JDK | 17 |
| Gradle | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 / 35 |
| Build Tools | 35.0.0 |
| minSdk | 26（Android 8.0） |
| package / applicationId | `com.shici.app` |

主线程准备的本地工具位于 `.tools/jdk17`、`.tools/gradle-8.11.1`、`.tools/android-sdk`。不用系统安装。`local.properties` 由环境负责人配置，不提交。可用本地 Gradle 直接执行：

```bash
JAVA_HOME="$PWD/.tools/jdk17" ANDROID_HOME="$PWD/.tools/android-sdk" \
GRADLE_USER_HOME="$PWD/.tools/gradle-home" \
.tools/gradle-8.11.1/bin/gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

没有准备 Android SDK/JDK 时不能只靠系统 Java 25 构建。标准 Gradle Wrapper 已由构建负责人使用经校验的官方 Gradle 8.11.1 发行包生成，工程中的 `gradlew`、`gradlew.bat`、wrapper JAR/属性均来自该生成结果，属性含官方发行版 SHA256。可用 `./gradlew` 替代上述本地 Gradle 路径；wrapper 首次执行仍可能下载自己的 Gradle 发行包，离线或不希望重复下载时继续用 `.tools` 内的 Gradle。实现负责人不重复下载/安装环境。

产物：`app/build/outputs/apk/debug/app-debug.apk`。生成物与 `.tools` 不进源码 Git；不自动安装、不部署、不推送。

## 无设备验证路径

1. 构建 JVM 单测验证：词头/词形歧义、不生成缺词释义、事件 ID 去重规则、当地日期与夏令时、框选缩放/黑边/扩边、PDF 分页与中文换行、旧查询标识失效。
2. 设置 → 演示输入查词：`evidence`、`studies` 应各成功一次；重复主动查词增加次数；`saw` 要求确认；`unknownword` 不收录。
3. SQLite Android 测试源码位于 `app/src/androidTest/`：事务去重、重试不复活撤销事件、撤销保留笔记、纠错原子性、取消回滚、搜索与重开库。它们必须在设备/模拟器运行才算通过，`assembleDebugAndroidTest` 仅验证可编译。
4. 完整构建/测试是否实际通过以本轮交付证据为准。没有设备时不能声称 UI、捕获、OCR 或 SQLite 运行时已验证。

本轮实现负责人还用已经到位的 Gradle 发行包内 Kotlin 2.0.20 编译器/JUnit 4.13.2 对纯逻辑做过独立预检，26 项测试通过（含新增计时单测；仍需正式项目构建确认）。它不替代项目 Kotlin 2.0.21 下的 Gradle 构建、lint 或设备测试。

## 捕获与隐私边界

- 该原型明确选择**全屏分享**，不是应用窗口捕获。首次开启先解释整屏权限，再走系统悬浮/通知/捕获授权。用户拒绝或取消不会留下“已开启”。Android 14+ 显式请求默认屏幕捕获。
- 临时截图只在内存保留；不录视频、不写截图文件、不上传。源码 manifest 用合并规则移除传递依赖的 `INTERNET` 与 `ACCESS_NETWORK_STATE`；最终 APK 的权限须核验合并 manifest，不能仅凭源码声明认定已离线。
- 点击入口移除本工具浮层，经下一显示帧回调后接受时间戳晚于该回调的新 ImageReader 图像；超时不复用旧截图。截图时间戳与显示合成时序需在设备核对，尚不承诺全部 ROM 都无入口残影。安全页面可能不可捕获；不得规避系统保护。
- 每个会话只创建一个 VirtualDisplay，旋转/捕获尺寸变化替换 ImageReader 并调整显示尺寸；旧选区失效。原型拒绝超过 8192 单边或 16,777,216 总像素的捕获尺寸并停止会话，避免无界内存分配。
- OCR 使用独立裁剪位图，避免全区域裁剪返回原图时被提前回收。关闭卡片和停止服务使旧回调失效；服务关闭释放虚拟显示、捕获、浮层与识别器。停止期间显示“正在关闭”并阻止重开，待资源清理后才允许新授权；旧会话收尾按 generation 核对，不覆盖新授权状态。已有提交可从生词详情撤销，不删除整个历史词项。
- 临时静态画面**不暂停**题目计时、视频或底层应用行为。OCR 英文字形、截图方向/尺寸、通知/返回键及 OPPO 后台限制仍需实机确认。
- 不需要无障碍服务、相机、存储读写或账号权限。系统文件选择器用于 PDF 写入，App 关闭 Android 自动备份（未提供备份恢复功能）。

## Debug 性能日志（供后续真机阶段 B）

只在 debug 构建用 `ShiciPerf` 标签输出 logcat，不写文件、不联网、不做遥测面板；release 构建不输出本工具的性能日志。每次点击用随机匿名 `op` ID 串联，只有固定阶段/状态与毫秒耗时，不包含单词、OCR 文本、截图或用户笔记。

在已由用户授权连接的测试设备上，可采集 `adb logcat -s ShiciPerf:D`。工程不会自动执行设备连接或安装。

- `click`：点击入口；`selection_ready`：选词层第一次绘制，`click_to_selection_ms`。
- `release`：有效框选松手；`definition_visible`：释义内容第一次绘制，`release_to_definition_ms`。
- `save`：提交状态；错误阶段包括 `frame_timeout`、`frame_conversion`、`ocr_empty`、`ocr`、`dictionary`、`session`；关闭/停止同样有状态日志。
- 所有计时使用 `SystemClock.elapsedRealtime()`；学习日期仍使用日历时间。第一次绘制是本轮交互就绪代理指标，不等于硬件光子级显示测量。
- `needs_confirmation` 标记多词/词形歧义；这些记录含人工确认等待，不应直接并入自动路径 P95。成功、未收录、取消、失败分别报告，不能只抽最快的样本。
- **尚未采集真机样本，未计算 P50/P95，不承诺性能达标。** 本轮只有计时计算的纯逻辑测试。

## 本轮未实现 / 未验证

- 未实现：正式词典许可与接入、发音、在线释义、例句、三档掌握、备份恢复、自测 PDF、日期范围筛选、批量选择、性能遥测面板。没有相应假按钮。
- 仍未验证：原定 OPPO Pad 3/海文兼容性、断网初装 OCR、P50/P95 速度、30 分钟功耗、后台回收、受保护画面、无障碍读屏、捕获期间旋转、字号放大、长文 PDF 及实际打印效果。首轮 OPPO Pad 3 Pro 自备画面与单页中文 PDF 结果见上方真机测试节。
- 本轮 PDF 逻辑测试不是字体渲染或打印验证。设备重建如果发生在系统导出文件选择期间，冻结快照未写磁盘，会提示重新导出而不写入不完整的新快照。
- 内存范围内的记录聚合适合原型；大量长期记录的查询与分页优化不在本轮范围。学习库 schema 为 v1；不提供破坏性迁移。

## 代码入口

- `ui/MainActivity.kt`：四页界面、权限流程、演示查词、PDF 文件选择。
- `capture/CaptureService.kt` / `SelectionView.kt`：捕获会话、浮层、框选、本地 OCR。
- `core/Dictionary.kt`：演示词库与显式词形映射（唯一来源）。
- `data/LearningRepository.kt`：SQLite 事务、事件计数、撤销、个人数据。
- `core/Geometry.kt` / `ExportLayout.kt`：可用 JVM 单测核对的几何与分页。
- `export/PdfExporter.kt`：系统 PDF 生成器。

工程是当前唯一运行源码；`docs/design/ui-concept.png` 是设计参考，不是运行时整页图片。

## 官方技术核验来源

- AGP 8.9 兼容 Gradle 8.11.1 / JDK 17 / API 35：<https://developer.android.com/build/releases/agp-8-9-0-release-notes>
- MediaProjection 会话授权、前台服务与尺寸变化：<https://developer.android.com/media/grow/media-projection>
- ML Kit 打包与动态下载依赖区别：<https://developers.google.com/ml-kit/vision/text-recognition/v2/android>
- 系统 PDF API：<https://developer.android.com/reference/android/graphics/pdf/PdfDocument>


## 本轮最终本地验证（2026-10-09 二级 UI 续作）

冻结源码后，主线程执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`，结果为 **BUILD SUCCESSFUL**。

- JVM 单元测试：26 项，0 失败、0 错误。
- lint：0 错误、5 项建议警告（2 项原生 Switch 选择、3 项 KTX 写法建议）；本轮增量编译另有一项 SOFT_INPUT_ADJUST_RESIZE 弃用提示，基线 scaledDensity 等兼容提示仍保留。
- 设备测试包已编译，**未运行仪器测试**。
- APK 内容包含 OCR 模型和 ARM64 原生库；实际 APK 权限无网络权限。
- 本机日志：`.tools/build-ui-verified.log`（本轮），`.tools/build-verified.log`（功能基线）；可移交的阶段说明：`docs/原型开发进度.md`。
- 未在模拟器或 OPPO 上运行界面、捕获、OCR 与 PDF 渲染；不能据此宣称海文兼容或速度达标。
