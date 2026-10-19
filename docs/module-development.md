# 卡片模块开发指南

模块是一个 ZIP 包，同时包含**背屏卡片**和**主屏设置网页**两部分。用户安装模块、绑定一个本地卡片载体、在系统背屏设置中重新应用后，卡片即可运行；卡片可以自行联网、保存数据，无需为每张卡片开发独立的 Android App。

## 1. 模块包结构

```text
weather.zip
├── module.json        # 必需：模块描述
├── icon.png           # 可选：模块图标
├── card/              # 必需：背屏卡片
│   ├── index.html
│   ├── main.js
│   └── assets/        # 可选：包内图片等静态资源
├── background/        # 可选：桥内后台刷新
│   └── main.js        # 提供 async function refresh()
└── webroot/           # 可选：设置网页；存在时必须有 index.html
    ├── index.html     # 第一个脚本必须引入 <script src="bridge-shim.js"></script>
    └── ...
```

`card/` 的编写规则见 [背屏应用卡开发指南](app-card-development.md)：它是 QuickJS + Canvas，不是普通网页。`card/` 下的 JS 源码可以直接用原生 `async / await` 书写（源码保持 async/await，不要手工改写成 then 链），但**打包进 ZIP 前必须用构建工具编译**，见 [发布与安装](#6-发布与安装)。`webroot/` 是运行在 WebView 里的正常网页，可以用任意前端技术，但要提前构建成静态文件——安装时不会执行 npm 或构建脚本。

### module.json

```json
{
  "id": "dev.example.weather",
  "name": "天气",
  "version": "1.0.0",
  "versionCode": 1,
  "apiVersion": 2,
  "description": "显示指定城市的天气",
  "author": "Example",
  "capabilities": ["network", "storage", "files"],
  "background": { "intervalMinutes": 15 }
}
```

| 字段 | 规则 |
| --- | --- |
| id | `^[A-Za-z][A-Za-z0-9._-]{0,63}$`，更新时按 id 识别同一模块 |
| versionCode | 正整数；同版本可直接覆盖，低于已安装版本时须由用户在前台确认降级。覆盖安装保留配置和业务数据 |
| apiVersion | 固定为 2 |
| capabilities | 只允许 `network` / `storage` / `files`；声明后对应的 API 才可用 |
| background | 可选；`intervalMinutes` 为 ≥ 1 的整数，作为默认间隔；实际执行最短 5 分钟，用户可在模块详情单独修改。必须提供 `background/main.js`；未声明的模块不参与后台刷新 |

`config` 操作（`bridge.config.*`）与日志不需要能力声明。

## 2. 基础 API

卡片（QuickJS）、设置页和后台刷新入口（WebView）共用 `bridge.config`、`bridge.storage`、`bridge.files`、`bridge.net` 和卡片联动操作，数据共享。桥接读写方法返回 Promise；失败时 reject 一个带 `code` 的 Error。设置页普通请求可直接使用 WebView 的 `fetch` / `XMLHttpRequest`；三端需要共享网页登录状态时使用 `bridge.net.request({ cookies: true, ... })`。

设置页注意：在 `webroot/index.html` 里**第一个**引入桥接脚本：

```html
<script src="bridge-shim.js"></script>
```

直接用手机浏览器打开设置页调试时 `bridge` 为 `undefined`，请先判断。

### 模块信息

```js
const info = await bridge.module.info();
// { id, name, version, versionCode, apiVersion, hasWebui }
```

### 配置（设置页写入，卡片读取）

```js
await bridge.config.set("city", "深圳");
const city = await bridge.config.get("city");       // 单个键，不存在时为 null
const all = await bridge.config.get();              // 省略参数返回整个配置对象

bridge.config.onChange(function (config) {
  // 任一端修改配置后，两端都会收到通知；卡片通常在这里重新拉取数据并重绘
});
```

### 业务存储（JSON 键值）

需要 `storage` 能力。保存请求结果、打卡记录等；跨模块更新保留。

```js
await bridge.storage.set("lastWeather", data);
const cached = await bridge.storage.get("lastWeather");
await bridge.storage.remove("lastWeather");
const keys = await bridge.storage.keys();
```

### 批量请求与同步结果体积

同步操作（`storage.get`、`files.readText`、`config.*` 等）的结果会随当前批次的 `sync_results` 一次回传给卡片，宿主侧最终落在同一个 MAML HostVar 上。**不要把多个大值放进同一批请求**。

实际踩过的例子：四张 912×596 的 base64 自定义背景，单张约 130～200K；卡片用 `Promise.all` 一次 `storage.get` 四页，`sync_results` 约 **706K**。桥端返回后卡片没有收到结果，同一批请求每 4 秒重发一次，背景一直保持默认，后续刷新请求也排不出去。单页约 198K 的同一批读取则正常。

建议：

- 一次只读一个大对象；多页图片按当前页读取，翻页时再读并缓存，不要 `Promise.all` 四页大图；
- 单次同步结果尽量控制在 **200K 字符以内**；超过时拆成多次请求，或只取当前需要的数据；
- 更大的数据用 `bridge.files` 分块读写，或在快照里放缩小后的 Data URL；
- 如果日志里看到同一批次每隔几秒重复、请求 id 不变，通常就是结果没有送达，不要在这个循环里继续堆请求；
- 单键 `storage` 值仍有 256K 字符上限，快照有 800K 字符上限，这些限制与上面的批次体积限制是两回事。

### 模块私有文件

需要 `files` 能力。只能操作模块自己的数据目录，不能访问手机其他路径。

```js
await bridge.files.writeText("cache/weather.json", JSON.stringify(data));
const text = await bridge.files.readText("cache/weather.json");
const list = await bridge.files.list("cache");      // [{name, dir, size}]
await bridge.files.remove("cache/weather.json");
```

### 设置页网络请求（WebView）

设置页是普通网页，可以直接使用浏览器 API；仅使用 `fetch` / XHR 不要求声明原生 `network` 能力：

```js
const response = await fetch("https://example.com/api", {
  headers: { "Accept": "application/json" }
});
const data = await response.json();
```

- 本地 `webroot/index.html` 通过 `file://` 加载，开启 WebView 的文件访问、文件间访问和文件页跨来源访问；不额外安装目录白名单或文件拦截规则。模块代码受信任，能访问的文件范围仍由 Android 应用权限决定。
- HTTP(S) 请求由 WebView 直接发送，不代理到原生后端、不替换浏览器网络 API、不修改 TLS 证书校验。文件页面的跨来源访问开关用于放开远程接口的 CORS 访问。
- `fetch` 的目标地址应为 HTTP(S)；Chromium 的 `fetch` 本身不支持 `file://` / `content://` 目标，本地文件请求可以用 XMLHttpRequest。
- 设置页主框架的 HTTP(S) 导航仍交给系统浏览器，防止远程网页替换已注入原生桥接口的设置页。登录请用 `window.open(url, '_blank')`：桥会打开不注入 bridge 的独立 WebView 子窗口，设置页保持不变。子窗口只加载网页，主框架的 HTTP 跳转自动升级到 HTTPS（兼容小米 Passport 的回调），不提供文件访问；可在窗口内前进、后退，或手动关闭。`window.close()` 也会关闭子窗口。
- 超时、取消、流式读取等由页面使用浏览器 API 控制，例如 `AbortController`；原生背屏网络接口的超时和大小限制不适用于设置页。

### 网页登录与模块独立会话

每个模块使用独立的 WebView Profile，设置页、登录子窗口、后台刷新 WebView 和该模块的原生 Cookie 请求共用这个 Profile。模块更新保留会话，卸载删除会话。设备的 Android System WebView 必须支持 `MULTI_PROFILE`；不支持时显示更新 WebView 的提示，不改用全局共享 Cookie。

```js
// 设置页：登录地址由平台返回，不需要新增 bridge 登录接口。
window.open(loginUrl, '_blank');
// 用户登录并关闭子窗口后，点击设置页自己的“检查登录”按钮发起下方请求。
const res = await bridge.net.request({ url: usageUrl, cookies: true });
```

桥不自动判定平台登录是否成功；模块按真实 API 响应判断。浏览器 `fetch` 仍受 Cookie/SameSite 等浏览器规则约束，允许跨域不等于允许任意设置 `Cookie` 请求头。带登录态的设置页请求和背屏请求统一使用 `cookies: true`，无需把 Cookie 复制进模块配置或业务存储。Profile 隔离浏览器会话，不是对受信任模块代码的文件访问沙箱。

### 原生网络请求（卡片、设置页与后台刷新共用）

需要 `network` 能力。由卡片桥在后台线程执行，结果异步回传给调用方，不替换浏览器的 `fetch` / XHR。

```js
const res = await bridge.net.request({
  url: "https://example.com/api",
  method: "GET",                 // 默认 GET
  headers: { "Accept": "application/json" },
  responseType: "text",          // "text"（默认）或 "base64"
  cookies: true,                 // 默认 false；自动读写本模块 Profile 的 Cookie
  redirect: "manual",            // 默认 "follow"；manual 返回当前这一跳
  timeoutMs: 180000              // 可选；不传默认 15000，0 表示不设超时
});
// res: { status, url, headers, body } 或 { status, url, headers, bodyBase64 }
// url 是最终地址；headers 为小写名称 → 字符串数组，例如 { location: ["/login"] }
```

- `timeoutMs` 同时设置连接超时和读取等待超时，不是请求总耗时限制。未传或传 `null` 时默认 15000 毫秒；正整数原样传给 Android 网络接口，不设 60 秒等业务上限；`0` 关闭这两项超时；负数报 `INVALID_ARGS`。
- 网络超时 reject 一个 `code === 'TIMEOUT'` 的 Error，提示“结果未知”。服务端可能已经受理，尤其是付费请求，不得将超时视为未执行并自动重试。不要编写自动重试该请求的逻辑。
- `cookies: true` 时，每一跳发送 URL 匹配的 Cookie，并在处理跳转前保存该跳所有 `Set-Cookie`；手动跳转模式也会保存。此时不要同时传自定义 `Cookie` 请求头。`cookies: false` 不读取或写入 Profile。
- `redirect: "manual"` 返回 3xx 状态和 `Location`，模块可像 MiMo 一样校验地址、把平台返回的 HTTP 地址主动改为 HTTPS 后再发下一跳。`follow` 最多跟随 8 次跳转；跨来源会移除调用方的 Authorization/Cookie 等凭据头，HTTPS 降级到 HTTP 会报错。301/302 的 POST 和 303 的非 HEAD 请求变成 GET，307/308 保留方法和正文。自动跳转不是网络失败重试。
- 响应头包含 `Set-Cookie`，因此不要把整段 headers 或包含签名参数的地址写入日志。TLS 校验保持系统默认，不接受无效证书。
- `POST` 等带请求体时传 `body`（字符串）或 `bodyBase64`。
- `responseType: "json"` 不存在：文本响应用 `JSON.parse(res.body)` 即可。
- 限制：请求体 ≤ 1MB，响应 ≤ 8MB。不要在背屏上做大文件下载。

### 刷新联动

```js
// 设置页：用户点“立即刷新”
await bridge.card.requestRefresh();

// 卡片：收到刷新事件
bridge.card.onRefresh(function () { refresh(); });
```

### 息屏常显快照（卡片必做）

系统每次从息屏恢复常显时都会重建背屏 JS 运行环境，并且只截取最初一两帧，之后就停帧。等桥接返回再画的内容不会出现在常显画面上。卡片必须在脚本同步执行阶段画出上次的内容：

```js
// 启动时同步读取并立即绘制，放在任何 await 之前
const snapshot = bridge.card.readSnapshot();   // 没有快照时返回 null
if (snapshot) render(snapshot);

// 拿到新数据并画完后保存
bridge.card.saveSnapshot({ profile, images });
```

- 卡片端仍可**同步**读取和更新本地快照：`readSnapshot()` 读取本地缓存，`saveSnapshot()` 立即更新缓存，同时异步把同一份快照提交给桥；内容变化时可 await 返回的 Promise 等待桥保存完成。桥保存的是前台和后台共用的快照，不是另一份业务存储。
- 主屏设置页也可调用 `await bridge.card.saveSnapshot(value)`：返回 Promise，成功后快照已写入桥，再调用 `await bridge.card.requestRefresh()` 通知背屏读取新数据。设置页不提供同步 `readSnapshot()`。
- 背屏读取脚本的同一次载体数据查询会带回最新快照；运行时在执行模块脚本之前同步更新 `localStorage`，首帧无需等待额外的 JS 桥接请求。后台保存的新快照会在宿主下次实际读取载体数据时生效，不主动唤醒背屏。
- 值必须能被 JSON 序列化，桥端单份快照限 800K 字符；图片请缩放后再转 Data URL。内容和已存快照相同时不会重复写入。
- **只有 Data URL 图片能赶上首帧。** 包内资源路径和本地文件路径的图片由宿主在后台线程解码，回调发生在帧之后，常显的最初一两帧等不到。
- JS 沙箱没有同步调用桥的入口：`files.readText` 等操作的结果都要等排队的批次返回，因此异步读到的图片同样进不了首帧。首帧数据只能来自 `readSnapshot()`。
- 上限是实测值：载体 Provider 能返回约 2M 字符的 payload，但宿主 JS 运行时在 1M 字符时报 `InternalError: out of memory`，900K 仍可解析。桥因此把快照限制在 800K，并在保存时直接报错，避免运行期才失败。
- Data URL 图片可以放进快照，同步阶段设置的图片在常显中能正常显示；体积尽量按显示尺寸控制。
- 快照格式由模块自己决定，脚本更新后可能读到旧格式，使用前自行校验。
- 已有载体需要更新载体资源，并到系统背屏设置移除旧卡、重新应用一次，才能使用共享快照链路。

### 后台刷新入口

在模块描述中声明 `"background": { "intervalMinutes": 15 }`，并提供 `background/main.js`：

```js
// 后台只取数并保存与 card/main.js 相同格式的快照，不进行背屏绘制。
async function refresh() {
  const city = await bridge.config.get("city") || "深圳";
  const response = await bridge.net.request({
    url: "https://example.com/weather?city=" + encodeURIComponent(city)
  });
  if (response.status !== 200) throw new Error("天气请求失败：" + response.status);
  const weather = JSON.parse(response.body);
  await bridge.card.saveSnapshot({ city, weather, updatedAt: Date.now() });
}
```

- 用户在桥的「设置与关于」开启后台刷新，在模块详情分别设置间隔（最短 5 分钟）。模块声明仅是默认值，低于 5 分钟时按 5 分钟执行；更新模块保留用户设置，卸载时清除。所有模块共用一个 `JobScheduler` 单次任务，按最早到期时间安排，任务中串行执行已到期模块。首次无需等待间隔，但仍由系统择机运行；之后每次尝试完成后等待所选间隔，失败也不立即重试。
- 每次任务创建独立 WebView JS 环境，加载入口后调用 `refresh()`，完成后销毁；周期由 Kotlin 调度，不依赖页面的 `setInterval`。不要把卡片的 Canvas 绘制脚本直接当成后台入口。可以在模块自己的构建流程中复用取数和快照生成代码，输出自包含入口。
- 后台提供 `bridge.module`、`config.get/set`、`storage`、`files`、`net.request`、`log` 和 `card` 的快照读写/刷新请求。能力声明与卡片端相同。后台 `readSnapshot()` 同步读取任务开始时的快照；**后台 `saveSnapshot()` 返回 Promise，必须 await**，成功返回后快照已写入桥。
- `refresh()` 必须等待本次所有异步工作完成，不要启动不等待的任务。单次执行最多 60 秒；失败不会清除已保存的快照，并记录日志，不立即重试请求，等待下一周期。
- 任务串行运行，不会堆积同一模块的并发刷新。模块更新、卸载会在后续调度中重新读取；正在执行的任务使用本次加载的脚本。
- 它更新的是快照，不保证背屏每个周期重绘。宿主休眠时卡片 JS 不运行，等宿主自行重载/查询时读取最新快照。系统省电可能延迟后台执行。
- 不使用常驻 `dataSync` 前台服务、不需要通知权限，也不受该类型的 6 小时运行限额约束。任务要求联网，可被系统省电、休眠或任务配额延后，不保证每 5 分钟准点执行；不使用精确闹钟，也不申请不受电池优化限制。等待期间没有运行中的服务或 WebView，不持有应用唤醒锁；执行期间的 CPU 唤醒由系统管理，任务结束即释放。任务计划跨重启保留；强行停止应用后需重新打开才能恢复。关闭开关取消任务；没有后台模块或系统未接受任务时显示原因，开关设置仍保留。

### 日志

```js
bridge.log.info("已刷新");   // warn / error 同理
```

输出到 logcat，tag 为 `CardModule.<模块id>`。

## 3. 动态图片

桥能下载图片内容，卡片用 Data URL 显示，**不需要重新应用载体**：

```js
const res = await bridge.net.request({ url: iconUrl, responseType: "base64" });
const image = new Image();
image.onload = requestDraw;
image.src = "data:image/png;base64," + res.bodyBase64;
```

注意按显示尺寸准备图片：Base64 会放大约 1/3 体积，且每帧解码大图有开销。

## 4. 静态资源与“重新应用”

| 改动 | 是否要重新应用载体 |
| --- | --- |
| 配置、存储、网络数据、Data URL 图片 | 否 |
| 脚本内容（热更新） | 否 |
| `card/assets/` 里的包内图片等静态资源 | **是**：更新载体后需到背屏设置移除旧卡并重新应用 |

系统运行的是应用时复制出去的载体；热更新只能替换脚本，不能替换载体包里的文件。

## 5. 生命周期须知

- 背屏休眠时卡片不运行。休眠期间**不会**定时执行卡片 JS；桥的后台刷新入口可独立取数并保存快照，卡片恢复显示时同步读取最新快照。未提供后台入口的模块仍需在恢复显示时按时间戳决定是否重新拉取。
- 卡片脚本热更新会重新执行脚本；进行中的异步请求会被取消（Promise 不再 resolve）。
- 设置页销毁后不再向该页面回调；关闭页面不会撤销服务端已受理的请求。
- 配置和业务数据保存在卡片桥中，卸载模块会一并删除。
- 不要把访问令牌、密码写进模块包或日志。

## 6. 发布与安装

### ZIP 安装（先编译再打包）

背屏 `card/` 脚本直接用原生 `async / await` 编写，但打成 ZIP 前**必须**用仓库内的构建工具把 `card/` 下的 JS 编译成纯 JS：宿主 QuickJS 运行时不驱动原生 Promise 微任务，`await` 必须编译为生成器后，才能由运行时提供的全局 Promise 正确执行。

```sh
# 需要 Node 22.18+（或 24.11+）；首次安装锁定版本的依赖
cd tools/card-build
npm ci

# <模块源目录> 的根目录直接是 module.json，<输出 ZIP> 是要安装的包
node bin/card-build.js <模块源目录> <输出.zip>
```

在仓库根目录也可以直接 `node tools/card-build/bin/card-build.js <模块源目录> <输出.zip>`。工具自带回归测试：`cd tools/card-build && npm test`。

编译与打包规则：

- 只有 `card/` 下的 `.js` 会交给 Babel 的 `@babel/plugin-transform-async-to-generator` 编译：输出是纯 JS（生成器 + 全局 Promise），不打包、不加 preset-env、不做 AST / 安全分析；语法错误会报出**出错文件**并使构建失败，失败时不写出 ZIP。
- 源码保持原生 async/await；ZIP 里放的是编译后的纯 JS，运行时通过新运行时在执行模块脚本前注入的全局 `window.Promise` 执行（Babel 生成的 helper 直接使用该全局 Promise）。
- `module.json`、`icon.png`、`card/` 非 JS 文件、`webroot/**` 和 `background/**` 按**原始字节**打包；`webroot/` 与 `background/` 运行在 WebView，保持原生 `async / await`，完全不编译。
- 只收录 `module.json`、`icon.png`、`card/`、`webroot/`、`background/` 这些模块根；源目录里的 `node_modules`、构建工具、仓库杂物不会进 ZIP。ZIP 的 CRC 由 ZIP 库管理，工具不计算任何自定义哈希或摘要。
- 旧运行时兼容：编译产物依赖新运行时注入的 Promise。已安装的**旧运行时**没有该注入，无法运行新产物——更新运行时后必须重新构建 ZIP 并**重新生成载体**（更新模块资源、到系统背屏设置重新应用）。
- 构建工具只在 Node 环境下验证编译与打包；真机背屏运行与联网行为由应用侧另行验证。

把编译好的 ZIP（根目录直接是 module.json，不要多套一层文件夹）通过「安装 ZIP 模块」选择文件。

### Git 仓库安装

仓库根目录放 module.json 与 card/、webroot/。用户输入仓库地址即可安装；支持 GitHub / GitLab / Gitee 公开仓库，可指定分支（地址里的 `/tree/<branch>` 或单独填写，默认 main）。仓库里的 card/ 脚本也必须是用 `tools/card-build` 编译后的纯 JS 再推送——手机上不执行构建；设置网页必须是构建产物，手机上不执行构建。

同一 id 再次安装即覆盖；同版本可直接安装，低版本需要用户确认降级。覆盖安装保留配置与业务数据，替换包内容。

### 商店上架

将构建好的模块 ZIP 发布到自己的 GitHub Releases，再向[商店仓库](https://github.com/finall1008/rear-card-modules)提交目录条目。完整操作、字段示例、多图预览和版本更新规则见[模块商店上架与更新](<module-shop.md>)。用户可从[网页商店](https://finall1008.github.io/rear-card-modules/)浏览，或在 App「商店」直接安装、更新。

## 7. 最小示例

见 [samples/hello-module](../samples/hello-module/)：一个带设置页的计数卡片，演示 config、storage、net 与刷新联动。卡片脚本源码使用原生 async/await，打包前按 [发布与安装](#6-发布与安装) 用 `tools/card-build` 编译。
