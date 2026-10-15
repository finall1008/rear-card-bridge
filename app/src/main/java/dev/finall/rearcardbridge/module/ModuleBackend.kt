package dev.finall.rearcardbridge.module

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.finall.rearcardbridge.live.ContentOperations
import java.io.File
import java.net.SocketTimeoutException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared executor behind the card-side bridge provider and the settings WebUI.
 * Every operation returns {"ok":true,"value":...} or {"ok":false,"error":{code,message}}.
 * Calls are blocking: the provider and WebUI host dispatch network requests to workers.
 * Local read-modify-write operations are serialized; a pending network request does not
 * hold that lock or block settings/configuration operations.
 */
class ModuleBackend(context: Context, private val store: ModuleStore = ModuleStore(context)) {
    fun execute(moduleId: String, op: String, args: JSONObject?): JSONObject {
        return try {
            val module = store.get(moduleId)
                ?: return error("MODULE_NOT_FOUND", "模块未安装：$moduleId")
            val capabilities = module.manifest.capabilities
            when {
                op.startsWith("storage.") -> requireCapability(capabilities, "storage", op)
                op.startsWith("files.") -> requireCapability(capabilities, "files", op)
                op == "net.request" -> requireCapability(capabilities, "network", op)
            }
            val value = if (op == "net.request") {
                executeOp(moduleId, module, op, args ?: JSONObject())
            } else synchronized(ContentOperations.lock) {
                executeOp(moduleId, module, op, args ?: JSONObject())
            }
            ok(value)
        } catch (error: SocketTimeoutException) {
            error("TIMEOUT", "网络请求超时，结果未知；请核实服务端结果，不要自动重试付费请求。")
        } catch (error: CapabilityDenied) {
            error("CAPABILITY_DENIED", error.message ?: "模块未声明所需能力。")
        } catch (error: IllegalArgumentException) {
            error("INVALID_ARGS", error.message ?: "参数无效。")
        } catch (error: UnsupportedOperationException) {
            error("UNSUPPORTED", error.message ?: "当前系统不支持该功能。")
        } catch (error: Exception) {
            error("ERROR", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun executeOp(moduleId: String, module: InstalledModule, op: String, args: JSONObject): Any? =
        when (op) {
            "module.info" -> JSONObject()
                .put("id", module.manifest.id)
                .put("name", module.manifest.name)
                .put("version", module.manifest.version)
                .put("versionCode", module.manifest.versionCode)
                .put("apiVersion", module.manifest.apiVersion)
                .put("hasWebui", module.manifest.hasWebui)
            "config.get" -> {
                val config = store.readConfig(moduleId)
                val key = args.optString("key")
                if (key.isEmpty()) config else config.opt(key) ?: JSONObject.NULL
            }
            "config.set" -> {
                val key = args.optString("key")
                require(key.isNotEmpty()) { "config.set 需要 key。" }
                val config = store.readConfig(moduleId)
                config.put(key, args.opt("value") ?: JSONObject.NULL)
                store.writeConfig(moduleId, config)
                true
            }
            "storage.get" -> {
                val key = requiredKey(args, "storage.get")
                store.readStorage(moduleId).opt(key) ?: JSONObject.NULL
            }
            "storage.set" -> {
                val key = requiredKey(args, "storage.set")
                val value = args.opt("value") ?: JSONObject.NULL
                require(value.toString().length <= MAX_VALUE_CHARS) { "存储内容过大。" }
                val storage = store.readStorage(moduleId)
                storage.put(key, value)
                store.writeStorage(moduleId, storage)
                true
            }
            "storage.remove" -> {
                val key = requiredKey(args, "storage.remove")
                val storage = store.readStorage(moduleId)
                storage.remove(key)
                store.writeStorage(moduleId, storage)
                true
            }
            "storage.keys" -> JSONArray().apply {
                store.readStorage(moduleId).keys().forEach { put(it) }
            }
            "files.list" -> {
                val dir = store.resolveInFiles(moduleId, args.optString("path"))
                require(dir.isDirectory) { "目录不存在。" }
                JSONArray().apply {
                    dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                        put(JSONObject().put("name", file.name)
                            .put("dir", file.isDirectory).put("size", if (file.isFile) file.length() else 0L))
                    }
                }
            }
            "files.readText" -> {
                val file = store.resolveInFiles(moduleId, requiredPath(args))
                require(file.isFile) { "文件不存在。" }
                require(file.length() <= MAX_READ_CHARS) { "文件过大。" }
                file.readText(Charsets.UTF_8)
            }
            "files.writeText" -> {
                val content = args.optString("content")
                require(content.length <= MAX_WRITE_CHARS) { "写入内容过大。" }
                val file = store.resolveInFiles(moduleId, requiredPath(args))
                check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
                    "无法创建目录。"
                }
                file.writeText(content, Charsets.UTF_8)
                true
            }
            "files.remove" -> {
                val file = store.resolveInFiles(moduleId, requiredPath(args))
                if (file.isDirectory) file.deleteRecursively() else file.delete()
                true
            }
            "net.request" -> httpRequest(moduleId, args)
            "log" -> {
                val level = args.optString("level").ifBlank { "info" }
                val message = args.optString("message").take(1000)
                val tag = "CardModule.$moduleId"
                when (level) {
                    "warn" -> Log.w(tag, message)
                    "error" -> Log.e(tag, message)
                    else -> Log.i(tag, message)
                }
                true
            }
            "card.requestRefresh" -> {
                store.bumpRefreshRev(moduleId)
                true
            }
            else -> throw IllegalArgumentException("未知操作：$op")
        }

    /**
     * net.request runs on the caller's worker (bridge executor / settings IO) and
     * stays outside [ContentOperations.lock]. `cookies: true` swaps the per-module
     * profile jar into [ModuleHttp] for this call only; without it, caller headers
     * — including an explicit Cookie header — are sent untouched.
     */
    private fun httpRequest(moduleId: String, args: JSONObject): JSONObject {
        val url = args.optString("url")
        require(url.startsWith("https://") || url.startsWith("http://")) { "仅支持 http/https 地址。" }
        val method = args.optString("method").ifBlank { "GET" }.uppercase()
        val responseType = args.optString("responseType").ifBlank { "text" }
        require(responseType == "text" || responseType == "base64") { "responseType 仅支持 text / base64。" }
        val timeout = networkTimeoutMillis(
            if (args.isNull("timeoutMs")) null else args.getInt("timeoutMs"),
        )
        val cookiesEnabled = when (val value = args.opt("cookies")) {
            null, JSONObject.NULL -> false
            is Boolean -> value
            else -> throw IllegalArgumentException("cookies 仅支持 true / false。")
        }
        val redirectMode = args.optString("redirect").ifBlank { "follow" }
        require(redirectMode == "follow" || redirectMode == "manual") { "redirect 仅支持 follow / manual。" }
        val headers = LinkedHashMap<String, String>()
        val requestHeaders = args.optJSONObject("headers")
        requestHeaders?.keys()?.forEach { key ->
            if (cookiesEnabled && key.equals("Cookie", ignoreCase = true)) {
                throw IllegalArgumentException(
                    "cookies:true 时 Cookie 由本模块 Profile 统一提供，不能显式传 Cookie 请求头。",
                )
            }
            headers[key] = requestHeaders.optString(key)
        }
        val bodyText = args.optString("body")
        val bodyBase64 = args.optString("bodyBase64")
        val bodyBytes = when {
            bodyBase64.isNotEmpty() -> Base64.decode(bodyBase64, Base64.DEFAULT)
            bodyText.isNotEmpty() -> bodyText.toByteArray(Charsets.UTF_8)
            else -> null
        }
        if (bodyBytes != null) require(bodyBytes.size <= MAX_BODY_BYTES) { "请求体过大。" }
        val cookieStore = if (cookiesEnabled) profileCookieStore(moduleId) else null
        val response = ModuleHttp.execute(
            ModuleHttp.Request(
                url = url,
                method = method,
                headers = headers,
                body = bodyBytes,
                followRedirects = redirectMode == "follow",
                timeoutMillis = timeout,
                maxResponseBytes = MAX_RESPONSE_BYTES,
            ),
            cookieStore,
        )
        val headerJson = JSONObject()
        response.headers.forEach { (name, values) ->
            headerJson.put(name, JSONArray().also { array -> values.forEach { array.put(it) } })
        }
        val result = JSONObject()
            .put("status", response.status)
            .put("url", response.url)
            .put("headers", headerJson)
        if (responseType == "base64") {
            result.put("bodyBase64", Base64.encodeToString(response.body, Base64.NO_WRAP))
        } else {
            result.put("body", response.body.toString(Charsets.UTF_8))
        }
        return result
    }

    /** Main-profile CookieManager from the worker; throws UNSUPPORTED without MULTI_PROFILE. */
    private fun profileCookieStore(moduleId: String): ModuleHttp.CookieStore {
        val manager = ModuleWebProfiles.cookieManager(moduleId)
        return object : ModuleHttp.CookieStore {
            override fun cookiesFor(url: String): String? = manager.getCookie(url)
            override fun store(url: String, setCookies: List<String>) {
                ModuleWebProfiles.saveCookies(manager, url, setCookies)
            }
        }
    }

    private fun requiredKey(args: JSONObject, op: String): String {
        val key = args.optString("key")
        require(key.isNotEmpty()) { "$op 需要 key。" }
        return key
    }

    private fun requiredPath(args: JSONObject): String {
        val path = args.optString("path")
        require(path.isNotEmpty()) { "缺少 path。" }
        return path
    }

    private fun requireCapability(capabilities: Set<String>, capability: String, op: String) {
        if (capability !in capabilities) {
            throw CapabilityDenied("模块未声明「$capability」能力，不能执行 $op。")
        }
    }

    private class CapabilityDenied(message: String) : Exception(message)

    private fun ok(value: Any?): JSONObject =
        JSONObject().put("ok", true).put("value", value ?: JSONObject.NULL)

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))

    companion object {
        /** Matches HttpURLConnection: zero disables its connection/read timeout. */
        internal fun networkTimeoutMillis(configured: Int?): Int {
            val timeout = configured ?: 15_000
            require(timeout >= 0) { "timeoutMs 不能为负数；0 表示不设超时。" }
            return timeout
        }

        private const val MAX_VALUE_CHARS = 256 * 1024
        private const val MAX_READ_CHARS = 512 * 1024
        private const val MAX_WRITE_CHARS = 1024 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}
