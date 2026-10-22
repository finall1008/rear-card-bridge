# CI Release 打包方案（含与本地一致的签名）

> 目标：给 Android 仓库接入 GitHub Actions release 打包流水线，CI 产出的 APK 签名与本地构建（macOS Keychain + `signing/release.jks`）**完全一致**，可直接覆盖安装。
> 本文档供另一个 agent 在另一个仓库复刻使用，按步骤执行即可。

## 前提

仓库已有本地签名约定（本方案依赖它，不要改动本地路径）：

- keystore 文件：`signing/release.jks`（整个 `signing/` 目录被 gitignore，**绝不提交**）
- `signing/keychain.properties` 里有 `keyAlias` / `keychainService` / `keychainAccount`
- 密码存 macOS Keychain，构建时通过 `/usr/bin/security find-generic-password` 读取
- debug 和 release 都用这把 key

## 总体思路

同一把 keystore 走两条取密码的路径：

- **本地**（`CI` 未设或为 false）：维持原样，从 macOS Keychain 读密码
- **CI**（`CI=true`，GitHub Actions 自动设置）：keystore 由 workflow 从 GitHub Secret（base64）还原到 `signing/release.jks`，alias 和密码从环境变量读

任何签名材料缺失都必须让 build **失败**，禁止回退到 debug key。

## 第 1 步：修改 `app/build.gradle.kts`

在 `android { ... }` 块内，把原来的签名配置改成下面这样（保留原有的 keychain 逻辑，外面包一层 `isCi` 分支）：

```kotlin
import java.util.Properties

android {
    // ... namespace / defaultConfig / buildFeatures / compileOptions 不变 ...

    // Local signing files are ignored by Git; never fall back to another signing key.
    val finallKeystore = rootProject.file("signing/release.jks")
    check(finallKeystore.isFile) {
        "Missing finall signing keystore: $finallKeystore. Do not replace it with the default debug key."
    }
    val isCi = providers.environmentVariable("CI").map(String::toBoolean).getOrElse(false)
    if (isCi) {
        // CI: the workflow restores the same signing/release.jks from secrets and
        // passes the same alias/password via environment. Anything missing fails the build.
        fun ciProperty(name: String): String =
            requireNotNull(providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }) {
                "Missing $name environment variable for CI signing; refusing to use a different signing key."
            }
        val finall = signingConfigs.create("finall") {
            storeFile = finallKeystore
            storePassword = ciProperty("RELEASE_STORE_PASSWORD")
            keyAlias = ciProperty("RELEASE_KEY_ALIAS")
            keyPassword = ciProperty("RELEASE_KEY_PASSWORD")
        }
        buildTypes {
            debug { signingConfig = finall }
            release { signingConfig = finall; isMinifyEnabled = false }
        }
    } else {
        // ↓↓↓ 以下为原有本地 Keychain 逻辑，原样保留 ↓↓↓
        val keychainConfigFile = rootProject.file("signing/keychain.properties")
        check(keychainConfigFile.isFile) { "Missing signing/keychain.properties." }
        val keychainConfig = Properties().apply {
            keychainConfigFile.inputStream().use { load(it) }
        }
        fun signingProperty(name: String): String =
            requireNotNull(keychainConfig.getProperty(name)?.takeIf { it.isNotBlank() }) {
                "Missing $name in signing/keychain.properties."
            }
        run {
            val keychain = providers.exec {
                commandLine("/usr/bin/security", "find-generic-password",
                    "-s", signingProperty("keychainService"),
                    "-a", signingProperty("keychainAccount"), "-w")
                isIgnoreExitValue = true
            }
            check(keychain.result.get().exitValue == 0) {
                "Cannot read finall signing password from macOS Keychain; refusing to use a different signing key."
            }
            val password = keychain.standardOutput.asText.get().trimEnd('\r', '\n')
            check(password.isNotEmpty()) { "The finall signing password is empty." }
            val finall = signingConfigs.create("finall") {
                storeFile = finallKeystore
                storePassword = password
                keyAlias = signingProperty("keyAlias")
                keyPassword = password
            }
            buildTypes {
                debug { signingConfig = finall }
                release { signingConfig = finall; isMinifyEnabled = false }
            }
        }
    }
}
```

