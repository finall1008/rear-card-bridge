package dev.finall.rearcardbridge.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkTimeoutTest {
    @Test
    fun `omitted timeout keeps the default`() {
        assertEquals(15_000, ModuleBackend.networkTimeoutMillis(null))
    }

    @Test
    fun `explicit long and short timeouts are not clamped`() {
        assertEquals(180_000, ModuleBackend.networkTimeoutMillis(180_000))
        assertEquals(500, ModuleBackend.networkTimeoutMillis(500))
    }

    @Test
    fun `zero disables connection and read timeouts`() {
        assertEquals(0, ModuleBackend.networkTimeoutMillis(0))
    }

    @Test
    fun `negative timeout is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleBackend.networkTimeoutMillis(-1)
        }
    }
}
