# 拾词官网

## 当前线上版本：2.9.0 / 56 双端公开测试版（2026-11-19）

- 安卓手机和安卓平板分别打开 `v2.9.0-phone-test`、`v2.9.0-tablet-test` 的 GitHub HTTPS 发布页；不会直接下载。iPhone、iPad 暂未提供。本批分发使用不可调试的构建，并沿用公开 2.8.0 的签名证书，真机保数据升级和大幅滚屏后重划仍待验证。
- 官网仍只提供 HTTP；请从已知 GitHub 仓库独立进入设备对应的发布页并核对 `SHA256SUMS.txt`。此前的 `/downloads/shici-2.6.1-52.apk` 已撤下公开路由，生产 GET、HEAD 和 Range 均实测返回 404，服务器原文件未删除。
- 四页共享资源缓存标记为 `20261119a`。七个线上静态文件与本批候选逐字节核对通过；仅更新 HTTP 站点和重载 nginx，未变更 443 服务。

## 历史候选：A01 官网 APK 明文分发止血（2026-11-17，当时未上线）

- 下载页仅导航到 `jiangnanqing188/shici-android` 的 HTTPS Release 页面：安卓手机 `v2.8.0-phone-test`，安卓平板 `v2.8.0-tablet-test`。均为 2.8.0 / 55 Debug 测试包，但附件不同；点按只打开发布页，不自动下载。版本及附件核对依据见 `../工作记录/20261117-2-A01与A03首批修复任务单.md` 的 A01；该证据仅为 Release 元数据核对，不代表重新下载或哈希整包。
- 官网页面仍为 HTTP，内容和链接都可能被篡改。应从已知的 GitHub 仓库独立打开对应设备的 Release，核对附件、大小和 `SHA256SUMS.txt`；不把官网或同页校验值当作信任锚。本候选不迁移站点 HTTPS。Apple 仍为纯文字；安装前备份及大幅滚屏后划词失败的已知问题提示继续保留。
- 四页共享资源引用统一为 `20261117a`。候选 `deploy/nginx.conf` 移除 `/downloads/shici-2.6.1-52.apk` 的精确路由；该路径的 GET/HEAD 在候选中应落入 404，不删除原文件。保留原七个静态文件及首页 `/` 别名、GET/HEAD 限制和 443 边界；未运行安装脚本，未探测、变更或重载生产站点。

下面的日期章节保留各批当时的状态，以本页开头的当前上线状态为准。

## 新版 Logo（2026-11-08）

本批使用用户最终批准的首版“墨绿底＋米色托手＋a 词卡”，不采用后续三个备选。CSS 内嵌 96×96 PNG 供四页页头/页尾复用，四页内嵌 favicon 为同源 64×64 PNG；原图只按尺寸派生，不重画、改色或改变页面布局。

四页三份共享资源引用统一由 `20261101a` 更新为 `20261108a`，避免旧 Logo 缓存。此次只发布四个 HTML 与 `styles.css`；`app.js`、`content.js`、nginx 配置与现有 2.6.1/52 APK 保持不变，无新增公开路径、无服务重启。本轮五静态已发布，当前线路真实 HTTP/浏览器有限补验通过。

Android 仅本机源码和构建产物同步新版 Launcher，仍为 2.6.2/53，未安装到平板、未更新公开下载包。历史 Logo 各轮结果保留历史含义；本批准确范围、验证限制和回退入口见 `../工作记录/20261108-1-新版图标接入工作记录.md`。

## 多页面结构（2026-11-01，已上线）

本批把原来的单个长滚动页拆成同一站点下的四个独立 HTML 页面，仍不买域名、不开子域、无新增后端。所有页面共用 `styles.css`、`content.js`、`app.js` 三份资源，没有重复副本。

