package dev.finall.rearcardbridge.module

/** Saved timing for one module; failed attempts also wait a full interval. */
data class BackgroundRefreshSchedule(
    val intervalMinutes: Int,
    val lastAttemptAt: Long = 0L,
    val lastRefreshAt: Long? = null,
    val error: String? = null,
) {
    init {
        require(intervalMinutes >= MIN_INTERVAL_MINUTES) { "后台刷新间隔不能少于 5 分钟。" }
    }

    val nextAttemptAt: Long
        get() = if (lastAttemptAt == 0L) 0L else lastAttemptAt + intervalMinutes.toLong() * 60_000L

    companion object {
        const val MIN_INTERVAL_MINUTES = 5
    }
}
