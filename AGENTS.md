# APK signing and installation
Always use the signing key at repository-relative `signing/release.jks` for BOTH debug and release builds. Never install an APK signed with the default Android debug key and never uninstall to work around a signature mismatch without explicit authorization.
The alias and macOS Keychain service/account are read from `signing/keychain.properties` (`keyAlias`, `keychainService`, `keychainAccount`). Passwords are read from Keychain without printing or storing them.
The entire `signing/` directory is ignored by Git. Never commit its contents. Missing signing files or inaccessible Keychain must fail the build, not silently fall back to another key.

# Project requirements
Do not implement backward compatibility, legacy groups, automatic migration, legacy read URIs, or old Intent aliases. Use only source-app/key groups and API 2.
Android-only Kotlin app, app ID dev.finall.rearcardbridge. UI uses Miuix Compose 0.9.4.
Accept one content URI ZIP containing index.html + JavaScript + optional assets. Show discovered local card resources so the user selects a replacement target. Never hardcode a target productId.
NO application-written hashes/digests/fingerprints or script security analysis (no AST, dangerous API scanning, JS execution/validation sandbox). ZIP/XML format and filesystem path handling are permitted. ZIP format's library-managed CRC is unavoidable and is not a custom digest feature.
NO UI automation, accessibility, input taps/swipes, synthetic clicks, root escalation, or phone installation/testing without separate user authorization.
Shizuku shell may replace writable local resource files and invoke Android Activity APIs; it cannot register a new card or directly apply via the signature-protected SDK. Do not pretend preview-opened means applied.
Source root: app/src/main/java/dev/finall/rearcardbridge. Shared models: model/BridgeModels.kt. IPC contract: app/src/main/aidl/dev/finall/rearcardbridge/ipc/ICardService.aidl. Respect assigned file ownership; main agent owns build/manifest/MainActivity/ViewModel/integration.

# Manual verification: update carrier, then show the rear card
Only use the steps below when the user explicitly authorizes phone/emulator installation, root/adb input and rear-screen testing.

## Update carrier resources
1. Build/install the signed APK without uninstalling: `./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`. It must use `signing/release.jks`; never switch keys to force an install.
2. Push the module ZIP, open the app, tap 导入 ZIP and select the ZIP in the system file picker.
3. Open the module detail. 替换目标 is the carrier card. If 载体状态 says static resources changed, tap 更新载体资源, select the carrier, tap 写入并绑定, then confirm 写入.
   - Script/config-only changes do not need a carrier rewrite. “静态资源一致” is the expected state and the new script is picked up on the next card launch.
4. For a static resource change, reapply in the system UI:
   `设置 → 妙享背屏 → AI生成应用卡 → 打开载体卡（如 煮蛋计时器）→ 移除 → 重新打开 → 应用`.
   Do not delete the carrier from 我的应用卡; that removes the app card and is the wrong entry point.
   Applied means the app list shows “已添加到背屏 N 个应用” and the card detail button becomes 移除. A preview page alone does not mean applied.

## Rear display (AOD / showing the card)
- Display 0 is the main phone screen (1120x2436); display 1 is the rear screen (912x596). Get the physical display ID each boot:
  `adb shell dumpsys SurfaceFlinger --display-id` and use the large ID for `EMU_display_1`.
- Capture the rear with the physical ID, not logical `1`:
  `adb shell su -c 'screencap -p -d <physical-id> /sdcard/rear.png'` then `adb pull /sdcard/rear.png`.
- Run privileged commands through `adb shell su -c '...'`. Shell `input` can fail with `INJECT_EVENTS`; run it under `su`.
- The rear may be OFF/DOZE. Wake it with `input -d 1 keyevent 26` (power) or `224` (WAKEUP), then verify `dumpsys display` shows displayId=1 `state ON`.
- On the lit clock page, swipe up to enter the app card list:
  `adb shell su -c 'input touchscreen -d 1 swipe 456 580 456 40 300'`
  Then tap the carrier card, e.g. `input touchscreen -d 1 tap 600 550`.
- Gesture behavior: from black/AOD a real double-tap wakes the screen; once lit, double-tap blacks it. Synthetic `input` may not trigger the AOD hardware gesture, so use the power/WAKEUP key as the adb fallback before swiping. Do not double-tap after the card is visible if you want to keep it on.
- A black `screencap` usually means OFF/DOZE (or FLAG_SECURE), not a failed card. Wake first and capture again. `uiautomator dump` only sees the main display; use rear screenshots and tap coordinates for display 1.