注意点：

- keystore 路径检查 `check(finallKeystore.isFile)` 要提到分支**外面**，两条路径共用
- 如果目标仓库本地 keyPassword 与 storePassword 不同，本地逻辑照旧；CI 的 `RELEASE_KEY_PASSWORD` 单独传即可
- `buildTypes` 在两个分支里都要配置（AGP 的 `buildTypes` DSL 不能在分支外共享 `signingConfig` 引用未创建的 config；照抄上面的写法即可）

## 第 2 步：新建 `.github/workflows/release.yml`

```yaml
name: Release

on:
  push:
    tags:
      - "v*"
  workflow_dispatch:

permissions:
  contents: write

env:
  CI: "true"

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v5

      - name: Set up JDK 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"
          cache: gradle

      # 不要用 android-actions/setup-android@v3：它在当前 runner 上会执行
      # `sdkmanager tools`，该包已不存在，必然失败。ubuntu-latest 自带 Android SDK。
      - name: Install Android SDK components
        run: |
          set -euo pipefail
          SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
          # 注意：必须在子 shell 里关掉 pipefail，否则 yes 收到 SIGPIPE（Broken pipe）会被算成失败
          (set +o pipefail; yes | "$SDKMANAGER" --licenses > /dev/null)
          "$SDKMANAGER" "platforms;android-37.0" "build-tools;36.0.0"

      - name: Restore release signing key
        env:
          RELEASE_JKS_BASE64: ${{ secrets.RELEASE_JKS_BASE64 }}
          RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: |
          set -euo pipefail
          : "${RELEASE_JKS_BASE64:?missing secret RELEASE_JKS_BASE64}"
          : "${RELEASE_STORE_PASSWORD:?missing secret RELEASE_STORE_PASSWORD}"
          : "${RELEASE_KEY_ALIAS:?missing secret RELEASE_KEY_ALIAS}"
          : "${RELEASE_KEY_PASSWORD:?missing secret RELEASE_KEY_PASSWORD}"
          mkdir -p signing
          printf '%s' "$RELEASE_JKS_BASE64" | base64 -d > signing/release.jks
          chmod 600 signing/release.jks

      - name: Build release APK
        env:
          RELEASE_STORE_PASSWORD: ${{ secrets.RELEASE_STORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: ./gradlew assembleRelease

      - name: Verify APK signature
        run: |
          set -euo pipefail
          APK=app/build/outputs/apk/release/app-release.apk
          "$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/apksigner" verify --print-certs "$APK"

      - name: Stage release artifact
        if: startsWith(github.ref, 'refs/tags/')
        run: |
          set -euo pipefail
          TAG="${GITHUB_REF_NAME}"
          mkdir -p dist
          cp app/build/outputs/apk/release/app-release.apk "dist/<APP_NAME>-${TAG}.apk"

      - name: Upload APK artifact
        uses: actions/upload-artifact@v5
        with:
          name: <APP_NAME>-release-apk
          path: app/build/outputs/apk/release/app-release.apk

      - name: Create GitHub Release
        if: startsWith(github.ref, 'refs/tags/')
        uses: softprops/action-gh-release@v2
        with:
          files: dist/<APP_NAME>-${{ github.ref_name }}.apk
          generate_release_notes: true
```

复刻时需要按目标仓库调整的地方：

