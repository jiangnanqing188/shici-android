# 拾词官网（私有开发目录）

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

- `index.html`：页面入口和语义结构。
- `styles.css`：页面样式与响应式布局，含纸张暖白、深墨绿主色和界面示意。
- `content.js`：全部简体中文产品文案和发布配置，只声明 `window.SHICI_SITE`；稳定产品简介与分状态文案分开存放。
- `app.js`：读取 `window.SHICI_SITE`，把 `publicRelease` 校验为唯一发布状态，并据此统一渲染简介状态句、运行要求、下载区、hero 提示、meta description 和页脚；`repositoryUrl` 仍按 `null` 处理。

入口按顺序先加载 `content.js`，再加载 `app.js`。修改文案时只改 `content.js` 中对应字段，不把事实写死在 HTML 或 JS 里。

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

不要在开发仓库根目录直接运行 `python3 -m http.server`：那会把整个私有开发仓库作为静态目录、默认绑定所有网络接口，可能意外暴露源码。上述命令只用于本地预览。公网侧只允许下述 H2 站点使用 `website` 中的四个静态文件；不要把开发仓库根目录、`website` 整个目录或任何运维工件交给 Web 服务器。

## 香港 H2 临时部署（HTTP + 公网 IP）

### 范围与现状

- 用户已授权在香港 H2（Ubuntu 24.04）部署现有静态官网。没有域名、暂无购买预算，本轮先通过服务器公网 IP 的 HTTP 80 访问；域名和 HTTPS 后续另做。
- 只公开四个文件：`index.html`、`styles.css`、`content.js`、`app.js`。它们是网站唯一可公开内容，精确复制到 `/srv/shici-website/www`；不上传或服务开发仓库根目录，也不把 `website/README.md`、`website/deploy/`、`.git` 或任何其他文件放进站点根目录。
- 现有 443 端口由服务器上的其他服务（sing-box）占用，本轮不改动 443、不代理该服务、不改 DNS、不改防火墙或云安全组。
- 不开放 APK、公开仓库、模型、词库或学习数据下载；`content.js` 的 `publicRelease` 与 `repositoryUrl` 继续保持 `null`，页面仍显示暂未开放下载。
- 本轮已完成 HTTP 80 临时站的首次部署（2026-10-26 11:43 安装成功、exit 0），站点可通过服务器公网 IP 访问。原首次 POST 复验 9 PASS / 1 FAIL 保留；唯一失败的 `styles.css` 显式 charset 缺口已修正，并经 post-css-r1 受影响有界复验 6 PASS / 0 FAIL 闭环。`nginx -t` 存在非阻断的 `duplicate MIME type "text/html"` warning，真实 exit 0；本批如实记录，不额外改配置消警告。安装终端回执为 completed/exit 0 且未标记截断，但声明字符数与返回正文长度存在差异，因此不声称逐字节完整终端流。该有限闭环不等于全部验收通过，也不代表页面已经开放下载。

### 服务器路径

- 站点根目录：`/srv/shici-website/www`（仅四个静态文件，root:root，目录 755、文件 644）。
- Nginx 站点配置：`/etc/nginx/sites-available/shici-website`。
- 唯一启用链接：`/etc/nginx/sites-enabled/shici-website`。
- 运维工件（本目录的 `README.md`、`deploy/nginx.conf`、`deploy/install.sh`）只用于打包和部署，不复制进站点根目录，Web 不可访问。

### 部署工件与前置

`website/deploy/` 只包含两个部署工件：

- `nginx.conf`：HTTP 80 默认站的 Nginx 配置。`server_name _`，同时监听 IPv4/IPv6 的 80，不监听 443，不代理其他服务；`root` 只指向 `/srv/shici-website/www`；只允许 `GET`/`HEAD`，只公开 `/`、`/index.html`、`/styles.css`、`/content.js`、`/app.js` 五个 URL，其他路径返回 404，关闭目录列表；显式声明 HTML/CSS/JS 三种 Content-Type，并通过 `charset utf-8` 与 `charset_types text/html text/css application/javascript` 为这三种响应附加 `charset=utf-8`，不把 MIME 覆盖成同一类型。
- `install.sh`：供 root 在已上传的 bundle 目录内手动执行一次的首次部署脚本，不自带联网逻辑（安装 nginx 时由 `apt-get` 访问系统软件源），不读取密钥或代理凭据，不硬编码服务器 IP。装包前先建立仅针对 nginx.service 的可恢复启动抑制，`nginx -t` 通过后才解除并首次启动。本轮首部署把 nginx 与 nginx-common 固定为已审版本 `1.24.0-2ubuntu7.18`，版本不可用即明确失败，不降级、不替换。请用 `bash install.sh` 执行，不依赖可执行位。

