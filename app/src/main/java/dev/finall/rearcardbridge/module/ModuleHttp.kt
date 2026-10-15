package dev.finall.rearcardbridge.module

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native HttpURLConnection loop behind [ModuleBackend] `net.request`.
 *
 * Deliberately free of android.* and org.json dependencies: redirect and credential
 * policy rules stay plain-JVM unit testable, while only [ModuleBackend] touches the
 * JSON envelope, Base64 and the per-module cookie profile. No global CookieHandler
 * or global CookieManager is ever consulted; cookies are read and stored hop by hop
 * through an injected [CookieStore] built from ModuleWebProfiles, or not at all.
 *
 * [Request.method] must already be upper-case and [Request.body] already checked
 * against the caller's body size cap.
 */
internal object ModuleHttp {
    /** Follow at most this many redirects; a further 3xx fails instead of looping. */
    internal const val MAX_REDIRECTS = 8

    /** Caller headers dropped when a redirect changes origin. */
    internal val CREDENTIAL_HEADERS = setOf("authorization", "cookie", "proxy-authorization")

    /** Caller headers dropped when a redirect rewrites the method to GET. */
    internal val BODY_HEADERS =
        setOf("content-type", "content-length", "content-encoding", "transfer-encoding")

    /** Only these statuses are followed; everything else (300/304/…) is final. */
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

    /** Per-module cookie profile access; the backend backs this with ModuleWebProfiles. */
    internal interface CookieStore {
        /** Matching cookie header for [url], or null when the jar holds nothing. */
        fun cookiesFor(url: String): String?

        /** Persist every Set-Cookie of the response received for [url]. */
        fun store(url: String, setCookies: List<String>)
    }

    internal class Request(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val followRedirects: Boolean,
        val timeoutMillis: Int,
        val maxResponseBytes: Int,
    )

    internal class Response(
        val status: Int,
        val url: String,
        /** Response header names lower-cased; repeated headers keep every value. */
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    )

    /** What the follow loop does with a followable 3xx response. */
    internal data class RedirectPlan(
        val url: String,
        val method: String,
        val dropBody: Boolean,
        val crossOrigin: Boolean,
    )

    /**
     * Send one request, following up to [MAX_REDIRECTS] redirects when
     * [Request.followRedirects] is set. Each hop recomputes jar cookies for its own
     * destination and stores that hop's Set-Cookie before any redirect decision;
     * manual mode stores the single response's cookies and returns it untouched.
     */
    internal fun execute(request: Request, cookies: CookieStore?): Response {
        var url = request.url
        var method = request.method
        var headers = request.headers
        var body = request.body
        var redirects = 0
        while (true) {
            val connection = URL(url).openConnection() as HttpURLConnection
            var plan: RedirectPlan? = null
            try {
                connection.connectTimeout = request.timeoutMillis
                connection.readTimeout = request.timeoutMillis
                connection.requestMethod = method
                // Redirects are planned here (never by HttpURLConnection) so every
                // hop stores cookies and drops credentials before the next request.
                connection.instanceFollowRedirects = false
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                cookies?.cookiesFor(url)
                    ?.takeIf { value -> value.isNotBlank() }
                    ?.let { value -> connection.setRequestProperty("Cookie", value) }
                body?.let { bytes ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                val responseHeaders = collectHeaders(connection)
                // Update the profile before following any redirect; manual mode keeps
                // it warm for the module's next explicit hop as well.
                cookies?.store(url, responseHeaders["set-cookie"].orEmpty())
                plan = if (request.followRedirects) {
                    redirectPlan(url, method, status, responseHeaders["location"]?.firstOrNull(), redirects)
                } else {
                    null
                }
                if (plan == null) {
                    return readResponse(connection, status, url, responseHeaders, request.maxResponseBytes)
                }
            } finally {
                connection.disconnect()
            }
            val next = checkNotNull(plan) { "redirect plan missing after a redirect response" }
            var nextHeaders = headers
            var nextBody = body
            if (next.crossOrigin) nextHeaders = stripHeaders(nextHeaders, CREDENTIAL_HEADERS)
            if (next.dropBody) {
                nextHeaders = stripHeaders(nextHeaders, BODY_HEADERS)
                nextBody = null
            }
            url = next.url
            method = next.method
            headers = nextHeaders
            body = nextBody
            redirects++
        }
    }

    /**
     * Plan the next hop of a follow-mode redirect, or return null when the response
     * is final (not a redirect status, or no usable Location). Throws for redirects
     * that must fail loudly instead of being followed: the redirect budget, a
     * non-HTTP(S) target, and HTTPS downgraded to HTTP (never send credentials to
     * plain HTTP automatically — the module can handle that hop itself in manual mode).
     */
    internal fun redirectPlan(
        currentUrl: String,
        method: String,
        status: Int,
        location: String?,
        redirectsSoFar: Int,
    ): RedirectPlan? {
        if (status !in REDIRECT_STATUSES || location.isNullOrBlank()) return null
        if (redirectsSoFar >= MAX_REDIRECTS) {
            throw IllegalStateException("重定向次数超过 $MAX_REDIRECTS 次，已停止跟随。")
        }
        val target = URL(URL(currentUrl), location)
        val scheme = target.protocol.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IllegalStateException("无法跟随重定向：仅支持 http/https 地址。")
        }
        if (URL(currentUrl).protocol.equals("https", ignoreCase = true) && scheme == "http") {
            throw IllegalStateException(
                "已阻止 HTTPS 降级到 HTTP 的自动跳转；请改用 redirect:\"manual\" 自行处理。",
            )
        }
        return RedirectPlan(
            url = target.toString(),
            method = redirectMethod(status, method),
            dropBody = dropsBody(status, method),
            crossOrigin = !sameOrigin(currentUrl, target.toString()),
        )
    }

    /** 301/302 demote only POST; 303 demotes everything except HEAD; 307/308 keep the method. */
    internal fun redirectMethod(status: Int, method: String): String = when (status) {
        301, 302 -> if (method == "POST") "GET" else method
        303 -> if (method == "HEAD") "HEAD" else "GET"
        else -> method
    }

    /** Only hops rewritten to GET lose their body (and later their body headers). */
    internal fun dropsBody(status: Int, method: String): Boolean = when (status) {
        301, 302 -> method == "POST"
        303 -> method != "HEAD"
        else -> false
    }

    /** Origin = scheme + host + effective port; unspecified ports equal the scheme default. */
    internal fun sameOrigin(left: String, right: String): Boolean {
        val a = URL(left)
        val b = URL(right)
        return a.protocol.equals(b.protocol, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)
    }

    internal fun stripHeaders(headers: Map<String, String>, names: Set<String>): Map<String, String> =
        headers.filterKeys { name -> name.lowercase() !in names }

    private fun effectivePort(url: URL): Int = when {
        url.port != -1 -> url.port
        url.protocol.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun collectHeaders(connection: HttpURLConnection): Map<String, List<String>> {
        val collected = LinkedHashMap<String, List<String>>()
        connection.headerFields?.forEach { (name, values) ->
            if (name != null && values != null) {
                val key = name.lowercase()
                val merged = collected[key]
                collected[key] = if (merged == null) values.toList() else merged + values
            }
        }
        return collected
    }

    private fun readResponse(
        connection: HttpURLConnection,
        status: Int,
        url: String,
        headers: Map<String, List<String>>,
        maxResponseBytes: Int,
    ): Response {
        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
        val bytes = stream?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                require(total <= maxResponseBytes) { "响应内容过大。" }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: ByteArray(0)
        return Response(status, url, headers, bytes)
    }
}
