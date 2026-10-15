package dev.finall.rearcardbridge.module

import android.content.Context
import dev.finall.rearcardbridge.live.ContentOperations
import dev.finall.rearcardbridge.live.LiveContentSnapshot
import dev.finall.rearcardbridge.live.LiveContentStore
import dev.finall.rearcardbridge.packageio.CardPackageBuilder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Installs a module ZIP and publishes its card/ subtree as the module's content group.
 * The card subtree is repacked with index.html / main.js at the zip root so the existing
 * live-content pipeline (hot updates, static resource tracking, carrier builds) applies
 * unchanged; webroot/ and module.json stay in the extracted package only.
 */
class ModuleInstaller(private val context: Context) {
    private val store = ModuleStore(context)
    private val contentStore = LiveContentStore(context)
    private val builder = CardPackageBuilder()

    /** Call under ContentOperations.lock. Returns the installed manifest and saved revision. */
    fun install(zip: File, source: String?, allowDowngrade: Boolean = false): Pair<InstalledModule, LiveContentSnapshot> {
        val installed = store.install(zip, source, allowDowngrade)
        val cardDir = File(installed.packageDir, "card")
        require(cardDir.isDirectory) { "模块缺少 card/ 目录。" }
        val inner = File.createTempFile("module-card-", ".zip", context.cacheDir)
        try {
            packDirectory(cardDir, inner)
            val snapshot = synchronized(ContentOperations.lock) {
                val group = contentStore.createGroup(context.packageName, installed.manifest.id)
                contentStore.save(installed.manifest.name, builder.inspect(inner),
                    builder.extractScript(inner), inner, group.id)
            }
            return installed to snapshot
        } finally {
            inner.delete()
        }
    }

    /** Writes every file under root into the zip with paths relative to root. */
    private fun packDirectory(root: File, output: File) {
        ZipOutputStream(output.outputStream()).use { out ->
            val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }
            for (file in files) {
                val relative = file.relativeTo(root).invariantSeparatorsPath
                out.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
}
