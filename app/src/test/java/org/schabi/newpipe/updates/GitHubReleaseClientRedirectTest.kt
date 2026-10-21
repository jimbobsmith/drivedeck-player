// Adapted from DriveDeckG updater, GPL-3.0-or-later, Copyright (C) 2026 DriveDeckG contributors.
// Source provenance and bounded fork adaptations: docs/updates/README.md.
package org.schabi.newpipe.updates

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseClientRedirectTest {
    @Test
    fun `rejected repository token is classified as a credential problem`() {
        val client = GitHubReleaseClient { url ->
            FakeConnection(url, 401, "", emptyMap())
        }

        val error = runCatching {
            client.findNewestRelease(
                GitHubReleaseRepository("jimbobsmith", "drivedeckg"),
                "test-token-12345678907890",
                "com.drivedeck.g",
                1L,
                listOf("arm64-v8a")
            )
        }.exceptionOrNull() as GitHubReleaseException

        assertEquals(GitHubReleaseFailureKind.CREDENTIAL, error.kind)
        assertTrue(error.message.orEmpty().contains("rejected"))
    }

    @Test
    fun `private text asset follows one trusted redirect without forwarding token`() {
        val manifestUrl = "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/11"
        val redirectUrl = "https://objects.githubusercontent.com/github-production-release-asset/manifest"
        val manifest = """
            {
              "schemaVersion": 1,
              "packageName": "com.drivedeck.g",
              "assets": [],
              "release": "DriveDeck – privé"
            }
        """.trimIndent()
        val connections = mutableListOf<FakeConnection>()
        val client = GitHubReleaseClient { url ->
            val response = when (url.host) {
                "api.github.com" -> FakeResponse(
                    status = 302,
                    body = "",
                    headers = mapOf("Location" to redirectUrl)
                )
                "objects.githubusercontent.com" -> FakeResponse(200, manifest)
                else -> error("Unexpected updater request: $url")
            }
            FakeConnection(url, response.status, response.body, response.headers)
                .also(connections::add)
        }

        val result = client.requestReleaseAssetText(
            URL(manifestUrl),
            "test-token-12345678907890"
        )

        assertEquals(manifest, result)
        val apiConnection = connections.single { it.url.host == "api.github.com" }
        val redirectConnection = connections.single {
            it.url.host == "objects.githubusercontent.com"
        }
        assertEquals(
            "Bearer test-token-12345678907890",
            apiConnection.requestHeaders["Authorization"]
        )
        assertFalse(redirectConnection.requestHeaders.containsKey("Authorization"))
    }

    @Test(expected = GitHubReleaseException::class)
    fun `private text asset rejects redirect outside trusted GitHub hosts`() {
        val client = GitHubReleaseClient { url ->
            FakeConnection(
                url = url,
                status = 302,
                body = "",
                headers = mapOf("Location" to "https://evil.example/manifest")
            )
        }

        client.requestReleaseAssetText(
            URL("https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/11"),
            "test-token-12345678907890"
        )
    }

    @Test
    fun `private asset retries metadata response with token authorization`() {
        val manifestUrl = "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/11"
        val redirectUrl = "https://release-assets.githubusercontent.com/manifest"
        val manifest = """
            {
              "schemaVersion": 1,
              "packageName": "com.drivedeck.g",
              "assets": []
            }
        """.trimIndent()
        val connections = mutableListOf<FakeConnection>()
        var apiRequestCount = 0
        val client = GitHubReleaseClient { url ->
            val response = when {
                url.host == "api.github.com" && apiRequestCount++ == 0 -> FakeResponse(
                    200,
                    """{"name":"release-manifest.json"}""",
                    headers = mapOf("Content-Type" to "application/json; charset=utf-8")
                )
                url.host == "api.github.com" -> FakeResponse(
                    302,
                    "",
                    headers = mapOf("Location" to redirectUrl)
                )
                url.host == "release-assets.githubusercontent.com" -> FakeResponse(200, manifest)
                else -> error("Unexpected updater request: $url")
            }
            FakeConnection(url, response.status, response.body, response.headers)
                .also(connections::add)
        }

        val result = client.requestReleaseAssetText(URL(manifestUrl), "test-token-12345678907890")

        assertEquals(manifest, result)
        val apiConnections = connections.filter { it.url.host == "api.github.com" }
        assertEquals(2, apiConnections.size)
        assertEquals(
            "Bearer test-token-12345678907890",
            apiConnections[0].requestHeaders["Authorization"]
        )
        assertEquals(
            "token test-token-12345678907890",
            apiConnections[1].requestHeaders["Authorization"]
        )
        assertFalse(
            connections.single { it.url.host == "release-assets.githubusercontent.com" }
                .requestHeaders.containsKey("Authorization")
        )
    }

    @Test
    fun `release lookup explains token that can list but not download assets`() {
        val releaseList = """
            [{
              "id": 263,
              "tag_name": "v0.1.0-alpha.263",
              "name": "DriveDeckG v263",
              "body": "Updater proof",
              "published_at": "2026-08-10T00:00:00Z",
              "prerelease": true,
              "draft": false,
              "assets": [{
                "name": "release-manifest.json",
                "url": "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/11",
                "size": 1200
              }]
            }]
        """.trimIndent()
        val assetMetadata = """
            {
              "url": "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/11",
              "browser_download_url": "https://github.com/jimbobsmith/drivedeckg/releases/download/v0.1.0-alpha.263/release-manifest.json",
              "name": "release-manifest.json"
            }
        """.trimIndent()
        val client = GitHubReleaseClient { url ->
            val response = when {
                url.path.endsWith("/releases") -> FakeResponse(200, releaseList)
                url.host == "api.github.com" && url.path.endsWith("/assets/11") ->
                    FakeResponse(200, assetMetadata)
                else -> error("Unexpected updater request: $url")
            }
            FakeConnection(url, response.status, response.body, response.headers)
        }

        val error = runCatching {
            client.findNewestRelease(
                GitHubReleaseRepository("jimbobsmith", "drivedeckg"),
                "test-token-12345678907890",
                "com.drivedeck.g",
                2026081005L,
                listOf("arm64-v8a")
            )
        }.exceptionOrNull()

        assertTrue(error is GitHubReleaseException)
        assertTrue(error?.message.orEmpty().contains("GitHub asset metadata"))
        assertFalse(error?.message.orEmpty().contains("test-token"))
    }

    @Test
    fun `valid update survives incompatible historical manifest`() {
        val manifestAsset = GitHubReleaseAsset(
            name = GitHubReleaseClient.RELEASE_MANIFEST_NAME,
            apiUrl = "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/2671",
            sizeBytes = 600
        )
        val apkAsset = GitHubReleaseAsset(
            name = "app-arm64-v8a-release.apk",
            apiUrl = "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/2672",
            sizeBytes = 2_000_000
        )
        val releases = listOf(
            ParsedGitHubRelease(
                id = 267,
                tag = "v0.1.0-alpha.267",
                name = "DriveDeckG v267",
                notes = "Updater regression proof",
                publishedAt = "2026-08-10T22:30:00Z",
                prerelease = true,
                draft = false,
                assets = listOf(manifestAsset, apkAsset)
            ),
            ParsedGitHubRelease(
                id = 258,
                tag = "v0.1.0-alpha.258",
                name = "DriveDeckG v258",
                notes = "Legacy release",
                publishedAt = "2026-08-10T14:00:00Z",
                prerelease = true,
                draft = false,
                assets = listOf(
                    manifestAsset.copy(
                        apiUrl = "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/2581"
                    )
                )
            )
        )
        val validManifest = DriveDeckReleaseManifest(
            packageName = "com.drivedeck.g",
            versionCode = 2026081010,
            versionName = "0.1.0-alpha.260810.267",
            tag = "v0.1.0-alpha.267",
            commitSha = "0123456789abcdef0123456789abcdef01234567",
            assets = listOf(
                DriveDeckReleaseAsset(
                    name = apkAsset.name,
                    abi = "arm64-v8a",
                    sha256 = "a".repeat(64),
                    sizeBytes = apkAsset.sizeBytes
                )
            )
        )
        val client = GitHubReleaseClient()
        val lookup = client.selectNewestReleaseFromManifests(
            releases = releases,
            currentPackageName = "com.drivedeck.g",
            currentVersionCode = 2026081009L,
            supportedAbis = listOf("arm64-v8a")
        ) { manifestAsset ->
            validManifest.takeIf { manifestAsset.apiUrl.endsWith("/assets/2671") }
        }

        assertEquals(1, lookup.manifestReleaseCount)
        assertEquals(2026081010L, lookup.newestRelease?.manifest?.versionCode)
    }

    @Test
    fun `installed release lookup returns exact current build for an older peer`() {
        val manifestAsset = GitHubReleaseAsset(
            name = GitHubReleaseClient.RELEASE_MANIFEST_NAME,
            apiUrl = "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/2731",
            sizeBytes = 600
        )
        val apkAsset = GitHubReleaseAsset(
            name = "app-arm64-v8a-release.apk",
            apiUrl = "https://api.github.com/repos/jimbobsmith/drivedeckg/releases/assets/2732",
            sizeBytes = 2_000_000
        )
        val releases = listOf(
            ParsedGitHubRelease(
                id = 273,
                tag = "v0.1.0-alpha.273",
                name = "DriveDeckG v273",
                notes = "Installed phone release",
                publishedAt = "2026-08-11T00:00:00Z",
                prerelease = true,
                draft = false,
                assets = listOf(manifestAsset, apkAsset)
            )
        )
        val manifest = DriveDeckReleaseManifest(
            packageName = "com.drivedeck.g",
            versionCode = 273L,
            versionName = "0.1.0-alpha.273",
            tag = "v0.1.0-alpha.273",
            commitSha = "0123456789abcdef0123456789abcdef01234567",
            assets = listOf(
                DriveDeckReleaseAsset(
                    name = apkAsset.name,
                    abi = "arm64-v8a",
                    sha256 = "a".repeat(64),
                    sizeBytes = apkAsset.sizeBytes
                )
            )
        )

        val selected = GitHubReleaseClient().selectReleaseAtVersionFromManifests(
            releases = releases,
            currentPackageName = "com.drivedeck.g",
            targetVersionCode = 273L,
            supportedAbis = listOf("arm64-v8a")
        ) { manifest }

        assertEquals(273L, selected?.manifest?.versionCode)
        assertEquals(apkAsset.apiUrl, selected?.assetApiUrl)
    }

    @Test
    fun `manifest decoder accepts current schema and rejects incomplete historical schema`() {
        val client = GitHubReleaseClient()
        val current = """
            {
              "schemaVersion": 1,
              "packageName": "com.drivedeck.g",
              "versionCode": 2026081010,
              "versionName": "0.1.0-alpha.260810.267",
              "tag": "v0.1.0-alpha.267",
              "commitSha": "0123456789abcdef0123456789abcdef01234567",
              "assets": [{
                "name": "app-arm64-v8a-release.apk",
                "abi": "arm64-v8a",
                "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "sizeBytes": 2000000
              }]
            }
        """.trimIndent()
        val historical = """
            {
              "schemaVersion": 1,
              "packageName": "com.drivedeck.g",
              "assets": []
            }
        """.trimIndent()

        assertEquals(2026081010L, client.decodeManifest(current)?.versionCode)
        assertEquals(null, client.decodeManifest(historical))
    }

    @Test
    fun `payload diagnostics classify errors without echoing their message`() {
        val description = GitHubReleaseParser.describePayload(
            """{"message":"secret repository detail","documentation_url":"https://docs.github.com"}"""
        )

        assertTrue(description.contains("GitHub error object"))
        assertFalse(description.contains("secret repository detail"))
    }

    private data class FakeResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    )

    private class FakeConnection(
        url: URL,
        private val status: Int,
        body: String,
        private val headers: Map<String, String>
    ) : HttpURLConnection(url) {
        private val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val requestHeaders = linkedMapOf<String, String>()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(bodyBytes)
        override fun getContentLengthLong(): Long = bodyBytes.size.toLong()
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun getContentType(): String? = headers["Content-Type"]
        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null && value != null) requestHeaders[key] = value
        }
    }
}
