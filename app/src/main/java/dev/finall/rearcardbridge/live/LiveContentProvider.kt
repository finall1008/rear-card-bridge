package dev.finall.rearcardbridge.live

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONObject

/** Read-only, exported so Xiaomi's MAML host can read without a signature permission. */
class LiveContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(uri.authority == LiveContentContract.AUTHORITY && uri.path == "/current") { "未知内容地址。" }
        val snapshot = LiveContentStore(requireNotNull(context)).current()
        val payload = snapshot?.payload() ?: JSONObject().put("revision", 0).put("script", "").toString()
        val columns = projection ?: arrayOf(LiveContentContract.COLUMN_PAYLOAD, "revision", "name")
        val row = columns.map { column ->
            when (column) {
                LiveContentContract.COLUMN_PAYLOAD -> payload
                "revision" -> snapshot?.revision ?: 0L
                "name" -> snapshot?.name ?: ""
                else -> throw IllegalArgumentException("未知字段：$column")
            }
        }.toTypedArray<Any>()
        return MatrixCursor(columns).apply {
            addRow(row)
            setNotificationUri(requireNotNull(context).contentResolver, LiveContentContract.CURRENT_URI)
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.${LiveContentContract.AUTHORITY}.current"
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("只读接口")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("只读接口")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("只读接口")
}
