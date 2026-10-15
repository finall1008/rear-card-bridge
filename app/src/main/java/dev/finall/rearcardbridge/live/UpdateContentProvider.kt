package dev.finall.rearcardbridge.live

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.UserManager
import dev.finall.rearcardbridge.module.ModuleInstaller
import dev.finall.rearcardbridge.module.ModuleDowngradeException

/** Synchronous IPC: no Activity, service, notification or Shizuku request is started here. */
class UpdateContentProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle().apply { putString("request_id", extras?.getString("request_id")) }
        fun failure(code: String, message: String) = result.apply {
            putString("status", code); putString("message", message)
        }
        if (method != "update_content") return failure("INVALID_INPUT", "未知方法。")
        if (extras?.getInt("api_version", 0) != 2) return failure("INVALID_INPUT", "后台接口要求 api_version=2。")
        val context = requireNotNull(context)
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked)
            return failure("USER_LOCKED", "本次开机尚未解锁，内容存储不可用。")
        val uid = Binder.getCallingUid()
        val source = callingPackage ?: return failure("CALLER_UNKNOWN", "无法确认调用者。")
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        if (packages.size != 1 || packages.single() != source)
            return failure("CALLER_UNKNOWN", "无法唯一确认调用者包名。")
        val uri = extras.getParcelable("package_uri", Uri::class.java)
            ?: return failure("INVALID_INPUT", "缺少 package_uri。")
        if (uri.scheme != "content") return failure("INVALID_INPUT", "仅接受 content:// ZIP 文件。")
        // The caller must grant the bridge app access; do not inherit the caller's provider identity.
        val token = Binder.clearCallingIdentity()
        try {
            return synchronized(ContentOperations.lock) {
                // Since modules arrived, external apps ship the same module zip format
                // (module.json + card/ + optional webroot/) and go through module install.
                val file = java.io.File.createTempFile("incoming-", ".zip", context.cacheDir)
                val (installed, saved) = try {
                    requireNotNull(context.contentResolver.openInputStream(uri)) {
                        "无法读取 ZIP 文件。"
                    }.use { input -> file.outputStream().use { input.copyTo(it) } }
                    ModuleInstaller(context).install(file, source)
                } finally { file.delete() }
                val store = LiveContentStore(context)
                val group = store.group(context.packageName, installed.manifest.id)
                if (group?.cardId == null) {
                    return@synchronized failure("BINDING_REQUIRED", "模块已安装；请通过前台入口选择并绑定目标。")
                        .apply {
                            putString("key", installed.manifest.id)
                            putLong("revision", saved.revision)
                        }
                }
                result.putString("key", installed.manifest.id)
                result.putString("card_id", group.cardId)
                val resources = store.resourceStatus(group.id)
                result.apply {
                    putString("status", "SAVED")
                    putString("message", "模块已更新；背屏读取时间由系统载体决定。${resources.message}")
                    putLong("revision", saved.revision)
                    putString("carrier_resource_status", resources.state.name)
                    if (resources.state != CarrierResourceState.UNKNOWN) {
                        putBoolean("carrier_update_required", resources.state == CarrierResourceState.UPDATE_REQUIRED)
                    }
                    putBoolean("reapply_confirmation_pending", resources.state == CarrierResourceState.REAPPLY_REQUIRED)
                    resources.changes?.let {
                        putInt("static_resources_added", it.added.size)
                        putInt("static_resources_modified", it.modified.size)
                        putInt("static_resources_removed", it.removed.size)
                    }
                }
            }
        } catch (error: SecurityException) {
            return failure("URI_PERMISSION_DENIED", error.message ?: "未授予 ZIP 读取权限。")
        } catch (error: ModuleDowngradeException) {
            return failure("DOWNGRADE_CONFIRMATION_REQUIRED", "${error.message}请通过前台入口确认降级安装。")
        } catch (error: IllegalArgumentException) {
            return failure("INVALID_INPUT", error.message ?: "输入无效。")
        } catch (error: Exception) {
            return failure("ERROR", error.message ?: "更新失败。")
        } finally { Binder.restoreCallingIdentity(token) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        throw UnsupportedOperationException("使用 call(update_content)")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