父侧打包 bundle 时必须包含且只校验以下文件：

```text
index.html
styles.css
content.js
app.js
nginx.conf
SHA256SUMS
```

`install.sh` 也需随 bundle 一同上传以供执行，但不进入 `SHA256SUMS`。

`SHA256SUMS` 由父侧生成，必须恰好覆盖上述五个文件（不含 `install.sh` 自身），脚本会拒绝额外文件名、重复条目、非常规文件和哈希不匹配。可在 bundle 目录内这样生成：

```bash
sha256sum index.html styles.css content.js app.js nginx.conf > SHA256SUMS
```

bundle 通过安全通道上传到服务器后，在该目录内以 root 执行：

```bash
bash install.sh
```

脚本前置检查包括：uid 为 0；系统为 Debian/Ubuntu 且 systemd 正在运行；bundle 五个文件与 `SHA256SUMS` 完整匹配；未安装 nginx/apache/caddy，`/etc/nginx`、nginx systemd 单元/`nginx.service.d` 抑制目录及同名部署路径均不存在；TCP 80 在 IPv4/IPv6 上均无监听。任一条件不满足即中止，不覆盖已有内容；无法确认能建立启动抑制时不安装 nginx。

### 脚本动作

1. **装包前**创建仅针对本批全新 nginx.service 的可恢复启动抑制：`/etc/systemd/system/nginx.service.d/10-shici-website-deploy-hold.conf` 中的 `ConditionPathExists=!.../shici-website-deploy.hold` 加标记文件。标记存在期间，apt 的 postinst 即使请求启动 nginx，该单元也会被 systemd 跳过；不写 `policy-rc.d`、不 mask 单元、不改全局启动策略，也不覆盖任何已有 systemd 对象。若已有 `nginx.service`、该 drop-in 目录或同名文件，脚本直接中止。
2. 执行 `apt-get update`，再执行 `apt-get install -y --no-install-recommends nginx=1.24.0-2ubuntu7.18 nginx-common=1.24.0-2ubuntu7.18`。这两个版本是本轮首部署前已审的 Ubuntu 24.04 软件源版本：`apt-get update` 后候选即使漂移也只安装该准确版本，版本不可用即明确失败，不降级、不替换；这是本批的版本锁定，不是永久更新策略。该次 apt 调用仅在本进程内使用 `NEEDRESTART_MODE=l`，避免 needrestart 自动重启既有服务（含占用 443 的服务），不写全局策略。这是系统软件源中的现有软件包，不购买新服务；安装前会再次确认启动抑制仍在，安装后复核两个包的实际版本确为上述版本。
3. 装包后复核抑制文件仍在、`nginx` 未被启动，且 80 监听（若已出现）只属于本次新装 nginx、`sites-enabled` 没有 `default` 以外的站点、`conf.d` 没有额外配置；否则中止。未知站点/配置保留不删。
4. 仅当 `sites-enabled/default` 是指向标准 `/etc/nginx/sites-available/default`、且该文件属于 nginx 软件包的符号链接时，移除该链接，避免与本站的 `default_server` 冲突；不删除不明配置。
5. 创建 `/srv/shici-website/www`，只复制四个静态文件并逐字节核对。
6. 安装 `sites-available/shici-website` 和唯一启用链接 `sites-enabled/shici-website`。
7. 执行 `nginx -t`；通过后先删除本批启动抑制并 `daemon-reload`，再启动 nginx（这是本批首次可能公开页面的时刻），随后 `systemctl enable nginx`。最终用本机 `ss` 确认 80 由 nginx 监听。

因此装包期间不会出现标准 `default` 站短暂公开的窗口：在站点只有四个静态文件、站点配置已就位且 `nginx -t` 通过之前，本批 nginx 不会被 systemd 启动。

脚本不做外网 HTTP 请求测试、不测试页面功能、不修改 443/防火墙/云安全组、不绑定域名、不申请证书、不发布安装包或数据。

### 失败回退

任何一步失败时脚本以非 0 退出，并只回退本批创建的对象：

