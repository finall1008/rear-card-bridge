package dev.finall.rearcardbridge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.finall.rearcardbridge.live.ContentOperations
import dev.finall.rearcardbridge.module.ModuleBackend
import dev.finall.rearcardbridge.module.ModuleStore
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Settings WebUI host for an installed card module, KernelSU-WebUI style: loads the module's
 * own webroot/index.html in a sandboxed WebView and exposes the native bridge as
 * window.BridgeNative ({@link NativeBridge}). Only file:// resources canonically inside the
 * module's webroot/ are served; everything else is cut off (http/https navigations are handed
 * to the system browser).
 */
class ModuleSettingsActivity : ComponentActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view != null && view.canGoBack()) view.goBack() else finish()
            }
        })

        val id = intent.getStringExtra(EXTRA_MODULE_ID)
        val store = ModuleStore(this)
        val module = id?.let { store.get(it) }
        if (id == null || module == null || !module.manifest.hasWebui) {
            showErrorPage()
            return
        }
        val webroot = File(module.packageDir, WEBROOT_DIR)
        val entryUrl = "file://${File(webroot, "index.html").absolutePath}"
        val backend = ModuleBackend(this, store)
        val title = module.manifest.name

        setContent {
            val themeController = remember { ThemeController() }
            MiuixTheme(controller = themeController) {
                Scaffold(topBar = {
                    SmallTopAppBar(title = title, navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    })
                }) { padding ->
                    AndroidView(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = false
                                settings.allowFileAccessFromFileURLs = false
                                settings.allowUniversalAccessFromFileURLs = false
                                webViewClient = WebUiClient(webroot)
                                addJavascriptInterface(NativeBridge(id, store, backend), BRIDGE_NAME)
                                loadUrl(entryUrl)
                            }.also { webView = it }
                        },
                    )
                }
            }
        }
    }

    private fun showErrorPage() {
        setContent {
            val themeController = remember { ThemeController() }
            MiuixTheme(controller = themeController) {
                Scaffold(topBar = { TopAppBar(title = "模块设置") }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                        Text("该模块不存在，或未提供设置页。")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary()) { Text("返回") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    /** Serves only files canonically inside [webroot]; falls back to the built-in bridge shim. */
    private inner class WebUiClient(private val webroot: File) : WebViewClient() {

        private val webrootPath: String = webroot.canonicalFile.path
        private val shimFile: File = File(webroot, SHIM_NAME).canonicalFile

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (request.url.scheme != "file") return null
            val path = request.url.path ?: return notFound()
            val target = File(path).canonicalFile
            if (target.path != webrootPath && !target.path.startsWith(webrootPath + File.separator)) {
                return notFound()
            }
            if (target == shimFile && !target.isFile) {
                return runCatching {
                    WebResourceResponse(SHIM_MIME, "UTF-8", assets.open(SHIM_NAME))
                }.getOrElse { notFound() }
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            when {
                url.scheme == "http" || url.scheme == "https" -> {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    return true
                }
                url.scheme == "file" -> {
                    val path = url.path ?: return true
                    val target = File(path).canonicalFile
                    return target.path != webrootPath &&
                        !target.path.startsWith(webrootPath + File.separator)
                }
                else -> return true
            }
        }

        private fun notFound(): WebResourceResponse = WebResourceResponse(
            "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)),
        )
    }

    /**
     * Submission returns immediately to the WebView JS thread. Work runs on IO workers;
     * completion is posted to the live WebView on the main thread using the request ID.
     * The Activity's lifecycle stops callbacks after it is destroyed; requests are not retried.
     */
    private inner class NativeBridge(
        private val moduleId: String,
        private val store: ModuleStore,
        private val backend: ModuleBackend,
    ) {
        @JavascriptInterface
        fun invoke(requestId: String, payload: String) {
            lifecycleScope.launch {
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
                webView?.evaluateJavascript(callback, null)
            }
        }
    }

    companion object {
        private const val EXTRA_MODULE_ID = "module_id"
        private const val WEBROOT_DIR = "webroot"
        private const val SHIM_NAME = "bridge-shim.js"
        private const val SHIM_MIME = "application/javascript"
        private const val BRIDGE_NAME = "BridgeNative"

        fun intent(context: Context, moduleId: String): Intent =
            Intent(context, ModuleSettingsActivity::class.java).putExtra(EXTRA_MODULE_ID, moduleId)
    }
}
