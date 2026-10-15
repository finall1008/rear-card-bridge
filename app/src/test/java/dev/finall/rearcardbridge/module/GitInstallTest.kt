package dev.finall.rearcardbridge.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitInstallTest {
    @Test
    fun `github url variants`() {
        assertEquals(
            "https://codeload.github.com/owner/repo/zip/refs/heads/main",
            GitInstall.parseRepoUrl("https://github.com/owner/repo", null).archiveUrl,
        )
        assertEquals(
            "https://codeload.github.com/owner/repo/zip/refs/heads/main",
            GitInstall.parseRepoUrl("https://github.com/owner/repo.git", null).archiveUrl,
        )
        assertEquals(
            "https://codeload.github.com/owner/repo/zip/refs/heads/dev",
            GitInstall.parseRepoUrl("https://github.com/owner/repo/tree/dev", null).archiveUrl,
        )
        assertEquals(
            "https://codeload.github.com/owner/repo/zip/refs/heads/feature",
            GitInstall.parseRepoUrl("https://github.com/owner/repo/tree/dev", "feature").archiveUrl,
        )
    }

    @Test
    fun `gitlab url`() {
        assertEquals(
            "https://gitlab.com/owner/repo/-/archive/main/repo-main.zip",
            GitInstall.parseRepoUrl("https://gitlab.com/owner/repo", null).archiveUrl,
        )
    }

    @Test
    fun `gitee url`() {
        assertEquals(
            "https://gitee.com/owner/repo/repository/archive/main.zip",
            GitInstall.parseRepoUrl("https://gitee.com/owner/repo", null).archiveUrl,
        )
    }

    @Test
    fun `unsupported hosts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GitInstall.parseRepoUrl("https://example.com/owner/repo", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitInstall.parseRepoUrl("not a url", null)
        }
    }
}
