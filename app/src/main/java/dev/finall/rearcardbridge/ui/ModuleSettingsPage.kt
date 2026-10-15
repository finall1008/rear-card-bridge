package dev.finall.rearcardbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.finall.rearcardbridge.live.ContentOperations
import dev.finall.rearcardbridge.module.ModuleBackend
import dev.finall.rearcardbridge.module.ModuleStore
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

private const val WEBROOT_DIR = "webroot"
private const val SHIM_NAME = "bridge-shim.js"
private const val SHIM_MIME = "application/javascript"
private const val BRIDGE_NAME = "BridgeNative"

/**
 * Settings WebUI page for an installed card module, KernelSU-WebUI style: loads the module's
 * own webroot/index.html from file://. WebView handles fetch/XHR itself;
 * window.BridgeNative ([NativeBridge]) only exposes module data and card operations.
 * File-page cross-origin access is enabled; no custom file-path filter is installed.
 *
 * Rendered as a Miuix Nav route inside the host activity. The page supplies its own
 * Scaffold and top-app-bar navigation; system back and gestures stay with the nav
 * controller, so no BackHandler is installed here and route exit goes through [onBack].
 * Assumes the caller already applied a MiuixTheme.
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
    val webroot = remember(module) { File(module.packageDir, WEBROOT_DIR) }
    val webUiClient = remember(webroot) { WebUiClient(webroot, context) }
    val backend = remember { ModuleBackend(context, store) }
    val title = module.manifest.name
    // Bound to this composition: coroutines stop when the page is removed from the nav stack.
    val scope = rememberCoroutineScope()
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

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
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // The installed module is trusted. Keep HTTP(S) in WebView, not a native proxy.
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    @Suppress("DEPRECATION")
                    settings.setAllowFileAccessFromFileURLs(true)
                    @Suppress("DEPRECATION")
                    settings.setAllowUniversalAccessFromFileURLs(true)
                    webViewClient = webUiClient
                    addJavascriptInterface(
                        NativeBridge(moduleId, store, backend, scope) { webViewRef.value },
                        BRIDGE_NAME,
                    )
                    loadUrl(webUiClient.entryUrl)
                }.also { webViewRef.value = it }
            },
            // Fires only when the AndroidView leaves composition, i.e. after the nav
            // transition has disposed the page, so push/pop never tears the WebView down
            // mid-animation. Nulling the ref first silences any in-flight JS callback.
            onRelease = { released ->
                if (webViewRef.value === released) webViewRef.value = null
                released.destroy()
            },
        )
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

/** Supplies the optional built-in data bridge script; all other loads stay in WebView. */
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
 * Submission returns immediately to the WebView JS thread. Work runs on IO workers;
 * completion is posted to the live WebView on the main thread using the request ID.
 * The page's composition scope stops callbacks as soon as the page is removed; requests
 * are not retried.
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
                    } else if (op == "net.request") {
                        JSONObject().put("ok", false).put("error", JSONObject()
                            .put("code", "UNSUPPORTED_OP")
                            .put("message", "设置页请直接使用 fetch 或 XMLHttpRequest。"))
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