- 页面与用途：`/` 与 `/index.html` 是简洁首页（品牌、示意图、三个入口）；`/features.html` 功能介绍与能力边界；`/guide.html` 使用指南，页内包含 `#how` 使用步骤、`#faq` 常见问题、`#privacy` 隐私说明；`/download.html` 下载与校验。
- 导航：四页顶部与页脚使用同一组四项导航，当前页在 HTML 里写死 `aria-current="page"`，`app.js` 再按解析后的实际路径校正一次；品牌与页脚品牌都明确返回首页。
- 旧入口兼容：`/?v=d99743c4#download`、`#features`、`#how`、`#privacy`、`#faq` 只会在首页（`/`、空路径、`/index.html`）被 `app.js` 一次性转到对应独立页面或页内锚点；在 `guide.html#privacy`、`guide.html#faq` 等二级页自身锚点上不再改写，避免同 URL 循环。旧查询串不参与判定，`v=d99743c4` 只是历史版本标记。
- 缓存一致性：四个页面统一引用 `styles.css?v=20261101a`、`content.js?v=20261101a`、`app.js?v=20261101a`；共享资源各只有一份，版本查询值相同且与页面同批更新。nginx 的三个精确 location 不包含查询串，带查询串的同一路径仍命中对应文件。
- 发布事实：`content.js` 的 `publicRelease` 仍是唯一真实发布源，仍是 2.6.1 / 52（110,669,521 字节，SHA-256 `e24e63322def2db7ea4e34783ae238ca5c35c6650703887de642b2f1bfac59b4`，Debug 签名测试包）。三态校验（`null` 准备中 / 非空但不完整不开放 / 完整才开放）保持不变，同一结果驱动每一页的标题、`meta[name="description"]`、hero 提示、下载区与页脚。
- 下载页平台事实：安卓区块分“安卓手机下载”“安卓平板下载”两个按钮，文案即按钮文字，二者指向同一个通用 APK，并在页面明确写出共包、不区分型号；苹果区块为“iPhone 暂未提供”“iPad 暂未提供”两段纯文字，没有 `href`、没有 `download`、没有 App Store 链接或占位跳转，也没有订阅、账号或信息收集入口。发布状态无效时，两个安卓按钮改为“暂未开放下载”文字，不渲染任何链接。
- 局部容器容错：`app.js` 对下载区、功能列表、步骤、问答、隐私等区块逐个判空，任何一页缺少某个容器（例如首页和功能页没有下载区、下载页没有功能列表）都只跳过对应模块，其它渲染照常；`content.js` 整体缺失时仍是先前的整页提示行为。
- 版本口径：`internalVersion` 为本地开发参考 2.6.2，`internalVersionCode` 为 53，仅作开发记录，不代表已发布；公开包仍固定为 2.6.1 / 52。页面把这两个值分开显示，不把 53 当作可下载版本。
- 无 JS 与可访问性：四页都有 `<noscript>` 说明，且标题、导航、入口、平台事实与边界文字直接写在 HTML 里，不依赖脚本即可阅读；键盘焦点样式、跳过链接与 `prefers-reduced-motion` 处理沿用原样式。
- 本批状态：**已实施、已上线**。官网已由原单页换成上述四页（统一导航、旧锚点兼容）；Android 手机与平板仍共用 2.6.1 / 52 通用 Debug 测试包，Apple iPhone / iPad 暂未提供且无链接；Android 源码与平板未改动，本地候选 2.6.2 / 53 未发布。
- 实现链：Command Code Plan（`commandcode-plan/deepseek/deepseek-v4.1-flash-fast`，请求 max）完整交付，末尾三条语法命令均 exit 0。中途 Qoder 认证失败、Command 入口 max-tokens 中断与本通道两次 TRANSPORT 中断的原始过程保留，不称“一遍全成功”。
- 本地有限验收：独立实际 `gpt-6.1-sol/high` 的 local01 唯一次 16 项原为 13 PASS / 2 FAIL / 1 ERROR，原结果保留，三处缺口均判定为夹具问题；新 local02 仅针对原未达尾部 3 PASS / 0 FAIL / 0 ERROR / 0 SKIP。只算本地有限闭环，不累计、不倒填原 16 项全绿。
- 生产发布与验收：经 Netcatty 官方 H2 发布准确的七个静态文件与一份只新增三条精确路径的 nginx 配置，私有备份与候选 hash 核对保留；候选与生产 `nginx -t`、reload 均 exit 0，原 `duplicate MIME type "text/html"` warning 仍在；443 的 PID/start/boot 与 unit/config/binary、旧 APK hash/字节数保持不变；未执行首次安装 `install.sh`，未改 DNS、防火墙、域名、HTTPS 或其它服务器。新独立实际 `gpt-6.1-sol/high` 唯一 post01 五项为 **5 PASS / 0 FAIL / 0 ERROR / 0 SKIP**：准确生产字节与版本资源、HTML 与旧 APK 的 HEAD、360px 真实浏览器下旧链接与下载分类、私有路径 404 与方法 405、官方部署与 443 保护回执核对（P05 为静态核对，非测试者操作服务器），0 次真实 APK GET。这不代表全网、HTTPS、原用户浏览器 IAB 缓存或 Android 真机验收；Git 提交与推送由父侧最后收尾，本文不预填提交结果。

### 本批部署工件同步（已发布）

- `deploy/nginx.conf` 只新增 `location = /features.html`、`= /guide.html`、`= /download.html` 三个精确路径，原 `/`、`/index.html`、`/styles.css`、`/content.js`、`/app.js` 与固定 APK 路径、`GET/HEAD` 限制、`charset utf-8` 与两个安全头保持；没有目录级匹配、没有 SPA fallback、没有代理。该配置已随本批发布，`nginx -t` 与 reload 均 exit 0。
- `deploy/install.sh` 的固定名单同步为四个页面加三份共享资源：`STATIC_FILES` 七项、`BUNDLE_FILES` 八项（多一个 `nginx.conf`），`SHA256SUMS` 白名单与条目数（8）、静态目录条目数（7）、回退逐文件哈希清理与结尾说明一并同步；脚本结构与首次部署语义不变。本批未执行该脚本（属首次安装工具，本次为既有站点的精确替换）。

