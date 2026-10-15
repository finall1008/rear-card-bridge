package dev.finall.rearcardbridge.module

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.finall.rearcardbridge.live.ContentOperations
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Executes one background refresh task of a module in its own headless WebView.
 * The module ships an async `refresh()` in background/main.js; after the entry script
 * is evaluated the runner awaits `refresh()` once, then destroys the WebView.
 * The whole task (load + scripts + refresh) is bounded by [RUN_TIMEOUT_MILLIS]; when it
 * ends — success, script error, timeout, renderer death or service stop — pending bridge
 * callbacks are dropped and the WebView destroyed, so a stopped scheduler cannot keep
 * saving snapshots. Periodic scheduling stays in [BackgroundRefreshScheduler]; the page
 * must not (and effectively cannot) rely on JS timers across tasks.
 *
 * Script text is injected with evaluateJavascript on a blank page, never inside HTML,
 * so no script-closing escaping is needed. No JS engine dependency, no script analysis
 * or validation sandbox: the installed module is trusted, errors are reported as-is.
 * WebView is created and destroyed on the main thread (lifecycle rules); bridge backend
 * work runs on IO workers and posts callbacks back to the live WebView.
 */
class BackgroundModuleRunner(context: Context) {
    private val appContext = context.applicationContext
    private val store = ModuleStore(context)
    private val backend = ModuleBackend(context, store)

    /** Runs a single background refresh; throws on any failure for the service to record. */
    suspend fun run(module: InstalledModule) {
        requireNotNull(module.manifest.backgroundIntervalMinutes) { "模块未声明后台刷新入口。" }
        val scripts = withContext(Dispatchers.IO) {
            val entry = File(module.packageDir, BACKGROUND_ENTRY)
            require(entry.isFile) { "模块缺少 $BACKGROUND_ENTRY。" }
            // Shared front/back snapshot as of task start; the page gets it verbatim, so
            // readSnapshot() matches what the card-side localStorage entry would hold.
            val snapshot = synchronized(ContentOperations.lock) {
                runCatching { store.readSnapshot(module.manifest.id) }.getOrNull()
            }.orEmpty().trim()
            Scripts(
                bridge = appContext.assets.open(BRIDGE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() },
                entry = entry.readText(Charsets.UTF_8),
                snapshot = snapshot.ifEmpty { "null" },
            )
        }
        try {
            withTimeout(RUN_TIMEOUT_MILLIS) { execute(module, scripts) }
        } catch (error: TimeoutCancellationException) {
            // Job cancellation is a stop signal; a module's own timeout is a failed attempt.
            throw IllegalStateException("后台刷新超过 ${RUN_TIMEOUT_MILLIS / 1000} 秒未完成，已取消本次执行。")
        }
    }

