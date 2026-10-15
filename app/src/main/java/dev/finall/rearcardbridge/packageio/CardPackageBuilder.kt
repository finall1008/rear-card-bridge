package dev.finall.rearcardbridge.packageio

import dev.finall.rearcardbridge.BuildConfig
import dev.finall.rearcardbridge.model.PreparedCardPackage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns one HTML + JS zip into a rear-screen zip.
 * HTML structure is read with jsoup. Script text is concatenated, not parsed.
 */
class CardPackageBuilder {
    fun inspect(input: File): List<String> {
        openZip(input).use { zip ->
            return parse(zip).inputNames
        }
    }

    fun build(input: File, output: File, cardId: String): PreparedCardPackage {
        val canvasName = jsCanvasName(cardId)
        if (output.isDirectory) throw IllegalArgumentException("输出路径是目录")
        if (sameFile(input, output)) throw IllegalArgumentException("输出文件不能与输入压缩包相同")
        openZip(input).use { zip ->
            val loaded = parse(zip)
            val entryNames = writePackage(zip, loaded, output, canvasName)
            return PreparedCardPackage(output, entryNames)
        }
    }

    fun extractScript(input: File): String = openZip(input).use { parse(it).combinedScript }

    fun buildLiveCarrier(
        input: File?,
        output: File,
        cardId: String,
        contentUri: String,
        bridgeGroupId: String? = null,
        bridgeModuleId: String? = null,
    ): PreparedCardPackage {
        val name = jsCanvasName(cardId)
        require(!output.isDirectory) { "输出路径是目录" }
        if (input != null) {
            require(!sameFile(input, output)) { "输出文件不能与输入压缩包相同" }
            openZip(input).use { zip ->
                return PreparedCardPackage(
                    output,
                    writePackage(zip, parse(zip), output, name, live = true, contentUri = contentUri,
                        bridgeGroupId = bridgeGroupId, bridgeModuleId = bridgeModuleId),
                )
            }
        }
        val parent = requireNotNull(output.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "无法创建输出目录" }
        val tmp = File(parent, "${output.name}.partial")
        try {
            ZipOutputStream(tmp.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.xml"))
                zip.write(liveManifestXml(name, contentUri, bridgeGroupId, bridgeModuleId).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            moveReplace(tmp, output)
        } catch (error: Exception) {
            tmp.delete()
            throw error
        }
        return PreparedCardPackage(output, listOf("manifest.xml"))
    }

    /**
     * Writes the current copy plan, using layout output paths (including image aliases).
     * index.html and classic scripts that are concatenated — main.js, plus external classic
     * script src — are omitted. Other files stay, whatever their extension. A null input
     * writes an empty zip.
     */
    fun writeStaticResourceSnapshot(input: File?, output: File) {
        if (output.isDirectory) throw IllegalArgumentException("输出路径是目录")
        if (input != null && sameFile(input, output)) {
            throw IllegalArgumentException("输出文件不能与输入压缩包相同")
        }
        val files = if (input == null) emptyMap() else openZip(input).use { staticResourceFiles(it) }
        writeResourceZip(output, files)
    }

    /**
     * Compares unpacked paths and bytes. Each input source counts once, so image aliases are
     * not listed twice. Paths or bytes that the layout would install differently count as changes.
     */
    fun compareStaticResources(input: File?, installedSnapshot: File): StaticResourceChanges {
        val current = if (input == null) emptyMap() else openZip(input).use { staticResourcesBySource(it) }
        val installed = openZip(installedSnapshot).use { readSnapshotFiles(it) }
        return diffStaticResources(current, installed)
    }

    private class Loaded(
        val inputNames: List<String>,
        val entries: Map<String, ZipEntry>,
        val combinedScript: String,
        val copyPlan: Map<String, String>,
    )

    private fun parse(zip: ZipFile): Loaded {
        val entries = readEntries(zip)
        val index = entries["index.html"] ?: throw IllegalArgumentException("缺少 index.html")
        if ("main.js" !in entries) throw IllegalArgumentException("缺少 main.js")
        val doc = Jsoup.parse(readUtf8(zip, index), "", Parser.htmlParser())
        if (doc.selectFirst("canvas") == null) {
            throw IllegalArgumentException("缺少 canvas 元素")
        }
        checkHtmlImages(doc, entries)
        val combined = combineScripts(zip, doc, entries)
        return Loaded(
            inputNames = entries.keys.sorted(),
            entries = entries,
            combinedScript = combined,
            copyPlan = layout(entries.keys),
        )
    }

    private fun readEntries(zip: ZipFile): LinkedHashMap<String, ZipEntry> {
        val files = LinkedHashMap<String, ZipEntry>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            val raw = entry.name
            if (raw.isBlank()) continue
            val normalized = normalizeZipPath(raw)
            if (normalized.isEmpty() || entry.isDirectory || raw.endsWith("/")) continue
            if (files.put(normalized, entry) != null) {
                throw IllegalArgumentException("压缩包内存在重复路径：$normalized")
            }
        }
        return files
    }

    private fun combineScripts(zip: ZipFile, doc: Document, entries: Map<String, ZipEntry>): String {
        val parts = mutableListOf<String>()
        var sawMain = false
        for (script in doc.select("script")) {
            when (scriptKind(script.attr("type"))) {
                ScriptKind.MODULE ->
                    throw IllegalArgumentException("不支持 ES Module 脚本，请改成经典脚本")
                ScriptKind.OTHER -> continue
                ScriptKind.CLASSIC -> {
                    val src = script.attr("src").trim()
                    if (src.isEmpty()) {
                        val code = script.data()
                        if (code.isNotEmpty()) parts.add(code)
                    } else {
                        val path = localScriptPath(src)
                        val entry = entries[path] ?: throw IllegalArgumentException("找不到本地脚本：$path")
                        if (path == "main.js") sawMain = true
                        val code = readUtf8(zip, entry)
                        if (code.isNotEmpty()) parts.add(code)
                    }
                }
            }
        }
        if (!sawMain) {
            val code = readUtf8(zip, entries.getValue("main.js"))
            if (code.isNotEmpty()) parts.add(code)
        }
        return parts.joinToString("\n;\n")
    }

    private fun checkHtmlImages(doc: Document, entries: Map<String, ZipEntry>) {
        for (el in doc.select("img[src], image[src], image[href], source[src]")) {
            val raw = el.attr("src").ifBlank { el.attr("href") }.ifBlank { el.attr("xlink:href") }
            if (raw.isNotBlank()) requireLocalImage(raw, entries)
        }
        for (el in doc.select("[style]")) collectCssImages(el.attr("style"), entries)
        for (el in doc.select("style")) collectCssImages(el.data(), entries)
    }

    private fun collectCssImages(css: String, entries: Map<String, ZipEntry>) {
        for (match in CSS_URL.findAll(css)) {
            val raw = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
            if (looksLikeImagePath(raw)) requireLocalImage(raw, entries)
        }
    }

    private fun requireLocalImage(raw: String, entries: Map<String, ZipEntry>) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("data:", ignoreCase = true)) return
        if (isRemote(trimmed)) throw IllegalArgumentException("不支持外部图片：$trimmed")
        val path = localPath(trimmed, "图片")
        if (!imageExists(path, entries)) throw IllegalArgumentException("找不到本地图片：$path")
    }

    private fun imageExists(path: String, entries: Map<String, ZipEntry>): Boolean {
        if (path in entries) return true
        if ("assets/$path" in entries) return true
        return path.startsWith("assets/") && path.removePrefix("assets/") in entries
    }

    /**
     * Keep every input file under assets/ without doubling that prefix.
     * Images are also stored at the package root relative path so JS references resolve.
     */
    private fun layout(names: Set<String>): LinkedHashMap<String, String> {
        val plan = LinkedHashMap<String, String>()
        fun claim(output: String, stored: String) {
            val previous = plan[output]
            if (previous == null) plan[output] = stored
            else if (previous != stored) throw IllegalArgumentException("输出路径冲突：$output")
        }
        for (stored in names.sorted()) {
            val underAssets = if (stored.startsWith("assets/")) stored else "assets/$stored"
            claim(underAssets, stored)
            if (!isImage(stored)) continue
            if (stored != underAssets) claim(stored, stored)
            if (stored.startsWith("assets/")) {
                val stripped = stored.removePrefix("assets/")
                if (stripped.isNotEmpty() && stripped != underAssets) claim(stripped, stored)
            }
        }
        return plan
    }

    private fun writePackage(
        zip: ZipFile,
        loaded: Loaded,
        output: File,
        canvasName: String,
        live: Boolean = false,
        contentUri: String? = null,
        bridgeGroupId: String? = null,
        bridgeModuleId: String? = null,
    ): List<String> {
        val parent = output.absoluteFile.parentFile
            ?: throw IllegalArgumentException("无法写入输出压缩包：没有父目录")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalArgumentException("无法写入输出压缩包：不能创建目录")
        }
        val tmp = File(parent, "${output.name}.partial")
        val entryNames = ArrayList<String>(loaded.copyPlan.size + 1)
        try {
            ZipOutputStream(tmp.outputStream()).use { out ->
                val manifest = (
                    if (live) liveManifestXml(canvasName, requireNotNull(contentUri), bridgeGroupId, bridgeModuleId)
                    else manifestXml(canvasName, loaded.combinedScript)
                ).toByteArray(Charsets.UTF_8)
                out.putNextEntry(ZipEntry("manifest.xml"))
                out.write(manifest)
                out.closeEntry()
                entryNames.add("manifest.xml")
                for (name in loaded.copyPlan.keys.sorted()) {
                    val stored = loaded.copyPlan.getValue(name)
                    out.putNextEntry(ZipEntry(name))
                    zip.getInputStream(loaded.entries.getValue(stored)).use { it.copyTo(out) }
                    out.closeEntry()
                    entryNames.add(name)
                }
            }
            moveReplace(tmp, output)
        } catch (error: IllegalArgumentException) {
            tmp.delete()
            throw error
        } catch (error: Exception) {
            tmp.delete()
            throw IllegalArgumentException("无法写入输出压缩包：${error.message ?: error.javaClass.simpleName}")
        }
        return entryNames
    }

    private fun manifestXml(canvasName: String, script: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<Widget version=\"2\" frameRate=\"30\" screenWidth=\"912\" clearCanvas=\"true\">\n")
        append("  <JsCanvas name=\"")
        append(canvasName)
        append("\" w=\"#view_width\" h=\"#view_height\">\n")
        append("    <Script>")
        append(cdata(script))
        append("</Script>\n")
        append("  </JsCanvas>\n")
        append("</Widget>\n")
    }

    /**
     * Live carrier manifest. With a module id it additionally wires the bridge protocol
     * validated on-device: every second MAML reads the JsCanvas SharedPreferences key the
     * runtime writes from localStorage, forwards new outbox batches through
     * ContentResolver.call, and publishes batch acks / synchronous results / polled results
     * back as HostVars.
     */
    private fun liveManifestXml(
        canvasName: String,
        contentUri: String,
        bridgeGroupId: String? = null,
        bridgeModuleId: String? = null,
    ): String {
        val bridge = if (bridgeGroupId != null && bridgeModuleId != null) {
            bridgeManifestSection(canvasName, bridgeGroupId)
        } else ""
        val bridgeBinders = if (bridgeGroupId != null && bridgeModuleId != null) """
            <ContentProviderBinder name="bridge_results_source" uri="content://${BuildConfig.APPLICATION_ID}.bridge/results/${xmlAttr(bridgeGroupId)}" columns="payload" updateInterval="1">
              <Variable name="bridge_results" type="string" column="payload" row="0"/>
            </ContentProviderBinder>""" else ""
        val bridgeHostVars = if (bridgeGroupId != null && bridgeModuleId != null) """
            <HostVar name="bridge_results" type="string"/>
            <HostVar name="bridge_outbox_ack" type="string"/>
            <HostVar name="bridge_sync" type="string"/>""" else ""
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <Widget version="2" frameRate="30" screenWidth="912" clearCanvas="true">
          <VariableBinders>
            <ContentProviderBinder name="bridge_live_source" uri="${xmlAttr(contentUri)}" columns="payload" updateInterval="1">
              <Variable name="bridge_live_payload" type="string" column="payload" row="0"/>
            </ContentProviderBinder>$bridgeBinders
          </VariableBinders>$bridge
          <JsCanvas name="${xmlAttr(canvasName)}" w="#view_width" h="#view_height">
            <HostVar name="bridge_live_payload" type="string"/>$bridgeHostVars
            <Script>${cdata(LiveCarrierRuntime.script(canvasName, bridgeModuleId))}</Script>
          </JsCanvas>
        </Widget>
    """.trimIndent().trim()
    }

    /**
     * Native-side half of the bridge protocol. The JsCanvas localStorage of the host writes
     * into SharedPreferences named jscanvas_<canvasName.hashCode hex>_<customEditLocalId|0>;
     * this block polls the outbox key every 100 ms and forwards unseen batches. Local ops
     * (config/storage/files) must answer well within a second; a 1 s tick made sequential
     * module startup chains take tens of seconds.
     */
    private fun bridgeManifestSection(canvasName: String, groupId: String): String {
        val prefsHash = Integer.toHexString(canvasName.hashCode())
        return """
          <Var name="bridge_tick" expression="int(#time/100)" threshold="1">
            <Trigger>
              <FieldCommand class="com.miui.maml.ScreenContext" target="__objContext" targetType="var" field="mContext" method="get" return="bridge_ctx" returnType="object"/>
              <MethodCommand class="android.content.Context" target="bridge_ctx" targetType="var" method="createDeviceProtectedStorageContext" return="bridge_dp" returnType="object"/>
              <MethodCommand class="android.content.Context" target="bridge_dp" targetType="var" method="getSharedPreferences" paramTypes="java.lang.String,int" params="'jscanvas_${prefsHash}_' + ifelse(eq(len(@customEditLocalId),0),'0',@customEditLocalId),0" return="bridge_prefs" returnType="object"/>
              <MethodCommand class="android.content.SharedPreferences" target="bridge_prefs" targetType="var" method="getString" paramTypes="java.lang.String,java.lang.String" params="'bridge_outbox',''" return="bridge_outbox" returnType="string"/>
              <IfCommand ifCondition="gt(len(@bridge_outbox),0)*not(eqs(@bridge_outbox,@bridge_outbox_seen))">
                <Consequent>
                  <VariableCommand name="bridge_outbox_seen" expression="@bridge_outbox" type="string"/>
                  <MethodCommand class="android.content.Context" target="bridge_ctx" targetType="var" method="getContentResolver" return="bridge_resolver" returnType="object"/>
                  <MethodCommand class="android.os.Bundle" target="bridge_extras" targetType="ctor" method="Bundle" return="bridge_extras" returnType="object"/>
                  <MethodCommand class="android.os.Bundle" target="bridge_extras" targetType="var" method="putString" paramTypes="java.lang.String,java.lang.String" params="'payload',@bridge_outbox"/>
                  <MethodCommand class="android.content.ContentResolver" target="bridge_resolver" targetType="var" method="call" paramTypes="java.lang.String,java.lang.String,java.lang.String,android.os.Bundle" params="'${BuildConfig.APPLICATION_ID}.bridge','bridge_batch','${xmlAttr(groupId)}','bridge_extras'" return="bridge_result" returnType="object"/>
                  <MethodCommand class="android.os.Bundle" target="bridge_result" targetType="var" method="getString" paramTypes="java.lang.String" params="'batch'" return="bridge_outbox_ack" returnType="string"/>
                  <MethodCommand class="android.os.Bundle" target="bridge_result" targetType="var" method="getString" paramTypes="java.lang.String" params="'sync_results'" return="bridge_sync" returnType="string"/>
                </Consequent>
              </IfCommand>
            </Trigger>
          </Var>"""
    }

    private fun openZip(input: File): ZipFile {
        if (!input.isFile) throw IllegalArgumentException("找不到输入压缩包：${input.path}")
        try {
            return ZipFile(input)
        } catch (error: ZipException) {
            throw IllegalArgumentException("无法读取 ZIP 压缩包：${error.message ?: "格式无效"}")
        } catch (error: IOException) {
            throw IllegalArgumentException("无法读取 ZIP 压缩包：${error.message ?: "读取失败"}")
        }
    }

    private fun readUtf8(zip: ZipFile, entry: ZipEntry): String {
        try {
            val text = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
            return text.removePrefix("\uFEFF")
        } catch (error: IOException) {
            throw IllegalArgumentException("无法读取压缩包条目：${entry.name}")
        }
    }

    private enum class ScriptKind { CLASSIC, MODULE, OTHER }

    private fun scriptKind(typeRaw: String): ScriptKind {
        val base = typeRaw.trim().lowercase().substringBefore(';').trim()
        return when {
            base.isEmpty() || base in CLASSIC_TYPES -> ScriptKind.CLASSIC
            base == "module" -> ScriptKind.MODULE
            else -> ScriptKind.OTHER
        }
    }

    private fun localScriptPath(raw: String): String {
        val trimmed = raw.trim()
        if (isRemote(trimmed)) throw IllegalArgumentException("不支持外部脚本：$trimmed")
        val path = localPath(trimmed, "脚本")
        if (!path.endsWith(".js", ignoreCase = true)) {
            throw IllegalArgumentException("不支持的脚本格式：$trimmed")
        }
        return path
    }

    private fun localPath(raw: String, kind: String): String {
        val trimmed = raw.trim()
        val withoutHash = trimmed.substringBefore('#')
        if ('?' in withoutHash) throw IllegalArgumentException("${kind}路径不能包含查询参数：$trimmed")
        val decoded = percentDecode(withoutHash)
        val path = try {
            normalizeZipPath(decoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("${kind}路径非法：$trimmed")
        }
        if (path.isEmpty()) throw IllegalArgumentException("${kind}路径非法：$trimmed")
        return path
    }

    private fun normalizeZipPath(raw: String): String {
        val cleaned = raw.replace('\\', '/')
        if ('\u0000' in cleaned) throw IllegalArgumentException("压缩包路径不能包含空字符")
        if (cleaned.startsWith('/')) throw IllegalArgumentException("压缩包路径不能是绝对路径：$raw")
        val parts = cleaned.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return ""
        if (parts.any { it == ".." }) throw IllegalArgumentException("压缩包路径不能跳出当前目录：$raw")
        if (':' in parts[0]) throw IllegalArgumentException("压缩包路径不能是绝对路径：$raw")
        return parts.joinToString("/")
    }

    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        return try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("路径无法解码：$value")
        }
    }

    private fun isRemote(raw: String): Boolean {
        val lower = raw.trim().lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("//") ||
            lower.startsWith("file:") ||
            lower.startsWith("blob:")
    }

    private fun looksLikeImagePath(raw: String): Boolean {
        val path = raw.trim().substringBefore('#').substringBefore('?')
        return isImage(path)
    }

    private fun isImage(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXT
    }

    private fun sameFile(left: File, right: File): Boolean =
        try {
            left.canonicalFile == right.canonicalFile
        } catch (_: IOException) {
            left.absoluteFile == right.absoluteFile
        }

    private fun moveReplace(tmp: File, output: File) {
        try {
            Files.move(
                tmp.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            tmp.copyTo(output, overwrite = true)
            tmp.delete()
        }
    }

    private fun staticResourceFiles(zip: ZipFile): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        for (outputs in staticResourcesBySource(zip).values) files.putAll(outputs)
        return files
    }

    private fun staticResourcesBySource(zip: ZipFile): LinkedHashMap<String, LinkedHashMap<String, ByteArray>> {
        val loaded = parse(zip)
        val excluded = excludedStaticSources(zip, loaded)
        val outputsBySource = LinkedHashMap<String, MutableList<String>>()
        for (output in loaded.copyPlan.keys.sorted()) {
            val stored = loaded.copyPlan.getValue(output)
            if (stored in excluded) continue
            outputsBySource.getOrPut(stored) { mutableListOf() }.add(output)
        }
        val grouped = LinkedHashMap<String, LinkedHashMap<String, ByteArray>>()
        for (stored in outputsBySource.keys.sorted()) {
            val bytes = readBytes(zip, loaded.entries.getValue(stored))
            val outputs = LinkedHashMap<String, ByteArray>()
            for (output in outputsBySource.getValue(stored)) outputs[output] = bytes
            grouped[stored] = outputs
        }
        return grouped
    }

    private fun excludedStaticSources(zip: ZipFile, loaded: Loaded): Set<String> {
        val doc = Jsoup.parse(readUtf8(zip, loaded.entries.getValue("index.html")), "", Parser.htmlParser())
        val excluded = HashSet<String>()
        excluded.add("index.html")
        excluded.add("main.js")
        for (script in doc.select("script")) {
            if (scriptKind(script.attr("type")) != ScriptKind.CLASSIC) continue
            val src = script.attr("src").trim()
            if (src.isEmpty()) continue
            excluded.add(localScriptPath(src))
        }
        return excluded
    }

    private fun readSnapshotFiles(zip: ZipFile): LinkedHashMap<String, ByteArray> {
        val entries = readEntries(zip)
        val files = LinkedHashMap<String, ByteArray>()
        for (name in entries.keys.sorted()) files[name] = readBytes(zip, entries.getValue(name))
        return files
    }

    private fun readBytes(zip: ZipFile, entry: ZipEntry): ByteArray =
        try {
            zip.getInputStream(entry).use { it.readBytes() }
        } catch (error: IOException) {
            throw IllegalArgumentException("无法读取压缩包条目：${entry.name}")
        }

    private fun diffStaticResources(
        current: Map<String, Map<String, ByteArray>>,
        installed: Map<String, ByteArray>,
    ): StaticResourceChanges {
        val added = ArrayList<String>()
        val modified = ArrayList<String>()
        val claimed = HashSet<String>()
        for (source in current.keys) {
            val outputs = current.getValue(source)
            var present = false
            var differs = false
            for ((path, bytes) in outputs) {
                val existing = installed[path]
                if (existing == null) {
                    differs = true
                } else {
                    present = true
                    claimed.add(path)
                    if (!existing.contentEquals(bytes)) differs = true
                }
            }
            when {
                !present -> added.add(source)
                differs -> modified.add(source)
            }
        }
        return StaticResourceChanges(
            added.sorted(),
            modified.sorted(),
            collapseRemoved(installed, claimed).sorted(),
        )
    }

    /**
     * Alias pairs share one source id. The unprefixed path is that id; a lone assets/ file
     * drops the layout prefix.
     */
    private fun collapseRemoved(installed: Map<String, ByteArray>, claimed: Set<String>): List<String> {
        val pending = installed.keys.filter { it !in claimed }.toMutableSet()
        val names = ArrayList<String>()
        for (path in pending.toList().sorted()) {
            if (path !in pending) continue
            val alias = imageAlias(path)
            if (
                alias != null &&
                alias in pending &&
                installed.getValue(path).contentEquals(installed.getValue(alias))
            ) {
                pending.remove(path)
                pending.remove(alias)
                names.add(if (path.startsWith("assets/")) alias else path)
            } else {
                pending.remove(path)
                names.add(if (path.startsWith("assets/")) path.removePrefix("assets/") else path)
            }
        }
        return names
    }

    private fun imageAlias(path: String): String? {
        if (!isImage(path)) return null
        return if (path.startsWith("assets/")) {
            val stripped = path.removePrefix("assets/")
            if (stripped.isNotEmpty() && isImage(stripped)) stripped else null
        } else {
            "assets/$path"
        }
    }

    private fun writeResourceZip(output: File, files: Map<String, ByteArray>) {
        val parent = output.absoluteFile.parentFile
            ?: throw IllegalArgumentException("无法写入输出压缩包：没有父目录")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalArgumentException("无法写入输出压缩包：不能创建目录")
        }
        val tmp = File(parent, "${output.name}.partial")
        try {
            ZipOutputStream(tmp.outputStream()).use { out ->
                for (name in files.keys.sorted()) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(files.getValue(name))
                    out.closeEntry()
                }
            }
            moveReplace(tmp, output)
        } catch (error: IllegalArgumentException) {
            tmp.delete()
            throw error
        } catch (error: Exception) {
            tmp.delete()
            throw IllegalArgumentException("无法写入输出压缩包：${error.message ?: error.javaClass.simpleName}")
        }
    }

    companion object {
        private val CLASSIC_TYPES = setOf(
            "text/javascript",
            "application/javascript",
            "text/ecmascript",
            "application/ecmascript",
        )
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg")
        private val CSS_URL = Regex("""(?i)url\(\s*(?:"([^"]*)"|'([^']*)'|([^)'"\s]+))\s*\)""")

        private fun cdata(script: String): String {
            val body = script.replace("]]>", "]]]]><![CDATA[>")
            return "<![CDATA[\n$body\n]]>"
        }

        private fun xmlAttr(value: String): String = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }

        private fun jsCanvasName(cardId: String): String {
            val trimmed = cardId.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("卡片 ID 不能为空")
            val mapped = buildString(trimmed.length) {
                for (ch in trimmed) {
                    append(
                        when {
                            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                                ch == '.' || ch == '_' || ch == '-' -> ch
                            else -> '_'
                        },
                    )
                }
            }
            return if (mapped[0].isLetter() || mapped[0] == '_') mapped else "n$mapped"
        }
    }
}
