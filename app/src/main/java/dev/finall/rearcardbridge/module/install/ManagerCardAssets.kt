package dev.finall.rearcardbridge.module.install

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Icon, preview, and relay package for the in-app manager card. */
object ManagerCardAssets {
    fun writeTo(context: Context, dir: File): File {
        require(!dir.exists() || dir.isDirectory) { "输出路径不是目录。" }
        check(dir.isDirectory || dir.mkdirs()) { "无法创建管理器卡目录。" }
        writeRoundedPng(File(dir, "app_icon.png"), ICON_SIZE, ICON_SIZE, ICON_COLOR, ICON_RADIUS)
        writeRoundedPng(File(dir, "preview.png"), PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_COLOR, PREVIEW_RADIUS)
        return writePackage(context, dir)
    }

    private fun writeRoundedPng(file: File, width: Int, height: Int, color: Int, radius: Float) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法写出 ${file.name}。" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Writes (once) a generated placeholder icon next to the caller's directory. */
    fun defaultIcon(dir: File): File {
        check(dir.isDirectory || dir.mkdirs()) { "无法创建图标目录。" }
        val file = File(dir, "default-icon.png")
        if (!file.isFile || file.length() == 0L) {
            writeRoundedPng(file, ICON_SIZE, ICON_SIZE, ICON_COLOR, ICON_RADIUS)
        }
        return file
    }

    private fun writePackage(context: Context, dir: File): File {
        val mrc = File(dir, "rearScreen.mrc")
        val partial = File(dir, ".rearScreen.mrc.partial")
        try {
            ZipOutputStream(partial.outputStream()).use { zip ->
                // rearScreen.mrc 是 zip 包，宿主读取根部的 manifest.xml。
                zip.putNextEntry(ZipEntry("manifest.xml"))
                context.assets.open("relay/manifest.xml").use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            if (mrc.exists() && !mrc.delete()) throw IllegalStateException("无法替换 rearScreen.mrc。")
            check(partial.renameTo(mrc)) { "无法写出 rearScreen.mrc。" }
        } catch (error: Exception) {
            partial.delete()
            if (error is IllegalStateException) throw error
            throw IllegalStateException("无法生成管理器卡：${error.message ?: error.javaClass.simpleName}", error)
        }
        return mrc
    }

    private const val ICON_SIZE = 192
    private const val ICON_RADIUS = 36f
    private const val ICON_COLOR = 0xFF3D6BF5.toInt()
    private const val PREVIEW_WIDTH = 912
    private const val PREVIEW_HEIGHT = 596
    private const val PREVIEW_RADIUS = 28f
    private const val PREVIEW_COLOR = 0xFF152033.toInt()
}
