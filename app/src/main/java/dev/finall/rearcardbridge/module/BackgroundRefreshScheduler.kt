package dev.finall.rearcardbridge.module

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One deferrable job for the next due modules, with no service running between attempts. */
object BackgroundRefreshScheduler {
    private const val JOB_ID = 1001
    private const val PREFERENCES = "background_refresh"
    private const val ENABLED = "enabled"
    private const val DUE_AT = "due_at"
    private val mutableState = MutableStateFlow(BackgroundRefreshState())
    val state = mutableState.asStateFlow()

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, false)

    fun restorePreference(context: Context) {
        val enabled = isEnabled(context)
        if (mutableState.value.enabled != enabled) {
            mutableState.update { it.copy(enabled = enabled,
                message = if (enabled) "后台刷新已开启，等待系统安排" else "后台刷新未开启") }
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
        if (!enabled) context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        mutableState.update { it.copy(enabled = enabled,
            message = if (enabled) "后台刷新已开启，等待系统安排" else "后台刷新未开启") }
    }

    fun scheduleFor(context: Context, moduleId: String, defaultMinutes: Int): BackgroundRefreshSchedule {
        val prefs = preferences(context)
        return BackgroundRefreshSchedule(
            intervalMinutes = prefs.getInt("interval.$moduleId",
                defaultMinutes.coerceAtLeast(BackgroundRefreshSchedule.MIN_INTERVAL_MINUTES)),
            lastAttemptAt = prefs.getLong("attempt.$moduleId", 0L),
            lastRefreshAt = prefs.getLong("success.$moduleId", 0L).takeIf { it != 0L },
            error = prefs.getString("error.$moduleId", null),
        )
    }

    fun setInterval(context: Context, moduleId: String, minutes: Int) {
        require(minutes >= BackgroundRefreshSchedule.MIN_INTERVAL_MINUTES) { "后台刷新间隔不能少于 5 分钟。" }
        preferences(context).edit().putInt("interval.$moduleId", minutes).apply()
    }

    fun removeModule(context: Context, moduleId: String) {
        preferences(context).edit().remove("interval.$moduleId").remove("attempt.$moduleId")
            .remove("success.$moduleId").remove("error.$moduleId").apply()
    }

    /** Record before starting too, so a killed/interrupted task does not cause an immediate retry. */
    fun recordAttempt(context: Context, moduleId: String, error: String? = null, success: Boolean = false) {
        val now = System.currentTimeMillis()
        preferences(context).edit().putLong("attempt.$moduleId", now).apply {
            if (error == null) remove("error.$moduleId") else putString("error.$moduleId", error)
            if (success) putLong("success.$moduleId", now)
        }.apply()
    }

    fun publish(running: Boolean, message: String) {
        mutableState.update { it.copy(running = running, message = message) }
    }

    /** Called after module/settings changes and after a job finishes. Never replaces a running job. */
    fun schedule(context: Context, modules: List<InstalledModule>, replacePending: Boolean = false) {
        restorePreference(context)
        if (!isEnabled(context) || state.value.running) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val next = modules.mapNotNull { module ->
            module.manifest.backgroundIntervalMinutes?.let { minutes ->
                scheduleFor(context, module.manifest.id, minutes).nextAttemptAt
            }
        }.minOrNull()
        if (next == null) {
            scheduler.cancel(JOB_ID)
            publish(false, "没有提供后台刷新入口的模块")
            return
        }
        // Opening the app must not keep postponing an already queued job.
        if (!replacePending && scheduler.getPendingJob(JOB_ID)?.extras?.getLong(DUE_AT) == next) {
            publish(false, "已安排后台刷新，实际执行时间由系统决定")
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BackgroundRefreshService::class.java))
            .setMinimumLatency((next - System.currentTimeMillis()).coerceAtLeast(0L))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(5 * 60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
            .setPersisted(true)
            .setExtras(PersistableBundle().apply { putLong(DUE_AT, next) })
            .build()
        try {
            val scheduled = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
            publish(false, if (scheduled) "已安排后台刷新，实际执行时间由系统决定" else "系统未接受后台刷新任务")
        } catch (error: Exception) {
            publish(false, "无法安排后台刷新：${error.message ?: error.javaClass.simpleName}")
        }
    }
}

data class BackgroundRefreshState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val message: String = "后台刷新未开启",
)
