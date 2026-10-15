package dev.finall.rearcardbridge.module

import android.app.job.JobParameters
import android.app.job.JobService
import dev.finall.rearcardbridge.live.ContentOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs only due work. JobScheduler owns wakefulness; no service or timer waits between runs. */
class BackgroundRefreshService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        BackgroundRefreshScheduler.restorePreference(this)
        if (!BackgroundRefreshScheduler.isEnabled(this)) return false
        BackgroundRefreshScheduler.publish(true, "正在执行后台刷新")
        work = scope.launch {
            try {
                val runner = BackgroundModuleRunner(this@BackgroundRefreshService)
                val modules = installedModules()
                for (module in modules) {
                    val minutes = module.manifest.backgroundIntervalMinutes ?: continue
                    val id = module.manifest.id
                    val schedule = BackgroundRefreshScheduler.scheduleFor(this@BackgroundRefreshService, id, minutes)
                    if (System.currentTimeMillis() < schedule.nextAttemptAt) continue
                    BackgroundRefreshScheduler.recordAttempt(this@BackgroundRefreshService, id)
                    BackgroundRefreshScheduler.publish(true, "正在刷新：${module.manifest.name}")
                    try {
                        runner.run(module)
                        BackgroundRefreshScheduler.recordAttempt(this@BackgroundRefreshService, id, success = true)
                        BackgroundRefreshScheduler.publish(true, "已刷新：${module.manifest.name}")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val message = error.message ?: error.javaClass.simpleName
                        BackgroundRefreshScheduler.recordAttempt(this@BackgroundRefreshService, id, error = message)
                        android.util.Log.e("CardBackgroundRefresh", "${module.manifest.name} 刷新失败：$message", error)
                        BackgroundRefreshScheduler.publish(true, "${module.manifest.name} 刷新失败：$message")
                    }
                }
                // Re-read after execution: a module may have been installed/removed while this job ran.
                val current = installedModules()
                work = null
                BackgroundRefreshScheduler.publish(false, "等待下次后台刷新")
                jobFinished(params, false)
                BackgroundRefreshScheduler.schedule(this@BackgroundRefreshService, current, replacePending = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                work = null
                BackgroundRefreshScheduler.publish(false, "后台刷新失败：${error.message ?: error.javaClass.simpleName}")
                jobFinished(params, true)
            }
        }
        return true
    }

    private suspend fun installedModules() = withContext(Dispatchers.IO) {
        synchronized(ContentOperations.lock) { ModuleStore(this@BackgroundRefreshService).list() }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        val enabled = BackgroundRefreshScheduler.isEnabled(this)
        BackgroundRefreshScheduler.publish(false,
            if (enabled) "系统已暂停任务，等待重新安排" else "后台刷新未开启")
        // Retry is deferred by JobScheduler's backoff, never a local restart loop.
        return enabled
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
