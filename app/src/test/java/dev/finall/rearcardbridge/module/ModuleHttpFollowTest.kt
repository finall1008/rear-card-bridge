package dev.finall.rearcardbridge.module

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback tests for the net.request HTTP loop: real HttpURLConnection traffic
 * against a tiny raw-socket HTTP/1.1 server. Android unit tests compile against
 * android.jar, which does not contain com.sun.net.httpserver, so the test server
 * sticks to java.net. No mocking framework and no org.json — the engine returns
 * plain values on purpose.
 */
class ModuleHttpFollowTest {
    private val servers = mutableListOf<LoopbackServer>()

    @After
    fun stopServers() {
        servers.forEach { server -> server.close() }
    }

    private fun startServer(handler: (RecordedRequest) -> RecordedResponse): LoopbackServer =
        LoopbackServer(handler).also { servers += it }

    private fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        follow: Boolean = true,
    ) = ModuleHttp.Request(
        url = url,
        method = method,
        headers = headers,
        body = body,
        followRedirects = follow,
        timeoutMillis = 5_000,
        maxResponseBytes = 1024 * 1024,
    )

    /** Snapshot under the list's own monitor so handler and test threads agree. */
    private fun <T> List<T>.snapshot(): List<T> = synchronized(this) { toList() }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private data class RecordedResponse(
        val status: Int,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: String = "",
    )

    private val reasonPhrases = mapOf(
        200 to "OK",
        302 to "Found",
        307 to "Temporary Redirect",
        404 to "Not Found",
    )

    /** Minimal sequential HTTP/1.1 server: one request per connection, then close. */
    private inner class LoopbackServer(
        private val handler: (RecordedRequest) -> RecordedResponse,
    ) : AutoCloseable {
        private val listener = ServerSocket()
        private val serverThread: Thread

        init {
            listener.bind(InetSocketAddress("127.0.0.1", 0))
            serverThread = Thread { acceptLoop() }.apply {
                isDaemon = true
                name = "module-http-test"
            }
            serverThread.start()
        }

        fun base(): String = "http://127.0.0.1:${listener.localPort}"

        override fun close() {
            listener.close()
        }

        private fun acceptLoop() {
            while (!listener.isClosed) {
                val connection = try {
                    listener.accept()
                } catch (error: IOException) {
                    break
                }
                try {
                    serve(connection)
                } catch (error: Exception) {
                    // The asserting client thread surfaces protocol problems instead.
                } finally {
                    try {
                        connection.close()
                    } catch (ignored: IOException) {
                    }
                }
            }
        }

        private fun serve(connection: Socket) {
            val input = connection.getInputStream()
            val output = connection.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            require(parts.size >= 3) { "malformed request line: $requestLine" }
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            check(!headers.containsKey("transfer-encoding")) {
                "the test server only reads Content-Length request bodies"
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val bodyBytes = if (length > 0) readFully(input, length) else ByteArray(0)
            val response = handler(
                RecordedRequest(
                    method = parts[0],
                    path = parts[1].substringBefore('?'),
                    headers = headers,
                    body = bodyBytes.toString(Charsets.UTF_8),
                ),
            )
            writeResponse(output, response)
        }

        private fun writeResponse(output: OutputStream, response: RecordedResponse) {
            val body = response.body.toByteArray(Charsets.UTF_8)
            val headerLines = response.headers.map { (name, value) -> "$name: $value" }
                .toMutableList()
            if (body.isNotEmpty() &&
                headerLines.none { line -> line.substringBefore(':').equals("Content-Type", ignoreCase = true) }
            ) {
                headerLines += "Content-Type: text/plain; charset=utf-8"
            }
            val head = StringBuilder()
                .append("HTTP/1.1 ")
                .append(response.status)
                .append(' ')
                .append(reasonPhrases[response.status] ?: "Status")
                .append("\r\n")
            headerLines.forEach { line -> head.append(line).append("\r\n") }
            head.append("Content-Length: ").append(body.size).append("\r\n")
                .append("Connection: close\r\n\r\n")
            output.write(head.toString().toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }

        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (line.size() == 0) null else line.toByteArray().toString(Charsets.US_ASCII)
                if (byte == '\n'.code) break
                if (byte != '\r'.code) line.write(byte)
            }
            return line.toByteArray().toString(Charsets.US_ASCII)
        }

        private fun readFully(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) throw IOException("connection closed after ${offset} of $length body bytes")
                offset += read
            }
            return bytes
        }
    }

    /** In-memory jar standing in for ModuleWebProfiles' profile CookieManager. */
    private class RecordingCookieStore : ModuleHttp.CookieStore {
        val stored = Collections.synchronizedList(mutableListOf<Pair<String, List<String>>>())
        @Volatile
        var jar: String? = null

        override fun cookiesFor(url: String): String? = jar

        override fun store(url: String, setCookies: List<String>) {
            if (setCookies.isEmpty()) return
            stored += url to setCookies.toList()
            jar = setCookies.joinToString("; ") { value -> value.substringBefore(";") }
        }
    }

    @Test
    fun `follow saves each hop Set-Cookie before the next request and sends jar cookies onward`() {
        val store = RecordingCookieStore()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val server = startServer { request ->
            when (request.path) {
                "/start" -> {
                    events += "start"
                    RecordedResponse(
                        302,
                        headers = listOf(
                            "Location" to "/next",
                            "Set-Cookie" to "step=1; Path=/",
                        ),
                    )
                }
                "/next" -> {
                    // store() must already have run when hop 2 arrives.
                    events += "next cookie=${request.headers["cookie"]} stored=${store.stored.size}"
                    RecordedResponse(200, body = "done")
                }
                else -> RecordedResponse(404, body = "nope")
            }
        }
        val url = server.base()

        val response = ModuleHttp.execute(request("$url/start"), store)

        assertEquals(200, response.status)
        assertEquals("$url/next", response.url)
        assertEquals("done", response.body.toString(Charsets.UTF_8))
        assertEquals(listOf("start", "next cookie=step=1 stored=1"), events.snapshot())
        val stored = store.stored.snapshot()
        assertEquals(1, stored.size)
        assertEquals("$url/start", stored[0].first)
        assertTrue(stored[0].second.single().startsWith("step=1"))
        assertTrue(response.headers.keys.all { name -> name == name.lowercase() })
        assertTrue(response.headers.containsKey("content-type"))
    }

    @Test
    fun `manual mode returns the current hop and still saves its cookies`() {
        val store = RecordingCookieStore()
        val nextHits = AtomicInteger()
        val server = startServer { request ->
            when (request.path) {
                "/start" -> RecordedResponse(
                    302,
                    headers = listOf(
                        "Location" to "/next",
                        "Set-Cookie" to "m=1; Path=/",
                        "X-Mixed-Case" to "Value",
                    ),
                )
                "/next" -> {
                    nextHits.incrementAndGet()
                    RecordedResponse(200, body = "should not run")
                }
                else -> RecordedResponse(404, body = "nope")
            }
        }
        val url = server.base()

        val response = ModuleHttp.execute(request("$url/start", follow = false), store)

        assertEquals(302, response.status)
        assertEquals("$url/start", response.url)
        assertEquals(listOf("/next"), response.headers["location"])
        assertEquals(listOf("Value"), response.headers["x-mixed-case"])
        val stored = store.stored.snapshot()
        assertEquals(1, stored.size)
        assertTrue(stored[0].second.single().startsWith("m=1"))
        assertEquals(0, nextHits.get())
        assertTrue(response.headers.keys.all { name -> name == name.lowercase() })
    }

    @Test
    fun `repeated Set-Cookie headers stay separate values and all reach the jar`() {
        val store = RecordingCookieStore()
        val server = startServer {
            RecordedResponse(
                200,
                headers = listOf(
                    "Set-Cookie" to "a=1; Expires=Wed, 21 Oct 2030 07:28:00 GMT",
                    "Set-Cookie" to "b=2; Path=/",
                ),
                body = "ok",
            )
        }

        val response = ModuleHttp.execute(request(server.base() + "/x"), store)

        assertEquals(200, response.status)
        val setCookies = requireNotNull(response.headers["set-cookie"])
        assertEquals(2, setCookies.size)
        assertTrue(setCookies[0].startsWith("a=1"))
        assertTrue(setCookies[1].startsWith("b=2"))
        val stored = store.stored.snapshot()
        assertEquals(1, stored.size)
        assertEquals(2, stored[0].second.size)
    }

    @Test
    fun `cross origin redirect drops caller credentials but keeps other headers`() {
        val seen = Collections.synchronizedList(mutableListOf<Map<String, String?>>())
        val target = startServer { request ->
            seen += mapOf(
                "authorization" to request.headers["authorization"],
                "cookie" to request.headers["cookie"],
                "proxy" to request.headers["proxy-authorization"],
                "keep" to request.headers["x-keep"],
            )
            RecordedResponse(200, body = "landed")
        }
        val entry = startServer {
            RecordedResponse(302, headers = listOf("Location" to target.base() + "/landing"))
        }

        val response = ModuleHttp.execute(
            request(
                entry.base() + "/start",
                headers = linkedMapOf(
                    "Authorization" to "Bearer t",
                    "Cookie" to "a=b",
                    "Proxy-Authorization" to "Basic c",
                    "X-Keep" to "1",
                ),
            ),
            null,
        )

        assertEquals(200, response.status)
        assertEquals("landed", response.body.toString(Charsets.UTF_8))
        val hop = seen.snapshot().single()
        assertNull(hop["authorization"])
        assertNull(hop["cookie"])
        assertNull(hop["proxy"])
        assertEquals("1", hop["keep"])
    }

    @Test
    fun `same origin redirect keeps caller credentials`() {
        val seen = Collections.synchronizedList(mutableListOf<String?>())
        val server = startServer { request ->
            when (request.path) {
                "/start" -> RecordedResponse(302, headers = listOf("Location" to "/same"))
                "/same" -> {
                    seen += request.headers["authorization"]
                    RecordedResponse(200, body = "ok")
                }
                else -> RecordedResponse(404, body = "nope")
            }
        }

        val response = ModuleHttp.execute(
            request(server.base() + "/start", headers = mapOf("Authorization" to "Bearer t")),
            null,
        )

        assertEquals(200, response.status)
        assertEquals(listOf("Bearer t"), seen.snapshot())
    }

    @Test
    fun `302 turns POST into GET and drops body and body headers`() {
        val seen = Collections.synchronizedList(mutableListOf<Triple<String, String, String?>>())
        val server = startServer { request ->
            seen += Triple(request.method, request.body, request.headers["content-type"])
            when (request.path) {
                "/post" -> RecordedResponse(302, headers = listOf("Location" to "/landing"))
                else -> RecordedResponse(200, body = "ok")
            }
        }

        val response = ModuleHttp.execute(
            request(
                server.base() + "/post",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"a":1}""".toByteArray(Charsets.UTF_8),
            ),
            null,
        )

        assertEquals(200, response.status)
        assertEquals(
            listOf(
                Triple("POST", """{"a":1}""", "application/json"),
                Triple("GET", "", null),
            ),
            seen.snapshot(),
        )
    }

    @Test
    fun `307 keeps POST method body and content type`() {
        val seen = Collections.synchronizedList(mutableListOf<Triple<String, String, String?>>())
        val server = startServer { request ->
            seen += Triple(request.method, request.body, request.headers["content-type"])
            when (request.path) {
                "/post" -> RecordedResponse(307, headers = listOf("Location" to "/landing"))
                else -> RecordedResponse(200, body = "ok")
            }
        }

        val response = ModuleHttp.execute(
            request(
                server.base() + "/post",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"a":1}""".toByteArray(Charsets.UTF_8),
            ),
            null,
        )

        assertEquals(200, response.status)
        assertEquals(
            listOf(
                Triple("POST", """{"a":1}""", "application/json"),
                Triple("POST", """{"a":1}""", "application/json"),
            ),
            seen.snapshot(),
        )
    }

    @Test
    fun `follow gives up after eight redirects`() {
        val server = startServer {
            RecordedResponse(302, headers = listOf("Location" to "/loop"))
        }

        val error = assertThrows(IllegalStateException::class.java) {
            ModuleHttp.execute(request(server.base() + "/loop"), null)
        }
        assertTrue(error.message!!.contains("8"))
    }
}
