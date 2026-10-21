package org.schabi.newpipe.updates

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PublicPlayerUpdatesTest {
    private val repo = GitHubReleaseRepository("jimbobsmith", "drivedeck-player")
    private fun manifest(code: Long = 304, pkg: String = "com.drivedeck.player.test", tag: String = "player-test-v3") = """{"schemaVersion":1,"packageName":"$pkg","versionCode":$code,"versionName":"0.1.0-alpha03","tag":"$tag","commitSha":"${"a".repeat(40)}","assets":[{"name":"player-universal.apk","abi":"universal","sha256":"${"b".repeat(64)}","sizeBytes":1000000}]}"""
    private fun release(tag: String = "player-test-v3", pkg: String = "com.drivedeck.player.test") = """[{"id":1,"tag_name":"$tag","draft":false,"prerelease":true,"assets":[{"name":"release-manifest.json","url":"${repo.apiReleasesUrl}/assets/11","size":1000},{"name":"player-universal.apk","url":"${repo.apiReleasesUrl}/assets/12","size":1000000}]}]"""

    @Test fun `public discovery uses same-repository browser assets without authorization`() {
        val calls = mutableListOf<Connection>()
        val client = GitHubReleaseClient { url ->
            Connection(url, 200, (if (url.host == "api.github.com") release() else manifest()).toByteArray()).also(calls::add)
        }
        val result = client.findNewestRelease(repo, "", "com.drivedeck.player.test", 204, listOf("x86_64"), "player-test-")
        assertEquals(304L, result.newestRelease!!.manifest.versionCode)
        assertEquals("https://github.com/jimbobsmith/drivedeck-player/releases/download/player-test-v3/player-universal.apk", result.newestRelease!!.assetApiUrl)
        assertTrue(calls.all { "Authorization" !in it.headers })
        assertEquals(2, calls.size)
    }
    @Test fun `wrong release channel is excluded before requesting a manifest`() {
        var count = 0
        val client = GitHubReleaseClient { url -> count++; Connection(url, 200, release("player-release-v3").toByteArray()) }
        assertNull(client.findNewestRelease(repo, "", "com.drivedeck.player.test", 204, listOf("x86_64"), "player-test-").newestRelease)
        assertEquals(1, count)
    }
    @Test fun `other-repository asset is never followed`() {
        val client = GitHubReleaseClient { url ->
            assertEquals("api.github.com", url.host)
            Connection(url, 200, release().replace("drivedeck-player/releases/assets", "different/releases/assets").toByteArray())
        }
        assertNull(client.findNewestRelease(repo, "", "com.drivedeck.player.test", 204, listOf("x86_64"), "player-test-").newestRelease)
    }
    @Test fun `manifest strictly rejects quoted or fractional numeric fields`() {
        assertNull(GitHubReleaseParser.parseManifest(manifest().replace("\"versionCode\":304", "\"versionCode\":\"304\"")))
        assertNull(GitHubReleaseParser.parseManifest(manifest().replace("\"versionCode\":304", "\"versionCode\":304.5")))
        assertNull(GitHubReleaseParser.parseManifest(manifest().replace("\"schemaVersion\":1", "\"schemaVersion\":1.5")))
        assertNotNull(GitHubReleaseParser.parseManifest(manifest()))
    }
    @Test fun `Test and production packages cannot select each others releases or downgrades`() {
        val client = GitHubReleaseClient()
        val releases = GitHubReleaseParser.parseReleaseList(release())
        assertNull(client.selectNewestRelease(releases, "com.drivedeck.player", 1, listOf("x86_64")) { manifest() }.newestRelease)
        assertNull(client.selectNewestRelease(releases, "com.drivedeck.player.test", 304, listOf("x86_64")) { manifest() }.newestRelease)
        assertNull(client.selectNewestRelease(releases, "com.drivedeck.player.test", 204, listOf("x86_64")) { manifest(tag = "different") }.newestRelease)
    }
    @Test fun `public redirect rejects userinfo custom port and fragment`() {
        for (target in listOf("https://user@objects.githubusercontent.com/x", "https://objects.githubusercontent.com:8443/x", "https://objects.githubusercontent.com/x#y")) {
            var count = 0
            val client = GitHubReleaseClient { url -> count++; Connection(url, 302, byteArrayOf(), mapOf("Location" to target)) }
            assertTrue(runCatching { client.requestReleaseAssetText(URL("https://api.github.com/repos/a/b/releases/assets/1"), "") }.exceptionOrNull() is GitHubReleaseException)
            assertEquals(1, count)
        }
    }
    @Test fun `unknown-length JSON stream is bounded before reading the whole response`() {
        val client = GitHubReleaseClient { url -> Connection(url, 200, ByteArray(2_100_000) { 32 }, advertised = -1) }
        assertTrue(runCatching { client.findNewestRelease(repo, "", "com.drivedeck.player.test", 1, listOf("x86_64")) }.exceptionOrNull() is GitHubReleaseException)
    }
    @Test fun `unknown-length release manifest is bounded before reading the whole response`() {
        val client = GitHubReleaseClient { url -> Connection(url, 200, ByteArray(2_100_000) { 32 }, advertised = -1) }
        assertTrue(runCatching { client.requestReleaseAssetText(URL("https://github.com/jimbobsmith/drivedeck-player/releases/download/player-test-v3/release-manifest.json"), "") }.exceptionOrNull() is GitHubReleaseException)
    }
    @Test fun `cancel before HTTP does not allocate a connection`() {
        var count = 0
        val client = GitHubReleaseClient { url -> count++; Connection(url, 200, byteArrayOf()) }
        client.cancel()
        assertTrue(runCatching { client.findNewestRelease(repo, "", "com.drivedeck.player.test", 1, listOf("x86_64")) }.exceptionOrNull() is IOException)
        assertEquals(0, count)
    }
    @Test fun `cancelled or truncated downloads leave no candidate or partial file`() {
        val data = ByteArray(1_000_000)
        val asset = DriveDeckReleaseAsset("player-universal.apk", "universal", "b".repeat(64), data.size.toLong())
        val model = GitHubReleaseParser.parseManifest(manifest())!!
        val release = DriveDeckRelease(1, "test", "", "", true, model, asset, "https://github.com/a/b/releases/download/v1/player.apk")
        val directory = File.createTempFile("dd-update-", ".tmp").apply { delete(); mkdir() }
        try {
            val destination = File(directory, "candidate.apk")
            val truncated = GitHubReleaseClient { url -> Connection(url, 200, byteArrayOf(1,2,3), advertised = data.size.toLong()) }
            assertTrue(runCatching { truncated.download(release, "", destination) { _, _ -> } }.isFailure)
            assertFalse(destination.exists()); assertFalse(File(directory, "candidate.apk.part").exists())
            lateinit var cancelled: GitHubReleaseClient
            cancelled = GitHubReleaseClient { url -> Connection(url, 200, data) }
            assertTrue(runCatching { cancelled.download(release, "", destination) { _, _ -> cancelled.cancel() } }.isFailure)
            assertFalse(destination.exists()); assertFalse(File(directory, "candidate.apk.part").exists())
        } finally { directory.listFiles()?.forEach(File::delete); directory.delete() }
    }
    @Test fun `small prepared metadata input is bounded and zero reads cannot spin`() {
        val input = object : ByteArrayInputStream(byteArrayOf(1,2,3)) { override fun read(b: ByteArray, off: Int, len: Int) = 0 }
        assertArrayEquals(byteArrayOf(1,2,3), MediaUpdateInput.read(input))
        assertTrue(runCatching { MediaUpdateInput.read(ByteArrayInputStream(ByteArray(32_769))) }.isFailure)
    }
    @Test fun `tag channels and unsupported package fail closed`() {
        assertEquals("player-test-", PlayerUpdatePolicy.tagPrefix("com.drivedeck.player.test"))
        assertEquals("player-release-", PlayerUpdatePolicy.tagPrefix("com.drivedeck.player"))
        assertTrue(runCatching { PlayerUpdatePolicy.tagPrefix("org.schabi.newpipe") }.isFailure)
    }
    @Test fun `deep JSON fails safely while brackets inside quoted notes are allowed`() {
        val deep = "[".repeat(65) + "0" + "]".repeat(65)
        assertTrue(runCatching { GitHubReleaseParser.parseReleaseList(deep) }.exceptionOrNull() is GitHubReleaseException)
        val valid = release().replace("\"draft\":false", "\"body\":\"[[[{hello}]]\",\"draft\":false")
        assertEquals(1, GitHubReleaseParser.parseReleaseList(valid).size)
    }
    private class Connection(url: URL, private val code: Int, private val body: ByteArray,
        private val responseHeaders: Map<String,String> = emptyMap(), private val advertised: Long = body.size.toLong()) : HttpURLConnection(url) {
        val headers = mutableMapOf<String,String>()
        override fun connect() { }
        override fun disconnect() { }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = advertised
        override fun getHeaderField(name: String) = responseHeaders[name]
        override fun getContentType() = responseHeaders["Content-Type"]
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
    }
}