## 平板 / 手机下载入口（2026-10-30，已上线）

- 下载区分为“平板下载”和“手机下载”两个按钮，均从同一发布配置读取目标，并明确提示“手机和平板共用同一个 APK，两个入口下载内容一致。”当前仍为 2.6.1 / 52 通用 Debug 测试包，不是两个独立适配版本；未重新构建或改动 APK。
- 两入口添加 `download` 属性，移除原“将前往外部下载地址，不会自动开始下载”的不符提示。通过 Netcatty 原子替换官网 `app.js`，并仅将 `index.html` 的脚本地址补为 `app.js?v=d99743c4`，减少继续复用旧脚本的问题；两文件旧备份保留，`content.js`、样式、nginx 与既有 443 服务保持，无需 reload。
- 独立实际 `gpt-6.1-sol / high`：本地双入口/360px/未发布配置与真实点击小夹具 2 项通过；上线新浏览器展示及单次 APK HEAD 另 1 项通过（200、110,669,521 字节、Android APK MIME）。小夹具不是完整 APK，本次未重复下载 110 MB 包，也不是 Android 功能验收。环境准备失败保留，不计作产品通过。 版本化脚本另经新独立 Sol/high 单点复验 1 项通过：新 HTML 与带查询参数的脚本实际 200、准确 hash，双入口和 360px 布局保持；未重跑前述用例或下载 APK。
- 内置浏览器最初重新加载仍见旧按钮；补脚本版本并重新打开页面后，父已观察到双按钮与共用提示。此观察不替代独立验收，不外推所有浏览器缓存行为。以下内容保留历史状态，不反向改写。


## 2.6.1 / 52 测试包下载（2026-10-29，本次发布）

用户已明确授权开放香港站点的测试包下载，并确认当前词库公开再分发授权；这里记录用户确认，不代表本项目另做了法律审查。网站配置与发布由父 GPT 按用户“你直接做”的本次例外完成，不改变长期总工程师默认。

- 包：`shici-2.6.1-52.apk`，110,669,521 字节（105.54 MiB）；SHA-256 `e24e63322def2db7ea4e34783ae238ca5c35c6650703887de642b2f1bfac59b4`。直接复用已保数据安装到平板的 source08 APK，本次不重新构建、不修改 APK、不更换签名。
- 属于 **Debug 签名公开测试包**，不是正式发行版或应用商店版本。安装前须备份学习数据。大幅滚屏后重试划词可能失败或不显示笔迹的问题仍未定位修复，页面如实提示，手动输入可作为暂时替代入口。
- 只新增固定下载路径 `/downloads/shici-2.6.1-52.apk`。下载 URL 按当前页面 origin 生成，不在源码中绑定服务器地址；目录索引、其它 APK、源码、README、运维目录、词库原文件、模型和用户数据均不开放。
- `content.js` 是发布信息唯一来源；`index.html` 的无脚本占位只提示启用 JavaScript，不再写过时的“暂未开放下载”。`app.js` 与 Logo/CSS 不变。Nginx 仅增加上述精确 APK location 和正确 Android 包 MIME，其它路径仍 404，仍只接受 GET/HEAD。
- 主站仍为 HTTP/IP，没有新域名或 HTTPS。站内 SHA-256 可校验下载字节，但同站 HTTP 校验不能抵御同时篡改，不能当成 HTTPS 等价保护。
- 发布前须检查当前远程文件/hash、容量、443 服务身份；APK 先经 Netcatty 官方 SFTP 进入 root:root 700 的私有暂存，完整 hash 核对后才开放。保留原 index/content/Nginx 配置备份；只 reload Nginx 使新精确路径生效，不重启/修改 443 服务，不动 H1/H5、DNS 或防火墙。
- 实际本地验收：父核真实 `gpt-6.1-sol/high` 上下文与 58 份稳定证据；local-01 原 8 项为 7 PASS / 1 FAIL，唯一 L08 是夹具把 rollback 函数里的 reload 当作前向发布；local-02 仅该项补验 1 PASS，原失败保留、不合并宣称原 8 项重跑全绿。真实 Chrome/360px、准确链接/完整本地下载、坏配置拒绝均有证据；本地域的 Nginx/运维检查为静态审查；真实发布与 HTTP 结果见后项，回滚没有做故障注入。
- 发布执行：Netcatty 官方 SFTP 私有暂存完整字节/hash通过后，固定审查脚本已实际完成 APK/两静态/单站点 Nginx 配置发布，`SHICI_RELEASE_APPLIED`、exit 0；仅 reload Nginx。其原有 `duplicate MIME type "text/html"` warning 保留，`nginx -t` 实际 exit 0。443 服务 unit/config/bin/hash、当前 MainPID/boot 与两不变静态均保持。含 shell substitution 的外层核验命令曾被 Netcatty 安全规则拒绝、没有执行，原回执保留；之后按已核脚本固定路径直接执行成功。
- 真实上线后验：独立实际 `gpt-6.1-sol/high` 的 post01 六项为 **6 PASS / 0 FAIL / 0 ERROR / 0 SKIP**。完整公网 GET 110,669,521 字节/hash相等；原始 HEAD 200且空体；前段/携真实ETag的续传段/尾段均206且逐字节一致；九条指定目录/README/.git/私有stage/其它APK均404；一次360px真实线上浏览器显示正确元信息、测试/Debug/未修问题/HTTP说明且无横向溢出。父核44份稳定载荷及全部3实际模型上下文，旧58份证据保持；各域不合并成Android全验。
- 继承的 `app.js` 下载说明仍写“外部下载地址”，与本次同源APK直链不符，列为非阻断N01，本批不扩修。Netcatty自建会话与测试自建浏览器均已关闭。以下旧章节均保留历史含义。


