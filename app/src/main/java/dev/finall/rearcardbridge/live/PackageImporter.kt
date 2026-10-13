package dev.finall.rearcardbridge.live

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.finall.rearcardbridge.packageio.CardPackageBuilder
import java.io.File

class PackageImporter(private val context: Context) {
    fun import(uri: Uri, bindingId: String): LiveContentSnapshot = synchronized(ContentOperations.lock) {
        require(uri.scheme == "content") { "仅接受 content:// ZIP 文件。" }
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "card.zip"
        val file = File.createTempFile("incoming-", ".zip", context.cacheDir)
        try {
            requireNotNull(resolver.openInputStream(uri)) { "无法读取 ZIP 文件。" }.use { source ->
                file.outputStream().use { source.copyTo(it) }
            }
            val builder = CardPackageBuilder()
            LiveContentStore(context).save(name, builder.inspect(file), builder.extractScript(file), file, bindingId)
        } finally { file.delete() }
    }
}
