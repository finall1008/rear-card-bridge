package dev.finall.rearcardbridge.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModuleStorePathTest {
    @Test
    fun `normal relative paths pass through`() {
        assertEquals("a/b/c.txt", ModuleStore.cleanRelativePath("a/b/c.txt"))
        assertEquals("a/b", ModuleStore.cleanRelativePath("./a//b"))
        assertEquals("a/b", ModuleStore.cleanRelativePath("a\\b"))
    }

    @Test
    fun `absolute and escaping paths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ModuleStore.cleanRelativePath("/etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { ModuleStore.cleanRelativePath("../x") }
        assertThrows(IllegalArgumentException::class.java) { ModuleStore.cleanRelativePath("a/../../x") }
        assertThrows(IllegalArgumentException::class.java) { ModuleStore.cleanRelativePath("C:/x") }
    }
}