    private suspend fun execute(module: InstalledModule, scripts: Scripts) =
        withContext(Dispatchers.Main.immediate) {
            val closed = AtomicBoolean(false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val loadResult = CompletableDeferred<Unit>()
            val runResult = CompletableDeferred<String>()
            val consoleError = AtomicReference<String?>(null)
            val webView = WebView(appContext)
            try {
                // Use the same module profile as settings/login, never the global default.
                ModuleWebProfiles.attach(webView, module.manifest.id)
                // The installed module is trusted: no script sandbox, and no WebView capability
                // lockdown beyond JavaScript (background networking goes through the native bridge).
                webView.settings.javaScriptEnabled = true
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            consoleError.set(message.message())
                        }
                        Log.w(LOG_TAG, "后台脚本控制台：${message.message()}")
                        return true
                    }
                }
                val bridge = BackgroundBridge(
                    moduleId = module.manifest.id,
                    backend = backend,
                    scope = scope,
                    closed = closed,
                    webView = { webView },
                    onRunComplete = { runResult.complete(it) },
                )
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loadResult.complete(Unit)
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            val failure = IllegalStateException("后台刷新页面加载失败：${error.description}")
                            loadResult.completeExceptionally(failure)
                            runResult.completeExceptionally(failure)
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?,
                    ): Boolean {
                        val failure = IllegalStateException("WebView 渲染进程已退出，后台刷新终止。")
                        loadResult.completeExceptionally(failure)
                        runResult.completeExceptionally(failure)
                        return true
                    }
                }
                webView.addJavascriptInterface(bridge, BRIDGE_NAME)
                webView.loadUrl("about:blank")
                loadResult.await()
                // Inject as a quoted string and JSON.parse in the page: raw JSON text is not a
                // safe JS literal (U+2028/2029), while JSONObject.quote always is. EvaluateJavascript
                // keeps script text out of HTML, so "</script>" inside scripts is harmless.
                webView.evaluateJavascript(
                    "window.__rearCardBackgroundSnapshotText = ${JSONObject.quote(scripts.snapshot)};",
                    null,
                )
                webView.evaluateJavascript(scripts.bridge, null)
                webView.evaluateJavascript(scripts.entry, null)
                webView.evaluateJavascript(DRIVER_SCRIPT, null)
                val scriptError = runResult.await()
                if (scriptError.isNotEmpty()) {
                    val detail = consoleError.get()?.let { "$scriptError（控制台：$it）" } ?: scriptError
                    throw IllegalStateException("后台刷新脚本失败：$detail")
                }
            } finally {
                // Success, error, timeout or cancellation: no bridge callback survives this task.
                closed.set(true)
                scope.cancel()
                webView.destroy()
            }
        }

    private class Scripts(val bridge: String, val entry: String, val snapshot: String)

    companion object {
        internal const val RUN_TIMEOUT_MILLIS = 60_000L
        internal const val BRIDGE_NAME = "BridgeNative"
        private const val BRIDGE_ASSET = "background-bridge.js"
        private const val BACKGROUND_ENTRY = "background/main.js"
        private const val LOG_TAG = "CardBackgroundRefresh"

        /** Invoked after the entry script; reports "" on success or the refresh() failure. */
        private val DRIVER_SCRIPT = """
            (function () {
              function done(message) {
                try { window.BridgeNative.completeRun(String(message || '')); } catch (error) {}
              }
              if (typeof refresh !== 'function') {
                done('background/main.js 未定义 async function refresh()');
                return;
              }
              try {
                Promise.resolve(refresh()).then(function () { done(''); },
                  function (error) { done(error && error.message ? error.message : String(error)); });
              } catch (error) {
                done(error && error.message ? error.message : String(error));
              }
            })();
        """.trimIndent()
    }
}

/**
 * Headless bridge object for background scripts, same submit-and-callback protocol as the
 * settings WebUI: invoke() returns to the JS thread immediately, backend work runs on IO,
 * the completion is posted into the live WebView. Once [closed] flips, no new operation
 * executes and no callback is posted.
 */
private class BackgroundBridge(
    private val moduleId: String,
    private val backend: ModuleBackend,
    private val scope: CoroutineScope,
    private val closed: AtomicBoolean,
    private val webView: () -> WebView,
    private val onRunComplete: (String) -> Unit,
) {
    @JavascriptInterface
    fun invoke(requestId: String, payload: String) {
        if (closed.get()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val request = try {
                    JSONObject(payload)
                } catch (error: Exception) {
                    return@withContext invalidArgs()
                }
                val op = request.optString("op")
                if (op == "net.request") {
                    // Network requests take no local lock; a completed stop only drops the callback.
                    if (closed.get()) stopped() else execute(op, request)
                } else {
                    // Local ops run under the shared lock in the backend. Re-check closed inside
                    // that lock: a saveSnapshot that queued behind it must not write after a stop.
                    synchronized(ContentOperations.lock) {
                        if (closed.get()) stopped() else execute(op, request)
                    }
                }
            }
            if (closed.get()) return@launch
            val callback = "window.__rearCardBridgeComplete && " +
                "window.__rearCardBridgeComplete(${JSONObject.quote(requestId)},$result);"
            runCatching { webView().evaluateJavascript(callback, null) }
        }
    }

    private fun execute(op: String, request: JSONObject): JSONObject = try {
        backend.execute(moduleId, op, request.optJSONObject("args"))
    } catch (error: Exception) {
        invalidArgs()
    }

    /** Reports the driver outcome ('' = refresh() resolved) from the page JS thread. */
    @JavascriptInterface
    fun completeRun(message: String) {
        if (closed.get()) return
        onRunComplete(message)
    }

    private fun stopped() = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", "STOPPED").put("message", "后台刷新已停止。"))

    private fun invalidArgs() = JSONObject().put("ok", false)
        .put("error", JSONObject().put("code", "INVALID_ARGS").put("message", "请求格式无效。"))
}