本目录是“拾词”介绍与下载落地页的源文件，只介绍软件，不介绍源码。原开发仓库 `jiangnanqing188/shici-android` 保持私有；将来另建的公开仓库只用于发布下载，不是源码仓库。本目录已获用户授权，四个静态文件已实际部署到香港 H2 服务器的 HTTP 80 临时站点（2026-10-26 11:43 安装成功、exit 0），当前可通过公网 IP 访问，暂无域名，也未启用 HTTPS。原首次 POST 有限复验为 9 PASS / 1 FAIL，唯一失败是 `styles.css` 的 GET/HEAD 响应头缺少显式 `charset=utf-8`（内容本身可正常 UTF-8 解码，不是乱码）。该缺口已按指定执行链以 `charset_types` 修正：准确候选先经独立静态单点审查（gpt-6.1-sol/high，1 PASS），再由父侧通过 Netcatty 上传并完成候选 `nginx -t`、生产 `nginx -t`，仅 reload nginx，未 restart 443。修正后 post-css-r1 受影响有界复验 run-01 为 6 PASS / 0 FAIL / 0 ERROR / 0 SKIP：五 URL 的 GET/HEAD 共 10 个响应均 200，HTML/CSS/JS 的 MIME 正确且显式 `charset=utf-8`，正文/hash/长度与冻结四静态一致，HEAD body 为 0；三条私有路径 404，POST/OPTIONS 405；远程只读证据显示原 443 进程身份及 unit/config/binary 三 hash 保持不变。该结论只覆盖当前本机线路 HTTP/IP 与给定远程证据的有限闭环，不能累计声称原 POST 10/10，也不代表整站或全网验收通过。本文说明部署工件与操作要求，完整验收状态仍以父侧部署记录与统一验收为准。

## 目录与加载顺序

网站是纯静态目录，没有构建步骤，不需要 npm，也不使用远程字体、CDN、分析追踪或网络请求。

- `index.html`：简洁首页（品牌、界面示意、三个页面入口）。
- `features.html`：功能介绍与能力边界。
- `guide.html`：使用指南，页内含 `#how`、`#faq`、`#privacy` 三个章节锚点。
- `download.html`：下载页，Android / 苹果两个平台区块与校验信息。
- `styles.css`：四页共用的样式与响应式布局，含纸张暖白、深墨绿主色和界面示意。
- `content.js`：全部简体中文产品文案和发布配置，只声明 `window.SHICI_SITE`；稳定产品简介、每页标题/meta、平台文案与分状态文案分开存放。
- `app.js`：四页共用的渲染脚本，读取 `window.SHICI_SITE`，把 `publicRelease` 校验为唯一发布状态，并据此统一渲染每页标题/meta、运行要求、下载区、hero 提示和页脚；按 `data-page` 选择页面文案，并对不存在的容器逐块跳过；`repositoryUrl` 仍按 `null` 处理。

每页按顺序先加载 `content.js`，再加载 `app.js`，与 `styles.css` 共用同一版本查询值。发布地址只在 `content.js` 配置，HTML 的无脚本说明不提供下载链接。四页结构自 2026-11-01 生效。

## Logo当前状态（2026-10-28）

用户批准原设计，未重画、未改色；2026-10-26 Logo 验收 17 PASS/1 ERROR 及失败断言原样保留，不并入本轮，不累计旧 18/18。

