// Adapted from DriveDeckG updater, GPL-3.0-or-later, Copyright (C) 2026 DriveDeckG contributors.
// Source provenance and bounded fork adaptations: docs/updates/README.md.
package org.schabi.newpipe.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateModelsTest {
    private val arm64 = asset("arm64-v8a")
    private val universal = asset("universal")

    @Test
    fun `manifest chooses first supported ABI before universal`() {
        val manifest = manifest(listOf(universal, arm64))

        assertEquals(arm64, manifest.preferredAsset(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(universal, manifest.preferredAsset(listOf("riscv64")))
    }

    @Test
    fun `manifest rejects duplicate asset names and malformed digest`() {
        assertFalse(manifest(listOf(arm64, arm64)).isPlausible())
        assertFalse(
            manifest(listOf(arm64.copy(sha256 = "not-a-digest"))).isPlausible()
        )
    }

    @Test
    fun `manifest without compatible or universal asset has no selection`() {
        assertNull(manifest(listOf(arm64)).preferredAsset(listOf("x86")))
    }

    @Test
    fun `credential policy accepts bounded tokens and masks only suffix`() {
        val token = "test-token-12345678907890"
        assertTrue(UpdateCredentialPolicy.isPlausible(token))
        assertFalse(UpdateCredentialPolicy.isPlausible("short"))
        assertFalse(UpdateCredentialPolicy.isPlausible("github_pat_has spaces 123456"))
        assertEquals("Connected ••••7890", UpdateCredentialPolicy.maskedLabel(token))
    }

    @Test
    fun `asset ABI parser handles x86 without confusing x86_64`() {
        assertEquals("x86_64", UpdateAssetPolicy.abiFromApkName("app-x86_64-release.apk"))
        assertEquals("x86", UpdateAssetPolicy.abiFromApkName("app-x86-release.apk"))
        assertEquals("universal", UpdateAssetPolicy.abiFromApkName("app-universal-release.apk"))
    }

    @Test
    fun `releases URL derives the trusted GitHub API repository`() {
        val repository = GitHubReleaseRepositoryPolicy.parse(
            "https://github.com/jimbobsmith/drivedeckg/releases"
        )

        assertEquals("jimbobsmith", repository?.owner)
        assertEquals("drivedeckg", repository?.name)
        assertEquals(
            "https://api.github.com/repos/jimbobsmith/drivedeckg/releases",
            repository?.apiReleasesUrl
        )
    }

    @Test
    fun `specific release link normalizes to repository releases page`() {
        assertEquals(
            GitHubReleaseRepositoryPolicy.DEFAULT_RELEASES_URL,
            GitHubReleaseRepositoryPolicy.normalizedUrl(
                "https://github.com/jimbobsmith/drivedeck-player/releases/tag/player-test-v0.1.0-alpha01"
            )
        )
    }

    @Test
    fun `repository policy rejects non GitHub insecure and ambiguous URLs`() {
        assertNull(GitHubReleaseRepositoryPolicy.parse("http://github.com/a/b/releases"))
        assertNull(GitHubReleaseRepositoryPolicy.parse("https://evil.example/a/b/releases"))
        assertNull(GitHubReleaseRepositoryPolicy.parse("https://github.com/a/b"))
        assertNull(GitHubReleaseRepositoryPolicy.parse("https://github.com/a/b/releases?x=1"))
        assertNull(GitHubReleaseRepositoryPolicy.parse("https://github.com/a/b/releases/latest"))
    }

    private fun manifest(assets: List<DriveDeckReleaseAsset>) = DriveDeckReleaseManifest(
        packageName = "com.drivedeck.g",
        versionCode = 2026081001,
        versionName = "0.1.0-alpha.260810.258",
        tag = "v0.1.0-alpha.258",
        commitSha = "a".repeat(40),
        assets = assets
    )

    private fun asset(abi: String) = DriveDeckReleaseAsset(
        name = "app-$abi-release.apk",
        abi = abi,
        sha256 = "b".repeat(64),
        sizeBytes = 200_000_000L
    )
}
