package dev.finall.rearcardbridge.ipc

import android.os.ParcelFileDescriptor
import dev.finall.rearcardbridge.BuildConfig
import android.system.Os
import android.util.Xml
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/** Runs as Shizuku shell; exposes only card operations, never arbitrary paths or commands. */
class CardUserService : ICardService.Stub() {
    private val resourceRoot = File("/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app")
    private val backupRoot = File("/storage/emulated/0/Android/data/" + BuildConfig.APPLICATION_ID + "/files/backups")

    @Synchronized
    override fun listCards(): String {
        val cards = JSONArray()
        resourceRoot.listFiles()?.sortedBy { it.name }?.forEach { directory ->
            if (!directory.name.endsWith("_extracted")) return@forEach
            val id = directory.name.removeSuffix("_extracted")
            val dir = runCatching { cardDirectory(id) }.getOrNull() ?: return@forEach
            val resource = File(dir, "rearscreen")
            if (!resource.isFile || resource.canonicalFile.parentFile != dir.canonicalFile) return@forEach
            cards.put(JSONObject().put("id", id).put("title", title(dir, id))
                .put("sourcePath", resource.absolutePath)
                .put("canReplace", Files.isWritable(dir.toPath()) && Files.isWritable(resource.toPath()))
                .put("hasBackup", File(backupRoot, "$id/original.zip").isFile))
        }
        return cards.toString()
    }

    @Synchronized
    override fun openPreviewImage(cardId: String): ParcelFileDescriptor? {
        val dir = cardDirectory(cardId)
        val preview = File(dir, "preview")
        if (preview.canonicalFile.parentFile != dir.canonicalFile || !preview.isDirectory) return null
        val image = preview.listFiles()?.sortedBy { it.name }?.firstOrNull {
            it.isFile && it.canonicalFile.parentFile == preview.canonicalFile &&
                it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")
        } ?: return null
        return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Synchronized
    override fun replaceCard(cardId: String, packageFd: ParcelFileDescriptor): String = operation {
        val resource = resourceFile(cardId)
        val temp = File.createTempFile(".bridge-", ".zip", resource.parentFile)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(packageFd).use { source ->
                temp.outputStream().use { source.copyTo(it); it.fd.sync() }
            }
            validatePackage(temp)
            val backups = backups(cardId)
            val original = File(backups, "original.zip")
            if (!original.exists()) atomicCopy(resource, original)
            atomicCopy(resource, File(backups, "previous.zip"))
            Os.chmod(temp.absolutePath, 0b110100100) // 0644; system theme app can read the new resource.
            Files.move(temp.toPath(), resource.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            result(true, "REPLACED", "资源已替换。")
        } finally { temp.delete() }
    }

    @Synchronized
    override fun restoreCard(cardId: String): String = operation {
        val resource = resourceFile(cardId)
        val backups = backups(cardId)
        val original = File(backups, "original.zip")
        require(original.isFile) { "未找到原始备份。" }
        validatePackage(original)
        atomicCopy(resource, File(backups, "previous.zip"))
        atomicCopy(original, resource, readableByTheme = true)
        result(true, "REPLACED", "原始资源已恢复。")
    }

    override fun destroy() { kotlin.system.exitProcess(0) }

    private fun cardDirectory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_.-]+")) && id != "." && id != "..") { "无效卡片 ID。" }
        val directory = File(resourceRoot, "${id}_extracted")
        require(directory.isDirectory && directory.canonicalFile.parentFile == resourceRoot.canonicalFile) { "本地卡片资源不存在。" }
        return directory
    }
    private fun resourceFile(id: String): File {
        val dir = cardDirectory(id)
        val file = File(dir, "rearscreen")
        require(file.isFile && file.canonicalFile.parentFile == dir.canonicalFile) { "未找到可替换资源。" }
        require(Files.isWritable(dir.toPath()) && Files.isWritable(file.toPath())) { "卡片资源不可写。" }
        return file
    }
    private fun backups(id: String): File {
        require(backupRoot.mkdirs() || backupRoot.isDirectory) { "无法创建备份目录。" }
        val directory = File(backupRoot, id)
        require(directory.canonicalFile.parentFile == backupRoot.canonicalFile) { "无效备份路径。" }
        require(directory.mkdirs() || directory.isDirectory) { "无法创建卡片备份目录。" }
        return directory
    }
    private fun atomicCopy(source: File, target: File, readableByTheme: Boolean = false) {
        val parent = requireNotNull(target.parentFile) { "目标没有父目录。" }
        require(!target.exists() || target.canonicalFile.parentFile == parent.canonicalFile) { "无效目标路径。" }
        val temp = File.createTempFile(".bridge-", ".tmp", parent)
        try {
            source.inputStream().use { input -> temp.outputStream().use { input.copyTo(it); it.fd.sync() } }
            if (readableByTheme) Os.chmod(temp.absolutePath, 0b110100100)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun validatePackage(file: File) {
        ZipFile(file).use { zip ->
            val manifest = requireNotNull(zip.getEntry("manifest.xml")) { "资源 ZIP 缺少 manifest.xml。" }
            zip.getInputStream(manifest).use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, "UTF-8")
                var rootFound = false
                while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
                    require(parser.eventType != XmlPullParser.DOCDECL) { "不支持 XML 文档类型声明。" }
                    if (parser.eventType == XmlPullParser.START_TAG && !rootFound) {
                        require(parser.name == "Widget") { "manifest.xml 根节点必须是 Widget。" }
                        rootFound = true
                    }
                }
                require(rootFound) { "manifest.xml 为空。" }
            }
        }
    }
    private fun title(directory: File, fallback: String): String = runCatching {
        val description = File(directory, "description.xml")
        require(description.canonicalFile.parentFile == directory.canonicalFile)
        description.inputStream().use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, "UTF-8")
            while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.DOCDECL) return@use fallback
                if (parser.eventType == XmlPullParser.START_TAG && parser.name in setOf("appName", "title", "name")) {
                    return@use parser.nextText().ifBlank { fallback }
                }
            }
            fallback
        }
    }.getOrDefault(fallback)
    private fun operation(block: () -> String): String = try { block() }
        catch (error: Exception) { result(false, "ERROR", error.message ?: error.javaClass.simpleName) }
    private fun result(success: Boolean, code: String, message: String): String =
        JSONObject().put("success", success).put("code", code).put("message", message).toString()
}
