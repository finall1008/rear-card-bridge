package dev.finall.rearcardbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import dev.finall.rearcardbridge.live.ContentOperations
import dev.finall.rearcardbridge.module.ModuleBackend
import dev.finall.rearcardbridge.module.ModuleStore
import dev.finall.rearcardbridge.module.ModuleWebProfiles
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close

private const val WEBROOT_DIR = "webroot"
private const val SHIM_NAME = "bridge-shim.js"
private const val SHIM_MIME = "application/javascript"
private const val BRIDGE_NAME = "BridgeNative"

/**
 * Settings WebUI page for an installed card module, KernelSU-WebUI style: loads the module's
 * own webroot/index.html from file://. WebView handles fetch/XHR itself;
 * window.BridgeNative ([NativeBridge]) exposes module data, card operations and native requests.
 * File-page cross-origin access is enabled; no custom file-path filter is installed.
 *
 * Both this settings WebView and every window.open() popup (login windows) attach to the
 * module's isolated WebView profile through [ModuleWebProfiles] before any settings or
 * navigation, so one module never sees another module's cookies. When the system WebView
 * lacks multi-profile support the page shows a comprehensible error instead of silently
 * falling back to the shared default profile. Popups never receive the JS bridge and only
 * follow HTTP(S); they render as a native Miuix fullscreen overlay with a close control.
 *
 * Rendered as a Miuix Nav route inside the host activity. The page supplies its own
 * Scaffold and top-app-bar navigation; system back and gestures stay with the nav
 * controller for the route itself (exit goes through [onBack]). The single [BackHandler]
 * below is for an open popup only: it walks the popup's history first and closes the
 * popup at the start of that history. Assumes the caller already applied a MiuixTheme.
 */
@Composable
fun ModuleSettingsPage(moduleId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ModuleStore(context) }
    val module = remember(moduleId) { store.get(moduleId) }
    if (module == null || !module.manifest.hasWebui) {
        ModuleSettingsErrorPage(onBack)
        return
    }
    // Module-isolated profile is mandatory for the settings page and its login popups.
    // No shared-default-profile fallback: rendering anyway would mix module sessions.
    if (!ModuleWebProfiles.supported()) {
        ModuleProfileErrorPage(
            message = "当前系统 WebView 不支持模块独立会话（MULTI_PROFILE），无法以隔离配置打开模块设置页。" +
                "请更新 Android System WebView 后重试。为避免模块间会话串用，这里不会回退到共享的默认配置。",
            onBack = onBack,
        )
        return
    }
    val webroot = remember(module) { File(module.packageDir, WEBROOT_DIR) }
    val webUiClient = remember(webroot) { WebUiClient(webroot, context) }
    val backend = remember { ModuleBackend(context, store) }
    val title = module.manifest.name
    // Bound to this composition: coroutines stop when the page is removed from the nav stack.
    val scope = rememberCoroutineScope()
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    // Set when attaching the settings WebView to the profile fails despite supported() being
    // true; the page then swaps to a comprehensible error instead of running unisolated.
    val profileError = remember { mutableStateOf<String?>(null) }
    val popup = remember { PopupController(context, moduleId) }

    // Popup only: while a popup is open, back walks the popup's own history first and closes
    // the popup at the start of that history. With no popup the callback is disabled and the
    // event stays with the nav controller (route exit through onBack).
    BackHandler(enabled = popup.current.value != null) { popup.backOrClose() }

    val currentError = profileError.value
    Box(Modifier.fillMaxSize()) {
        if (currentError == null) {
            Scaffold(topBar = {
                SmallTopAppBar(title = title, navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                })
            }) { padding ->
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    factory = { viewContext ->
                        WebView(viewContext).also { webView ->
                            // Attach to the module profile before configuring or navigating
                            // (ModuleWebProfiles contract: main-thread, attach-first).
                            val attached = runCatching {
                                ModuleWebProfiles.attach(webView, moduleId)
                            }
                            if (attached.isFailure) {
                                profileError.value = attached.exceptionOrNull()?.message
                                    ?: "无法初始化模块独立会话，模块设置页已停止打开。"
                                return@also
                            }
                            webView.settings.javaScriptEnabled = true
                            webView.settings.domStorageEnabled = true
                            // Trusted local pages may use browser fetch/XHR directly.
                            webView.settings.allowFileAccess = true
                            webView.settings.allowContentAccess = true
                            @Suppress("DEPRECATION")
                            webView.settings.setAllowFileAccessFromFileURLs(true)
                            @Suppress("DEPRECATION")
                            webView.settings.setAllowUniversalAccessFromFileURLs(true)
                            // window.open() must become a native popup (login flows) instead of
                            // an in-page window, and scripted openers may pop without a gesture.
                            // Explicit setter: the SDK stub getter is supportMultipleWindows(),
                            // which Kotlin does not synthesize into a property.
                            webView.settings.setSupportMultipleWindows(true)
                            webView.settings.javaScriptCanOpenWindowsAutomatically = true
                            webView.webViewClient = webUiClient
                            webView.webChromeClient = WebUiChromeClient(popup)
                            webView.addJavascriptInterface(
                                NativeBridge(moduleId, store, backend, scope) { webViewRef.value },
                                BRIDGE_NAME,
                            )
                            webView.loadUrl(webUiClient.entryUrl)
                            webViewRef.value = webView
                        }
                    },
                    // Fires only when the AndroidView leaves composition, i.e. after the nav
                    // transition has disposed the page, so push/pop never tears the WebView down
                    // mid-animation. Nulling the ref first silences any in-flight JS callback.
                    // Parent-page disposal must also take an open popup down with it.
                    onRelease = { released ->
                        if (webViewRef.value === released) webViewRef.value = null
                        released.destroy()
                        popup.close()
                    },
                )
            }
            // Fullscreen native overlay for the popup, drawn above the settings page with its
            // own close control. key() rebuilds the host when the popup is replaced, since
            // AndroidView would otherwise keep hosting the old WebView.
            popup.current.value?.let { child ->
                key(child) {
                    ModulePopupOverlay(webView = child, onClose = { popup.close() })
                }
            }
        } else {
            ModuleProfileErrorPage(message = currentError, onBack = onBack)
        }
    }
}

