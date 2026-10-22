package dev.finall.rearcardbridge.module.install

import android.content.Context
import dev.finall.rearcardbridge.live.LiveContentContract
import dev.finall.rearcardbridge.live.LiveContentStore
import dev.finall.rearcardbridge.module.ModuleStore
import dev.finall.rearcardbridge.packageio.CardPackageBuilder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-side install coordinator. Builds the rear-card package and records a task.
 * Staging into the privileged card directory is left for a later integration step.
 */
class InstallManager(private val context: Context) {
    private val modules = ModuleStore(context)
    private val content = LiveContentStore(context)
    private val tasks = InstallTaskStore(context)
    private val builder = CardPackageBuilder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableTasks = MutableStateFlow<List<InstallTask>>(emptyList())
    val taskFlow: StateFlow<List<InstallTask>> = mutableTasks.asStateFlow()

    init {
        refresh()
        scope.launch {
            while (true) {
                delay(POLL_MS)
                refresh()
            }
        }
    }

    private fun cardIdFor(moduleId: String): String = "$CARD_ID_PREFIX$moduleId"

    /** Snapshot of the last successfully installed card resources for one cardId. */
    private fun installedBundle(cardId: String): File =
        File(context.filesDir, "install/installed-$cardId.zip")

    /**
     * Builds the staging ZIP for [moduleId] into [output]; the task id is only the file
     * name on disk afterwards, so any file may be used.
     */
    private fun buildStageBundle(moduleId: String, output: File): Long {
        val module = modules.get(moduleId) ?: throw IllegalArgumentException("模块不存在：$moduleId")
        val cardDir = File(module.packageDir, "card")
        require(cardDir.isDirectory) { "模块缺少 card/ 目录。" }
        val outputDir = File(context.cacheDir, "install/${module.manifest.id}")
        check(outputDir.isDirectory || outputDir.mkdirs()) { "无法创建安装缓存目录。" }
        val packed = File(outputDir, "card.zip")
        val mrc = File(outputDir, "rearScreen.mrc")
        val group = content.createGroup(context.packageName, module.manifest.id)
        val revision = content.current(group.id)?.revision ?: module.manifest.versionCode
        try {
            val archive = content.archive(group.id) ?: packed.also { packDirectory(cardDir, it) }
            builder.buildLiveCarrier(
                archive, mrc, cardIdFor(moduleId),
                LiveContentContract.uri(group.id).toString(),
                bridgeGroupId = group.id, bridgeModuleId = module.manifest.id,
            )
        } finally {
            packed.delete()
        }
        packStageBundle(mrc, File(module.packageDir, "icon.png"), File(module.packageDir, "preview.png"), output)
        return revision
    }

    /**
     * Repackages the module card/ tree into a staging ZIP and records an INSTALL/UPDATE task.
     * The ZIP contains rearScreen.mrc plus icon/preview; [InstallTask.stagingPath] stays
     * null until the privileged service has extracted it under /data/local/tmp.
     */
    fun submitInstall(moduleId: String, update: Boolean = false): InstallTask {
        val module = modules.get(moduleId) ?: throw IllegalArgumentException("模块不存在：$moduleId")
        val cardId = cardIdFor(moduleId)
        // Supersede queued installs for the same card so only the newest bundle runs.
        cancelPendingForCard(cardId)
        val group = content.createGroup(context.packageName, module.manifest.id)
        val revision = content.current(group.id)?.revision ?: module.manifest.versionCode
        val task = tasks.create(
            op = if (update) InstallOp.UPDATE else InstallOp.INSTALL,
            groupId = group.id,
            // Standalone card resIds are namespaced away from any prior carrier-card usage.
            cardId = cardId,
            cardName = module.manifest.name,
            resourceRevision = revision,
            stagingPath = null,
        )
        val bundle = stagingBundle(task.taskId)
        try {
            buildStageBundle(moduleId, bundle)
        } catch (error: Exception) {
            runCatching { tasks.clear(task.taskId) }
            bundle.delete()
            refresh()
            throw if (error is IllegalStateException) error
                else IllegalStateException("无法生成安装暂存包：${error.message ?: error.javaClass.simpleName}", error)
        }
        refresh()
        return task
    }