- 新独立实际 gpt-6.1-sol/high 的全部 5 个真实 turn_context 已由父侧核对，分三个互不相加批次：L01–L03 圆形与当前包补验 3 PASS，P01–P05 私有替身部署准备 5 PASS，H01–H06 真实 HTTP 6 PASS，均 0 FAIL/ERROR/SKIP；不得相加宣称 14 项全站或全软件通过。
- 官方 Netcatty 真实单次仅替换香港 H2 `/srv/shici-website/www` 的 `index.html`、`styles.css`，旧备份保留在私有 700 stage 内、文件 600；`app.js`、`content.js`、nginx 配置、当前 443 服务身份及 unit/config/binary 三 hash 未变；无 nginx reload、改防火墙/DNS、装包、H1/H5 动作；0 新 APK/模型/词库公开，`publicRelease`/`repositoryUrl` 仍为 `null`。
- 真实五 URL 的 GET/HEAD 共 10 个响应全部 200，hash/字节数/MIME 一致且显式 UTF-8，HEAD 空体；两条私有路径 404；一次 360px 真实浏览器检查中页头、页脚 Logo 可见，无横向溢出、无外站请求。该结论仅覆盖当前线路 HTTP/IP 与 360px，不承诺国内各网络、HTTPS、OEM Launcher 或真机。
- Android 候选包 Launcher 已含同源 Logo，圆形当前包引用补验通过；电脑内部 2.5.0/50，平板仍 2.4.2/49，本批 0 安装、0 设备连接；不能把官网替换说成平板已更新，本轮不正式发布。

以下保留 2026-10-26 本地候选历史原文。

## Logo 品牌接入（2026-10-26 本地候选，未上线）

本轮把用户确认的墨绿米白“托起 a 词卡、右侧四指合拢、两道动势线”Logo 接入本地候选。唯一设计源是私有证据目录中的 `approved-logo.png`（1254×1254，SHA-256 `feb77c74558190d5eba97a2dd7116fe4df15707469d71e523012b90a2bf59223`），只做确定性缩放与 PNG 编码，不重画、不改色、不拆开手指或动势线。

- 页头和页脚品牌共用 `styles.css` 中同一个 `--brand-logo` 内嵌 `data:image/png;base64` 背景（96×96 PNG，10521 bytes），`index.html` 的 favicon 内嵌另一个 64×64 PNG data URI（5533 bytes）；两者 MIME 均为 `image/png`，不新增 URL、不发起额外网络请求。
- 品牌链接原有 `aria-label` 和可见“拾词”文字保留，装饰用的 `brand-mark` span 仍为 `aria-hidden`，图片不会被重复朗读；移动端沿用原 38px / 34px 尺寸，不改变导航换行。
- `app.js`、`content.js`、下载配置（仍为 `null`）、`nginx.conf`、`install.sh` 和四静态边界未改。该段只说明本地候选已接入，不代表已部署、已通过真实 HTTP 或真机验收；线上更新以父侧统一验收和 Netcatty 流程为准，语法结果见私有交付记录。

## 本地预览

本地预览只服务 `website` 目录，并只绑定回环地址 `127.0.0.1`。在开发仓库根目录运行：

```bash
python3 -m http.server 8000 --bind 127.0.0.1 --directory website
```

然后打开 `http://127.0.0.1:8000/`（根 URL 就是网站首页）。也可以先进入本目录再运行 `python3 -m http.server 8000 --bind 127.0.0.1`，两种方式访问地址相同。

不要在开发仓库根目录直接运行 `python3 -m http.server`：那会把整个私有开发仓库作为静态目录、默认绑定所有网络接口，可能意外暴露源码。上述命令只用于本地预览。公网侧只允许下述 H2 站点使用 `website` 中明确列入部署白名单的静态文件（2026-10-30 之前为四个单页文件，2026-11-01 起为四个页面加三份共享资源共七个，均已上线）；不要把开发仓库根目录、`website` 整个目录或任何运维工件交给 Web 服务器。

## 香港 H2 临时部署（HTTP + 公网 IP）

### 范围与现状

> 时间范围提示：本节以下保留 2026-10-26 至 2026-10-30 单页版本的实际部署与验收证据（当时为五个 URL），原数字与含义不改、作为历史保留。2026-11-01 的四页结构与三处新增 nginx 精确路径已上线，最新当前状态见文首“多页面结构”一节。

- 用户已授权在香港 H2（Ubuntu 24.04）部署现有静态官网。没有域名、暂无购买预算，本轮先通过服务器公网 IP 的 HTTP 80 访问；域名和 HTTPS 后续另做。
- 截至 2026-10-30 那次发布，线上公开四个文件：`index.html`、`styles.css`、`content.js`、`app.js`（单页版本）。它们精确复制到 `/srv/shici-website/www`；2026-11-01 起已追加 `features.html`、`guide.html`、`download.html`，线上为七个静态文件，最新状态见文首。不上传或服务开发仓库根目录，也不把 `website/README.md`、`website/deploy/`、`.git` 或任何其他文件放进站点根目录。
- 现有 443 端口由服务器上的其他服务（sing-box）占用，本轮不改动 443、不代理该服务、不改 DNS、不改防火墙或云安全组。
- 不开放 APK、公开仓库、模型、词库或学习数据下载；`content.js` 的 `publicRelease` 与 `repositoryUrl` 继续保持 `null`，页面仍显示暂未开放下载。
- 本轮已完成 HTTP 80 临时站的首次部署（2026-10-26 11:43 安装成功、exit 0），站点可通过服务器公网 IP 访问。原首次 POST 复验 9 PASS / 1 FAIL 保留；唯一失败的 `styles.css` 显式 charset 缺口已修正，并经 post-css-r1 受影响有界复验 6 PASS / 0 FAIL 闭环。`nginx -t` 存在非阻断的 `duplicate MIME type "text/html"` warning，真实 exit 0；本批如实记录，不额外改配置消警告。安装终端回执为 completed/exit 0 且未标记截断，但声明字符数与返回正文长度存在差异，因此不声称逐字节完整终端流。该有限闭环不等于全部验收通过，也不代表页面已经开放下载。