/** Module missing or without a settings page. */
@Composable
private fun ModuleSettingsErrorPage(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = "模块设置") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("该模块不存在，或未提供设置页。")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("返回") }
        }
    }
}

/** Settings page unavailable because the module-isolated WebView profile cannot be used. */
@Composable
private fun ModuleProfileErrorPage(message: String, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = "模块设置") }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(message)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("返回") }
        }
    }
}

/**
 * Native fullscreen host for a window.open() popup: its own Miuix top bar with a visible
 * close control and the popup WebView below. The WebView instance comes from
 * [PopupController]; this overlay only hosts it, view teardown happens in [teardownPopup].
 */
@Composable
private fun ModulePopupOverlay(webView: WebView, onClose: () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        SmallTopAppBar(title = "弹出窗口", navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(MiuixIcons.Close, contentDescription = "关闭弹出窗口")
            }
        })
    }) { padding ->
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * Local file:// stays inside the WebView with the bridge (entry page plus optional shim);
 * ordinary HTTP(S) main-frame navigation intentionally continues to the external browser:
 * a remote page must never execute inside this privileged file-origin WebView, which carries
 * BridgeNative plus universal file access, and there is deliberately no host allowlist that
 * could police which remote sites are "allowed" to run there. window.open() is not a
 * main-frame load — it reaches WebChromeClient.onCreateWindow instead and becomes the
 * isolated module-profile popup, so login popups keep working without opening the door for
 * full-page remote navigation. Sub-resource HTTP(S) fetch/XHR from the file page stays in
 * WebView. Only native bridge.net requests check the module's network capability.
 */
private class WebUiClient(
    webroot: File,
    private val context: Context,
) : WebViewClient() {
    val entryUrl = Uri.fromFile(File(webroot, "index.html")).toString()
    private val shimFile = File(webroot, SHIM_NAME)

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (request.url.scheme == "file" && request.url.path == shimFile.absolutePath && !shimFile.isFile) {
            return WebResourceResponse(SHIM_MIME, "UTF-8", context.assets.open(SHIM_NAME))
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (request.isForMainFrame && (url.scheme == "http" || url.scheme == "https")) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
            return true
        }
        return false
    }
}

/**
 * Popup navigation policy: HTTP(S) follows inside the popup with no host allowlist (any
 * site the module page opens may load here, but always inside the module profile and with
 * file/content access disabled on this WebView); the initial about:blank handed over through
 * the WebViewTransport window is allowed; every other scheme — notably file:, content:, and
 * intent: — is refused so a popup can never load privileged local pages or leave the app.
 * TLS errors keep the platform default: cancel (no onReceivedSslError override).
 */
private class PopupWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        // about:blank / about:blank#blocked — the transport's initial blank window.
        if (scheme == "about" && url.toString().startsWith("about:blank")) return false
        // Passport can redirect its HTTPS login through an HTTP callback. Upgrade that
        // navigation rather than send the login session over cleartext (or hit Android's
        // cleartext block); this rule is generic, not tied to a particular provider.
        if (request.isForMainFrame && scheme == "http") {
            view.loadUrl(url.buildUpon().scheme("https").build().toString())
            return true
        }
        if (scheme == "http" || scheme == "https") return false
        // Sub-frames may keep ephemeral data:/blob: payloads; they gain no file/content
        // privilege because allowFileAccess/allowContentAccess are off for this WebView.
        if (!request.isForMainFrame && (scheme == "data" || scheme == "blob")) return false
        return true
    }
}

/** Settings-side chrome client: window.open() becomes the module-profile popup. */
private class WebUiChromeClient(private val popup: PopupController) : WebChromeClient() {
    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean = popup.create(resultMsg)
}

/**
 * Popup-side chrome client: only window.close() matters here. Nested window.open() is never
 * served because supportMultipleWindows stays off for popups (see [PopupController.create]).
 */
