<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="拾词 Logo">
</p>

# 拾词 Shici

**阅读不停，生词随手拾起。**

拾词是面向 Android 平板和手机的阅读查词与复习工具。在阅读材料上划一笔，抬手查看词义；也可以直接在悬浮窗口输入。把查过的词留下来，加上笔记、收藏，再按每天的计划复习。

[下载测试版](https://github.com/jiangnanqing188/shici-android/releases) · [产品网站](http://83.229.122.116/) · [反馈问题](https://github.com/jiangnanqing188/shici-android/issues)

## 下载与版本

目前是持续开发的 **Debug 签名测试版**，尚未发布应用商店正式版。Android 8.0（API 26）及以上；暂不支持 iPhone、iPad。

| 设备与分支 | 最新测试包 | 当前区别 |
| --- | --- | --- |
| 平板：[codex/android-mvp](https://github.com/jiangnanqing188/shici-android/tree/codex/android-mvp) | [2.8.0 / 55](https://github.com/jiangnanqing188/shici-android/releases/tag/v2.8.0-tablet-test) | 宽屏侧栏直接入口、列表与详情分栏、真实界面动画引导和“下一步” |
| 手机：[codex/phone-adaptation](https://github.com/jiangnanqing188/shici-android/tree/codex/phone-adaptation) | [2.8.0 / 55](https://github.com/jiangnanqing188/shici-android/releases/tag/v2.8.0-phone-test) | 保留固定四入口、紧凑布局与键盘适配；同步真实界面引导、下一步与锚定“更多”菜单 |

从 Release 的 **Assets** 下载对应的 `.apk`；`Source code` 压缩包是源码，不能直接安装。每个 Release 提供安装包大小与 SHA-256 校验文件。

两个包共用应用 ID `com.shici.app`，不能并排安装。升级前先在“设置 → 备份与恢复”导出 `.shicibackup`；已装 2.8 的设备不要尝试覆盖较旧的 2.7，也不要为降级卸载而丢失学习数据。官网现有下载仍是旧的 2.6.1 通用包，最新两端包请以 GitHub Releases 为准。

## 能做什么

- **阅读时查词**：主动开启悬浮查词，在原文上勾画取词；结果框可拖动，固定提供划词、手动输入与退出入口。
- **离线词典与词形**：大词库使用本地 SQLite 索引，含 770,482 个可检索词头、56,001 组有来源的单跳词形关系。保留原词条和原型候选，不靠随意截词尾猜单词。
- **个人生词本**：记录查询次数与时间，支持搜索、日期筛选、排序、收藏、笔记和掌握状态；移除时明确选择撤销最近一次查询或删除该词全部学习记录。
- **补充词义**：为查询词添加自己的释义，与原始词典分别保存。
- **每天复习**：考研、六级与个人生词共同参与，每日新词目标默认合计 20，优先个人生词，采用四选一作答、复习调度与重启续学。
- **PDF 与数据备份**：导出复习版、自测版与答案页；完整学习数据另存 `.shicibackup`。PDF 不能替代备份，恢复目前为覆盖方式。
- **可选句子翻译**：使用 Google ML Kit 的设备端英译中组件；初始不下载模型，用户确认后才下载。它不是开源大语言模型，下载与兼容性受 Google 服务及网络环境影响。

## 开始使用

1. 安装对应测试包，先用“查词”页输入一个单词。
2. 需要在其他应用上查词时，主动开启悬浮查词，按系统提示授予悬浮窗、屏幕捕获等权限。
3. 回到阅读材料，点击悬浮条的划词入口，在词上划过并抬手；有候选时先选词，再查看结果。
4. 在生词本整理记录，在“复习”里完成当天任务。2.8 的使用引导可在真实页面上跟着操作，也可用“下一步／跳过”。

## 数据与权限

查词、学习记录、个人词义与复习进度保存在设备本机，没有账号、云同步或自建查询后端。屏幕捕获由用户主动授权，画面仅在本机内存中临时识别；可选文字直读需要用户另外开启无障碍服务，不自动代开。

备份和 PDF 通过系统文件选择器保存。`.shicibackup` 为未加密的本地文件，请妥善保管。可选 ML Kit 翻译会涉及模型下载、更新及 SDK 诊断通信；不使用翻译不影响本地查词和学习功能。

## 已知限制与验证范围

这是可体验、可反馈的测试版本，不代表全部设备或全部功能通过验收。

- **大幅滚屏后连续重划**仍有失败或笔迹不显示的已知问题；遇到时可先停止滚动、退出后重开，或使用手动输入。
- 原始词义与词形没有逐条语义核对，不保证覆盖全部考试大纲；缺失释义不自动编造。
- 平板 2.8 已保数据更新到真实设备；最后受影响范围检查为 6 通过、1 测试操作异常、1 未测。真实英文题页“划词 → 词卡 → 手动查询 → 退出”完整引导链尚未验完。
- 手机 2.8 已同步真实界面引导，完成编译与有限本地宿主/源码验收，尚无这一版的手机真机验收。物理笔、大字号、不同厂商系统和长期功耗仍需进一步验证。
- 可选翻译的真实下载、译文质量和设备兼容性不作普遍保证。

详细记录在 [工作记录](工作记录/) 与 [验收清单](docs/原型验收清单.md)。历史结论保留当时范围，不将不同批次的通过数累加成“全软件通过”。

## 从源码构建

技术栈为 Kotlin、Android 原生 View、SQLite 与 Google ML Kit。需要 JDK 17、Android SDK 35 / Build Tools 35.0.0、Python 3；仓库自带 Gradle 8.11.1 Wrapper。

```sh
git clone --branch codex/android-mvp https://github.com/jiangnanqing188/shici-android.git
cd shici-android
```

手机布局请改用 `codex/phone-adaptation` 分支。在 Android Studio 中配置本机 SDK，或在不入库的 `local.properties` 中填写 `sdk.dir`。

**完整词库二进制不随 Git 分发。** 仅克隆源码不能直接得到完整可用的查词包；需要先准备下列资产，或直接使用 Release 安装包。

| 运行时资产 | 来源与生成方式 |
| --- | --- |
| `app/src/main/assets/dictionary/ecdict.sqlite3` | 固定 ECDICT CSV，通过 `tools/dictionary/build_ecdict_sqlite.py` 生成 |
| `app/src/main/assets/dictionary/kajweb.tsv.bin` | 固定五书原件，通过 `tools/dictionary/build_kajweb_app.py` 生成后按运行时文件名放置 |
| `app/src/main/assets/dictionary/study-books.tsv.bin` | 固定考研/六级成员索引，通过 `tools/dictionary/build_study_books.py` 生成 |

固定来源、版本和 SHA-256 位于 [词库来源清单](docs/dictionary/source-inventory-20261009.json) 与 [资产清单目录](app/src/main/assets/dictionary/)。生成器会核对输入，不使用未应用的词义修订。

<details>
<summary>词库构建命令（原件需自行备齐）</summary>

将固定 ECDICT CSV 与许可放入 `.tools/dictionaries/ecdict/`，五个固定词书 ZIP 放入 `.tools/dictionaries/kajweb/`。两个生成目录必须尚不存在。

```sh
python3 tools/dictionary/build_ecdict_sqlite.py \
  --csv .tools/dictionaries/ecdict/ecdict.csv \
  --license .tools/dictionaries/ecdict/LICENSE \
  --output app/src/main/assets/dictionary

python3 tools/dictionary/build_kajweb_app.py \
  --manifest docs/dictionary/source-inventory-20261009.json \
  --source-dir .tools/dictionaries/kajweb \
  --output-dir .tools/generated-kajweb

cp .tools/generated-kajweb/assets/dictionary/kajweb.tsv.gz \
  app/src/main/assets/dictionary/kajweb.tsv.bin

python3 tools/dictionary/build_study_books.py \
  --inventory docs/dictionary/source-inventory-20261009.json \
  --source-dir .tools/dictionaries/kajweb \
  --output-dir .tools/generated-study-books

cp .tools/generated-study-books/dictionary/study-books.tsv.bin \
  app/src/main/assets/dictionary/study-books.tsv.bin
```

二进制需与仓库内对应 manifest 的校验值相符。生成物、原始词书、签名和个人数据不要提交到 Git。

</details>

准备资产后构建：

```sh
./gradlew :app:assembleDebug
```

输出为 `app/build/outputs/apk/debug/app-debug.apk`。自己构建使用本机签名，与已安装测试包签名可能不同；请先备份，不要通过卸载来绕过签名不一致。

## 目录与参与

- `app/`：Android 应用、单元与仪器测试源码。
- `tools/dictionary/`：离线词库构建与核验工具。
- `website/`：独立静态网站源码与部署工件。
- `docs/`：[产品方案](docs/安卓划词查词与生词本软件方案.md)、验收与词库来源说明。
- `工作记录/`：历史实现、测试结论及回退线索。

反馈 Bug 时请提供版本号、设备与 Android 版本、复现步骤、预期和实际表现。截图请遮住个人信息，不要上传学习数据库、备份、账号或密钥。

## 词库来源与许可

完整查询词库来自 [ECDICT](https://github.com/skywind3000/ECDICT)，固定版本为 `bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b`，其 MIT 声明保留在 [ECDICT-LICENSE.txt](app/src/main/assets/dictionary/ECDICT-LICENSE.txt)。考试词书来源与固定版本另见来源清单；资产清单中历史的 `redistribution_status` 不因本次公开改写。

项目自身源码许可证尚未确定；不把第三方词库的 MIT 声明扩展为整个项目的许可证。原始词库、生成数据库、APK、模型、签名及个人数据不纳入 Git；APK 仅通过 Releases 作为测试包分发。
