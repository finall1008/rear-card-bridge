package dev.finall.rearcardbridge.module

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Downloads public repository archives without an embedded git client.
 * Supported hosts: GitHub, GitLab, Gitee. The archive usually wraps content in one
 * top-level <repo>-<branch>/ directory; stripTopLevel removes it.
 */
object GitInstall {
    data class GitRepo(val host: String, val archiveUrl: String, val display: String)

    fun parseRepoUrl(raw: String, branch: String?): GitRepo {
        val trimmed = raw.trim()
        val match = REPO_URL.matchEntire(trimmed)
            ?: throw IllegalArgumentException("无法识别的仓库地址：$trimmed")
        val host = match.groupValues[1].lowercase()
        val owner = match.groupValues[2]
        val repo = match.groupValues[3].removeSuffix(".git")
        val urlBranch = match.groupValues[4].takeIf { it.isNotBlank() }
        val resolvedBranch = branch?.trim()?.takeIf { it.isNotEmpty() } ?: urlBranch ?: "main"
        require(owner.isNotBlank() && repo.isNotBlank()) { "仓库地址缺少所有者或仓库名。" }
        val base = "https://$host/$owner/$repo"
        val archive = when (host) {
            "github.com" -> "https://codeload.github.com/$owner/$repo/zip/refs/heads/$resolvedBranch"
            "gitlab.com" -> "$base/-/archive/$resolvedBranch/$repo-$resolvedBranch.zip"
            "gitee.com" -> "$base/repository/archive/$resolvedBranch.zip"
            else -> throw IllegalArgumentException("仅支持 GitHub / GitLab / Gitee 公开仓库。")
        }
        return GitRepo(host, archive, "$host/$owner/$repo@$resolvedBranch")
    }

    fun download(repo: GitRepo, dest: File) {
        val connection = URL(repo.archiveUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            val status = connection.responseCode
            require(status in 200..299) { "仓库归档下载失败（HTTP $status）。" }
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val chunk = ByteArray(64 * 1024)
                    var total = 0L
                    var first = true
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        if (first) {
                            require(read >= 2 && chunk[0] == 'P'.code.toByte() && chunk[1] == 'K'.code.toByte()) {
                                "下载内容不是 ZIP 归档。"
                            }
                            first = false
                        }
                        total += read
                        require(total <= MAX_ARCHIVE_BYTES) { "仓库归档过大。" }
                        output.write(chunk, 0, read)
                    }
                    require(!first) { "仓库归档为空。" }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Extracts the archive, dropping a single shared top-level directory when present. */
    fun stripTopLevel(zipFile: File, outDir: File) {
        ZipFile(zipFile).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }
                .map { ModuleStore.cleanRelativePath(it.name) }.filter { it.isNotEmpty() }.toList()
            val prefix = names.map { it.substringBefore('/', "") }.distinct().singleOrNull()
                ?.takeIf { it.isNotEmpty() && names.all { name -> name.startsWith("$it/") } }
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                var path = ModuleStore.cleanRelativePath(entry.name)
                if (path.isEmpty()) continue
                if (prefix != null) path = path.removePrefix("$prefix/")
                if (path.isEmpty()) continue
                val target = File(outDir, path)
                require(target.canonicalFile.path.startsWith(outDir.canonicalFile.path + File.separator)) {
                    "归档路径越界：$path"
                }
                check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
                    "无法创建解压目录。"
                }
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    private val REPO_URL = Regex(
        "https://([A-Za-z0-9.-]+)/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:/tree/([^/?#]+))?/?(?:[?#].*)?$",
    )
}
