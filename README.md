# 背屏卡片桥 · 动态更新

Android / Kotlin 应用，包名 `dev.finall.rearcardbridge`，界面使用 Miuix Compose 0.9.4。

开发自己的应用卡请先阅读 [应用卡开发指南](<docs/app-card-development.md>)：JsCanvas 与网页的差异、RAF 绘制时机、触摸手势、资源加载、热更新与排错。

## 首次设置

1. 启动 Shizuku（ADB / shell 模式），在「配置载体」中授权。
2. 从实际扫描出的本地资源选择一张原生卡片。
3. 点击「安装并绑定动态载体」。应用备份原资源，写入固定载体包。
4. 应用打开「背屏设置」。进入「智能应用 → 我生成的」，移除旧卡后重新应用所选卡片一次。
5. 导入内容。载体读取桥接应用提供的脚本并在背屏绘制。

资源列表用于首次选择载体，不是原生「我生成的」列表。不能从外部直接登记新卡片或自动点击系统的应用按钮。

## 日常更新

「选择 ZIP 导入」读取整个 ZIP，将合并后的脚本、内容名称、版本号和原 ZIP 保存到本应用私有目录。按来源 App 和 key 分组，每组保留自己的当前内容、独立版本和目标卡片。

外部未传 key 时使用来源 App 包名；内部以 `(来源包名, key)` 区分分组。同一张目标卡片只能被一个分组占用。首次遇到一组内容时，先暂存内容，再由用户选择目标、安装载体并在系统里重新应用一次。更换目标前先恢复原载体；已知组日常更新不再选卡。界面的分组列表用于切换、导入、重装载体和恢复。

仅支持按来源 App / key 的分组载体，不迁移原有单组内容或绑定。原有单组载体需要重新导入内容、选择目标并安装。

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

首次写入载体时会复制当前 ZIP 的资源。动态更新更新脚本，不会刷新已应用载体包中的图片、字体等文件。每次导入后会把静态资源与该组上次成功写入载体的快照比较（路径与解压后的字节，不使用哈希）；入口 HTML 和参与合并的脚本不触发静态资源更新。仅脚本变化无需重新安装；静态资源新增、修改或删除时，页面显示变化数量和「更新载体资源」按钮。

更新载体后，需到「我生成的」移除旧卡并重新应用，再点击「我已移除旧卡并重新应用」记录自己的确认。写入和打开设置不代表已应用，应用不会自动检测系统是否完成应用。已有绑定如果没有安装快照，显示「尚无资源比对记录」；再次成功安装后建立快照。恢复原始资源会清除该绑定的快照和重新应用提示。

示例：[rear-visible.zip](samples/rear-visible.zip)。生成命令：

```sh
python3 samples/pack-rear-card.py
```

不计算自定义哈希或摘要，不做 JS AST、安全扫描、危险 API 黑名单或脚本执行检查。仅检查 ZIP、路径和 HTML / XML 格式。请使用你愿意在系统卡片环境中执行的脚本。

## 前台 Intent（API 2）

调用方使用自己的 FileProvider 提供整个 ZIP，并授予读取权限。

```kotlin
val intent = Intent("dev.finall.rearcardbridge.action.UPDATE_CONTENT").apply {
    setPackage("dev.finall.rearcardbridge")
    setDataAndType(packageUri, "application/zip")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    clipData = ClipData.newRawUri("card package", packageUri)
    putExtra("api_version", 2)
    putExtra("request_id", requestId)
    putExtra("key", "weather") // 可省略，默认来源 App 包名
}
launcher.launch(intent)
```

也支持 `ACTION_SEND` 的 `EXTRA_STREAM` ZIP 分享。自定义更新入口要求 `api_version=2`，不提供旧 action 别名。

正式集成请使用 Activity Result（`launcher.launch` / `startActivityForResult`），系统提供的 `callingPackage` 用于确定来源；不使用调用方填写的包名、referrer 或 URI authority。普通 `ACTION_SEND` 或无法确认调用者的启动方式，需要用户在分组列表明确选择已有分组，才会导入，不猜测来源。强制复用已有 Activity 的新 Intent 同样按无法确认来源处理。

