package dev.finall.rearcardbridge.live

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.finall.rearcardbridge.module.ModuleSnapshot
import dev.finall.rearcardbridge.module.ModuleStore
import org.json.JSONObject

/** Read-only, exported so Xiaomi's MAML host can read without a signature permission. */
class LiveContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(uri.authority == LiveContentContract.AUTHORITY) { "未知内容地址。" }
        val segments = uri.pathSegments
        require(segments.size == 3 && segments[0] == "bindings" && segments[2] == "current") { "未知内容地址。" }
        val id = segments[1]
        val appContext = requireNotNull(context)
        val (content, snapshot) = synchronized(ContentOperations.lock) {
            LiveContentStore(appContext).current(id) to
                moduleFor(appContext, id)?.let { ModuleStore(appContext).readSnapshotRecord(it) }
        }
        // One query supplies both the script and its synchronous first-frame snapshot.
        val payload = buildPayload(content, snapshot)
        val columns = projection ?: arrayOf(LiveContentContract.COLUMN_PAYLOAD, "revision", "name")
        val row = columns.map { column ->
            when (column) {
                LiveContentContract.COLUMN_PAYLOAD -> payload
                "revision" -> content?.revision ?: 0L
                "name" -> content?.name ?: ""
                else -> throw IllegalArgumentException("未知字段：$column")
            }
        }.toTypedArray<Any>()
        return MatrixCursor(columns).apply {
            addRow(row)
            setNotificationUri(appContext.contentResolver, uri)
        }
    }

    /** Same source-app/key module mapping as the bridge provider. */
    private fun moduleFor(context: android.content.Context, bindingId: String): String? {
        val binding = LiveContentStore(context).bindings().firstOrNull { it.id == bindingId }
            ?: return null
        if (binding.sourcePackage != context.packageName) return null
        val moduleId = binding.key
        return if (ModuleStore(context).get(moduleId) != null) moduleId else null
    }

    private fun buildPayload(content: LiveContentSnapshot?, snapshot: ModuleSnapshot?): String {
        val revision = content?.revision ?: 0L
        val script = content?.script ?: ""
        return JSONObject().put("revision", revision).put("script", script).apply {
            if (snapshot != null) {
                put("snapshotRev", snapshot.rev).put("snapshot", snapshot.value)
            }
        }.toString()
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.${LiveContentContract.AUTHORITY}.current"
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("只读接口")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("只读接口")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("只读接口")

}
