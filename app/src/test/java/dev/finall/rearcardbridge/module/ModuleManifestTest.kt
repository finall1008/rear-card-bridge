package dev.finall.rearcardbridge.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleManifestTest {
    private val cardEntries = setOf("module.json", "card/index.html", "card/main.js")

    private fun validate(
        id: String = "dev.example.weather",
        name: String = "天气",
        version: String = "1.0.0",
        versionCode: Long = 1,
        apiVersion: Int = 2,
        capabilities: Set<String> = emptySet(),
        entries: Set<String> = cardEntries,
    ) = ModuleManifest.validate(id, name, version, versionCode, apiVersion, "", "", capabilities, entries)

    @Test
    fun `valid manifest passes`() {
        val manifest = validate(capabilities = setOf("network", "storage"))
        assertEquals("dev.example.weather", manifest.id)
        assertEquals(setOf("network", "storage"), manifest.capabilities)
        assertFalse(manifest.hasWebui)
    }

    @Test
    fun `webui requires index html`() {
        assertThrows(IllegalArgumentException::class.java) {
            validate(entries = cardEntries + "webroot/style.css")
        }
        val manifest = validate(entries = cardEntries + "webroot/index.html")
        assertTrue(manifest.hasWebui)
    }

    @Test
    fun `invalid ids are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { validate(id = "1bad") }
        assertThrows(IllegalArgumentException::class.java) { validate(id = "has space") }
        assertThrows(IllegalArgumentException::class.java) { validate(id = "a".repeat(65)) }
        assertThrows(IllegalArgumentException::class.java) { validate(id = "") }
    }

    @Test
    fun `unsupported api version is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { validate(apiVersion = 1) }
        assertThrows(IllegalArgumentException::class.java) { validate(apiVersion = 3) }
    }

    @Test
    fun `unknown capability is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { validate(capabilities = setOf("shell")) }
    }

    @Test
    fun `missing card entries are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validate(entries = setOf("module.json", "card/index.html"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(entries = setOf("module.json", "card/main.js"))
        }
    }

    @Test
    fun `module id validation`() {
        assertTrue(ModuleManifest.isValidModuleId("dev.example.weather"))
        assertTrue(ModuleManifest.isValidModuleId("a"))
        assertTrue(ModuleManifest.isValidModuleId("a-b_c.d"))
        assertFalse(ModuleManifest.isValidModuleId("1abc"))
        assertFalse(ModuleManifest.isValidModuleId("a/b"))
        assertFalse(ModuleManifest.isValidModuleId(""))
    }
}
