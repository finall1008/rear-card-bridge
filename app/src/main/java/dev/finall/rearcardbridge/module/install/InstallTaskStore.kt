package dev.finall.rearcardbridge.module.install

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * One JSON file per install task under filesDir/install-tasks.
 * The lock is process-wide so the provider and [InstallManager] share claim atomicity.
 */
class InstallTaskStore(context: Context) {
    private val dir = File(context.filesDir, "install-tasks")

    fun create(
        op: InstallOp,
        groupId: String,
        cardId: String,
        cardName: String,
        resourceRevision: Long,
        stagingPath: String?,
    ): InstallTask = locked {
        val now = System.currentTimeMillis()
        var taskId = UUID.randomUUID().toString()
        while (File(dir, "$taskId.json").exists()) taskId = UUID.randomUUID().toString()
        val task = InstallTask(
            taskId = taskId,
            op = op,
            groupId = groupId,
            cardId = cardId,
            cardName = cardName,
            resourceRevision = resourceRevision,
            stagingPath = stagingPath,
            createdAt = now,
            updatedAt = now,
            state = InstallTaskState.PENDING,
            resultCode = null,
            resultMessage = null,
        )
        writeUnlocked(task)
        task
    }

    fun update(taskId: String, transform: (InstallTask) -> InstallTask): InstallTask = locked {
        val current = readUnlocked(taskId) ?: throw IllegalArgumentException("任务不存在：$taskId")
        val updated = transform(current)
        require(updated.taskId == current.taskId) { "不能修改任务 ID。" }
        if (updated == current) return@locked current
        writeUnlocked(updated)
        if (updated.state == InstallTaskState.SUCCEEDED || updated.state == InstallTaskState.FAILED) {
            pruneCompletedUnlocked()
        }
        updated
    }

    /** Fills in the staging directory after the privileged service wrote it. */
    fun markStaged(taskId: String, stagingPath: String): InstallTask = update(taskId) { current ->
        require(current.stagingPath == null) { "任务已经登记了暂存目录。" }
        current.copy(stagingPath = stagingPath, updatedAt = System.currentTimeMillis())
    }

    fun get(taskId: String): InstallTask? = locked { readUnlocked(taskId) }

    fun list(): List<InstallTask> = locked {
        loadAllUnlocked().sortedWith(compareBy({ it.createdAt }, { it.taskId }))
    }

    fun byState(state: InstallTaskState): List<InstallTask> = locked {
        loadAllUnlocked().filter { it.state == state }.sortedWith(compareBy({ it.createdAt }, { it.taskId }))
    }

    /** Oldest PENDING task, or null. Does not change state. */
    fun peekPending(): InstallTask? = byState(InstallTaskState.PENDING).firstOrNull()

    /** Atomically moves the oldest PENDING task to CLAIMED and returns it. */
    fun claimNext(): InstallTask? = locked {
        val next = loadAllUnlocked()
            .filter { it.state == InstallTaskState.PENDING }
            .minWithOrNull(compareBy<InstallTask> { it.createdAt }.thenBy { it.taskId })
            ?: return@locked null
        val claimed = next.copy(state = InstallTaskState.CLAIMED, updatedAt = System.currentTimeMillis())
        writeUnlocked(claimed)
        claimed
    }

    fun cardIds(): Set<String> = locked { loadAllUnlocked().map { it.cardId }.toSet() }

    /** Marks a task SUCCEEDED/FAILED. Used by the direct root path which never gets claimed. */
    fun finish(taskId: String, success: Boolean, message: String): InstallTask = update(taskId) { current ->
        current.copy(
            state = if (success) InstallTaskState.SUCCEEDED else InstallTaskState.FAILED,
            resultCode = if (success) "OK" else "ROOT_FAILED",
            resultMessage = message,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Deletes a PENDING task file. Used by cancel. */
    fun clear(taskId: String) {
        locked {
            val task = readUnlocked(taskId) ?: throw IllegalArgumentException("任务不存在：$taskId")
            if (task.state != InstallTaskState.PENDING) {
                throw IllegalArgumentException("只能取消等待中的任务。")
            }
            val file = fileFor(taskId)
            AtomicFile(file).delete()
            if (file.exists()) throw IllegalStateException("无法删除安装任务。")
        }
    }

    private fun readUnlocked(taskId: String): InstallTask? {
        val file = fileFor(taskId)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        try {
            return AtomicFile(file).openRead().use { input ->
                InstallTask.fromJson(JSONObject(input.readBytes().toString(Charsets.UTF_8)))
            }
        } catch (error: Exception) {
            throw IllegalStateException("无法读取安装任务 $taskId。", error)
        }
    }

    private fun loadAllUnlocked(): List<InstallTask> {
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles() ?: throw IllegalStateException("无法读取安装任务目录。")
        return files.filter { it.isFile && it.name.endsWith(".json") }.mapNotNull { file ->
            val id = file.name.removeSuffix(".json")
            if (!TASK_ID.matches(id)) return@mapNotNull null
            readUnlocked(id)
        }
    }

    private fun writeUnlocked(task: InstallTask) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("无法创建安装任务目录。")
        val atomic = AtomicFile(fileFor(task.taskId))
        val out = atomic.startWrite()
        try {
            out.write(task.toJson().toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (error: Exception) {
            atomic.failWrite(out)
            throw IllegalStateException("无法写入安装任务 ${task.taskId}。", error)
        }
    }

    /** Keeps the 20 newest SUCCEEDED/FAILED tasks and deletes the rest. */
    private fun pruneCompletedUnlocked() {
        val finished = loadAllUnlocked().filter {
            it.state == InstallTaskState.SUCCEEDED || it.state == InstallTaskState.FAILED
        }.sortedWith(
            compareByDescending<InstallTask> { it.updatedAt }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.taskId },
        )
        finished.drop(KEEP_COMPLETED).forEach { task ->
            AtomicFile(fileFor(task.taskId)).delete()
        }
    }

    private fun fileFor(taskId: String): File {
        val id = requireTaskId(taskId)
        val file = File(dir, "$id.json")
        val root = dir.absoluteFile.canonicalPath
        val resolved = file.absoluteFile.canonicalPath
        if (!resolved.startsWith(root + File.separator) || file.name != "$id.json") {
            throw IllegalStateException("任务路径越界。")
        }
        return file
    }

    private inline fun <T> locked(block: () -> T): T = io {
        synchronized(lock) {
            if (dir.isDirectory) pruneCompletedUnlocked()
            block()
        }
    }

    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException(error.message ?: "安装任务读写失败。", error)
        }

    companion object {
        private val lock = Any()
        private const val KEEP_COMPLETED = 20
        private val TASK_ID =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        private fun requireTaskId(taskId: String): String {
            require(
                TASK_ID.matches(taskId) &&
                    '/' !in taskId &&
                    '\\' !in taskId &&
                    ".." !in taskId &&
                    File.separator !in taskId,
            ) { "任务 ID 非法。" }
            return taskId
        }
    }
}