Activity 返回 `request_id`、`status`、`message`、`card_id`、`key`、`revision`。已绑定组导入返回 `SAVED`；未绑定组暂存返回 `BINDING_REQUIRED`；首次写入载体返回 `CARRIER_WRITTEN`；手动打开设置返回 `SETTINGS_OPENED`。不返回 `APPLIED`。调用方在桥接 Activity 退出时取得最后一次操作结果。

## 后台更新（API 2）

后台入口是独立的 `ContentProvider.call()`，不调用 Activity、前台服务、通知或 Shizuku；没有绑定时只返回错误，不自动弹出配置页面。调用方应在自己的 IO 线程同步调用，并在调用期间保持 ZIP 可读。

```kotlin
// 在调用方的后台/IO线程中执行；后台调用不会自动传递 URI 授权。
val bridgePackage = "dev.finall.rearcardbridge"
context.grantUriPermission(bridgePackage, packageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
try {
    val result = context.contentResolver.call(
        Uri.parse("content://dev.finall.rearcardbridge.update"),
        "update_content", null,
        Bundle().apply {
            putInt("api_version", 2)
            putParcelable("package_uri", packageUri)
            putString("request_id", requestId)
            putString("key", "weather") // 与前台首次绑定一致；可省略
        },
    )
    // status=SAVED 表示保存完成，不表示背屏已执行。
} finally {
    context.revokeUriPermission(bridgePackage, packageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
```

调用方如果启用了包可见性过滤，可在自己的 manifest 中加入：

```xml
<queries>
    <provider android:authorities="dev.finall.rearcardbridge.update" />
</queries>
```

Binder 调用身份确定来源包名；无法唯一确定来源（含共享 UID）返回 `CALLER_UNKNOWN`。返回字段包括 `request_id`、`status`、`message`，来源解析后包括 `key`，找到绑定后包括 `card_id`，保存成功包括 `revision`。

- `SAVED`：该组内容保存完成。
- `BINDING_REQUIRED`：来源 / key 尚未绑定或已恢复取消绑定；需用户主动走前台配置。
- `USER_LOCKED`：用户存储不可用时的保护性返回。重启后首次解锁前，系统也可能直接拒绝启动 Provider，调用方需处理接口不可用异常。
- `URI_PERMISSION_DENIED`：未授予桥接 App ZIP 读取权限。
- `INVALID_INPUT` / `ERROR`：协议、ZIP 或存储操作失败。

设备本次开机解锁过后，重新锁屏无需解锁即可调用；不点亮屏幕、不拉起桥接界面。背屏休眠时仍可能延后到点亮才读取。调用方自身能否获得后台执行机会取决于 Android / 系统省电策略。此版本不支持首次解锁前的 Direct Boot 更新。

保存成功后还返回静态资源检测结果，不会在后台安装载体：

- `carrier_resource_status`：`UNKNOWN`（没有安装快照）、`CURRENT`（资源一致）、`UPDATE_REQUIRED`（静态资源变化）、`REAPPLY_REQUIRED`（已写入载体，等待用户确认重新应用）。
- `carrier_update_required`：是否需要更新载体；`UNKNOWN` 时不返回此字段，不能把缺省值当成已确认无需更新。
- `reapply_confirmation_pending`：是否正在提示用户确认重新应用，不代表检测到了系统应用状态。
- `static_resources_added` / `static_resources_modified` / `static_resources_removed`：有安装快照时返回资源变化数量。

动态更新仍只刷新脚本。`UPDATE_REQUIRED` 时需在前台更新同一组载体，再到「我生成的」移除旧卡并手动重新应用。

## 持久化接口与备份

```text
content://dev.finall.rearcardbridge.live/bindings/<内部bindingId>/current
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

Debug 和 Release 均使用仓库相对路径 `signing/release.jks` 内的签名，不使用默认 debug key。构建从 `signing/keychain.properties` 读取 `keyAlias`、`keychainService`、`keychainAccount`，再从 macOS 钥匙串读取密码。整个 `signing/` 目录被 Git 忽略，不提交密钥、本机配置或密码。构建前需自行准备这些本地文件；文件缺失或钥匙串不可访问会中止构建。安装使用 `adb install -r` 覆盖保留数据，不以卸载绕过签名冲突。

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```

资源路径、原生 HostVar 接口和公开背屏设置入口基于 Xiaomi 18 Pro / Android 17 的实测。其他机型和系统版本可能不同。
