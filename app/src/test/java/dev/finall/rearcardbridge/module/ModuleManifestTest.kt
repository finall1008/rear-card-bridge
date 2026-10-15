package dev.finall.rearcardbridge.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleManifestTest {
    private val cardEntries = setOf("module.json", "card/index.html", "card/main.js")
    private val backgroundEntries = cardEntries + "background/main.js"

    private fun validate(
        id: String = "dev.example.weather",
        name: String = "天气",
        version: String = "1.0.0",
        versionCode: Long = 1,
        apiVersion: Int = 2,
        capabilities: Set<String> = emptySet(),
        entries: Set<String> = cardEntries,
        backgroundIntervalMinutes: Int? = null,
    ) = ModuleManifest.validate(id, name, version, versionCode, apiVersion, "", "",
        capabilities, entries, backgroundIntervalMinutes)

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
    fun `background is opt-in and defaults to null`() {
        assertNull(validate().backgroundIntervalMinutes)
        // background/main.js without the module.json declaration does not opt in.
        assertNull(validate(entries = backgroundEntries).backgroundIntervalMinutes)
    }

    @Test
    fun `valid background interval passes`() {
        val manifest = validate(
            entries = backgroundEntries,
            backgroundIntervalMinutes = 15,
        )
        assertEquals(15, manifest.backgroundIntervalMinutes)
    }

    @Test
    fun `background requires background main js`() {
        assertThrows(IllegalArgumentException::class.java) {
            validate(backgroundIntervalMinutes = 15)
        }
    }

    @Test
    fun `background interval below one is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            validate(entries = backgroundEntries, backgroundIntervalMinutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validate(entries = backgroundEntries, backgroundIntervalMinutes = -5)
        }
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
