package dev.finall.rearcardbridge.store

import dev.finall.rearcardbridge.module.ModuleManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** One installable module listed by the static shop index. */
@Serializable
data class CatalogModule(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val apiVersion: Int,
    val description: String,
    val author: String,
    val capabilities: List<String>,
    val repository: String,
    val releaseUrl: String,
    val downloadUrl: String,
    /** Omitted tags decode as an empty list. */
    val tags: List<String> = emptyList(),
    /** Ordered image URLs; the first preview is the shop cover. */
    val previews: List<String> = emptyList(),
)

/** Shop tab state. The caller updates it; this type does not load or install. */
data class ModuleShopState(
    val modules: List<CatalogModule> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val installingModuleId: String? = null,
)

/**
 * Static HTTPS module index and GitHub Release ZIP download.
 * [load] and [download] block on the network; call them from an IO thread.
 * No GitHub API, progress, or cancellation.
 */
object ModuleCatalogRepository {
    const val CATALOG_URL = "https://finall1008.github.io/rear-card-modules/modules.json"

    private const val SCHEMA_VERSION = 1
    private const val TIMEOUT_MS = 30_000
    private const val MAX_CATALOG_BYTES = 2L * 1024 * 1024
    /** Matches the archive ceiling in GitInstall. */
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /** GET [CATALOG_URL] and parse it. An invalid entry rejects the whole index. */
    fun load(): List<CatalogModule> {
        requireHttps(CATALOG_URL, "模块目录")
        val body = ByteArrayOutputStream()
        transfer(CATALOG_URL, MAX_CATALOG_BYTES, "模块目录", requireZip = false) { chunk, read ->
            body.write(chunk, 0, read)
        }
        return parse(body.toString(Charsets.UTF_8))
    }

    /**
     * Parses a schemaVersion 1 catalog. Each entry must use API 2, a valid module id,
     * required metadata, and HTTPS repository, release, and download links.
     */
    fun parse(jsonText: String): List<CatalogModule> {
        val catalog = try {
            json.decodeFromString<CatalogFile>(jsonText)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("模块目录格式不正确。")
        }
        require(catalog.schemaVersion == SCHEMA_VERSION) { "模块目录 schemaVersion 不受支持。" }
        val seen = HashSet<String>()
        return catalog.modules.map { module ->
            validate(module)
            require(seen.add(module.id)) { "模块目录包含重复 id：${module.id}" }
            module
        }
    }

    /**
     * Downloads the release ZIP to [destination], then checks the root module.json
     * id, version, versionCode, and apiVersion against [module].
     * Does not delete [destination] when the download or the identity check fails.
     */
    fun download(module: CatalogModule, destination: File) {
        validate(module)
        destination.outputStream().use { output ->
            transfer(module.downloadUrl, MAX_ARCHIVE_BYTES, "模块包", requireZip = true) { chunk, read ->
                output.write(chunk, 0, read)
            }
        }
        verifyReleaseIdentity(destination, module)
    }

    /**
     * Reads root module.json and requires id, version, versionCode, and apiVersion
     * to match [module]. Extra JSON fields and other ZIP entries are ignored.
     * Full package validation stays with the installer.
     */
    internal fun verifyReleaseIdentity(zip: File, module: CatalogModule) {
        val identity = try {
            ZipFile(zip).use { archive -> readIdentity(archive) }
        } catch (_: ZipException) {
            throw IllegalArgumentException("模块包不是有效的 ZIP。")
        }
        require(ModuleManifest.isValidModuleId(identity.id)) { "模块 id 非法：${identity.id}" }
        require(identity.apiVersion == ModuleManifest.API_VERSION) {
            "模块 apiVersion 不受支持，需要 ${ModuleManifest.API_VERSION}。"
        }
        require(
            identity.id == module.id &&
                identity.version == module.version &&
                identity.versionCode == module.versionCode &&
                identity.apiVersion == module.apiVersion,
        ) { "模块包与目录不一致。" }
    }

    private fun validate(module: CatalogModule) {
        require(ModuleManifest.isValidModuleId(module.id)) { "模块 id 非法：${module.id}" }
        require(module.name.isNotBlank()) { "模块缺少 name。" }
        require(module.version.isNotBlank()) { "模块缺少 version。" }
        require(module.author.isNotBlank()) { "模块缺少 author。" }
        require(module.versionCode > 0) { "模块 versionCode 必须大于 0。" }
        require(module.apiVersion == ModuleManifest.API_VERSION) {
            "模块 apiVersion 不受支持，需要 ${ModuleManifest.API_VERSION}。"
        }
        requireHttps(module.repository, "模块仓库地址")
        requireHttps(module.releaseUrl, "模块发布页")
        requireHttps(module.downloadUrl, "模块下载地址")
        module.previews.forEach { requireHttps(it, "模块预览图") }
    }

    private fun requireHttps(value: String, label: String) {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("${label}无效。")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "${label}必须是 HTTPS。" }
        require(!uri.host.isNullOrBlank()) { "${label}无效。" }
    }

    private fun openGet(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        // GitHub Release files answer 302 to release-assets.githubusercontent.com.
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        return connection
    }

    private inline fun transfer(
        url: String,
        maxBytes: Long,
        label: String,
        requireZip: Boolean,
        crossinline write: (ByteArray, Int) -> Unit,
    ) {
        val connection = openGet(url)
        try {
            val status = connection.responseCode
            require(status in 200..299) { "${label}下载失败（HTTP $status）。" }
            val declared = connection.contentLengthLong
            require(declared < 0 || declared <= maxBytes) { "${label}过大。" }
            connection.inputStream.use { input ->
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                var started = false
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    if (!started && requireZip) {
                        require(read >= 2 && chunk[0] == 'P'.code.toByte() && chunk[1] == 'K'.code.toByte()) {
                            "下载内容不是 ZIP 归档。"
                        }
                    }
                    started = true
                    total += read
                    require(total <= maxBytes) { "${label}过大。" }
                    write(chunk, read)
                }
                require(started) { "${label}为空。" }
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("${label}下载失败。", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun readIdentity(archive: ZipFile): PackagedModuleIdentity {
        val entry = archive.getEntry("module.json")
            ?: throw IllegalArgumentException("模块包缺少 module.json。")
        require(!entry.isDirectory) { "模块包缺少 module.json。" }
        require(entry.size < 0 || entry.size <= MAX_MANIFEST_BYTES) { "module.json 过大。" }
        val bytes = ByteArrayOutputStream()
        var total = 0
        archive.getInputStream(entry).use { input ->
            val chunk = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                require(total + read <= MAX_MANIFEST_BYTES) { "module.json 过大。" }
                total += read
                bytes.write(chunk, 0, read)
            }
        }
        return try {
            json.decodeFromString<PackagedModuleIdentity>(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw IllegalArgumentException("module.json 格式不正确。")
        }
    }
}

@Serializable
private data class CatalogFile(
    val schemaVersion: Int,
    val modules: List<CatalogModule>,
)

@Serializable
private data class PackagedModuleIdentity(
    val id: String,
    val version: String,
    val versionCode: Long,
    val apiVersion: Int,
)