    /** Whether a card has been successfully installed and its resources snapshot kept. */
    fun cardInstalled(cardId: String): Boolean = installedBundle(cardId).isFile

    /**
     * Compares the installed snapshot against a freshly built bundle.
     * Returns null when no install was recorded or inputs are missing.
     */
    fun cardNeedsUpdate(moduleId: String): Boolean? {
        val installed = installedBundle(cardIdFor(moduleId))
        if (!installed.isFile) return null
        val fresh = File(context.cacheDir, "install/diff-${moduleId}.zip")
        return try {
            buildStageBundle(moduleId, fresh)
            !sameBundleContent(fresh, installed)
        } catch (_: Exception) {
            null
        } finally {
            fresh.delete()
        }
    }

    /**
     * Compares stage bundles entry-by-entry. rearScreen.mrc is itself a ZIP whose
     * container bytes change every build (entry timestamps), so its entries are
     * compared by content instead.
     */
    private fun sameBundleContent(left: File, right: File): Boolean {
        java.util.zip.ZipFile(left).use { a ->
            java.util.zip.ZipFile(right).use { b ->
                for (name in STAGE_NAMES) {
                    val entryA = a.getEntry(name) ?: return false
                    val entryB = b.getEntry(name) ?: return false
                    val bytesA = a.getInputStream(entryA).use { it.readBytes() }
                    val bytesB = b.getInputStream(entryB).use { it.readBytes() }
                    if (name.endsWith(".mrc")) {
                        if (!sameZipContent(bytesA, bytesB)) return false
                    } else if (!bytesA.contentEquals(bytesB)) return false
                }
                return true
            }
        }
    }

    private fun sameZipContent(left: ByteArray, right: ByteArray): Boolean {
        fun entriesOf(bytes: ByteArray): Map<String, ByteArray> =
            java.util.zip.ZipInputStream(bytes.inputStream()).use { input ->
                generateSequence { input.nextEntry }
                    .associate { it.name to input.readBytes() }
            }
        val a = runCatching { entriesOf(left) }.getOrElse { return false }
        val b = runCatching { entriesOf(right) }.getOrElse { return false }
        return a.size == b.size && a.all { (name, bytes) ->
            b[name]?.contentEquals(bytes) ?: false
        }
    }

    /** Cancels queued INSTALL/UPDATE tasks for one cardId; returns true if anything was cancelled. */
    fun cancelPendingForCard(cardId: String): Boolean {
        var cancelled = false
        runCatching {
            tasks.list()
                .filter { it.cardId == cardId && it.state == InstallTaskState.PENDING &&
                    (it.op == InstallOp.INSTALL || it.op == InstallOp.UPDATE) }
                .forEach {
                    runCatching { tasks.clear(it.taskId) }
                    discardStageBundle(it.taskId)
                    cancelled = true
                }
        }
        refresh()
        return cancelled
    }

    /** Writes the staging ZIP the privileged service extracts for one task. */
    fun stageBundleFile(taskId: String): File {
        val file = File(context.cacheDir, "install/stage-$taskId.zip")
        require(file.isFile) { "安装暂存包不存在，请重新发起安装。" }
        return file
    }

    /** Terminal result for the root path, which bypasses claim/report. */
    fun finishTask(taskId: String, success: Boolean, message: String) {
        runCatching { tasks.finish(taskId, success, message) }
        refresh()
    }

    fun markStaged(taskId: String, stagingPath: String): InstallTask {
        val task = tasks.markStaged(taskId, stagingPath)
        refresh()
        return task
    }

    /** Removes the cached staging ZIP after a task completes or is cancelled. */
    fun discardStageBundle(taskId: String) {
        File(context.cacheDir, "install/stage-$taskId.zip").delete()
    }

