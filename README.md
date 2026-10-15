# 背屏卡片桥

Android / Kotlin，界面使用 Miuix Compose 0.9.4。一个模块 ZIP 包含背屏卡片资源和可选的设置网页；卡片通过统一 `bridge` API 联网、读写自己的数据，不再需要每张卡片配一个 Android App。

- [模块格式与基础 API](<docs/module-development.md>)
- [Canvas 绘制、触摸与生命周期](<docs/app-card-development.md>)
- [示例模块](<samples/hello-module/>)

## 使用流程

1. 在「设置与关于」启动/连接 Shizuku 并授权。
2. 在「主页」导入模块 ZIP，或从公开 Git 仓库安装。
3. 安装后选择实际扫描出的本地卡片作为替换目标。
4. 确认写入：应用先备份目标原始资源，再写入模块载体。
5. 到系统背屏设置移除旧卡，再从该卡片的原入口重新应用。

预设卡片和自行生成的卡片均可作为目标，前提是对应资源已经下载到本机且可写；不会注册新系统卡片，也不会模拟点击应用按钮。更换目标前须恢复原始资源，解除原绑定。

主页只有模块安装和模块列表。点击模块可打开其设置网页、查看载体状态、更新资源或卸载；Shizuku 状态、作者和仓库信息统一放在「设置与关于」。

## 模块包

```text
module.zip
├── module.json
├── icon.png          # 可选
├── card/
│   ├── index.html
│   ├── main.js
│   └── assets/       # 可选
└── webroot/          # 可选：设置网页
    └── index.html
```

同一模块由 `module.json` 的 `id` 标识，更新包的 `versionCode` 必须递增。更新保留配置和业务数据，卸载则删除这些数据。仓库安装支持 GitHub、GitLab、Gitee 的公开仓库归档；前端须预先构建为静态文件，手机上不执行安装脚本或构建脚本。

背屏端运行 MAML / QuickJS / Canvas，不是 WebView；设置页运行在卡片桥的 WebView 中。两端共用 `bridge.config`、`bridge.storage`、`bridge.files`、`bridge.net` 等接口，详见开发指南。

## 热更新和重新应用

- 脚本更新：经只读 Provider / HostVar 热更新，无需 Shizuku。
- 配置、网络数据、Base64 Data URL 图片：走数据通道，无需重新应用。
- 包内静态图片等资源变化：需更新载体，然后在系统中移除旧卡并重新应用。
- 写入完成、打开设置和用户确认，都不代表应用检测到了系统已应用状态。

休眠期间卡片 JS 不保证运行；重新显示时再获取最新状态。Shizuku 只用于扫描、写入和恢复本地载体，不参与日常模块 API 请求。

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

已绑定模块更新还返回 `carrier_resource_status`（`UNKNOWN`、`CURRENT`、`UPDATE_REQUIRED`、`REAPPLY_REQUIRED`），以及已知时的静态资源变化数量。后台不会代替用户重新应用。

## 独立 Debug 版

- Release：`dev.finall.rearcardbridge`
- Debug：`dev.finall.rearcardbridge.debug`，名称「背屏卡片桥 Debug」

两个版本的私有数据、Provider authority 和备份目录分开。外部 App 测试 Debug 时，目标包名和 Provider authority 都需换成 Debug 包名；Intent action 仍是同一协议名称。

**本机系统卡片资源是共享的**：请给 Debug 选择专用测试目标，不要覆盖 release 正在使用的卡片。两个版本不会互相识别对方的绑定。

## 备份与构建

本地目标来自主题管理器已提取的 `.ai_app/<productId>_extracted/rearscreen` 资源。首次原始备份与前一版本保存在本版本应用对应的外部 files/backups 目录；恢复后仍需系统重新应用。

构建需要 JDK 17+ 和 Android SDK 37：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

Debug 和 Release 均使用本地 `signing/release.jks` 签名，别名和 Keychain 定位来自 `signing/keychain.properties`。密码只从 Keychain 读取，不输出或写入文件；缺少签名文件或无法读取 Keychain 时构建失败。整个 signing 目录不提交 Git。签名冲突不能通过自动卸载绕过。

资源路径及 HostVar 行为依据当前实测 Xiaomi 设备，其他设备和系统版本需单独验证。
