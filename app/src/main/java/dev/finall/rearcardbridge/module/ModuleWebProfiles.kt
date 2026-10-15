package dev.finall.rearcardbridge.module

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** WebUI, login windows and native requests share a profile, never another module's cookies. */
object ModuleWebProfiles {
    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun requireSupported() {
        if (!supported()) throw UnsupportedOperationException("当前 Android System WebView 不支持模块独立会话，请更新 WebView。")
    }

    private fun profileName(moduleId: String): String {
        require(ModuleManifest.isValidModuleId(moduleId)) { "模块 id 非法。" }
        return "module:$moduleId"
    }

    /** Main thread only; attach before configuring or navigating the new WebView. */
    fun attach(view: WebView, moduleId: String) {
        requireSupported()
        WebViewCompat.setProfile(view, profileName(moduleId))
        WebViewCompat.getProfile(view).cookieManager.apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
    }

    /** ProfileStore is UI-thread-only, but CookieManager getCookie can be used by IO workers. */
    fun cookieManager(moduleId: String): CookieManager = runBlocking(Dispatchers.Main.immediate) {
        requireSupported()
        ProfileStore.getInstance().getOrCreateProfile(profileName(moduleId)).cookieManager.apply {
            setAcceptCookie(true)
        }
    }

    /** Worker-only: await each Set-Cookie before following the next redirect. */
    fun saveCookies(manager: CookieManager, url: String, values: List<String>) {
        if (values.isEmpty()) return
        runBlocking(Dispatchers.Main.immediate) {
            for (value in values) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    manager.setCookie(url, value) { continuation.resume(Unit) }
                }
            }
        }
        manager.flush()
    }

    /** Updates retain the profile; uninstall removes it after its WebViews have been closed. */
    fun remove(moduleId: String) = runBlocking(Dispatchers.Main.immediate) {
        if (supported()) {
            val store = ProfileStore.getInstance()
            val name = profileName(moduleId)
            if (name in store.allProfileNames) {
                check(store.deleteProfile(name)) { "模块会话仍在使用，请关闭模块设置后重试。" }
            }
        }
    }
}
