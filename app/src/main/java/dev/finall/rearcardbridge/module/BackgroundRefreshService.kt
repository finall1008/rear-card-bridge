package dev.finall.rearcardbridge.module

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import dev.finall.rearcardbridge.MainActivity
import dev.finall.rearcardbridge.live.ContentOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One user-started scheduler for all modules with a background refresh entry. */
class BackgroundRefreshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRefresh("后台刷新已停止")
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "卡片后台刷新", NotificationManager.IMPORTANCE_LOW,
        ))
        try {
            startForeground(NOTIFICATION_ID, notification("正在启动后台刷新"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (error: Exception) {
            stopRefresh("无法启动后台刷新：${error.message ?: error.javaClass.simpleName}")
            return START_NOT_STICKY
        }
        started = true
        publish("正在启动后台刷新")
        scope.launch {
            try {
                refreshLoop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                stopRefresh("后台刷新已停止：${error.message ?: error.javaClass.simpleName}")
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun refreshLoop() {
        val store = ModuleStore(this)
        val runner = BackgroundModuleRunner(this)
        val dueAt = mutableMapOf<String, Long>()
        while (true) {
            val modules = withContext(Dispatchers.IO) {
                synchronized(ContentOperations.lock) {
                    store.list().filter { it.manifest.backgroundIntervalMinutes != null }
                }
            }
            if (modules.isEmpty()) {
                stopRefresh("没有提供后台刷新入口的模块")
                return
            }
            dueAt.keys.retainAll(modules.map { it.manifest.id }.toSet())
            for (module in modules) {
                val id = module.manifest.id
                if (SystemClock.elapsedRealtime() < (dueAt[id] ?: 0L)) continue
                publish("正在刷新：${module.manifest.name}")
                try {
                    runner.run(module)
                    publish("已刷新：${module.manifest.name}")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = "${module.manifest.name} 刷新失败：${error.message ?: error.javaClass.simpleName}"
                    android.util.Log.e("CardBackgroundRefresh", message, error)
                    publish(message)
                }
                // A failed request is not retried immediately. Each run finishes before the next begins.
                dueAt[id] = SystemClock.elapsedRealtime() +
                    requireNotNull(module.manifest.backgroundIntervalMinutes).toLong() * 60_000L
            }
            val remaining = (dueAt.values.minOrNull() ?: 0L) - SystemClock.elapsedRealtime()
            // Re-read installed modules as well: updates/removal do not require restarting the service.
            delay(remaining.coerceIn(1_000L, 30_000L))
        }
    }

    private fun publish(message: String) {
        mutableState.value = BackgroundRefreshState(true, message)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, BackgroundRefreshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("卡片后台刷新")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    private fun stopRefresh(message: String) {
        mutableState.value = BackgroundRefreshState(false, message)
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits background dataSync FGS time. Do not restart to evade the limit.
        stopRefresh("系统后台同步时长已用尽，请回到应用后手动开启")
    }

    override fun onDestroy() {
        scope.cancel()
        if (mutableState.value.running) {
            mutableState.value = BackgroundRefreshState(false, "后台刷新已停止")
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "card_background_refresh"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "dev.finall.rearcardbridge.STOP_BACKGROUND_REFRESH"
        private val mutableState = MutableStateFlow(BackgroundRefreshState())
        val state = mutableState.asStateFlow()
    }
}

data class BackgroundRefreshState(
    val running: Boolean = false,
    val message: String = "后台刷新未开启",
)
