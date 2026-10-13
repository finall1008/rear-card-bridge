package dev.finall.rearcardbridge.packageio

import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticResourceSnapshotTest {
    private val builder = CardPackageBuilder()
    private val mark = byteArrayOf(9, 8, 7, 6)
    private val icon = byteArrayOf(1, 2, 3, 4)
    private val config = "{\"v\":1}".toByteArray()
    private val tmpl = "keep-template".toByteArray()
    private val helper = "helper-script".toByteArray()

    @Test
    fun nullInputWritesAnEmptySnapshot() {
        val root = Files.createTempDirectory("rear-static-empty").toFile()
        val output = File(root, "empty.zip")
        builder.writeStaticResourceSnapshot(null, output)
        ZipFile(output).use { zip -> assertFalse(zip.entries().hasMoreElements()) }

        val none = builder.compareStaticResources(null, output)
        assertFalse(none.changed)
        assertEquals(emptyList<String>(), none.added)
        assertEquals(emptyList<String>(), none.modified)
        assertEquals(emptyList<String>(), none.removed)

        val input = pack(root, "one.zip", "images/mark.png" to mark)
        val added = builder.compareStaticResources(input, output)
        assertEquals(listOf("images/mark.png"), added.added)
        assertTrue(added.modified.isEmpty())
        assertTrue(added.removed.isEmpty())
        assertTrue(added.changed)

        val snapshot = File(root, "snap.zip")
        builder.writeStaticResourceSnapshot(input, snapshot)
        val removed = builder.compareStaticResources(null, snapshot)
        assertEquals(listOf("images/mark.png"), removed.removed)
        assertTrue(removed.changed)
    }

    @Test
    fun snapshotKeepsLayoutPathsAndSkipsCombinedScripts() {
        val root = Files.createTempDirectory("rear-static-shape").toFile()
        val input = zip(File(root, "in.zip"), sample())
        val snapshot = File(root, "snap.zip")
        builder.writeStaticResourceSnapshot(input, snapshot)

        val expected = listOf(
            "assets/helper.js",
            "assets/icon.png",
            "assets/images/mark.png",
            "assets/tmpl.js",
            "assets/vendor/config.json",
            "icon.png",
            "images/mark.png",
        )
        assertEquals(expected, namesInOrder(snapshot))
        assertBytes(snapshot, "icon.png", icon)
        assertBytes(snapshot, "assets/icon.png", icon)
        assertBytes(snapshot, "images/mark.png", mark)
        assertBytes(snapshot, "assets/images/mark.png", mark)
        assertBytes(snapshot, "assets/vendor/config.json", config)
        assertBytes(snapshot, "assets/helper.js", helper)
        assertBytes(snapshot, "assets/tmpl.js", tmpl)
        assertTrue(namesInOrder(snapshot).none { it.startsWith("assets/assets/") })
        assertTrue("index.html" !in namesInOrder(snapshot).map { it.substringAfterLast('/') })
        assertFalse(namesInOrder(snapshot).any { it == "assets/main.js" || it == "assets/lib.js" || it == "assets/index.html" })
    }

    @Test
    fun scriptOnlyChangesDoNotRequireAnUpdate() {
        val root = Files.createTempDirectory("rear-static-script").toFile()
        val installed = File(root, "installed.zip")
        builder.writeStaticResourceSnapshot(zip(File(root, "base.zip"), sample()), installed)
        val scriptOnly = sample(
            main = "var main = 2;",
            lib = "var lib = 2;",
            inline = "var inline = 2;",
            banner = "replaced copy",
        )
        val snapshot = File(root, "script-only.zip")
        builder.writeStaticResourceSnapshot(zip(File(root, "script.zip"), scriptOnly), snapshot)
        assertSameEntries(installed, snapshot)
        val changes = builder.compareStaticResources(zip(File(root, "script-compare.zip"), scriptOnly), installed)
        assertFalse(changes.changed)
        assertTrue(changes.added.isEmpty())
        assertTrue(changes.modified.isEmpty())
        assertTrue(changes.removed.isEmpty())
    }

    @Test
    fun repeatedImportsCompareAgainstAFixedSnapshot() {
        val root = Files.createTempDirectory("rear-static-repeat").toFile()
        val installed = File(root, "installed.zip")
        builder.writeStaticResourceSnapshot(zip(File(root, "base.zip"), sample()), installed)
        val installedBytes = installed.readBytes()

        val scriptOnly = builder.compareStaticResources(
            zip(File(root, "script.zip"), sample(main = "changed", lib = "changed", inline = "changed", banner = "x")),
            installed,
        )
        assertFalse(scriptOnly.changed)

        val modifiedImage = builder.compareStaticResources(
            zip(File(root, "mark.zip"), sample(markBytes = byteArrayOf(4, 5, 6))),
            installed,
        )
        assertEquals(listOf("images/mark.png"), modifiedImage.modified)
        assertTrue(modifiedImage.added.isEmpty())
        assertTrue(modifiedImage.removed.isEmpty())
        assertTrue(modifiedImage.changed)

        val modifiedIcon = builder.compareStaticResources(
            zip(File(root, "icon.zip"), sample(iconBytes = byteArrayOf(7, 7, 7))),
            installed,
        )
        assertEquals(listOf("assets/icon.png"), modifiedIcon.modified)

        val modifiedConfig = builder.compareStaticResources(
            zip(File(root, "config.zip"), sample(configBytes = "{\"v\":2}".toByteArray())),
            installed,
        )
        assertEquals(listOf("vendor/config.json"), modifiedConfig.modified)

        val modifiedHelper = builder.compareStaticResources(
            zip(File(root, "helper.zip"), sample(helperBytes = "edited".toByteArray())),
            installed,
        )
        assertEquals(listOf("helper.js"), modifiedHelper.modified)

        val modifiedTemplate = builder.compareStaticResources(
            zip(File(root, "tmpl.zip"), sample(tmplBytes = "edited".toByteArray())),
            installed,
        )
        assertEquals(listOf("tmpl.js"), modifiedTemplate.modified)

        val removedMark = builder.compareStaticResources(
            zip(File(root, "no-mark.zip"), sample(markBytes = null)),
            installed,
        )
        assertEquals(listOf("images/mark.png"), removedMark.removed)
        assertTrue(removedMark.added.isEmpty())
        assertTrue(removedMark.modified.isEmpty())

        val removedIcon = builder.compareStaticResources(
            zip(File(root, "no-icon.zip"), sample(iconBytes = null)),
            installed,
        )
        assertEquals(listOf("icon.png"), removedIcon.removed)

        val addedFile = builder.compareStaticResources(
            zip(File(root, "extra.zip"), sample(more = mapOf("extra.txt" to "new".toByteArray()))),
            installed,
        )
        assertEquals(listOf("extra.txt"), addedFile.added)
        assertTrue(addedFile.modified.isEmpty())
        assertTrue(addedFile.removed.isEmpty())

        val mixed = builder.compareStaticResources(
            zip(
                File(root, "mixed.zip"),
                sample(
                    markBytes = byteArrayOf(1),
                    configBytes = null,
                    more = mapOf("extra.txt" to "new".toByteArray()),
                ),
            ),
            installed,
        )
        assertEquals(listOf("extra.txt"), mixed.added)
        assertEquals(listOf("images/mark.png"), mixed.modified)
        assertEquals(listOf("vendor/config.json"), mixed.removed)
        assertTrue(mixed.changed)

        val restored = builder.compareStaticResources(zip(File(root, "again.zip"), sample()), installed)
        assertFalse(restored.changed)
        assertTrue(installedBytes.contentEquals(installed.readBytes()))
    }

    @Test
    fun zipOrderAndEntryMetadataDoNotAffectComparison() {
        val root = Files.createTempDirectory("rear-static-order").toFile()
        val forward = zip(File(root, "forward.zip"), sample())
        val backward = zip(File(root, "backward.zip"), sample(), reverse = true)
        val installed = File(root, "installed.zip")
        val rewritten = File(root, "rewritten.zip")
        builder.writeStaticResourceSnapshot(forward, installed)
        builder.writeStaticResourceSnapshot(backward, rewritten)
        assertSameEntries(installed, rewritten)
        assertFalse(builder.compareStaticResources(backward, installed).changed)

        val reversed = File(root, "reversed.zip")
        val retimed = File(root, "retimed.zip")
        rewrite(installed, reversed, reverse = true, time = 0L, level = Deflater.BEST_COMPRESSION)
        rewrite(installed, retimed, reverse = false, time = 86_400_000L, level = Deflater.BEST_SPEED)
        assertFalse(builder.compareStaticResources(forward, reversed).changed)
        assertFalse(builder.compareStaticResources(backward, retimed).changed)
        assertEquals(
            builder.compareStaticResources(forward, installed),
            builder.compareStaticResources(forward, reversed),
        )
    }

    @Test
    fun layoutOutputPathsDecideWhetherResourcesChanged() {
        val root = Files.createTempDirectory("rear-static-layout").toFile()
        val bytes = byteArrayOf(3, 3, 3, 3)
        val rooted = pack(root, "root.zip", "icon.png" to bytes)
        val prefixed = pack(root, "prefixed.zip", "assets/icon.png" to bytes)
        val installed = File(root, "installed.zip")
        builder.writeStaticResourceSnapshot(rooted, installed)
        assertFalse(builder.compareStaticResources(prefixed, installed).changed)

        val markBytes = byteArrayOf(8, 8, 8)
        val original = pack(root, "mark.zip", "images/mark.png" to markBytes)
        val moved = pack(root, "moved.zip", "pics/mark.png" to markBytes)
        builder.writeStaticResourceSnapshot(original, installed)
        val movedChanges = builder.compareStaticResources(moved, installed)
        assertEquals(listOf("pics/mark.png"), movedChanges.added)
        assertEquals(emptyList<String>(), movedChanges.modified)
        assertEquals(listOf("images/mark.png"), movedChanges.removed)
        assertTrue(movedChanges.changed)

        val full = File(root, "full.zip")
        builder.writeStaticResourceSnapshot(original, full)
        val partial = File(root, "partial.zip")
        ZipOutputStream(partial.outputStream()).use { out ->
            ZipFile(full).use { zip ->
                val name = "assets/images/mark.png"
                out.putNextEntry(ZipEntry(name))
                zip.getInputStream(zip.getEntry(name)).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val aliasChanges = builder.compareStaticResources(original, partial)
        assertEquals(emptyList<String>(), aliasChanges.added)
        assertEquals(listOf("images/mark.png"), aliasChanges.modified)
        assertEquals(emptyList<String>(), aliasChanges.removed)
        assertTrue(aliasChanges.changed)
    }

    private fun sample(
        main: String = "var main = 1;",
        lib: String = "var lib = 1;",
        inline: String = "var inline = 1;",
        banner: String = "",
        markBytes: ByteArray? = mark,
        iconBytes: ByteArray? = icon,
        configBytes: ByteArray? = config,
        tmplBytes: ByteArray? = tmpl,
        helperBytes: ByteArray? = helper,
        more: Map<String, ByteArray> = emptyMap(),
    ): LinkedHashMap<String, ByteArray> {
        val map = linkedMapOf(
            "index.html" to html(
                """
                <canvas id="rear"></canvas>
                <p>$banner</p>
                <script src="lib.js"></script>
                <script>$inline</script>
                <script src="./main.js"></script>
                <script type="text/template" src="tmpl.js"></script>
                """.trimIndent(),
            ).toByteArray(),
            "lib.js" to lib.toByteArray(),
            "main.js" to main.toByteArray(),
        )
        if (tmplBytes != null) map["tmpl.js"] = tmplBytes
        if (helperBytes != null) map["helper.js"] = helperBytes
        if (markBytes != null) map["images/mark.png"] = markBytes
        if (iconBytes != null) map["assets/icon.png"] = iconBytes
        if (configBytes != null) map["vendor/config.json"] = configBytes
        map.putAll(more)
        return map
    }

    private fun pack(root: File, name: String, vararg files: Pair<String, ByteArray>): File {
        val entries = linkedMapOf(
            "index.html" to html("<canvas></canvas><script src=\"main.js\"></script>").toByteArray(),
            "main.js" to "var x = 1;".toByteArray(),
        )
        for ((path, bytes) in files) entries[path] = bytes
        return zip(File(root, name), entries)
    }

    private fun html(body: String) = "<!DOCTYPE html><html><body>$body</body></html>"

    private fun zip(file: File, entries: Map<String, ByteArray>, reverse: Boolean = false): File {
        val ordered = if (reverse) entries.entries.reversed() else entries.entries.toList()
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, bytes) in ordered) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun rewrite(source: File, dest: File, reverse: Boolean, time: Long, level: Int) {
        val stored = ZipFile(source).use { zip ->
            val found = ArrayList<Pair<String, ByteArray>>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                found.add(entry.name to zip.getInputStream(entry).readBytes())
            }
            if (reverse) found.reversed() else found
        }
        ZipOutputStream(dest.outputStream()).use { out ->
            out.setLevel(level)
            for ((name, bytes) in stored) {
                val entry = ZipEntry(name)
                entry.time = time
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun namesInOrder(file: File): List<String> =
        ZipFile(file).use { zip ->
            val names = ArrayList<String>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) names.add(enumeration.nextElement().name)
            names
        }

    private fun assertBytes(file: File, name: String, expected: ByteArray) {
        val actual = ZipFile(file).use { zip ->
            val entry = requireNotNull(zip.getEntry(name)) { "缺少 $name" }
            zip.getInputStream(entry).readBytes()
        }
        assertTrue(name, expected.contentEquals(actual))
    }

    private fun assertSameEntries(expected: File, actual: File) {
        val left = unzip(expected)
        val right = unzip(actual)
        assertEquals(left.keys.sorted(), right.keys.sorted())
        for (name in left.keys) {
            assertTrue(name, left.getValue(name).contentEquals(right.getValue(name)))
        }
    }

    private fun unzip(file: File): Map<String, ByteArray> =
        ZipFile(file).use { zip ->
            val out = linkedMapOf<String, ByteArray>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                out[entry.name] = zip.getInputStream(entry).readBytes()
            }
            out
        }
}
