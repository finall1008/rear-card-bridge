package dev.finall.rearcardbridge.live

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object LiveContentContract {
    const val AUTHORITY = "dev.finall.rearcardbridge.live"
    val CURRENT_URI: Uri = Uri.parse("content://$AUTHORITY/current")
    const val COLUMN_PAYLOAD = "payload"
}

data class LiveContentSnapshot(
    val revision: Long,
    val name: String,
    val entries: List<String>,
    val script: String,
) {
    fun payload(): String = JSONObject().put("revision", revision).put("script", script).toString()
}

/** App-owned content. The rear-screen process reads it through our provider. */
class LiveContentStore(private val context: Context) {
    private val directory = File(context.filesDir, "live-content")
    private val content = AtomicFile(File(directory, "current.json"))
    private val input = AtomicFile(File(directory, "current.zip"))

    fun current(): LiveContentSnapshot? = synchronized(lock) {
        if (!content.baseFile.exists() && !File(directory, "current.json.bak").exists()) return@synchronized null
        val json = content.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val entries = json.getJSONArray("entries")
        LiveContentSnapshot(json.getLong("revision"), json.getString("name"),
            List(entries.length()) { entries.getString(it) }, json.getString("script"))
    }

    fun archive(): File? = synchronized(lock) {
        if (!input.baseFile.exists()) null else {
            // Let AtomicFile recover a previous write before the package builder opens it.
            input.openRead().close()
            input.baseFile
        }
    }

    fun save(name: String, entries: List<String>, script: String, archive: File): LiveContentSnapshot {
        val result = synchronized(lock) {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建内容目录。" }
            val snapshot = LiveContentSnapshot((current()?.revision ?: 0L) + 1L, name, entries, script)
            val zipOut = input.startWrite()
            try {
                archive.inputStream().use { it.copyTo(zipOut) }
                input.finishWrite(zipOut)
            } catch (error: Exception) {
                input.failWrite(zipOut)
                throw error
            }
            val json = JSONObject().put("revision", snapshot.revision).put("name", name)
                .put("entries", JSONArray(entries)).put("script", script)
            val out = content.startWrite()
            try {
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                content.finishWrite(out)
            } catch (error: Exception) {
                content.failWrite(out)
                throw error
            }
            snapshot
        }
        context.contentResolver.notifyChange(LiveContentContract.CURRENT_URI, null)
        return result
    }

    private companion object { val lock = Any() }
}