private class PopupWindowChromeClient(private val popup: PopupController) : WebChromeClient() {
    override fun onCloseWindow(window: WebView) {
        if (popup.current.value === window) popup.close()
    }
}

/**
 * Owns the single window.open() popup of one settings page. Creation runs inside
 * WebChromeClient.onCreateWindow — on the settings WebView's UI thread, matching the
 * main-thread contract of [ModuleWebProfiles] — before any popup settings or navigation.
 * Closing always funnels through [close]: the native close control, system back,
 * JS window.close() and parent-page disposal.
 */
private class PopupController(
    private val context: Context,
    private val moduleId: String,
) {
    /** Recomposition handle for the overlay; a new identity also keys the host view. */
    val current = mutableStateOf<WebView?>(null)

    fun close() {
        val child = current.value ?: return
        current.value = null
        teardownPopup(child)
    }

    /** System back inside an open popup: history first, close at the start of the history. */
    fun backOrClose() {
        val child = current.value ?: return
        if (child.canGoBack()) child.goBack() else close()
    }

    fun create(resultMsg: Message): Boolean {
        // One popup at a time: replace an existing window instead of stacking overlays.
        close()
        val child = WebView(context)
        // Attach to the same module profile as the settings page BEFORE any settings or
        // navigation; attach() also selects the profile CookieManager and turns on its
        // cookie + third-party-cookie acceptance for the login flow.
        if (runCatching { ModuleWebProfiles.attach(child, moduleId) }.isFailure) {
            child.destroy()
            return false
        }
        child.settings.javaScriptEnabled = true
        child.settings.domStorageEnabled = true
        // Assert third-party-cookie acceptance explicitly: cross-site logins commonly keep
        // their session in a third-party cookie of the popup's provider.
        WebViewCompat.getProfile(child).cookieManager.setAcceptThirdPartyCookies(child, true)
        // Remote popup content must never reach privileged file/content/local pages even
        // though it shares the module profile with the settings WebUI; the popup
        // WebViewClient additionally refuses every non-HTTP(S) main-frame scheme.
        child.settings.allowFileAccess = false
        child.settings.allowContentAccess = false
        // Nested window.open() stays unsupported: popups never spawn further windows.
        child.settings.setSupportMultipleWindows(false)
        child.webViewClient = PopupWebViewClient()
        // window.close() support; no onCreateWindow on this client (see above).
        child.webChromeClient = PopupWindowChromeClient(this)
        // Deliberately NO addJavascriptInterface: popup content never sees BridgeNative.
        val transport = resultMsg.obj as? WebView.WebViewTransport
        if (transport == null) {
            child.destroy()
            return false
        }
        // Preserve the opener: hand the new WebView back so window.opener keeps working and
        // the opener script can navigate this window to its login URL.
        transport.webView = child
        current.value = child
        resultMsg.sendToTarget()
        return true
    }
}

/** Release the WebView on main immediately; persisting its profile must not retain a hidden window. */
private fun teardownPopup(child: WebView) {
    child.stopLoading()
    val cookies = WebViewCompat.getProfile(child).cookieManager
    (child.parent as? ViewGroup)?.removeView(child)
    child.destroy()
    // Cookie storage belongs to the profile, not the destroyed WebView. Flush outside the
    // composition scope so closing the whole settings page does not cancel persistence.
    CoroutineScope(Dispatchers.IO).launch { cookies.flush() }
}

/**
 * Submission returns immediately to the WebView JS thread. Work runs on IO workers;
 * completion is posted to the live WebView on the main thread using the request ID.
 * The page's composition scope stops callbacks as soon as the page is removed; requests
 * are not retried. Every op the backend knows (including net.request) goes through
 * [ModuleBackend.execute]; module capabilities decide what is allowed.
 */
private class NativeBridge(
    private val moduleId: String,
    private val store: ModuleStore,
    private val backend: ModuleBackend,
    private val scope: CoroutineScope,
    private val webView: () -> WebView?,
) {
    @JavascriptInterface
    fun invoke(requestId: String, payload: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val request = JSONObject(payload)
                    val op = request.getString("op")
                    if (op == "bridge.poll") {
                        val state = synchronized(ContentOperations.lock) {
                            val revision = store.readState(moduleId)
                            JSONObject()
                                .put("configRev", revision.optLong("configRev", 0L))
                                .put("refreshRev", revision.optLong("refreshRev", 0L))
                                .put("config", store.readConfig(moduleId))
                        }
                        JSONObject().put("ok", true).put("value", state)
                    } else {
                        backend.execute(moduleId, op, request.optJSONObject("args"))
                    }
                } catch (error: Exception) {
                    JSONObject().put("ok", false)
                        .put("error", JSONObject()
                            .put("code", "INVALID_ARGS")
                            .put("message", "请求格式无效。"))
                }
            }
            val callback = "window.__rearCardBridgeComplete && " +
                "window.__rearCardBridgeComplete(${JSONObject.quote(requestId)},$result);"
            webView()?.evaluateJavascript(callback, null)
        }
    }
}
