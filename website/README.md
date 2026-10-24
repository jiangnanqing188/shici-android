# 拾词官网（私有开发目录）

本目录是“拾词”介绍与下载落地页的源文件，只介绍软件，不介绍源码。原开发仓库 `jiangnanqing188/shici-android` 保持私有；将来另建的公开仓库只用于发布下载，不是源码仓库。本目录目前不部署，也没有确定域名。

## 目录与加载顺序

网站是纯静态目录，没有构建步骤，不需要 npm，也不使用远程字体、CDN、分析追踪或网络请求。

- `index.html`：页面入口和语义结构。
- `styles.css`：页面样式与响应式布局，含纸张暖白、深墨绿主色和界面示意。
- `content.js`：全部简体中文产品文案和发布配置，只声明 `window.SHICI_SITE`；稳定产品简介与分状态文案分开存放。
- `app.js`：读取 `window.SHICI_SITE`，把 `publicRelease` 校验为唯一发布状态，并据此统一渲染简介状态句、运行要求、下载区、hero 提示、meta description 和页脚；`repositoryUrl` 仍按 `null` 处理。

入口按顺序先加载 `content.js`，再加载 `app.js`。修改文案时只改 `content.js` 中对应字段，不把事实写死在 HTML 或 JS 里。

## 本地预览

本地预览只服务 `website` 目录，并只绑定回环地址 `127.0.0.1`。在开发仓库根目录运行：

```bash
python3 -m http.server 8000 --bind 127.0.0.1 --directory website
```

然后打开 `http://127.0.0.1:8000/`（根 URL 就是网站首页）。也可以先进入本目录再运行 `python3 -m http.server 8000 --bind 127.0.0.1`，两种方式访问地址相同。

不要在开发仓库根目录直接运行 `python3 -m http.server`：那会把整个私有开发仓库作为静态目录、默认绑定所有网络接口，可能意外暴露源码。本目录只是本地预览，当前不使用公网地址，也不部署到任何服务器；不要将开发仓库根目录对外提供服务。

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

`repositoryUrl` 只填写公开下载仓库的绝对 `http:` 或 `https:` 真实地址，页面只展示链接、不主动请求。不要使用示例域名、占位下载地址或未创建的仓库链接。正式接入前需要确认签名发布方式、包内容与许可、下载托管位置、SHA-256 和公开包状态；这些尚未确定。官网当前不联网、不部署，发布前仍需逐项核对页面事实。
