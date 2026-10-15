package dev.finall.rearcardbridge.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackgroundRefreshScheduleTest {
    @Test
    fun `five minutes is accepted and shorter intervals are rejected`() {
        assertEquals(5, BackgroundRefreshSchedule(5).intervalMinutes)
        assertThrows(IllegalArgumentException::class.java) { BackgroundRefreshSchedule(4) }
    }

    @Test
    fun `first refresh is due immediately and every completed attempt waits the chosen interval`() {
        assertEquals(0L, BackgroundRefreshSchedule(5).nextAttemptAt)
        val failed = BackgroundRefreshSchedule(30, lastAttemptAt = 1_000L, error = "请求失败")
        assertEquals(1_801_000L, failed.nextAttemptAt)
        // Changing the user interval recalculates the next attempt, including after a failure.
        assertEquals(301_000L, failed.copy(intervalMinutes = 5).nextAttemptAt)
    }
}