| 位置 | 说明 |
|---|---|
| `platforms;android-37.0` | 目标仓库的 `compileSdk` 对应的 platform 包名。先本地跑 `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager --list \| grep 'platforms;android'` 确认准确包名（本仓库 compileSdk=37 对应包名是 `android-37.0`，不是 `android-37`） |
| `build-tools;36.0.0` | 目标仓库 AGP 要求的 build-tools 版本，看本地 `~/Library/Android/sdk/build-tools/` 里装的 |
| `<APP_NAME>` | APK 文件名前缀，替换成目标应用名 |
| `app/build/outputs/apk/release/app-release.apk` | 如果 application module 不叫 `app`，改成对应模块路径 |
| JDK 版本 | 与目标仓库 AGP/Gradle 要求一致（本仓库 AGP 9.x + Gradle 9.x 用 JDK 17） |

## 第 3 步：配置 GitHub Secrets（4 个）

仓库 **Settings → Secrets and variables → Actions → New repository secret**：

| Secret 名 | 值 |
|---|---|
| `RELEASE_JKS_BASE64` | `base64 -i signing/release.jks \| tr -d '\n'` 的输出（单行） |
| `RELEASE_KEY_ALIAS` | `signing/keychain.properties` 里的 `keyAlias`（本仓库是 `finall`） |
| `RELEASE_STORE_PASSWORD` | Keychain 里存的签名密码（store 和 key 同密码时两者填一样） |
| `RELEASE_KEY_PASSWORD` | 同上 |

生成 base64：`base64 -i signing/release.jks | tr -d '\n' | pbcopy` 直接进剪贴板。

如果操作者记不清密码，用脚本核验（不回显、不落盘）：与 Keychain 中 `security find-generic-password -s <keychainService> -a <keychainAccount> -w` 的值比对，再用 `keytool -list -keystore signing/release.jks -alias <alias> -storepass:env XX` 实际解锁验证。

## 第 4 步：触发与验证

```bash
git tag v0.2.1 && git push origin v0.2.1   # tag 名对应 workflow 里的 v* 触发器
```

- tag push → 构建 + 验签 + 自动创建 GitHub Release（APK 命名 `<APP_NAME>-<tag>.apk`）
- 手动 `workflow_dispatch` → 只构建 + 上传 artifact，不建 Release
- 如果 tag 推错了 commit：`git tag -f v0.2.1 && git push -f origin v0.2.1` 重推即可重新触发

**签名一致性验证（必做）**：下载 CI 产出的 APK，与本地构建的 APK 对比证书指纹，两者必须完全一致：

```bash
~/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs <ci.apk> | grep 'SHA-256 digest'
~/Library/Android/sdk/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep 'SHA-256 digest'
```

（本仓库验证结果：两者均为 `33f92075e81fc19c93bb58f83c0db334888e635926ef400849569b91f86102f5`）

## 本地冒烟测试 CI 签名路径（可选）

不推 tag 也能在本地验证 CI 分支逻辑：

```bash
PW=$(/usr/bin/security find-generic-password -s <keychainService> -a <keychainAccount> -w)
CI=true RELEASE_STORE_PASSWORD="$PW" RELEASE_KEY_PASSWORD="$PW" RELEASE_KEY_ALIAS=<alias> ./gradlew clean assembleRelease
unset PW
```

## 已踩过的坑（复刻时直接避开）

1. **`android-actions/setup-android@v3` 已坏**：它在 ubuntu-latest 上跑 `sdkmanager tools`，该包不存在，step 必挂。runner 镜像自带 Android SDK，只需 `sdkmanager` 补装 platform/build-tools。
2. **`yes | sdkmanager --licenses` + `set -o pipefail` = 假失败**：sdkmanager 退出后 yes 收到 SIGPIPE 报 "Broken pipe"，pipefail 把整个 step 判 1。必须 `(set +o pipefail; yes | ...)`。
3. **platform 包名不是直觉的 `android-37`**：本仓库 compileSdk=37 对应的包名是 `platforms;android-37.0`。一定先用 `sdkmanager --list` 查准。
4. **发版前记得递增 `versionCode` / `versionName`**（硬编码在 build.gradle.kts，workflow 不会自动改）。
