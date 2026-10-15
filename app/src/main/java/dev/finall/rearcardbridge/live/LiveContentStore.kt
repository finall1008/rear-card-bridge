package dev.finall.rearcardbridge.live

import android.content.Context
import dev.finall.rearcardbridge.BuildConfig
import android.net.Uri
import android.util.AtomicFile
import dev.finall.rearcardbridge.packageio.CardPackageBuilder
import dev.finall.rearcardbridge.packageio.StaticResourceChanges
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object ContentOperations { val lock = Any() }
object LiveContentContract {
    val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".live"
    const val COLUMN_PAYLOAD = "payload"
    fun uri(id: String): Uri = Uri.parse("content://$AUTHORITY/bindings/$id/current")
}

data class ContentBinding(val id: String, val sourcePackage: String, val key: String,
    val cardId: String?, val cardTitle: String?)

data class LiveContentSnapshot(val revision: Long, val name: String, val entries: List<String>, val script: String) {
    fun payload(): String = JSONObject().put("revision", revision).put("script", script).toString()
}

enum class CarrierResourceState { UNKNOWN, CURRENT, UPDATE_REQUIRED, REAPPLY_REQUIRED }

data class CarrierResourceStatus(
    val state: CarrierResourceState,
    val changes: StaticResourceChanges? = null,
) {
    val message: String get() = when (state) {
        CarrierResourceState.UNKNOWN -> "尚无资源比对记录，请更新载体以建立快照。"
        CarrierResourceState.CURRENT -> "静态资源一致，无需重新安装载体。"
        CarrierResourceState.UPDATE_REQUIRED -> "静态资源有变化：新增 ${changes!!.added.size} 个、修改 ${changes.modified.size} 个、删除 ${changes.removed.size} 个。请更新载体，再移除旧卡并重新应用。"
        CarrierResourceState.REAPPLY_REQUIRED -> "载体资源已写入，请到背屏设置中移除旧卡并重新应用。"
    }
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
        val groups = bindings()
        writeBindings(groups.map { if (it.cardId == cardId) it.copy(cardId = null, cardTitle = null) else it })
        groups.filter { it.cardId == cardId }.forEach {
            installedResources(it.id).delete()
            reapplyPending(it.id).delete()
        }
    }

    /** Removes a module's content group together with its stored content. */
    fun removeGroup(id: String) = synchronized(ContentOperations.lock) {
        writeBindings(bindings().filterNot { it.id == id })
        directory(id).deleteRecursively()
    }

    private fun installedResources(id: String) = AtomicFile(File(directory(id), "installed-resources.zip"))
    private fun reapplyPending(id: String) = AtomicFile(File(directory(id), "reapply-pending.json"))

    fun resourceStatus(id: String): CarrierResourceStatus = synchronized(ContentOperations.lock) {
        val installed = installedResources(id)
        if (!exists(installed)) return@synchronized CarrierResourceStatus(CarrierResourceState.UNKNOWN)
        installed.openRead().close()
        val changes = CardPackageBuilder().compareStaticResources(archive(id), installed.baseFile)
        CarrierResourceStatus(when {
            changes.changed -> CarrierResourceState.UPDATE_REQUIRED
            exists(reapplyPending(id)) -> CarrierResourceState.REAPPLY_REQUIRED
            else -> CarrierResourceState.CURRENT
        }, changes)
    }

    /** Called only after the carrier has been successfully written. */
    fun recordInstalledResources(id: String, snapshot: File) = synchronized(ContentOperations.lock) {
        val dir = directory(id)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建内容目录。" }
        writeJson(reapplyPending(id), "{}")
        val installed = installedResources(id)
        val out = installed.startWrite()
        try { snapshot.inputStream().use { it.copyTo(out) }; installed.finishWrite(out) }
        catch (error: Exception) { installed.failWrite(out); throw error }
    }

    fun confirmReapplied(id: String) = synchronized(ContentOperations.lock) {
        require(resourceStatus(id).state == CarrierResourceState.REAPPLY_REQUIRED) { "请先更新载体资源。" }
        reapplyPending(id).delete()
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