- **先停止并取消自启**：按实际安装结果（而非“安装成功”标记）判断本批是否引入了 nginx，包括 apt 部分失败。若已运行则 `systemctl stop nginx`，并对本批 nginx 主进程做兜底 TERM/KILL；随后 `systemctl disable nginx`，清理仅指向 nginx 软件包单元的启用链接，并复查 `is-active` 与 `is-enabled`。状态查询失败、空输出或未知取值不会被当成“已停止/未启用”，而是视为无法确认并保留启动抑制；`systemctl disable` 或 `daemon-reload` 执行失败同样不视为安全。不卸载软件包、不停止其他服务。
- 移除本批创建的启用链接、站点配置和 `/srv/shici-website` 静态目录：启用链接需目标与创建时记录的 inode 均一致；站点配置与四个静态文件逐文件比对 `SHA256SUMS` 中的本批哈希，内容不一致、已被替换或不是常规文件的保留并告警；目录用 `rmdir` 且仅在为空时删除。
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
- 服务器资源有限，本方案使用系统 nginx 直接服务四个静态文件，不引入 Docker、Node、数据库、CDN 或额外运行时。

## 发布状态与文案

页面只有三种发布状态，且只由 `publicRelease` 的校验结果决定：

- `publicRelease: null`：准备中，显示“公开测试版准备中 / 暂未开放下载”。
- `publicRelease` 非空但任一条件不满足：配置不完整，显示“发布信息待确认 / 暂未开放下载”，不渲染下载链接。
- `publicRelease` 全部条件满足：有效公开测试版，下载区、hero 提示和页脚统一显示已开放。

`content.js` 的 `releaseStatus.preparing`、`releaseStatus.incomplete`、`releaseStatus.open` 只是上述三种状态的中文文案来源，不参与状态判定。`app.js` 先校验 `publicRelease`，再用同一个状态选择文案，并同时更新稳定简介后的状态句、运行要求末项、下载区、hero 下载提示、`meta[name="description"]` 和页脚说明，避免同页出现“已开放下载”与“尚无公开发行包／暂未开放下载”并存。不要把 `open` 文案当作已发布的证据，也不要通过只改状态文案来制造可下载状态。

`description` 只写稳定产品简介，不写发布状态；状态句放在 `releaseStatus` 中。运行要求的前三项是稳定内容，第四项由当前状态的 `requirement` 生成。状态文案中的 `{name}`、`{version}` 会由页面替换为站点名和已校验的版本号。

## 上线时需要填写的字段

目前 `content.js` 中：

- `publicRelease` 必须保持 `null`，页面会显示“公开测试版准备中 / 暂未开放下载”，不出现空链接或假下载。
- `repositoryUrl` 必须保持 `null`，页面不渲染公开仓库入口；只有公开下载仓库真正创建并获批准后才填写真实地址。
- `internalVersion` 当前为 `2.2.1`，只是内部版本参考，不代表已有公开发行包。
- `description` 只写稳定产品简介；三个状态的状态句、运行要求末项和页脚说明都在 `releaseStatus` 中维护。

将来有经过确认的公开测试包时，只改 `content.js` 的发布配置字段；三种状态的文案已在 `releaseStatus` 中准备好，不需要改 `app.js` 或逐处追改页面文案：

```js
publicRelease: {
  version: '版本号',
  versionCode: 1,
  url: 'https://真实下载地址',
  sha256: '64 位十六进制 SHA-256',
  sizeLabel: '安装包大小',
  releaseNotesUrl: 'https://真实更新说明地址',
  channel: 'test'
}
```

页面只在配置完整且有效时渲染下载链接，必须同时满足：

- `version` 非空。
- `versionCode` 为正整数。
- `sha256` 为 64 位十六进制字符串。
- `sizeLabel` 非空。
- `url` 为绝对 `http:` 或 `https:` 地址，不能是 `#`、空值、相对路径或带用户名密码的地址。
- `channel` 为 `test`（当前只有公开测试包）。

任一条件不满足时，页面不渲染下载链接，显示“发布信息待确认 / 暂未开放下载”，不会把占位或残缺配置当作可下载版本。`releaseNotesUrl` 为可选字段，同样只接受绝对 `http:` 或 `https:` 地址。

`repositoryUrl` 只填写公开下载仓库的绝对 `http:` 或 `https:` 真实地址，页面只展示链接、不主动请求。不要使用示例域名、占位下载地址或未创建的仓库链接。正式接入前需要确认签名发布方式、包内容与许可、下载托管位置、SHA-256 和公开包状态；这些尚未确定。官网页面本身不发起网络请求；本轮只做上述 HTTP 80 静态站部署，不改变下载与发布状态。正式开放下载前仍需逐项核对页面事实。
