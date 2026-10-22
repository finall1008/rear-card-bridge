package dev.finall.rearcardbridge.ipc

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Runs as Shizuku shell; exposes only the install staging operations, never arbitrary paths or commands. */
class CardUserService : ICardService.Stub() {
    // Host-readable shell staging. The theme external dir is not readable by subscreencenter.
    private val stageRoot = File("/data/local/tmp/rearcard-staging")
    private val taskIdPattern = Regex("[A-Za-z0-9-]{8,64}")
    private val stageEntryNames = setOf("rearScreen.mrc", "app_icon.png", "preview.png")
    private val carrierRoot = File(
        "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app")

    @Synchronized
    override fun stageInstallPackage(taskId: String, packageFd: ParcelFileDescriptor): String {
        var streamOpened = false
        return try {
            if (!taskId.matches(taskIdPattern)) {
                packageFd.close()
                return stageResult(false, "BAD_TASK_ID", "无效任务 ID。", null)
            }
            val dir = recreateStage(taskId)
            streamOpened = true
            ParcelFileDescriptor.AutoCloseInputStream(packageFd).use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip -> unpackStage(zip, dir) }
            }
            stageResult(true, "OK", "资源已暂存。", dir.canonicalPath)
        } catch (error: Exception) {
            if (!streamOpened) runCatching { packageFd.close() }
            if (taskId.matches(taskIdPattern)) runCatching { checkedStageDir(taskId).deleteRecursively() }
            val code = (error as? StageException)?.code ?: "ERROR"
            stageResult(false, code, error.message ?: "暂存失败。", null)
        }
    }

    @Synchronized
    override fun clearInstallStage(taskId: String): String = try {
        if (!taskId.matches(taskIdPattern)) {
            stageResult(false, "BAD_TASK_ID", "无效任务 ID。", null)
        } else {
            val dir = checkedStageDir(taskId)
            if (dir.exists() && !dir.deleteRecursively()) {
                stageResult(false, "IO", "无法删除暂存目录。", null)
            } else {
                stageResult(true, "OK", "暂存已清理。", dir.canonicalPath)
            }
        }
    } catch (error: Exception) {
        val code = (error as? StageException)?.code ?: "ERROR"
        stageResult(false, code, error.message ?: "清理失败。", null)
    }

    @Synchronized
    override fun restoreCarrier(cardId: String, backupPath: String): String = try {
        if (!cardId.matches(Regex("[A-Za-z0-9_.-]+")) || cardId == "." || cardId == "..") {
            throw StageException("BAD_CARD_ID", "无效卡片 ID。")
        }
        val backup = File(backupPath)
        require(backup.isFile && backup.name == "original.zip") { "备份文件不存在。" }
        val dir = File(carrierRoot, cardId + "_extracted")
        require(dir.isDirectory && dir.canonicalFile.parentFile == carrierRoot.canonicalFile) {
            "本地卡片资源不存在。"
        }
        val target = File(dir, "rearscreen")
        require(target.isFile && target.canonicalFile.parentFile == dir.canonicalFile) {
            "未找到可恢复资源。"
        }
        val temp = File.createTempFile(".carrier-", ".zip", dir)
        try {
            backup.inputStream().use { input -> temp.outputStream().use { input.copyTo(it); it.fd.sync() } }
            Os.chmod(temp.absolutePath, 0b110100100) // 0644 for the theme host.
            java.nio.file.Files.move(temp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
        stageResult(true, "OK", "载体已恢复原状。", target.canonicalPath)
    } catch (error: Exception) {
        val code = (error as? StageException)?.code ?: "ERROR"
        stageResult(false, code, error.message ?: "恢复失败。", null)
    }

    override fun destroy() { kotlin.system.exitProcess(0) }

    private fun stageResult(success: Boolean, code: String, message: String, path: String?): String {
        val json = JSONObject().put("success", success).put("code", code).put("message", message)
        if (path != null) json.put("path", path)
        return json.toString()
    }

    private fun recreateStage(taskId: String): File {
        val dir = checkedStageDir(taskId)
        if (dir.exists() && !dir.deleteRecursively()) throw StageException("IO", "无法清理暂存目录。")
        if (!dir.mkdirs() && !dir.isDirectory) throw StageException("IO", "无法创建暂存目录。")
        Os.chmod(dir.absolutePath, 0b111101101) // 0755; the rear-screen host must traverse and read.
        return dir
    }

    private fun checkedStageDir(taskId: String): File {
        if (!stageRoot.isDirectory && !stageRoot.mkdirs()) throw StageException("IO", "无法创建暂存根目录。")
        Os.chmod(stageRoot.absolutePath, 0b111101101)
        val dir = File(stageRoot, taskId)
        if (dir.canonicalFile.parentFile != stageRoot.canonicalFile) {
            throw StageException("BAD_TASK_ID", "无效暂存路径。")
        }
        return dir
    }

    private fun unpackStage(zip: ZipInputStream, dir: File) {
        val seen = HashSet<String>()
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            if (name !in stageEntryNames || entry.isDirectory) {
                throw StageException("BAD_ZIP", "资源包包含不允许的条目。")
            }
            if (!seen.add(name)) throw StageException("BAD_ZIP", "资源包条目重复。")
            val destination = File(dir, name)
            if (destination.canonicalFile.parentFile != dir.canonicalFile) {
                throw StageException("ZIP_SLIP", "资源包路径越界。")
            }
            destination.outputStream().use { output ->
                zip.copyTo(output)
                output.fd.sync()
            }
            Os.chmod(destination.absolutePath, 0b110100100) // 0644
            zip.closeEntry()
        }
        if (seen != stageEntryNames) throw StageException("BAD_ZIP", "资源包缺少必要文件。")
    }

    private class StageException(val code: String, message: String) : IllegalArgumentException(message)
}
