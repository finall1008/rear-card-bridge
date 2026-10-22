package dev.finall.rearcardbridge.module.install

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import dev.finall.rearcardbridge.BuildConfig

/**
 * Task endpoint for the rear-card host.
 * Caller uid is recorded and not rejected; the integration layer decides which uid may drive installs.
 */
class InstallProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        result.putInt("callerUid", Binder.getCallingUid())
        try {
            when (method) {
                "claim_next" -> claimNext(result)
                "report" -> report(result, arg, extras)
                "cancel" -> cancel(result, arg)
                else -> {
                    result.putString("status", "INVALID_INPUT")
                    result.putString("message", "未知方法。")
                }
            }
        } catch (error: IllegalArgumentException) {
            if (!result.containsKey("status")) {
                result.putString("status", "INVALID_INPUT")
                result.putString("message", error.message ?: "输入无效。")
            }
        } catch (error: IllegalStateException) {
            result.putString("status", "ERROR")
            result.putString("message", error.message ?: "安装任务失败。")
        }
        return result
    }

    private fun claimNext(result: Bundle) {
        val task = store().claimNext()
        result.putBoolean("found", task != null)
        if (task == null) {
            result.putString("status", "EMPTY")
            result.putString("message", "没有等待中的安装任务。")
        } else {
            putTask(result, task)
            result.putString("status", "OK")
        }
    }

    private fun report(result: Bundle, arg: String?, extras: Bundle?) {
        val taskId = arg?.takeIf { it.isNotBlank() }
        if (taskId == null) {
            result.putString("status", "INVALID_INPUT")
            result.putString("message", "缺少任务 ID。")
            return
        }
        if (extras == null || !extras.containsKey("success") || !extras.containsKey("code") || !extras.containsKey("message")) {
            result.putString("status", "INVALID_INPUT")
            result.putString("message", "缺少结果字段。")
            return
        }
        val outcome = InstallOutcome(
            extras.getBoolean("success"),
            extras.getString("code").orEmpty(),
            extras.getString("message").orEmpty(),
        )
        var applied = false
        val updated = try {
            store().update(taskId) { current ->
                if (current.state != InstallTaskState.CLAIMED) current
                else {
                    applied = true
                    current.copy(
                        state = if (outcome.success) InstallTaskState.SUCCEEDED else InstallTaskState.FAILED,
                        resultCode = outcome.code,
                        resultMessage = outcome.message,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
        } catch (error: IllegalArgumentException) {
            result.putString("status", statusFor(error))
            result.putString("message", error.message ?: "任务不存在。")
            return
        }
        if (!applied) {
            result.putString("status", "INVALID_STATE")
            result.putString("message", "只能回报已领取的任务。")
            putTask(result, updated)
            return
        }
        putTask(result, updated)
        result.putString("status", "OK")
    }

    private fun cancel(result: Bundle, arg: String?) {
        val taskId = arg?.takeIf { it.isNotBlank() }
        if (taskId == null) {
            result.putString("status", "INVALID_INPUT")
            result.putString("message", "缺少任务 ID。")
            return
        }
        try {
            store().clear(taskId)
            result.putString("status", "OK")
            result.putString("taskId", taskId)
        } catch (error: IllegalArgumentException) {
            result.putString("status", statusFor(error))
            result.putString("message", error.message ?: "无法取消任务。")
            result.putString("taskId", taskId)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri.authority == AUTHORITY) { "未知内容地址。" }
        val segments = uri.pathSegments
        require(segments.size == 2 && segments[0] == "tasks") { "未知内容地址。" }
        val cursor = MatrixCursor(arrayOf(COLUMN_PAYLOAD))
        val task = store().get(segments[1])
        if (task != null) cursor.addRow(arrayOf<Any>(task.toJson().toString()))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.task"
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("使用 call")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("使用 call")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("使用 call")

    private fun store(): InstallTaskStore = InstallTaskStore(requireNotNull(context))

    private fun statusFor(error: IllegalArgumentException): String {
        val message = error.message.orEmpty()
        return when {
            "不存在" in message -> "NOT_FOUND"
            "非法" in message -> "INVALID_INPUT"
            else -> "INVALID_STATE"
        }
    }

    private fun putTask(result: Bundle, task: InstallTask) {
        result.putString("taskId", task.taskId)
        result.putString("op", task.op.name)
        result.putString("groupId", task.groupId)
        result.putString("cardId", task.cardId)
        result.putString("cardName", task.cardName)
        result.putLong("resourceRevision", task.resourceRevision)
        result.putString("stagingPath", task.stagingPath)
        result.putLong("createdAt", task.createdAt)
        result.putLong("updatedAt", task.updatedAt)
        result.putString("state", task.state.name)
        result.putString("resultCode", task.resultCode)
        result.putString("resultMessage", task.resultMessage)
    }

    companion object {
        val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".install"
        const val COLUMN_PAYLOAD = "payload"
        fun taskUri(taskId: String): Uri = Uri.parse("content://$AUTHORITY/tasks/$taskId")
    }
}
