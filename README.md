# 背屏卡片桥 · 动态更新

Android / Kotlin 应用，包名 `dev.finall.rearcardbridge`，界面使用 Miuix Compose 0.9.4。

## 首次设置

1. 启动 Shizuku（ADB / shell 模式），在「配置载体」中授权。
2. 从实际扫描出的本地资源选择一张原生卡片。
3. 点击「安装并绑定动态载体」。应用备份原资源，写入固定载体包。
4. 应用打开「背屏设置」。进入「智能应用 → 我生成的」，移除旧卡后重新应用所选卡片一次。
5. 导入内容。载体读取桥接应用提供的脚本并在背屏绘制。

资源列表用于首次选择载体，不是原生「我生成的」列表。不能从外部直接登记新卡片或自动点击系统的应用按钮。

## 日常更新

「选择 ZIP 导入」读取整个 ZIP，将合并后的脚本、内容名称、版本号和原 ZIP 保存到本应用私有目录。只保留当前内容。

已应用的固定载体通过只读 `ContentProvider` 查询最新脚本，经 MAML `HostVar` 传给 `JsCanvas`，在运行时执行。更新不会替换系统资源、移除卡片或跳转系统页面，也不需要 Shizuku。

重新打开卡片会再次读取持久化的最新内容。背屏休眠时，更新会延后到点亮后载入。界面的「已保存版本」表示本应用保存完成，不表示已经在背屏执行。

载体在执行新版本前取消旧脚本的动画、计时器和通过原生事件接口注册的监听，然后执行新脚本。运行时仍是 MAML / QuickJS / Canvas，不是 WebView。

## 输入包与资源边界

```text
index.html
main.js
assets/       # 可选
```

HTML 必须包含 `canvas`。支持本地 `.js` 经典脚本和内联经典脚本，按文档顺序合并。没有引用的 `main.js` 会追加到末尾。不支持 ES Module 或远程脚本引用。

首次写入载体时会复制当前 ZIP 的资源。动态更新目前更新脚本，不会刷新已应用载体包中的图片、字体等文件。使用新增或改变的资源文件时，需要重新安装载体；不能把它当成完整资源包热更新。

示例：[rear-visible.zip](samples/rear-visible.zip)。生成命令：

```sh
python3 samples/pack-rear-card.py
```

不计算自定义哈希或摘要，不做 JS AST、安全扫描、危险 API 黑名单或脚本执行检查。仅检查 ZIP、路径和 HTML / XML 格式。请使用你愿意在系统卡片环境中执行的脚本。

## 外部 Intent（API 1）

调用方使用自己的 FileProvider 提供整个 ZIP，并授予读取权限。

```kotlin
val intent = Intent("dev.finall.rearcardbridge.action.UPDATE_CONTENT").apply {
    setPackage("dev.finall.rearcardbridge")
    setDataAndType(packageUri, "application/zip")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    clipData = ClipData.newRawUri("card package", packageUri)
    putExtra("api_version", 1)
    putExtra("request_id", requestId)
}
launcher.launch(intent)
```

也支持 `ACTION_SEND` 的 `EXTRA_STREAM` ZIP 分享。旧的 `REPLACE_CARD` action 保留为导入更新的别名，不再直接替换目标资源。

Activity 返回 `request_id`、`status`、`message`、`card_id`。导入返回 `SAVED`；首次写入载体返回 `CARRIER_WRITTEN`；手动打开设置返回 `SETTINGS_OPENED`。不返回 `APPLIED`。调用方在桥接 Activity 退出时取得结果。

## 持久化接口与备份

```text
content://dev.finall.rearcardbridge.live/current
columns: payload, revision, name
payload: {"revision": 1, "script": "..."}
```

接口仅供读取，不提供外部写入能力。Xiaomi 的背屏进程通过它读取本应用保存的内容。

首次配置沿用 Shizuku 资源扫描和备份：

```text
/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app/<productId>_extracted/rearscreen
/storage/emulated/0/Android/data/dev.finall.rearcardbridge/files/backups/
```

备份保留首次原始资源和前一版。恢复按钮只恢复原始资源文件，随后需要在系统页面重新应用原卡。

## 构建与适配

需要 JDK 17 或更新版本和 Android SDK 37，本机使用 JDK 21。

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```

资源路径、原生 HostVar 接口和公开背屏设置入口基于 Xiaomi 18 Pro / Android 17 的实测。其他机型和系统版本可能不同。
