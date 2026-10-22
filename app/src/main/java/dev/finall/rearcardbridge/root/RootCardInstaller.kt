package dev.finall.rearcardbridge.root

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Copies the generated root agent dex and runs it under su via app_process. */
object RootCardInstaller {
    private const val DEX_ASSET = "rootagent.dex"
    private const val DEX_NAME = "rootagent.dex"
    private const val ROOT_CHECK_SECONDS = 5L
    private const val RELAY_SECONDS = 60L

    fun isRootAvailable(): Boolean = try {
        val output = exec(listOf("su", "-c", "id -u"), ROOT_CHECK_SECONDS)
        output.exitCode == 0 && output.text.lineSequence().any { it.trim() == "0" }
    } catch (_: Exception) {
        false
    }

    fun install(
        context: Context,
        cardId: String,
        cardName: String,
        mrc: File,
        icon: File,
        preview: File,
        targetDir: String,
        resourceRevision: Long,
        token: String,
        expectedCount: Int,
    ): Pair<Boolean, String> = mutate(
        context, token, "install", cardId, cardName, mrc, icon, preview, targetDir, resourceRevision, expectedCount,
    )

    fun update(
        context: Context,
        cardId: String,
        cardName: String,
        mrc: File,
        icon: File,
        preview: File,
        targetDir: String,
        resourceRevision: Long,
        token: String,
        expectedCount: Int,
    ): Pair<Boolean, String> = mutate(
        context, token, "update", cardId, cardName, mrc, icon, preview, targetDir, resourceRevision, expectedCount,
    )

    fun remove(context: Context, cardId: String, token: String): Pair<Boolean, String> = try {
        val output = runRelay(context, token, listOf("remove", cardId), RELAY_SECONDS)
        (output.exitCode == 0 && resultCode(output.text) == 0) to output.text
    } catch (error: Exception) {
        false to (error.message ?: error.javaClass.simpleName)
    }

    fun restore(context: Context, cardId: String, backupPath: String, token: String): Pair<Boolean, String> = try {
        val output = runRelay(context, token, listOf("restore", cardId, backupPath), RELAY_SECONDS)
        (output.exitCode == 0 && resultCode(output.text) == 0) to output.text
    } catch (error: Exception) {
        false to (error.message ?: error.javaClass.simpleName)
    }

    fun count(context: Context, token: String): Pair<Boolean, String> = try {
        val output = runRelay(context, token, listOf("count"), RELAY_SECONDS)
        (output.exitCode == 0 && resultCode(output.text) != null) to output.text
    } catch (error: Exception) {
        false to (error.message ?: error.javaClass.simpleName)
    }

    private fun mutate(
        context: Context,
        token: String,
        action: String,
        cardId: String,
        cardName: String,
        mrc: File,
        icon: File,
        preview: File,
        targetDir: String,
        resourceRevision: Long,
        expectedCount: Int,
    ): Pair<Boolean, String> = try {
        if (!mrc.isFile || !icon.isFile || !preview.isFile) {
            false to "资源文件不存在。"
        } else {
            val output = runRelay(
                context,
                token,
                listOf(
                    action,
                    mrc.absolutePath,
                    icon.absolutePath,
                    preview.absolutePath,
                    cardId,
                    cardName,
                    targetDir,
                    resourceRevision.toString(),
                    expectedCount.toString(),
                ),
                RELAY_SECONDS,
            )
            (output.exitCode == 0 && resultCode(output.text) == 0) to output.text
        }
    } catch (error: Exception) {
        false to (error.message ?: error.javaClass.simpleName)
    }

    private fun runRelay(context: Context, token: String, args: List<String>, timeoutSec: Long): ExecOutput {
        require(token.isNotBlank()) { "token 为空。" }
        val dex = stageDex(context)
        val command = buildList {
            add("RELAY_TOKEN=${shQuote(token)}")
            add("CLASSPATH=${shQuote(dex.absolutePath)}")
            add("app_process")
            add("/system/bin")
            add("RelayInstaller")
            args.forEach { add(shQuote(it)) }
            add(shQuote(token))
        }.joinToString(" ")
        return exec(listOf("su", "-c", command), timeoutSec)
    }

    private fun stageDex(context: Context): File {
        val out = File(context.cacheDir, DEX_NAME)
        if (out.exists()) {
            runCatching { Os.chmod(out.absolutePath, 0b110100100) }
            check(out.delete()) { "无法更新 rootagent.dex" }
        }
        context.assets.open(DEX_ASSET).use { input ->
            FileOutputStream(out).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        Os.chmod(out.absolutePath, 0b100100100) // 0444
        return out
    }

    private fun resultCode(output: String): Int? =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("RESULT=") }
            .mapNotNull { it.removePrefix("RESULT=").trim().toIntOrNull() }
            .lastOrNull()

    private fun shQuote(value: String): String {
        require('\u0000' !in value && '\n' !in value && '\r' !in value) { "参数含有换行。" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun exec(command: List<String>, timeoutSec: Long): ExecOutput {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val buffer = StringBuilder()
        val reader = Thread {
            try {
                val input = process.inputStream
                val buf = ByteArray(1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    buffer.append(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000)
            return ExecOutput(-1, buffer.toString() + "\nTIMEOUT")
        }
        reader.join(5_000)
        return ExecOutput(process.exitValue(), buffer.toString())
    }

    private data class ExecOutput(val exitCode: Int, val text: String)
}
