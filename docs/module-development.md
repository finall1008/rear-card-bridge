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
└── webroot/           # 可选：设置网页；存在时必须有 index.html
    ├── index.html     # 第一个脚本必须引入 <script src="bridge-shim.js"></script>
    └── ...
```

`card/` 的编写规则见 [背屏应用卡开发指南](app-card-development.md)：它是 QuickJS + Canvas，不是普通网页。`webroot/` 是运行在 WebView 里的正常网页，可以用任意前端技术，但要提前构建成静态文件——安装时不会执行 npm 或构建脚本。

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
  "capabilities": ["network", "storage", "files"]
}
```

| 字段 | 规则 |
| --- | --- |
| id | `^[A-Za-z][A-Za-z0-9._-]{0,63}$`，更新时按 id 识别同一模块 |
| versionCode | 整数，**更新安装时必须大于已安装版本**，否则拒绝 |
| apiVersion | 固定为 2 |
| capabilities | 只允许 `network` / `storage` / `files`；声明后对应的 API 才可用 |

`config` 操作（`bridge.config.*`）与日志不需要能力声明。

## 2. 基础 API

卡片（QuickJS）和设置页（WebView）暴露**同一个 `bridge` 对象**，语义一致、数据共享。所有读写方法返回 Promise；失败时 reject 一个带 `code` 的 Error。

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

### 模块私有文件

需要 `files` 能力。只能操作模块自己的数据目录，不能访问手机其他路径。

```js
await bridge.files.writeText("cache/weather.json", JSON.stringify(data));
const text = await bridge.files.readText("cache/weather.json");
const list = await bridge.files.list("cache");      // [{name, dir, size}]
await bridge.files.remove("cache/weather.json");
```

### 网络请求

需要 `network` 能力。由卡片桥在后台线程执行，结果异步返回。

```js
const res = await bridge.net.request({
  url: "https://example.com/api",
  method: "GET",                 // 默认 GET
  headers: { "Accept": "application/json" },
  responseType: "text",          // "text"（默认）或 "base64"
  timeoutMs: 15000               // 1s–60s
});
// res: { status: 200, body: "..." } 或 { status, bodyBase64 }
```

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

- 背屏休眠时卡片不运行。休眠期间**不会**定时执行 JS；卡片恢复显示时应检查数据时间戳决定是否重新拉取。
- 卡片脚本热更新会重新执行脚本；进行中的异步请求会被取消（Promise 不再 resolve）。
- 配置和业务数据保存在卡片桥中，卸载模块会一并删除。
- 不要把访问令牌、密码写进模块包或日志。

## 6. 发布与安装

### ZIP 安装

把上述结构打成 ZIP（根目录直接是 module.json，不要多套一层文件夹），用户通过「安装 ZIP 模块」选择文件。

### Git 仓库安装

仓库根目录放 module.json 与 card/、webroot/。用户输入仓库地址即可安装；支持 GitHub / GitLab / Gitee 公开仓库，可指定分支（地址里的 `/tree/<branch>` 或单独填写，默认 main）。设置网页必须是构建产物，手机上不执行构建。

同一 id 再次安装即更新；versionCode 不增大会被拒绝。更新保留配置与业务数据，替换包内容。

## 7. 最小示例

见 [samples/hello-module](../samples/hello-module/)：一个带设置页的计数卡片，演示 config、storage、net 与刷新联动。
