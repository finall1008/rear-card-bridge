package dev.finall.rearcardbridge.packageio

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

class CardPackageBuilderTest {
    private val builder = CardPackageBuilder()

    @Test
    fun buildPairsScriptsAndKeepsImagePaths() {
        val root = Files.createTempDirectory("rear-card-pkg").toFile()
        val icon = byteArrayOf(1, 2, 3, 4)
        val mark = byteArrayOf(5, 6, 7, 8)
        val input = zip(
            File(root, "in.zip"),
            mapOf(
                "index.html" to html(
                    """
                    <canvas id="rear" width="912" height="412"></canvas>
                    <script src="lib.js"></script>
                    <script>window.__inline = "ok]]>"</script>
                    <script src="./main.js"></script>
                    """.trimIndent(),
                ).toByteArray(),
                "lib.js" to "var fromLib = 1;".toByteArray(),
                "main.js" to "var icon = \"icon.png\";\nvar mark = \"images/mark.png\";".toByteArray(),
                "images/mark.png" to mark,
                "assets/icon.png" to icon,
            ),
        )
        assertEquals(
            listOf("assets/icon.png", "images/mark.png", "index.html", "lib.js", "main.js"),
            builder.inspect(input),
        )

        val output = File(root, "out.zip")
        val prepared = builder.build(input, output, "rear.card/01")
        assertEquals(output, prepared.file)
        assertEquals(
            listOf(
                "manifest.xml",
                "assets/icon.png",
                "assets/images/mark.png",
                "assets/index.html",
                "assets/lib.js",
                "assets/main.js",
                "icon.png",
                "images/mark.png",
            ),
            prepared.entryNames,
        )
        assertFalse(prepared.entryNames.any { it.startsWith("assets/assets/") })

        ZipFile(output).use { zip ->
            val stored = ArrayList<String>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) stored.add(enumeration.nextElement().name)
            assertEquals(prepared.entryNames.toSet(), stored.toSet())
            assertBytes(zip, "icon.png", icon)
            assertBytes(zip, "assets/icon.png", icon)
            assertBytes(zip, "images/mark.png", mark)
            assertBytes(zip, "assets/images/mark.png", mark)
            val xml = zip.getInputStream(zip.getEntry("manifest.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(xml.contains("]]]]><![CDATA[>"))
            val widget = parseXml(xml)
            assertEquals("Widget", widget.tagName)
            assertEquals("2", widget.getAttribute("version"))
            assertEquals("30", widget.getAttribute("frameRate"))
            assertEquals("912", widget.getAttribute("screenWidth"))
            assertEquals("true", widget.getAttribute("clearCanvas"))
            val canvases = widget.getElementsByTagName("JsCanvas")
            assertEquals(1, canvases.length)
            val canvas = canvases.item(0) as Element
            assertEquals("rear.card_01", canvas.getAttribute("name"))
            assertEquals("#view_width", canvas.getAttribute("w"))
            assertEquals("#view_height", canvas.getAttribute("h"))
            val script = (canvas.getElementsByTagName("Script").item(0) as Element).textContent
            val libAt = script.indexOf("fromLib")
            val inlineAt = script.indexOf("ok]]>")
            val mainAt = script.indexOf("images/mark.png")
            assertTrue(libAt >= 0 && libAt < inlineAt && inlineAt < mainAt)
        }

        val appended = File(root, "appended.zip")
        zip(
            appended,
            mapOf(
                "index.html" to html("<canvas></canvas><script>var inlineOnly = 1;</script>").toByteArray(),
                "main.js" to "var fromMain = 2;".toByteArray(),
            ),
        )
        val appendedOut = File(root, "appended-out.zip")
        builder.build(appended, appendedOut, "demo-card")
        ZipFile(appendedOut).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("manifest.xml")).readBytes().toString(Charsets.UTF_8)
            val script = ((parseXml(xml).getElementsByTagName("Script").item(0)) as Element).textContent
            val inlineAt = script.indexOf("inlineOnly")
            val mainAt = script.indexOf("fromMain")
            assertTrue(inlineAt >= 0 && inlineAt < mainAt)
            assertTrue(xml.contains("name=\"demo-card\""))
        }
    }

    @Test
    fun rejectsUnsupportedFormat() {
        val root = Files.createTempDirectory("rear-card-bad").toFile()
        val junk = File(root, "no.zip").apply { writeText("not a zip") }
        assertFormat("无法读取 ZIP") { builder.inspect(junk) }

        val slip = zip(File(root, "slip.zip"), mapOf("../evil.txt" to "x".toByteArray(), "index.html" to "<canvas></canvas>".toByteArray(), "main.js" to "".toByteArray()))
        assertFormat("不能跳出当前目录") { builder.build(slip, File(root, "slip-out.zip"), "card") }
        assertFalse(File(root, "slip-out.zip").exists())
        val absolute = zip(File(root, "absolute.zip"), mapOf("/index.html" to "<canvas></canvas>".toByteArray(), "main.js" to "".toByteArray()))
        assertFormat("不能是绝对路径") { builder.inspect(absolute) }

        assertFormat("缺少 canvas") {
            builder.build(pack(root, "nocanvas.zip", "<script src=\"main.js\"></script>", "var x = 1;"), File(root, "a.zip"), "card")
        }
        assertFormat("缺少 main.js") {
            builder.inspect(zip(File(root, "nomain.zip"), mapOf("index.html" to html("<canvas></canvas>").toByteArray())))
        }
        assertFormat("不支持外部脚本") {
            builder.build(
                pack(root, "remote.zip", "<canvas></canvas><script src=\"https://example.com/a.js\"></script>", "var x = 1;"),
                File(root, "b.zip"),
                "card",
            )
        }
        assertFormat("不支持 ES Module") {
            builder.build(
                pack(root, "module.zip", "<canvas></canvas><script type=\"module\" src=\"main.js\"></script>", "var x = 1;"),
                File(root, "c.zip"),
                "card",
            )
        }
        assertFormat("卡片 ID 不能为空") {
            builder.build(pack(root, "id.zip", "<canvas></canvas><script src=\"main.js\"></script>", "var x = 1;"), File(root, "d.zip"), "  ")
        }
        assertFormat("不支持外部图片") {
            builder.build(
                pack(root, "img.zip", "<canvas></canvas><img src=\"https://example.com/a.png\"><script src=\"main.js\"></script>", "var x = 1;"),
                File(root, "e.zip"),
                "card",
            )
        }
    }

    @Test
    fun liveCarrierReadsPersistentContentRatherThanEmbeddingTheImportedScript() {
        val root = Files.createTempDirectory("rear-live-carrier").toFile()
        val input = pack(root, "content.zip", "<canvas></canvas><script src=\"main.js\"></script>", "var importedMarker = '最新内容';")
        assertEquals("var importedMarker = '最新内容';", builder.extractScript(input))
        val output = File(root, "carrier.zip")
        builder.buildLiveCarrier(input, output, "live/card", "content://dev.finall.rearcardbridge.live/bindings/test/current")
        ZipFile(output).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("manifest.xml")).readBytes().toString(Charsets.UTF_8)
            val widget = parseXml(xml)
            val binder = widget.getElementsByTagName("ContentProviderBinder").item(0) as Element
            assertEquals("content://dev.finall.rearcardbridge.live/bindings/test/current", binder.getAttribute("uri"))
            assertEquals("payload", binder.getAttribute("columns"))
            val canvas = widget.getElementsByTagName("JsCanvas").item(0) as Element
            assertEquals("live_card", canvas.getAttribute("name"))
            val host = canvas.getElementsByTagName("HostVar").item(0) as Element
            assertEquals("bridge_live_payload", host.getAttribute("name"))
            assertEquals("string", host.getAttribute("type"))
            val script = canvas.getElementsByTagName("Script").item(0).textContent
            assertTrue(script.contains("data.bridge_live_payload"))
            assertFalse(script.contains("importedMarker"))
            assertTrue(zip.getEntry("assets/main.js") != null)
        }
        val blank = File(root, "blank-carrier.zip")
        assertEquals(listOf("manifest.xml"), builder.buildLiveCarrier(null, blank, "blank", "content://dev.finall.rearcardbridge.live/bindings/blank/current").entryNames)
        ZipFile(blank).use { zip -> parseXml(zip.getInputStream(zip.getEntry("manifest.xml")).readBytes().toString(Charsets.UTF_8)) }
    }

    @Test
    fun liveGroupCarriersReadTheirOwnUris() {
        val root = Files.createTempDirectory("rear-live-groups").toFile()
        val input = pack(root, "content.zip", "<canvas></canvas><script src=\"main.js\"></script>", "var importedMarker = 1;")
        val alphaUri = "content://dev.finall.rearcardbridge.live/group/alpha?q=1&name=\"\u5361\u7247\""
        val betaUri = "content://dev.finall.rearcardbridge.live/group/beta?x=<2>&y='ok'"
        val alpha = File(root, "alpha.zip")
        val beta = File(root, "beta.zip")
        builder.buildLiveCarrier(input, alpha, "group/alpha", alphaUri)
        builder.buildLiveCarrier(null, beta, "group/beta", betaUri)

        ZipFile(alpha).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("manifest.xml")).readBytes().toString(Charsets.UTF_8)
            val binder = parseXml(xml).getElementsByTagName("ContentProviderBinder").item(0) as Element
            assertEquals(alphaUri, binder.getAttribute("uri"))
            assertEquals("payload", binder.getAttribute("columns"))
            assertTrue(xml.contains("uri=\"content://dev.finall.rearcardbridge.live/group/alpha?q=1&amp;name=&quot;\u5361\u7247&quot;\""))
            assertFalse(xml.contains(betaUri))
            assertFalse(xml.contains("importedMarker"))
            assertTrue(zip.getEntry("assets/main.js") != null)
        }
        ZipFile(beta).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("manifest.xml")).readBytes().toString(Charsets.UTF_8)
            val binder = parseXml(xml).getElementsByTagName("ContentProviderBinder").item(0) as Element
            assertEquals(betaUri, binder.getAttribute("uri"))
            assertTrue(xml.contains("uri=\"content://dev.finall.rearcardbridge.live/group/beta?x=&lt;2&gt;&amp;y=&apos;ok&apos;\""))
            assertFalse(xml.contains(alphaUri))
            assertEquals(listOf("manifest.xml"), zip.entries().toList().map { it.name })
        }

    }

    @Test
    fun moduleCarrierWiresBridgeProtocol() {
        val root = Files.createTempDirectory("rear-module-carrier").toFile()
        val input = pack(root, "content.zip", "<canvas></canvas><script src=\"main.js\"></script>", "var x = 1;")
        val groupId = "3f6f9c7c-2c5b-4d4f-9c4d-2f8a2a4f9a11"
        val output = File(root, "module-carrier.zip")
        builder.buildLiveCarrier(input, output, "module/card",
            "content://dev.finall.rearcardbridge.live/bindings/$groupId/current",
            bridgeGroupId = groupId, bridgeModuleId = "dev.example.weather")
        ZipFile(output).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("manifest.xml")).readBytes().toString(Charsets.UTF_8)
            val widget = parseXml(xml)
            val binders = widget.getElementsByTagName("ContentProviderBinder")
            val uris = (0 until binders.length).map { (binders.item(it) as Element).getAttribute("uri") }
            val bridgeAuthority = dev.finall.rearcardbridge.BuildConfig.APPLICATION_ID + ".bridge"
            assertTrue(uris.contains("content://$bridgeAuthority/results/$groupId"))
            val canvas = widget.getElementsByTagName("JsCanvas").item(0) as Element
            val hostVars = canvas.getElementsByTagName("HostVar")
            val hostNames = (0 until hostVars.length).map { (hostVars.item(it) as Element).getAttribute("name") }
            assertTrue(hostNames.containsAll(listOf("bridge_live_payload", "bridge_results", "bridge_outbox_ack", "bridge_sync")))
            val prefsName = "jscanvas_" + Integer.toHexString("module_card".hashCode()) + "_"
            assertTrue(xml.contains("'$prefsName' + ifelse(eq(len(@customEditLocalId),0),'0',@customEditLocalId)"))
            assertTrue(xml.contains("'$bridgeAuthority','bridge_batch','$groupId','bridge_extras'"))
            val script = canvas.getElementsByTagName("Script").item(0).textContent
            assertTrue(script.contains("var MODULE_ID = 'dev.example.weather';"))
            assertTrue(script.contains("localStorage.setItem('bridge_outbox'"))
        }
        val plain = File(root, "plain-carrier.zip")
        builder.buildLiveCarrier(input, plain, "module/card",
            "content://dev.finall.rearcardbridge.live/bindings/$groupId/current")
        ZipFile(plain).use { zip ->
            val xml = zip.getInputStream(zip.getEntry("manifest.xml")).readBytes().toString(Charsets.UTF_8)
            assertFalse(xml.contains("dev.finall.rearcardbridge.bridge"))
            val hostVars = parseXml(xml).getElementsByTagName("HostVar")
            val hostNames = (0 until hostVars.length).map { (hostVars.item(it) as Element).getAttribute("name") }
            assertEquals(listOf("bridge_live_payload"), hostNames)
            assertTrue(parseXml(xml).getElementsByTagName("Script").item(0).textContent
                .contains("var MODULE_ID = '';"))
        }
    }

    private fun pack(root: File, name: String, body: String, main: String): File =
        zip(
            File(root, name),
            mapOf(
                "index.html" to html(body).toByteArray(),
                "main.js" to main.toByteArray(),
            ),
        )

    private fun html(body: String) = "<!DOCTYPE html><html><body>$body</body></html>"

    private fun zip(file: File, entries: Map<String, ByteArray>): File {
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun assertBytes(zip: ZipFile, name: String, expected: ByteArray) {
        val actual = zip.getInputStream(zip.getEntry(name)).readBytes()
        assertTrue(expected.contentEquals(actual))
    }

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        return document.documentElement
    }

    private fun assertFormat(part: String, block: () -> Unit) {
        try {
            block()
            fail("应当抛出 IllegalArgumentException：$part")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), part in (error.message ?: ""))
        }
    }
}
