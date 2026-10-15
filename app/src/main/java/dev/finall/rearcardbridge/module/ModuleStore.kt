package dev.finall.rearcardbridge.module

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.json.JSONObject

data class InstalledModule(
    val manifest: ModuleManifest,
    val packageDir: File,
    val dataDir: File,
    val source: String?,
)

/**
 * Module registry. Each module owns filesDir/modules/<id>/package (the extracted zip,
 * replaced on update) and filesDir/modules/<id>/data (preserved across updates: config.json,
 * storage.json, state.json and a files/ directory the module can freely use through the
 * bridge files API).
 */
class ModuleStore(context: Context) {
    private val root = File(context.filesDir, "modules")

    fun list(): List<InstalledModule> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { readInstalled(dir.name) }.getOrNull()
        }.sortedBy { it.manifest.id }
    }

    fun get(id: String): InstalledModule? =
        if (ModuleManifest.isValidModuleId(id) && File(root, id).isDirectory) {
            runCatching { readInstalled(id) }.getOrNull()
        } else null

    private fun readInstalled(id: String): InstalledModule {
        val packageDir = File(File(root, id), "package")
        val manifestFile = File(packageDir, "module.json")
        require(manifestFile.isFile) { "模块 $id 缺少 module.json。" }
        val entries = packageDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(packageDir).invariantSeparatorsPath }.toSet()
        val manifest = ModuleManifest.parse(manifestFile.readText(Charsets.UTF_8), entries)
        return InstalledModule(manifest, packageDir, File(File(root, id), "data"), readState(id).optString("source").ifBlank { null })
    }

    /**
     * Validates and installs a module zip. Updates require a strictly greater versionCode.
     * The package directory is replaced; data/ is preserved.
     */
    fun install(zip: File, source: String?): InstalledModule {
        ZipFile(zip).use { zipFile ->
            val names = LinkedHashSet<String>()
            val enumeration = zipFile.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                val path = cleanRelativePath(entry.name)
                require(path.isNotEmpty()) { "压缩包包含非法路径：${entry.name}" }
                require(names.add(path)) { "压缩包内存在重复路径：$path" }
            }
            require("module.json" in names) { "模块缺少 module.json。" }
            val manifest = ModuleManifest.parse(
                zipFile.getInputStream(zipFile.getEntry("module.json")).use {
                    it.readBytes().toString(Charsets.UTF_8)
                },
                names,
            )
            val existing = get(manifest.id)
            if (existing != null) {
                require(manifest.versionCode > existing.manifest.versionCode) {
                    "新版本号必须大于当前版本（当前 ${existing.manifest.versionCode}，安装包 ${manifest.versionCode}）。"
                }
            }
            val moduleDir = File(root, manifest.id)
            val packageDir = File(moduleDir, "package")
            val staging = File(root, ".install-${manifest.id}")
            try {
                if (staging.exists()) staging.deleteRecursively()
                check(staging.mkdirs()) { "无法创建模块临时目录。" }
                val iter = zipFile.entries()
                while (iter.hasMoreElements()) {
                    val entry = iter.nextElement()
                    if (entry.isDirectory) continue
                    val path = cleanRelativePath(entry.name)
                    if (path.isEmpty()) continue
                    val target = File(staging, path)
                    require(target.canonicalFile.path.startsWith(staging.canonicalFile.path + File.separator)) {
                        "压缩包路径越界：$path"
                    }
                    check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
                        "无法创建模块目录。"
                    }
                    zipFile.getInputStream(entry).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
                check(moduleDir.isDirectory || moduleDir.mkdirs()) { "无法创建模块目录。" }
                if (packageDir.exists()) packageDir.deleteRecursively()
                check(staging.renameTo(packageDir)) { "无法完成模块安装。" }
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
            writeState(manifest.id, readState(manifest.id)
                .put("installedAt", System.currentTimeMillis())
                .put("source", source ?: JSONObject.NULL))
            return readInstalled(manifest.id)
        }
    }

    fun remove(id: String) {
        require(ModuleManifest.isValidModuleId(id)) { "模块 id 非法。" }
        File(root, id).deleteRecursively()
    }

    fun icon(id: String): File? {
        val file = File(File(File(root, id), "package"), "icon.png")
        return if (file.isFile) file else null
    }

    fun readConfig(id: String): JSONObject = readJson(File(dataDir(id), "config.json"))
    fun writeConfig(id: String, config: JSONObject) {
        writeJson(File(dataDir(id), "config.json"), config)
        writeState(id, readState(id).put("configRev", readState(id).optLong("configRev", 0L) + 1))
    }

    fun readStorage(id: String): JSONObject = readJson(File(dataDir(id), "storage.json"))
    fun writeStorage(id: String, storage: JSONObject) = writeJson(File(dataDir(id), "storage.json"), storage)

    fun readState(id: String): JSONObject =
        readJson(File(dataDir(id), "state.json")).let { state ->
            if (!state.has("configRev")) state.put("configRev", 0L)
            if (!state.has("refreshRev")) state.put("refreshRev", 0L)
            state
        }

    fun bumpRefreshRev(id: String): Long {
        val state = readState(id)
        val next = state.optLong("refreshRev", 0L) + 1
        writeState(id, state.put("refreshRev", next))
        return next
    }

    fun filesDir(id: String): File {
        val dir = File(dataDir(id), "files")
        check(dir.isDirectory || dir.mkdirs()) { "无法创建模块文件目录。" }
        return dir
    }

    /** Resolves a module-relative path inside data/files, rejecting escapes. */
    fun resolveInFiles(id: String, relativePath: String): File {
        val base = filesDir(id)
        val cleaned = cleanRelativePath(relativePath)
        val target = if (cleaned.isEmpty()) base else File(base, cleaned)
        require(
            target.canonicalFile.path == base.canonicalFile.path ||
                target.canonicalFile.path.startsWith(base.canonicalFile.path + File.separator),
        ) { "路径越界。" }
        return target
    }

    private fun writeState(id: String, state: JSONObject) = writeJson(File(dataDir(id), "state.json"), state)

    private fun dataDir(id: String): File {
        val dir = File(File(root, id), "data")
        check(dir.isDirectory || dir.mkdirs()) { "无法创建模块数据目录。" }
        return dir
    }

    private fun readJson(file: File): JSONObject {
        val atomic = AtomicFile(file)
        if (!atomic.baseFile.exists()) return JSONObject()
        return try {
            atomic.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (error: Exception) {
            JSONObject()
        }
    }

    private fun writeJson(file: File, json: JSONObject) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "无法创建模块数据目录。"
        }
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (error: IOException) {
            atomic.failWrite(out)
            throw error
        }
    }

    companion object {
        /** Normalizes a zip/module-relative path; rejects absolute paths and parent escapes. */
        fun cleanRelativePath(raw: String): String {
            val cleaned = raw.replace('\\', '/')
            require('\u0000' !in cleaned) { "路径不能包含空字符。" }
            require(!cleaned.startsWith('/')) { "路径不能是绝对路径：$raw" }
            val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
            require(parts.none { it == ".." }) { "路径不能跳出当前目录：$raw" }
            require(parts.firstOrNull()?.contains(':') != true) { "路径不能是绝对路径：$raw" }
            return parts.joinToString("/")
        }
    }
}