### 服务器路径

- 站点根目录：`/srv/shici-website/www`（线上现有七个静态文件：四个页面与三份共享资源，root:root，目录 755、文件 644）。
- Nginx 站点配置：`/etc/nginx/sites-available/shici-website`。
- 唯一启用链接：`/etc/nginx/sites-enabled/shici-website`。
- 运维工件（本目录的 `README.md`、`deploy/nginx.conf`、`deploy/install.sh`）只用于打包和部署，不复制进站点根目录，Web 不可访问。

### 部署工件与前置

`website/deploy/` 只包含两个部署工件：

- `nginx.conf`：HTTP 80 默认站的 Nginx 配置。`server_name _`，同时监听 IPv4/IPv6 的 80，不监听 443，不代理其他服务；`root` 只指向 `/srv/shici-website/www`；只允许 `GET`/`HEAD`，只公开 `/`、`/index.html`、`/features.html`、`/guide.html`、`/download.html`、`/styles.css`、`/content.js`、`/app.js` 八个 URL 与一个固定 APK 路径，其他路径返回 404，关闭目录列表；显式声明 HTML/CSS/JS 三种 Content-Type，并通过 `charset utf-8` 与 `charset_types text/html text/css application/javascript` 为这三种响应附加 `charset=utf-8`，不把 MIME 覆盖成同一类型。
- `install.sh`：供 root 在已上传的 bundle 目录内手动执行一次的首次部署脚本，不自带联网逻辑（安装 nginx 时由 `apt-get` 访问系统软件源），不读取密钥或代理凭据，不硬编码服务器 IP。装包前先建立仅针对 nginx.service 的可恢复启动抑制，`nginx -t` 通过后才解除并首次启动。本轮首部署把 nginx 与 nginx-common 固定为已审版本 `1.24.0-2ubuntu7.18`，版本不可用即明确失败，不降级、不替换。请用 `bash install.sh` 执行，不依赖可执行位。

父侧打包 bundle 时必须包含且只校验以下文件：

```text
index.html
features.html
guide.html
download.html
styles.css
content.js
app.js
nginx.conf
SHA256SUMS
```

`install.sh` 也需随 bundle 一同上传以供执行，但不进入 `SHA256SUMS`。

`SHA256SUMS` 由父侧生成，必须恰好覆盖上述八个文件（不含 `install.sh` 自身），脚本会拒绝额外文件名、重复条目、非常规文件和哈希不匹配。可在 bundle 目录内这样生成：

```bash
sha256sum index.html features.html guide.html download.html styles.css content.js app.js nginx.conf > SHA256SUMS
```

bundle 通过安全通道上传到服务器后，在该目录内以 root 执行：

```bash
bash install.sh
```

脚本前置检查包括：uid 为 0；系统为 Debian/Ubuntu 且 systemd 正在运行；bundle 八个文件与 `SHA256SUMS` 完整匹配；未安装 nginx/apache/caddy，`/etc/nginx`、nginx systemd 单元/`nginx.service.d` 抑制目录及同名部署路径均不存在；TCP 80 在 IPv4/IPv6 上均无监听。任一条件不满足即中止，不覆盖已有内容；无法确认能建立启动抑制时不安装 nginx。

### 脚本动作