    private fun packStageBundle(mrc: File, icon: File?, preview: File?, output: File) {
        val parent = output.absoluteFile.parentFile ?: throw IllegalStateException("无法写出安装暂存包。")
        check(parent.isDirectory || parent.mkdirs()) { "无法写出安装暂存包。" }
        val tmp = File(parent, "${output.name}.partial")
        try {
            ZipOutputStream(tmp.outputStream()).use { out ->
                fun entry(name: String, file: File) {
                    out.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
                require(mrc.isFile && mrc.length() > 0) { "卡片包生成失败。" }
                entry("rearScreen.mrc", mrc)
                val iconFile = icon?.takeIf { it.isFile && it.length() > 0 }
                    ?: ManagerCardAssets.defaultIcon(output.parentFile ?: parent)
                entry("app_icon.png", iconFile)
                val previewFile = preview?.takeIf { it.isFile && it.length() > 0 }
                    ?: icon?.takeIf { it.isFile && it.length() > 0 }
                    ?: ManagerCardAssets.defaultIcon(output.parentFile ?: parent)
                entry("preview.png", previewFile)
            }
            if (output.exists() && !output.delete()) throw IllegalStateException("无法替换安装暂存包。")
            check(tmp.renameTo(output)) { "无法写出安装暂存包。" }
        } catch (error: Exception) {
            tmp.delete()
            throw error
        }
    }

    private fun stagingBundle(taskId: String): File =
        File(context.cacheDir, "install/stage-$taskId.zip")

    fun removeCard(cardId: String): InstallTask {
        require(cardId.isNotBlank()) { "卡片 ID 不能为空。" }
        val moduleId = cardId.removePrefix(CARD_ID_PREFIX)
        val group = content.group(context.packageName, moduleId)
        val previous = runCatching { tasks.list() }.getOrNull()?.lastOrNull { it.cardId == cardId }
        val task = tasks.create(
            op = InstallOp.REMOVE,
            groupId = group?.id ?: previous?.groupId ?: cardId,
            cardId = cardId,
            cardName = group?.let { modules.get(it.key)?.manifest?.name }
                ?: previous?.cardName ?: cardId,
            resourceRevision = group?.let { content.current(it.id)?.revision } ?: previous?.resourceRevision ?: 0L,
            stagingPath = null,
        )
        refresh()
        return task
    }

    fun cancel(taskId: String) {
        tasks.clear(taskId)
        refresh()
    }

    fun pendingTask(): InstallTask? = tasks.peekPending()

    private fun packDirectory(root: File, output: File) {
        val base = root.canonicalFile
        ZipOutputStream(output.outputStream()).use { out ->
            val files = root.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            for (file in files) {
                val canonical = file.canonicalFile
                require(
                    canonical.path == base.path || canonical.path.startsWith(base.path + File.separator),
                ) { "卡片路径越界。" }
                val relative = file.relativeTo(root).invariantSeparatorsPath
                require(relative.isNotEmpty() && ".." !in relative) { "卡片路径越界。" }
                out.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    private fun refresh() {
        val previous = mutableTasks.value
        val current = runCatching { tasks.list() }.getOrElse { previous }
        if (current !== previous) {
            // Terminal bookkeeping: snapshot newly-succeeded bundles, drop finished stage zips.
            for (task in current) {
                val before = previous.firstOrNull { it.taskId == task.taskId }?.state
                val finished = task.state == InstallTaskState.SUCCEEDED || task.state == InstallTaskState.FAILED
                if (finished) {
                    if (before == task.state) {
                        // Backfill: succeeded before snapshot support existed but the zip survived.
                        if (task.state == InstallTaskState.SUCCEEDED &&
                            task.op != InstallOp.REMOVE && !installedBundle(task.cardId).isFile) {
                            val zip = stagingBundle(task.taskId)
                            if (zip.isFile) {
                                runCatching { zip.copyTo(installedBundle(task.cardId), overwrite = true) }
                            }
                        }
                        continue
                    }
                    if (task.op == InstallOp.REMOVE) {
                        if (task.state == InstallTaskState.SUCCEEDED) installedBundle(task.cardId).delete()
                    } else if (task.state == InstallTaskState.SUCCEEDED) {
                        val zip = stagingBundle(task.taskId)
                        if (zip.isFile) {
                            runCatching { zip.copyTo(installedBundle(task.cardId), overwrite = true) }
                        }
                    }
                    discardStageBundle(task.taskId)
                }
            }
        }
        mutableTasks.value = current
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val CARD_ID_PREFIX = "finall-card-"
        val STAGE_NAMES = arrayOf("rearScreen.mrc", "app_icon.png", "preview.png")
    }
}
