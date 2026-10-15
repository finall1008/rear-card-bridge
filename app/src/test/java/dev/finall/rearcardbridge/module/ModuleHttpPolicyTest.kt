package dev.finall.rearcardbridge.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy tests for the net.request redirect rules: no sockets, no Android APIs.
 */
class ModuleHttpPolicyTest {
    private fun plan(
        url: String,
        method: String,
        status: Int,
        location: String?,
        redirectsSoFar: Int = 0,
    ) = ModuleHttp.redirectPlan(url, method, status, location, redirectsSoFar)

    @Test
    fun `non-redirect statuses and missing locations are final`() {
        assertNull(plan("https://a.test/x", "GET", 200, null))
        assertNull(plan("https://a.test/x", "GET", 404, "/y"))
        assertNull(plan("https://a.test/x", "GET", 304, "/y"))
        assertNull(plan("https://a.test/x", "GET", 300, "/y"))
        assertNull(plan("https://a.test/x", "GET", 302, null))
        assertNull(plan("https://a.test/x", "GET", 302, "   "))
    }

    @Test
    fun `relative locations resolve against the current hop`() {
        val base = "https://a.test/x/y"
        assertEquals("https://a.test/z", plan(base, "GET", 302, "/z")!!.url)
        assertEquals("https://a.test/x/z", plan(base, "GET", 302, "z")!!.url)
        assertEquals("https://b.test:9/p", plan(base, "GET", 302, "//b.test:9/p")!!.url)
        assertEquals("https://other.test/p", plan(base, "GET", 302, "https://other.test/p")!!.url)
    }

    @Test
    fun `follow stops after eight redirects`() {
        // The eighth redirect still plans; a ninth is refused.
        assertNotNull(plan("http://a.test/1", "GET", 302, "/2", ModuleHttp.MAX_REDIRECTS - 1))
        assertEquals(8, ModuleHttp.MAX_REDIRECTS)
        val error = assertThrows(IllegalStateException::class.java) {
            plan("http://a.test/1", "GET", 302, "/2", ModuleHttp.MAX_REDIRECTS)
        }
        assertTrue(error.message!!.contains("8"))
    }

    @Test
    fun `https downgrade to http fails instead of sending credentials`() {
        val error = assertThrows(IllegalStateException::class.java) {
            plan("https://a.test/x", "GET", 302, "http://a.test/x")
        }
        assertTrue(error.message!!.contains("HTTPS"))
        // Same-scheme hops and http->https upgrades stay allowed.
        assertNotNull(plan("https://a.test/x", "GET", 302, "https://b.test/y"))
        assertNotNull(plan("http://a.test/x", "GET", 302, "https://a.test/y"))
        assertNotNull(plan("http://a.test/x", "GET", 302, "http://a.test/y"))
    }

    @Test
    fun `non http redirect targets are refused`() {
        assertThrows(IllegalStateException::class.java) {
            plan("http://a.test/x", "GET", 302, "ftp://a.test/y")
        }
        assertThrows(IllegalStateException::class.java) {
            plan("http://a.test/x", "GET", 302, "file:///etc/passwd")
        }
    }

    @Test
    fun `301 302 demote POST to GET and drop its body`() {
        for (status in listOf(301, 302)) {
            val post = plan("http://a.test/x", "POST", status, "/y")!!
            assertEquals("GET", post.method)
            assertTrue(post.dropBody)
            val get = plan("http://a.test/x", "GET", status, "/y")!!
            assertEquals("GET", get.method)
            assertFalse(get.dropBody)
            val head = plan("http://a.test/x", "HEAD", status, "/y")!!
            assertEquals("HEAD", head.method)
            assertFalse(head.dropBody)
        }
    }

    @Test
    fun `303 demotes every method except HEAD and drops the body`() {
        val post = plan("http://a.test/x", "POST", 303, "/y")!!
        assertEquals("GET", post.method)
        assertTrue(post.dropBody)
        val put = plan("http://a.test/x", "PUT", 303, "/y")!!
        assertEquals("GET", put.method)
        assertTrue(put.dropBody)
        val head = plan("http://a.test/x", "HEAD", 303, "/y")!!
        assertEquals("HEAD", head.method)
        assertFalse(head.dropBody)
    }

    @Test
    fun `307 and 308 preserve method and body`() {
        for (status in listOf(307, 308)) {
            val post = plan("http://a.test/x", "POST", status, "/y")!!
            assertEquals("POST", post.method)
            assertFalse(post.dropBody)
            val put = plan("http://a.test/x", "PUT", status, "/y")!!
            assertEquals("PUT", put.method)
            assertFalse(put.dropBody)
        }
    }

    @Test
    fun `origin compares scheme host and effective port`() {
        assertTrue(ModuleHttp.sameOrigin("https://a.test/x", "https://A.TEST/y"))
        assertTrue(ModuleHttp.sameOrigin("https://a.test:443/x", "https://a.test/y"))
        assertTrue(ModuleHttp.sameOrigin("http://a.test:80/x", "http://a.test/y"))
        assertFalse(ModuleHttp.sameOrigin("https://a.test/x", "http://a.test/x"))
        assertFalse(ModuleHttp.sameOrigin("http://a.test:8080/x", "http://a.test:8081/x"))
        assertFalse(ModuleHttp.sameOrigin("http://a.test/x", "http://b.test/x"))

        assertFalse(plan("http://a.test/x", "GET", 302, "/y")!!.crossOrigin)
        assertTrue(plan("http://a.test/x", "GET", 302, "http://a.test:8080/y")!!.crossOrigin)
        assertTrue(plan("http://a.test/x", "GET", 302, "http://127.0.0.1/y")!!.crossOrigin)
    }

    @Test
    fun `cross origin strips exactly the caller credential headers`() {
        val headers = linkedMapOf(
            "Authorization" to "Bearer t",
            "Cookie" to "a=b",
            "PROXY-AUTHORIZATION" to "Basic c",
            "X-Keep" to "1",
        )
        assertEquals(
            mapOf("X-Keep" to "1"),
            ModuleHttp.stripHeaders(headers, ModuleHttp.CREDENTIAL_HEADERS),
        )
    }

    @Test
    fun `method rewrite drops only body headers`() {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Content-Length" to "12",
            "Content-Encoding" to "gzip",
            "Authorization" to "Bearer t",
            "X-Keep" to "1",
        )
        assertEquals(
            linkedMapOf("Authorization" to "Bearer t", "X-Keep" to "1"),
            ModuleHttp.stripHeaders(headers, ModuleHttp.BODY_HEADERS),
        )
    }
}
