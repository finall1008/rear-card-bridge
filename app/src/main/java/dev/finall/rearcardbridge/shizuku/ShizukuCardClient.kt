package dev.finall.rearcardbridge.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.finall.rearcardbridge.BuildConfig
import dev.finall.rearcardbridge.ipc.CardUserService
import dev.finall.rearcardbridge.ipc.ICardService
import dev.finall.rearcardbridge.model.CardInfo
import dev.finall.rearcardbridge.model.OperationResult
import dev.finall.rearcardbridge.model.ShizukuState
import dev.finall.rearcardbridge.model.ShizukuStatus
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

class ShizukuCardClient(context: Context) {
    private val mutableState = MutableStateFlow(ShizukuState(ShizukuStatus.NOT_RUNNING))
    val state = mutableState.asStateFlow()
    @Volatile private var service: ICardService? = null
    private var binding = false
    private var closed = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context, CardUserService::class.java))
        .daemon(false).processNameSuffix("card_service").tag("rear-card-bridge")
        .version(BuildConfig.VERSION_CODE).debuggable(BuildConfig.DEBUG)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (closed) return
            service = ICardService.Stub.asInterface(binder)
            binding = false
            mutableState.value = ShizukuState(ShizukuStatus.READY)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
            mutableState.value = ShizukuState(ShizukuStatus.ERROR, "Shizuku 服务连接已断开，请重新连接。")
        }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { connect() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        mutableState.value = ShizukuState(ShizukuStatus.NOT_RUNNING, "请先启动 Shizuku。")
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, granted ->
        if (code == PERMISSION_REQUEST) {
            if (granted == PackageManager.PERMISSION_GRANTED) connect()
            else mutableState.value = ShizukuState(ShizukuStatus.AUTH_REQUIRED, "Shizuku 授权被拒绝。")
        }
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }
    fun connect() {
        if (closed || binding || service != null) return
        try {
            if (!Shizuku.pingBinder()) {
                mutableState.value = ShizukuState(ShizukuStatus.NOT_RUNNING, "请先启动 Shizuku。")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                mutableState.value = ShizukuState(ShizukuStatus.AUTH_REQUIRED, "需要授权才能读取主题卡片资源。")
                return
            }
            binding = true
            mutableState.value = ShizukuState(ShizukuStatus.CONNECTING)
            Shizuku.bindUserService(args, connection)
        } catch (error: Exception) {
            binding = false
            mutableState.value = ShizukuState(ShizukuStatus.ERROR, error.message ?: "连接失败")
        }
    }
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) { connect(); return }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect()
            else Shizuku.requestPermission(PERMISSION_REQUEST)
        } catch (error: Exception) {
            mutableState.value = ShizukuState(ShizukuStatus.ERROR, error.message ?: "授权请求失败")
        }
    }
    fun listCards(): List<CardInfo> {
        val remote = requireService()
        val array = JSONArray(remote.listCards())
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val preview = remote.openPreviewImage(id)?.use { descriptor ->
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
            }
            CardInfo(id, item.getString("title"), item.getString("sourcePath"),
                item.getBoolean("canReplace"), item.getBoolean("hasBackup"), preview)
        }
    }
    fun replaceCard(cardId: String, packageFile: File): OperationResult =
        ParcelFileDescriptor.open(packageFile, ParcelFileDescriptor.MODE_READ_ONLY).use {
            parse(requireService().replaceCard(cardId, it))
        }
    fun restoreCard(cardId: String): OperationResult = parse(requireService().restoreCard(cardId))
    private fun requireService(): ICardService = requireNotNull(service) { "Shizuku 服务尚未连接。" }
    private fun parse(value: String): OperationResult {
        val json = JSONObject(value)
        return OperationResult(json.getBoolean("success"), json.getString("code"), json.getString("message"))
    }
    fun close() {
        closed = true
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }
    companion object { private const val PERMISSION_REQUEST = 1 }
}
