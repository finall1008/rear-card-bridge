# 背屏卡片桥

Android / Kotlin，界面使用 Miuix Compose 0.9.4。一个模块 ZIP 包含背屏卡片资源和可选的设置网页；卡片通过统一 `bridge` API 联网、读写自己的数据，不再需要每张卡片配一个 Android App。

- [模块商店](https://finall1008.github.io/rear-card-modules/)
- [模块格式与基础 API](<docs/module-development.md>)
- [模块商店上架与更新](<docs/module-shop.md>)
- [Canvas 绘制、触摸与生命周期](<docs/app-card-development.md>)
- [示例模块](<samples/hello-module/>)

## 视频教程

[▶ 在 B 站观看：背屏卡片桥使用教程](https://www.bilibili.com/video/BV12vJ962Evg/)

以「AI 用量背屏」为例，演示 Shizuku 授权、模块安装、下载新载体、刷新并绑定、模块设置，以及背屏查看效果。

## 使用流程

1. 在「设置与关于」启动/连接 Shizuku 并授权。
2. 在「商店」浏览、搜索并安装模块，也可在「主页」导入模块 ZIP 或从公开 Git 仓库安装。
3. 安装后选择实际扫描出的本地卡片作为替换目标。
4. 确认写入：应用先备份目标原始资源，再写入模块载体。
5. 到系统背屏设置移除旧卡，再从该卡片的原入口重新应用。

预设卡片和自行生成的卡片均可作为目标，前提是对应资源已经下载到本机且可写；不会注册新系统卡片，也不会模拟点击应用按钮。更换目标前须恢复原始资源，解除原绑定。

底部导航依次为「主页」「商店」「设置与关于」。商店使用原生 Compose 界面，读取 [GitHub Pages 模块目录](https://finall1008.github.io/rear-card-modules/modules.json)，从 GitHub Releases 下载对应版本 ZIP；预览图由模块仓库提供，支持左右滑动多图及点击全屏查看；已安装模块可在商店打开详情或更新。主页保留本地导入、仓库安装和已安装模块列表。点击模块可打开其设置网页、查看载体状态、更新资源或卸载；Shizuku 状态、作者和仓库信息统一放在「设置与关于」。

## 模块包

```text
module.zip
├── module.json
├── icon.png          # 可选
├── card/
│   ├── index.html
│   ├── main.js
│   └── assets/       # 可选
├── background/       # 可选：桥内后台刷新
│   └── main.js        # async function refresh()
└── webroot/          # 可选：设置网页
    └── index.html
```

同一模块由 `module.json` 的 `id` 标识，同版本可直接覆盖；低版本会弹出警告，用户确认继续后也可安装。覆盖安装保留配置和业务数据，卸载则删除这些数据。仓库安装支持 GitHub、GitLab、Gitee 的公开仓库归档；前端须预先构建为静态文件，手机上不执行安装脚本或构建脚本。

背屏端运行 MAML / QuickJS / Canvas，不是 WebView；设置页和后台刷新入口运行在卡片桥的 WebView 中。三端共用 `bridge.config`、`bridge.storage`、`bridge.files`、`bridge.net` 等数据接口。设置页普通联网用 WebView 的 `fetch` / XMLHttpRequest；网页登录用 `window.open` 打开不带桥接口的子窗口。每个模块有独立的浏览器 Profile，设置页、背屏和后台刷新均可通过 `bridge.net.request({ cookies: true, ... })` 共享该模块的登录状态，详见开发指南。

### 模块构建工具（tools/card-build）

背屏 `card/` 脚本源码直接用原生 `async / await`，但打包进 ZIP 前**必须**用仓库内的构建工具编译成生成器纯 JS：宿主 QuickJS 不驱动原生 Promise 微任务，编译产物执行依赖新运行时在执行模块脚本前注入的全局 `Promise`；`webroot/` 是浏览器页面，保持原生 `Promise` / `fetch`，完全不编译。源码保持 async/await，ZIP 里是编译后的纯 JS。

```sh
cd tools/card-build
npm ci        # Node 22.18+（或 24.11+），安装锁定版本的 Babel / fflate
node bin/card-build.js <模块源目录> <输出.zip>
# 仓库根目录等价写法：
# node tools/card-build/bin/card-build.js <模块源目录> <输出.zip>
```

工具只把 `card/` 下的 `.js` 交给 Babel `@babel/plugin-transform-async-to-generator`（不打包、不加 preset-env、不做 AST / 安全扫描）；语法错误报出出错文件并以非零状态退出。`module.json`、`icon.png`、`card/` 非 JS、`webroot/**` 与 `background/**` 按原始字节打包，只收录这些模块根，`node_modules` 与工具源码不会进包；ZIP 的 CRC 由库管理，不计算自定义哈希。运行时随载体写入：桥更新运行时后，须在模块详情更新载体并到系统重新应用。详见[模块开发指南](<docs/module-development.md>)的「发布与安装」。

## 热更新和重新应用

- 脚本更新：经只读 Provider / HostVar 热更新，无需 Shizuku。
- 配置、网络数据、Base64 Data URL 图片：走数据通道，无需重新应用。
- 包内静态图片等资源变化：需更新载体，然后在系统中移除旧卡并重新应用。
- 写入完成、打开设置和用户确认，都不代表应用检测到了系统已应用状态。

休眠期间卡片 JS 不保证运行。模块可以声明 `background: { "intervalMinutes": 15 }` 并提供后台入口，在「设置与关于」开启统一后台刷新后，由桥执行取数和 `bridge.card.saveSnapshot()`；背屏重载时 `readSnapshot()` 同步读取前台或后台保存的同一份最新快照。它不唤醒背屏，也不保证屏幕按刷新周期重绘。使用本功能前，已有载体须更新并在系统中重新应用一次。

后台刷新通过 `JobScheduler` 单次任务持续安排下一次执行，不常驻前台服务、不挂常驻通知，也不需要通知权限。模块详情中可单独修改刷新间隔，最短 5 分钟；模块声明是默认值，低于 5 分钟时按 5 分钟执行。系统可因省电、休眠、联网条件或任务配额延后执行，不保证准点。等待期间不持有唤醒锁、不保留运行中的 WebView；取数任务完成后释放，由系统安排下一次，不适用常驻 `dataSync` 的 6 小时限额。开关、用户设置及任务计划会保留，手机重启后系统恢复任务；在应用中关闭开关会取消任务。强行停止应用后需重新打开应用才能恢复调度。详见[后台刷新入口](<docs/module-development.md#后台刷新入口>)。

Shizuku 只用于扫描、写入和恢复本地载体，不参与日常模块 API 或后台刷新。

不计算内容哈希或摘要，不做脚本 AST、安全扫描、危险 API 扫描或执行验证。仅检查包格式、路径和 HTML / XML 结构。请只安装你信任的模块。

## 外部 App 导入（API 2）

外部 App 和文件选择器使用同一安装流程，**也必须提供上述模块格式 ZIP**。不再有外部内容分组选择页，也不接受裸 `index.html + main.js` ZIP。模块身份以 `module.json.id` 为准。

### 前台入口

调用方通过自己的 FileProvider 提供 ZIP，授予读取权限：

```kotlin
val intent = Intent("dev.finall.rearcardbridge.action.UPDATE_CONTENT").apply {
    setPackage("dev.finall.rearcardbridge")
    setDataAndType(packageUri, "application/zip")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    clipData = ClipData.newRawUri("module package", packageUri)
    putExtra("api_version", 2)
    putExtra("request_id", requestId)
}
launcher.launch(intent)
```

也支持 `ACTION_SEND` 的 `EXTRA_STREAM` ZIP。返回 `request_id`、`status`、`message`、`card_id`、`key`（模块 ID）、`revision`。`BINDING_REQUIRED` 表示模块已保存但尚未选择目标；`SAVED` 表示内容保存完成，不表示背屏已执行；不返回 `APPLIED`。

### 后台入口

后台调用不会启动 Activity 或替换载体。请在 IO 线程调用，并显式授予 URI 权限：

```kotlin
val bridgePackage = "dev.finall.rearcardbridge"
context.grantUriPermission(bridgePackage, packageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
try {
    val result = context.contentResolver.call(
        Uri.parse("content://$bridgePackage.update"), "update_content", null,
        Bundle().apply {
            putInt("api_version", 2)
            putParcelable("package_uri", packageUri)
            putString("request_id", requestId)
        }
    )
} finally {
    context.revokeUriPermission(bridgePackage, packageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
```

调用身份由 Binder 确定；不支持共享 UID 的模糊来源。无绑定时仍安装模块并返回 `BINDING_REQUIRED`，由用户随后打开桥接 App 选择替换目标。常见错误为 `USER_LOCKED`、`URI_PERMISSION_DENIED`、`INVALID_INPUT`、`ERROR`。

后台入口不会弹窗；降级时返回 `DOWNGRADE_CONFIRMATION_REQUIRED`，不安装、不改动现有模块，调用方应改用前台入口让用户确认。同版本覆盖直接安装。

已绑定模块更新还返回 `carrier_resource_status`（`UNKNOWN`、`CURRENT`、`UPDATE_REQUIRED`、`REAPPLY_REQUIRED`），以及已知时的静态资源变化数量。后台不会代替用户重新应用。

## Debug 与 Release

Debug 和 Release 均使用包名 `dev.finall.rearcardbridge` 和相同签名，相互覆盖安装并保留原应用数据，不再使用独立的 `.debug` 包名。外部 App 的目标包名和 Provider authority 无需因构建类型切换；Debug 版本名带 `-debug` 后缀。

## 备份与构建

本地目标来自主题管理器已提取的 `.ai_app/<productId>_extracted/rearscreen` 资源。首次原始备份与前一版本保存在本版本应用对应的外部 files/backups 目录；恢复后仍需系统重新应用。

构建需要 JDK 21+ 和 Android SDK 37：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

WebUI 的异步数据回调、并发请求与浏览器网络 API 边界另有无依赖 Node.js 回归测试：

```sh
node --test app/src/test/js/*.test.cjs
```

模块构建工具（编译 card 脚本、webroot 字节不变、ZIP 形状与错误报告）的 Node 内置测试：

```sh
cd tools/card-build && npm ci && npm test
```

Debug 和 Release 均使用本地 `signing/release.jks` 签名，别名和 Keychain 定位来自 `signing/keychain.properties`。密码只从 Keychain 读取，不输出或写入文件；缺少签名文件或无法读取 Keychain 时构建失败。整个 signing 目录不提交 Git。签名冲突不能通过自动卸载绕过。

资源路径及 HostVar 行为依据当前实测 Xiaomi 设备，其他设备和系统版本需单独验证。
