package dev.finall.rearcardbridge.store

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleCatalogTest {
    @Test
    fun `parse accepts the published schema 1 catalog`() {
        assertEquals(
            "https://finall1008.github.io/rear-card-modules/modules.json",
            ModuleCatalogRepository.CATALOG_URL,
        )
        val modules = ModuleCatalogRepository.parse(PUBLISHED_CATALOG)
        assertEquals(listOf("dev.finall.aitoken", "dev.finall.maiprofile"), modules.map { it.id })

        val token = modules[0]
        assertEquals("AI 用量背屏", token.name)
        assertEquals("0.2.3", token.version)
        assertEquals(5L, token.versionCode)
        assertEquals(2, token.apiVersion)
        assertEquals("查看 Codex、Kimi Code、DeepSeek 与小米 MiMo 用量", token.description)
        assertEquals("finall1008", token.author)
        assertEquals(listOf("network", "storage"), token.capabilities)
        assertEquals("https://github.com/finall1008/xiaomi-backscreen-ai-token", token.repository)
        assertEquals(
            "https://github.com/finall1008/xiaomi-backscreen-ai-token/releases/tag/v0.2.3",
            token.releaseUrl,
        )
        assertEquals(
            "https://github.com/finall1008/xiaomi-backscreen-ai-token/releases/download/v0.2.3/ai-usage-0.2.3.zip",
            token.downloadUrl,
        )
        assertEquals(listOf("效率", "AI"), token.tags)
        assertEquals(listOf(
            "https://raw.githubusercontent.com/finall1008/xiaomi-backscreen-ai-token/v0.2.3/prototype/preview-card.jpg",
            "https://raw.githubusercontent.com/finall1008/xiaomi-backscreen-ai-token/v0.2.3/prototype/preview-card-pro.jpg",
        ), token.previews)

        val maimai = modules[1]
        assertEquals("1.0.3", maimai.version)
        assertEquals(4L, maimai.versionCode)
        assertEquals(2, maimai.apiVersion)
        assertEquals(listOf("network", "storage", "files"), maimai.capabilities)
        assertTrue(maimai.downloadUrl.startsWith("https://"))
        assertEquals(listOf("游戏", "舞萌"), maimai.tags)
        assertEquals(listOf(
            "https://raw.githubusercontent.com/finall1008/xiaomi-backscreen-maimai-profile/v1.0.3/previews/maimai-rear-card-v2-912x596.png",
            "https://raw.githubusercontent.com/finall1008/xiaomi-backscreen-maimai-profile/v1.0.3/previews/maimai-rear-mockup-v2.png",
        ), maimai.previews)

        val omitted = ModuleCatalogRepository.parse(catalog(MODULE + ",\"note\":true"))
        assertEquals(emptyList<String>(), omitted.single().tags)
        assertEquals(emptyList<String>(), omitted.single().previews)
        assertEquals("dev.finall.aitoken", omitted.single().id)
    }

    @Test
    fun `parse rejects schema api identity and non-https links`() {
        assertRejects(catalog(schema = 2), "schemaVersion")
        assertRejects(catalog(MODULE.replace("\"apiVersion\":2", "\"apiVersion\":1")), "apiVersion")
        assertRejects(
            catalog(MODULE.replace("\"downloadUrl\":\"https://", "\"downloadUrl\":\"http://")),
            "下载地址",
        )
        assertRejects(
            catalog(MODULE.replace("\"id\":\"dev.finall.aitoken\"", "\"id\":\"1abc\"")),
            "id",
        )
        assertRejects(catalog(MODULE.replace("\"versionCode\":5", "\"versionCode\":0")), "versionCode")
        assertRejects(catalog(MODULE + ",\"previews\":[\"http://example.com/card.png\"]"), "预览图")
        assertRejects("{", "格式不正确")
        assertRejects("""{"schemaVersion":1,"modules":[{$MODULE},{$MODULE}]}""", "重复")
    }

    @Test
    fun `release zip identity compares catalog id version and api`() {
        val root = Files.createTempDirectory("module-catalog").toFile()
        try {
            val zip = zip(
                root,
                "match.zip",
                mapOf(
                    "module.json" to manifest(),
                    "icon.png" to "not-a-script",
                ),
            )
            ModuleCatalogRepository.verifyReleaseIdentity(zip, sample())
            assertIdentityRejects(zip, sample(id = "dev.finall.other"), "不一致")
            assertIdentityRejects(zip, sample(version = "9.9.9"), "不一致")
            assertIdentityRejects(zip, sample(versionCode = 99), "不一致")
            assertIdentityRejects(zip, sample(apiVersion = 3), "不一致")

            val oldApi = zip(root, "old.zip", mapOf("module.json" to manifest(apiVersion = 1)))
            assertIdentityRejects(oldApi, sample(apiVersion = 1), "apiVersion")

            val nested = zip(root, "nested.zip", mapOf("pkg/module.json" to manifest()))
            assertIdentityRejects(nested, sample(), "module.json")

            val garbage = File(root, "garbage.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            assertIdentityRejects(garbage, sample(), "ZIP")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shop state defaults to idle`() {
        val state = ModuleShopState()
        assertEquals(emptyList<CatalogModule>(), state.modules)
        assertFalse(state.loading)
        assertFalse(state.loaded)
        assertNull(state.error)
        assertNull(state.installingModuleId)
    }

    private fun assertRejects(json: String, snippet: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModuleCatalogRepository.parse(json)
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(snippet))
    }

    private fun assertIdentityRejects(zip: File, module: CatalogModule, snippet: String) {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModuleCatalogRepository.verifyReleaseIdentity(zip, module)
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(snippet))
    }

    private fun sample(
        id: String = "dev.finall.aitoken",
        version: String = "0.2.3",
        versionCode: Long = 5,
        apiVersion: Int = 2,
    ) = CatalogModule(
        id = id,
        name = "AI 用量背屏",
        version = version,
        versionCode = versionCode,
        apiVersion = apiVersion,
        description = "查看用量",
        author = "finall1008",
        capabilities = listOf("network", "storage"),
        repository = "https://github.com/finall1008/xiaomi-backscreen-ai-token",
        releaseUrl = "https://github.com/finall1008/xiaomi-backscreen-ai-token/releases/tag/v0.2.3",
        downloadUrl = "https://github.com/finall1008/xiaomi-backscreen-ai-token/releases/download/v0.2.3/ai-usage-0.2.3.zip",
    )

    private fun manifest(apiVersion: Int = 2) =
        """{"id":"dev.finall.aitoken","name":"不参与对比","version":"0.2.3","versionCode":5,"apiVersion":$apiVersion,"background":{"intervalMinutes":15},"extra":true}"""

    private fun zip(root: File, name: String, entries: Map<String, String>): File {
        val file = File(root, name)
        ZipOutputStream(file.outputStream()).use { out ->
            for ((path, text) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(text.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        return file
    }

    private fun catalog(moduleBody: String = MODULE, schema: Int = 1) =
        """{"schemaVersion":$schema,"modules":[{$moduleBody}]}"""

    private companion object {
        val MODULE =
            """
            "id":"dev.finall.aitoken","name":"AI 用量背屏","version":"0.2.3","versionCode":5,"apiVersion":2,"description":"查看用量","author":"finall1008","capabilities":["network","storage"],"repository":"https://github.com/finall1008/xiaomi-backscreen-ai-token","releaseUrl":"https://github.com/finall1008/xiaomi-backscreen-ai-token/releases/tag/v0.2.3","downloadUrl":"https://github.com/finall1008/xiaomi-backscreen-ai-token/releases/download/v0.2.3/ai-usage-0.2.3.zip"
            """.trimIndent()

        val PUBLISHED_CATALOG =
            """
            {
              "schemaVersion": 1,
              "updatedAt": "2026-10-16",
              "modules": [
                {
                  "id": "dev.finall.aitoken",
                  "name": "AI 用量背屏",
                  "version": "0.2.3",
                  "versionCode": 5,
                  "apiVersion": 2,
                  "description": "查看 Codex、Kimi Code、DeepSeek 与小米 MiMo 用量",
                  "author": "finall1008",
                  "capabilities": ["network", "storage"],
                  "repository": "https://github.com/finall1008/xiaomi-backscreen-ai-token",
                  "releaseUrl": "https://github.com/finall1008/xiaomi-backscreen-ai-token/releases/tag/v0.2.3",
                  "downloadUrl": "https://github.com/finall1008/xiaomi-backscreen-ai-token/releases/download/v0.2.3/ai-usage-0.2.3.zip",
                  "tags": ["效率", "AI"],
                  "previews": [
                    "https://raw.githubusercontent.com/finall1008/xiaomi-backscreen-ai-token/v0.2.3/prototype/preview-card.jpg",
                    "https://raw.githubusercontent.com/finall1008/xiaomi-backscreen-ai-token/v0.2.3/prototype/preview-card-pro.jpg"
                  ],
                  "license": "MIT"
                },
                {
                  "id": "dev.finall.maiprofile",
                  "name": "舞萌背屏",
                  "version": "1.0.3",
                  "versionCode": 4,
                  "apiVersion": 2,
                  "description": "舞萌玩家资料卡，支持下拉免费刷新和主屏资料设置。",
                  "author": "finall1008",
                  "capabilities": ["network", "storage", "files"],
                  "repository": "https://github.com/finall1008/xiaomi-backscreen-maimai-profile",
                  "releaseUrl": "https://github.com/finall1008/xiaomi-backscreen-maimai-profile/releases/tag/v1.0.3",
                  "downloadUrl": "https://github.com/finall1008/xiaomi-backscreen-maimai-profile/releases/download/v1.0.3/MaimaiRearProfile-v1.0.3.zip",
                  "tags": ["游戏", "舞萌"],
                  "previews": [
                    "https://raw.githubusercontent.com/finall1008/xiaomi-backscreen-maimai-profile/v1.0.3/previews/maimai-rear-card-v2-912x596.png",
                    "https://raw.githubusercontent.com/finall1008/xiaomi-backscreen-maimai-profile/v1.0.3/previews/maimai-rear-mockup-v2.png"
                  ]
                }
              ]
            }
            """.trimIndent()
    }
}