1. **装包前**创建仅针对本批全新 nginx.service 的可恢复启动抑制：`/etc/systemd/system/nginx.service.d/10-shici-website-deploy-hold.conf` 中的 `ConditionPathExists=!.../shici-website-deploy.hold` 加标记文件。标记存在期间，apt 的 postinst 即使请求启动 nginx，该单元也会被 systemd 跳过；不写 `policy-rc.d`、不 mask 单元、不改全局启动策略，也不覆盖任何已有 systemd 对象。若已有 `nginx.service`、该 drop-in 目录或同名文件，脚本直接中止。
2. 执行 `apt-get update`，再执行 `apt-get install -y --no-install-recommends nginx=1.24.0-2ubuntu7.18 nginx-common=1.24.0-2ubuntu7.18`。这两个版本是本轮首部署前已审的 Ubuntu 24.04 软件源版本：`apt-get update` 后候选即使漂移也只安装该准确版本，版本不可用即明确失败，不降级、不替换；这是本批的版本锁定，不是永久更新策略。该次 apt 调用仅在本进程内使用 `NEEDRESTART_MODE=l`，避免 needrestart 自动重启既有服务（含占用 443 的服务），不写全局策略。这是系统软件源中的现有软件包，不购买新服务；安装前会再次确认启动抑制仍在，安装后复核两个包的实际版本确为上述版本。
3. 装包后复核抑制文件仍在、`nginx` 未被启动，且 80 监听（若已出现）只属于本次新装 nginx、`sites-enabled` 没有 `default` 以外的站点、`conf.d` 没有额外配置；否则中止。未知站点/配置保留不删。
4. 仅当 `sites-enabled/default` 是指向标准 `/etc/nginx/sites-available/default`、且该文件属于 nginx 软件包的符号链接时，移除该链接，避免与本站的 `default_server` 冲突；不删除不明配置。
5. 创建 `/srv/shici-website/www`，只复制七个静态文件（四个页面与三份共享资源）并逐字节核对。
6. 安装 `sites-available/shici-website` 和唯一启用链接 `sites-enabled/shici-website`。
7. 执行 `nginx -t`；通过后先删除本批启动抑制并 `daemon-reload`，再启动 nginx（这是本批首次可能公开页面的时刻），随后 `systemctl enable nginx`。最终用本机 `ss` 确认 80 由 nginx 监听。

因此装包期间不会出现标准 `default` 站短暂公开的窗口：在站点只有七个静态文件、站点配置已就位且 `nginx -t` 通过之前，本批 nginx 不会被 systemd 启动。

脚本不做外网 HTTP 请求测试、不测试页面功能、不修改 443/防火墙/云安全组、不绑定域名、不申请证书、不发布安装包或数据。

### 失败回退

任何一步失败时脚本以非 0 退出，并只回退本批创建的对象：

- **先停止并取消自启**：按实际安装结果（而非“安装成功”标记）判断本批是否引入了 nginx，包括 apt 部分失败。若已运行则 `systemctl stop nginx`，并对本批 nginx 主进程做兜底 TERM/KILL；随后 `systemctl disable nginx`，清理仅指向 nginx 软件包单元的启用链接，并复查 `is-active` 与 `is-enabled`。状态查询失败、空输出或未知取值不会被当成“已停止/未启用”，而是视为无法确认并保留启动抑制；`systemctl disable` 或 `daemon-reload` 执行失败同样不视为安全。不卸载软件包、不停止其他服务。
- 移除本批创建的启用链接、站点配置和 `/srv/shici-website` 静态目录：启用链接需目标与创建时记录的 inode 均一致；站点配置与七个静态文件逐文件比对 `SHA256SUMS` 中的本批哈希，内容不一致、已被替换或不是常规文件的保留并告警；目录用 `rmdir` 且仅在为空时删除。
- 只有在确认本批 nginx 已停止且未自启时，才恢复本批移除的标准 `default` 启用链接；否则不恢复，避免“default 链接 + 仍 enabled”在重启后公开默认站。
- 启动抑制的撤除同样以“服务已停止且未自启”为前提；若无法确认，脚本按磁盘上真实的抑制状态处理：仍完整在位则保留；若已被部分删除（例如释放过程中 `rmdir` 或 `daemon-reload` 失败），则重建仅针对 nginx.service 的抑制，并明确提示人工检查；重建失败时提示人工恢复抑制文件。这样失败后本批新引入的 nginx 不运行、不自启，而已装软件包可以保留。
- 不删除不明文件、不卸载软件、不停改既有服务。若发现其他站点/`conf.d` 配置、80 被非本批进程占用等未知并发内容，脚本保留这些内容并明确失败；回退只停止本批新引入的 nginx，不会为了强行回滚而删除他人内容。
- 如果失败发生在 apt 安装阶段，已安装或部分安装的软件包可能保留，脚本会明确提示，不假装全部回滚。

手动回退同样只处理本批路径；执行前先确认 nginx 确实是本轮新装且没有其他在用站点：

```bash
# 先停止并取消自启，确保重启不会公开默认站
systemctl stop nginx
systemctl disable nginx
rm -f /etc/nginx/sites-enabled/shici-website
rm -f /etc/nginx/sites-available/shici-website
rm -f /srv/shici-website/www/index.html
rm -f /srv/shici-website/www/features.html
rm -f /srv/shici-website/www/guide.html
rm -f /srv/shici-website/www/download.html
rm -f /srv/shici-website/www/styles.css
rm -f /srv/shici-website/www/content.js
rm -f /srv/shici-website/www/app.js
rmdir /srv/shici-website/www
rmdir /srv/shici-website
# 只删除本批启动抑制；若有未知文件请保留并人工检查
rm -f /etc/systemd/system/nginx.service.d/shici-website-deploy.hold
rm -f /etc/systemd/system/nginx.service.d/10-shici-website-deploy-hold.conf
rmdir /etc/systemd/system/nginx.service.d 2>/dev/null || true
systemctl daemon-reload
# 可选：仅当本轮曾移除标准 default 链接、且确实需要默认站时才恢复并手动启动；
# 恢复后若再 enable/start nginx，默认站会对公网可见。
# ln -s ../sites-available/default /etc/nginx/sites-enabled/default
# systemctl start nginx
```

