package dev.finall.rearcardbridge.live

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object ContentOperations { val lock = Any() }
object LiveContentContract {
    const val AUTHORITY = "dev.finall.rearcardbridge.live"
    const val COLUMN_PAYLOAD = "payload"
    fun uri(id: String): Uri = Uri.parse("content://$AUTHORITY/bindings/$id/current")
}

data class ContentBinding(val id: String, val sourcePackage: String, val key: String,
    val cardId: String?, val cardTitle: String?)

data class LiveContentSnapshot(val revision: Long, val name: String, val entries: List<String>, val script: String) {
    fun payload(): String = JSONObject().put("revision", revision).put("script", script).toString()
}

/** Each carrier reads a stable internal ID, independent of the externally supplied key. */
class LiveContentStore(private val context: Context) {
    private val root = File(context.filesDir, "live-content")
    private val registry = AtomicFile(File(root, "bindings.json"))

    fun bindings(): List<ContentBinding> = synchronized(ContentOperations.lock) {
        if (!exists(registry)) return@synchronized emptyList()
        val array = registry.openRead().use { JSONArray(it.readBytes().toString(Charsets.UTF_8)) }
        (0 until array.length()).mapNotNull { index ->
            val json = array.getJSONObject(index)
            val id = json.getString("id")
            val source = json.nullable("sourcePackage")
            if (!isGroupId(id) || source.isNullOrBlank()) return@mapNotNull null
            ContentBinding(id, source, json.getString("key"),
                json.nullable("cardId"), json.nullable("cardTitle"))
        }
    }
    fun group(sourcePackage: String, key: String): ContentBinding? =
        bindings().firstOrNull { it.sourcePackage == sourcePackage && it.key == key }

    fun createGroup(sourcePackage: String, key: String): ContentBinding = synchronized(ContentOperations.lock) {
        require(sourcePackage.isNotBlank() && key.isNotBlank()) { "来源和 key 不能为空。" }
        group(sourcePackage, key) ?: ContentBinding(UUID.randomUUID().toString(), sourcePackage, key, null, null)
            .also { writeBindings(bindings() + it) }
    }
    fun bind(id: String, cardId: String, cardTitle: String) = synchronized(ContentOperations.lock) {
        val groups = bindings()
        val group = groups.first { it.id == id }
        require(group.cardId == null || group.cardId == cardId) { "更换目标前请先恢复原来的载体。" }
        require(groups.none { it.id != id && it.cardId == cardId }) { "目标已被其他分组占用。" }
        writeBindings(groups.map { if (it.id == id) it.copy(cardId = cardId, cardTitle = cardTitle) else it })
    }
    fun unbindCard(cardId: String) = synchronized(ContentOperations.lock) {
        writeBindings(bindings().map { if (it.cardId == cardId) it.copy(cardId = null, cardTitle = null) else it })
    }

    private fun isGroupId(id: String) = id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    private fun directory(id: String): File {
        require(isGroupId(id)) { "未知分组 ID。" }
        return File(root, "groups/$id")
    }
    fun current(id: String): LiveContentSnapshot? = synchronized(ContentOperations.lock) {
        val content = AtomicFile(File(directory(id), "current.json"))
        if (!exists(content)) return@synchronized null
        val json = content.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        val entries = json.getJSONArray("entries")
        LiveContentSnapshot(json.getLong("revision"), json.getString("name"),
            List(entries.length()) { entries.getString(it) }, json.getString("script"))
    }
    fun archive(id: String): File? = synchronized(ContentOperations.lock) {
        val input = AtomicFile(File(directory(id), "current.zip"))
        if (!exists(input)) null else { input.openRead().close(); input.baseFile }
    }
    fun save(name: String, entries: List<String>, script: String, archive: File, id: String): LiveContentSnapshot =
        synchronized(ContentOperations.lock) {
            val directory = directory(id)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建内容目录。" }
            val snapshot = LiveContentSnapshot((current(id)?.revision ?: 0L) + 1, name, entries, script)
            val input = AtomicFile(File(directory, "current.zip"))
            val previousArchive = if (exists(input)) input.openRead().use { it.readBytes() } else null
            val zipOut = input.startWrite()
            try { archive.inputStream().use { it.copyTo(zipOut) }; input.finishWrite(zipOut) }
            catch (error: Exception) { input.failWrite(zipOut); throw error }
            val json = JSONObject().put("revision", snapshot.revision).put("name", name)
                .put("entries", JSONArray(entries)).put("script", script)
            try { writeJson(AtomicFile(File(directory, "current.json")), json.toString()) }
            catch (error: Exception) {
                // Keep the archive used by a later carrier install consistent with the published script.
                if (previousArchive != null) {
                    val out = input.startWrite()
                    try { out.write(previousArchive); input.finishWrite(out) }
                    catch (rollback: Exception) { input.failWrite(out); error.addSuppressed(rollback) }
                } else input.delete()
                throw error
            }
            context.contentResolver.notifyChange(LiveContentContract.uri(id), null)
            snapshot
        }

    private fun writeBindings(groups: List<ContentBinding>) {
        val array = JSONArray()
        groups.forEach { array.put(JSONObject().put("id", it.id).put("sourcePackage", it.sourcePackage)
            .put("key", it.key).put("cardId", it.cardId ?: JSONObject.NULL).put("cardTitle", it.cardTitle ?: JSONObject.NULL)) }
        writeJson(registry, array.toString())
    }
    private fun writeJson(file: AtomicFile, text: String) {
        check(root.isDirectory || root.mkdirs()) { "无法创建内容目录。" }
        val out = file.startWrite()
        try { out.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (error: Exception) { file.failWrite(out); throw error }
    }
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
}