不要用 `rm -rf` 清理未知目录，不要卸载其他软件，不要停止或修改 443 服务。若脚本在无法确认停止/禁用时保留了启动抑制，重新部署前必须先按上述步骤确认 nginx 已停止、禁用并删除本批抑制目录；否则前置检查会拒绝继续。若需彻底移除本轮新装的 nginx 软件包，应由父侧单独评估后处理；本脚本和上述手动步骤都不自动卸载软件包。

### 访问与未做事项

- 本轮访问地址为 `http://<服务器公网IP>/`（真实 IP 只记录在父侧私有部署记录和交付报告中，不写入本文或仓库）。站点已部署；CSS 修正后的受限复验已通过当前本机线路的该 HTTP/IP 地址完成。HTTP 为明文传输，不是 HTTPS。
- 域名、DNS、HTTPS/443、证书、防火墙/云安全组未在本轮完成；443 仍由现有服务占用，后续不得直接抢占。已完成的是原 POST 9 PASS / 1 FAIL 与 post-css-r1 6 PASS / 0 FAIL 的有界结果，未覆盖浏览器视觉/交互、国内各网络与全网线路、HTTPS、动态故障回退或重启实测，故不得表述为全站或全网验收通过。原 PRE 的错误/失败记录保持原样，未倒填为通过。
- 本批只做官网事实收尾：`publicRelease` 与 `repositoryUrl` 仍为 `null`，未开放下载/APK/模型/源码/词库/学习数据，Android 与平板侧未改动。
- 服务器资源有限，本方案使用系统 nginx 直接服务静态文件（线上现为七个：四个页面与三份共享资源），不引入 Docker、Node、数据库、CDN 或额外运行时。

## 发布状态与文案

页面只有三种发布状态，且只由 `publicRelease` 的校验结果决定：

- `publicRelease: null`：准备中，显示“公开测试版准备中 / 暂未开放下载”。
- `publicRelease` 非空但任一条件不满足：配置不完整，显示“发布信息待确认 / 暂未开放下载”，不渲染下载链接。
- `publicRelease` 全部条件满足：下载区显示两个独立的 GitHub Release 页面入口，hero 提示和页脚统一说明发布渠道。

`content.js` 中的 `releaseStatus` 只提供中文文案，不参与校验。`app.js` 根据同一个状态更新各页的 `meta[name="description"]`、运行要求末项、下载区、hero 提示和页脚（若该页存在对应区块）。`open` 只表示本地配置满足规则，不证明线上站点已更新或目标附件已重新核验。

`description` 只写稳定产品简介，不写发布状态；状态句放在 `releaseStatus` 中。运行要求的前三项是稳定内容，第四项由当前状态的 `requirement` 生成。状态文案中的 `{name}`、`{version}` 会由页面替换为站点名和已校验的版本号。

## 上线时需要填写的字段

当前上线的 `publicRelease` 如下。替换为新版本前，先独立确认两个设备的 Release 页面和附件：

```js
publicRelease: {
  version: '2.9.0',
  versionCode: 56,
  phoneReleaseUrl: 'https://github.com/jiangnanqing188/shici-android/releases/tag/v2.9.0-phone-test',
  tabletReleaseUrl: 'https://github.com/jiangnanqing188/shici-android/releases/tag/v2.9.0-tablet-test',
  channel: 'test'
}
```

页面只在配置完整且有效时渲染下载链接，必须同时满足：

- `version` 为三段数字版本号。
- `versionCode` 为正整数。
- 两个 URL 分别精确匹配该仓库的 `releases/tag/v<版本>-phone-test` 和 `releases/tag/v<版本>-tablet-test` HTTPS 页面。拒绝其他仓库、HTTP、附件直链、相对路径、凭据、查询串、片段及设备错配。
- `channel` 为 `test`（当前只有公开测试包）。

任一条件不满足时，两个设备入口均不渲染链接，不回退到旧同源 APK。发布说明、附件大小和摘要从对应 GitHub Release 查看，不复制到 HTTP 官网作独立核验。

`repositoryUrl` 仍为 `null`，不额外开启页脚仓库入口；该字段如启用，只允许同一个已知仓库的 HTTPS 首页。站点不会主动请求 GitHub。`internalVersion` / `internalVersionCode` 仍为原开发参考 2.6.2 / 53，与发布入口的版本校验独立。平台和发布状态文案分别维护在 `platforms` 与 `releaseStatus`。
