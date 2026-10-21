// Adapted from DriveDeckG updater, GPL-3.0-or-later, Copyright (C) 2026 DriveDeckG contributors.
// Source provenance and bounded fork adaptations: docs/updates/README.md.
package org.schabi.newpipe.updates

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

enum class GitHubReleaseFailureKind {
    OTHER,
    CREDENTIAL
}

class GitHubReleaseException(
    message: String,
    val kind: GitHubReleaseFailureKind = GitHubReleaseFailureKind.OTHER
) : IOException(message)

class GitHubReleaseClient(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    }
) {
    @Volatile private var cancelled = false
    @Volatile private var active: HttpURLConnection? = null
    fun cancel() { cancelled = true; active?.disconnect() }

    fun findNewestRelease(
        repository: GitHubReleaseRepository,
        token: String,
        currentPackageName: String,
        currentVersionCode: Long,
        supportedAbis: List<String>,
        tagPrefix: String = ""
    ): ReleaseLookupResult {
        require(token.isBlank() || UpdateCredentialPolicy.isPlausible(token))
        val releases = mutableListOf<ParsedGitHubRelease>()
        for (page in 1..3) {
            val payload = requestText(URL("${repository.apiReleasesUrl}?per_page=20&page=$page"), token, GITHUB_JSON)
            val batch = GitHubReleaseParser.parseReleaseList(payload)
            releases += batch
            if (batch.size < 20) break
        }
        val ownAssets = releases.filter { !it.draft && it.tag.startsWith(tagPrefix) }.map { release ->
            release.copy(assets = release.assets.filter { it.apiUrl.startsWith("${repository.apiReleasesUrl}/assets/") }.map { asset ->
                // Public GitHub browser-download endpoints avoid account/token setup on tablets.
                val encode: (String) -> String = { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                if (token.isBlank()) asset.copy(apiUrl = "https://github.com/${repository.owner}/${repository.name}/releases/download/${encode(release.tag)}/${encode(asset.name)}") else asset
            })
        }
        return selectNewestRelease(ownAssets, currentPackageName, currentVersionCode, supportedAbis) { asset ->
            requestReleaseAssetText(URL(asset.apiUrl), token)
        }
    }

    fun findReleaseAtVersion(
        repository: GitHubReleaseRepository,
        token: String,
        currentPackageName: String,
        targetVersionCode: Long,
        supportedAbis: List<String>
    ): DriveDeckRelease? {
        require(token.isBlank() || UpdateCredentialPolicy.isPlausible(token))
        require(targetVersionCode > 0L)
        val payload = requestText(
            url = URL("${repository.apiReleasesUrl}?per_page=20"),
            token = token,
            accept = GITHUB_JSON
        )
        return selectReleaseAtVersionFromManifests(
            releases = GitHubReleaseParser.parseReleaseList(payload),
            currentPackageName = currentPackageName,
            targetVersionCode = targetVersionCode,
            supportedAbis = supportedAbis
        ) { manifestAsset ->
            decodeManifest(requestReleaseAssetText(URL(manifestAsset.apiUrl), token))
        }
    }

    internal fun selectNewestRelease(
        releases: List<ParsedGitHubRelease>,
        currentPackageName: String,
        currentVersionCode: Long,
        supportedAbis: List<String>,
        readManifest: (GitHubReleaseAsset) -> String
    ): ReleaseLookupResult = selectNewestReleaseFromManifests(
        releases = releases,
        currentPackageName = currentPackageName,
        currentVersionCode = currentVersionCode,
        supportedAbis = supportedAbis
    ) { asset ->
        decodeManifest(readManifest(asset))
    }

    internal fun decodeManifest(payload: String): DriveDeckReleaseManifest? =
        GitHubReleaseParser.parseManifest(payload)

    internal fun selectNewestReleaseFromManifests(
        releases: List<ParsedGitHubRelease>,
        currentPackageName: String,
        currentVersionCode: Long,
        supportedAbis: List<String>,
        readManifest: (GitHubReleaseAsset) -> DriveDeckReleaseManifest?
    ): ReleaseLookupResult {
        val scan = scanReleaseManifests(
            releases = releases,
            currentPackageName = currentPackageName,
            supportedAbis = supportedAbis,
            readManifest = readManifest,
            acceptVersion = { it > currentVersionCode }
        )
        return ReleaseLookupResult(
            newestRelease = scan.candidates.maxByOrNull { it.manifest.versionCode },
            manifestReleaseCount = scan.manifestCount
        )
    }

    internal fun selectReleaseAtVersionFromManifests(
        releases: List<ParsedGitHubRelease>,
        currentPackageName: String,
        targetVersionCode: Long,
        supportedAbis: List<String>,
        readManifest: (GitHubReleaseAsset) -> DriveDeckReleaseManifest?
    ): DriveDeckRelease? = scanReleaseManifests(
        releases = releases,
        currentPackageName = currentPackageName,
        supportedAbis = supportedAbis,
        readManifest = readManifest,
        acceptVersion = { it == targetVersionCode }
    ).candidates.singleOrNull()

    private fun scanReleaseManifests(
        releases: List<ParsedGitHubRelease>,
        currentPackageName: String,
        supportedAbis: List<String>,
        readManifest: (GitHubReleaseAsset) -> DriveDeckReleaseManifest?,
        acceptVersion: (Long) -> Boolean
    ): ReleaseCandidateScan {
        var manifestCount = 0
        val candidates = mutableListOf<DriveDeckRelease>()
        for (githubRelease in releases) {
            if (githubRelease.draft) continue
            val manifestAsset = githubRelease.assets.firstOrNull {
                it.name == RELEASE_MANIFEST_NAME
            } ?: continue
            val manifest = readManifest(manifestAsset)
            // A malformed or legacy manifest must not poison the complete release scan. The
            // authenticated asset transport has already verified that GitHub returned a
            // manifest-shaped payload; skip only this incompatible release while preserving
            // hard failures for authentication, redirects, and non-manifest responses.
            if (manifest == null) continue
            manifestCount += 1
            if (manifest.packageName != currentPackageName) continue
            if (manifest.tag != githubRelease.tag) continue
            if (!acceptVersion(manifest.versionCode)) continue
            val selected = manifest.preferredAsset(supportedAbis) ?: continue
            val apiAsset = githubRelease.assets.firstOrNull { it.name == selected.name }
                ?: continue
            if (apiAsset.sizeBytes != selected.sizeBytes) continue
            candidates += DriveDeckRelease(
                id = githubRelease.id,
                name = githubRelease.name.ifBlank { manifest.tag },
                notes = githubRelease.notes.take(MAX_RELEASE_NOTES_CHARACTERS),
                publishedAt = githubRelease.publishedAt,
                prerelease = githubRelease.prerelease,
                manifest = manifest,
                asset = selected,
                assetApiUrl = apiAsset.apiUrl
            )
        }
        return ReleaseCandidateScan(candidates, manifestCount)
    }

    fun download(
        release: DriveDeckRelease,
        token: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        require(token.isBlank() || UpdateCredentialPolicy.isPlausible(token))
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, "${destination.name}.part")
        if (part.exists()) part.delete()
        val connection = openReleaseAsset(
            URL(release.assetApiUrl),
            token = token,
            accept = BINARY
        )
        try {
            ensureSuccess(connection)
            val advertised = connection.contentLengthLong.takeIf { it > 0L }
                ?: release.asset.sizeBytes
            if (advertised != release.asset.sizeBytes) {
                throw GitHubReleaseException("The release asset size changed during download.")
            }
            connection.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        if (cancelled || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Update cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        if (total > release.asset.sizeBytes) {
                            throw GitHubReleaseException("The release asset exceeded its declared size.")
                        }
                        onProgress(total, release.asset.sizeBytes)
                    }
                }
            }
            if (part.length() != release.asset.sizeBytes) {
                throw GitHubReleaseException("The release download was incomplete.")
            }
            if (destination.exists()) destination.delete()
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
        } catch (error: Throwable) {
            part.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun requestText(url: URL, token: String, accept: String): String {
        val first = open(url, token, accept, followRedirects = false)
        val connection = followTrustedRedirect(first, accept)
        return try {
            ensureSuccess(connection)
            val length = connection.contentLengthLong
            if (length > MAX_JSON_BYTES) {
                throw GitHubReleaseException("GitHub returned an unexpectedly large response.")
            }
            connection.inputStream.use { input ->
                val bytes = readBounded(input)
                if (bytes.size > MAX_JSON_BYTES) {
                    throw GitHubReleaseException("GitHub returned an unexpectedly large response.")
                }
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun requestReleaseAssetText(url: URL, token: String): String {
        val bearerPayload = requestReleaseAssetText(url, token, BEARER_AUTHORIZATION)
        if (GitHubReleaseParser.looksLikeReleaseManifest(bearerPayload)) return bearerPayload
        val tokenPayload = requestReleaseAssetText(url, token, TOKEN_AUTHORIZATION)
        if (GitHubReleaseParser.looksLikeReleaseManifest(tokenPayload)) return tokenPayload
        throw GitHubReleaseException(
            "GitHub did not return the DriveDeck manifest. " +
                "Bearer received ${GitHubReleaseParser.describePayload(bearerPayload)}; " +
                "token received ${GitHubReleaseParser.describePayload(tokenPayload)}."
        )
    }

    private fun requestReleaseAssetText(
        url: URL,
        token: String,
        authorizationScheme: String
    ): String {
        val first = open(
            url,
            token,
            BINARY,
            followRedirects = false,
            authorizationScheme = authorizationScheme
        )
        val connection = followTrustedRedirect(first, BINARY)
        return try {
            ensureSuccess(connection)
            val length = connection.contentLengthLong
            if (length > MAX_JSON_BYTES) {
                throw GitHubReleaseException("GitHub returned an unexpectedly large response.")
            }
            connection.inputStream.use { input ->
                val bytes = readBounded(input)
                if (bytes.size > MAX_JSON_BYTES) {
                    throw GitHubReleaseException("GitHub returned an unexpectedly large response.")
                }
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GitHub documents Bearer authentication for fine-grained tokens, but its release-asset
     * endpoint can answer some valid fine-grained credentials with asset metadata instead of
     * the requested bytes. Retry that narrow API request once using GitHub's long-supported
     * token scheme; the credential is still sent only to api.github.com and is never forwarded
     * to the short-lived download host.
     */
    private fun openReleaseAsset(
        url: URL,
        token: String,
        accept: String
    ): HttpURLConnection {
        var first = open(url, token, accept, followRedirects = false)
        if (first.responseCode in 200..299 && first.contentType.isJsonContentType()) {
            first.disconnect()
            first = open(
                url,
                token,
                accept,
                followRedirects = false,
                authorizationScheme = TOKEN_AUTHORIZATION
            )
        }
        return followTrustedRedirect(first, accept)
    }

    /**
     * GitHub's private release-asset API answers with one HTTPS redirect to a short-lived
     * githubusercontent download URL. Follow exactly one trusted hop and deliberately omit the
     * repository token from that second request. This applies to both APK bytes and the small
     * release-manifest asset; treating the latter as ordinary JSON previously surfaced HTTP 302.
     */
    private fun followTrustedRedirect(
        first: HttpURLConnection,
        accept: String
    ): HttpURLConnection = when (first.responseCode) {
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308 -> {
            val location = try {
                first.getHeaderField("Location")
                    ?: throw GitHubReleaseException("GitHub did not provide a download location.")
            } finally {
                first.disconnect()
            }
            val redirect = URL(location)
            requireTrustedDownloadHost(redirect)
            open(redirect, token = null, accept = accept, followRedirects = false)
        }
        else -> first
    }

    private fun open(
        url: URL,
        token: String?,
        accept: String,
        followRedirects: Boolean,
        authorizationScheme: String = BEARER_AUTHORIZATION
    ): HttpURLConnection {
        requireTrustedDownloadHost(url)
        if (cancelled) throw java.io.InterruptedIOException("Update cancelled")
        val connection = connectionFactory(url)
        active = connection
        if (cancelled) { connection.disconnect(); throw java.io.InterruptedIOException("Update cancelled") }
        return connection.apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = followRedirects
            useCaches = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "DriveDeckPlayer-Android-Updater")
            setRequestProperty("Cache-Control", "no-cache")
            if (url.host.equals("api.github.com", ignoreCase = true)) {
                setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            }
            token?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "$authorizationScheme $it")
            }
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (cancelled || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Update cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) { val one = input.read(); if (one < 0) break; output.write(one) }
            else output.write(buffer, 0, count)
            if (output.size() > MAX_JSON_BYTES) throw GitHubReleaseException("GitHub returned an unexpectedly large response.")
        }
        return output.toByteArray()
    }

    private fun String?.isJsonContentType(): Boolean =
        this?.substringBefore(';')?.trim()?.let { mediaType ->
            mediaType.equals("application/json", ignoreCase = true) ||
                mediaType.endsWith("+json", ignoreCase = true)
        } == true

    private fun ensureSuccess(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status in 200..299) return
        throw GitHubReleaseException(
            when (status) {
                HttpURLConnection.HTTP_UNAUTHORIZED ->
                    "GitHub rejected update access."
                HttpURLConnection.HTTP_FORBIDDEN ->
                    "GitHub denied this request or the public API rate limit was reached. Try later."
                HttpURLConnection.HTTP_NOT_FOUND ->
                    "The update release is unavailable. Try again later."
                429 -> "GitHub is rate limiting update checks. Try again later."
                else -> "GitHub update request failed (HTTP $status)."
            },
            kind = if (status in setOf(
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    HttpURLConnection.HTTP_NOT_FOUND
                )
            ) {
                GitHubReleaseFailureKind.CREDENTIAL
            } else {
                GitHubReleaseFailureKind.OTHER
            }
        )
    }

    private fun requireTrustedDownloadHost(url: URL) {
        val uri = URI(url.toString())
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || uri.fragment != null || host !in TRUSTED_DOWNLOAD_HOSTS &&
            TRUSTED_DOWNLOAD_SUFFIXES.none(host::endsWith)
        ) {
            throw GitHubReleaseException("GitHub returned an untrusted download location.")
        }
    }

    companion object {
        const val RELEASE_MANIFEST_NAME = "release-manifest.json"
        private const val GITHUB_JSON = "application/vnd.github+json"
        private const val BINARY = "application/octet-stream"
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val BEARER_AUTHORIZATION = "Bearer"
        private const val TOKEN_AUTHORIZATION = "token"
        private const val CONNECT_TIMEOUT_MILLIS = 12_000
        private const val READ_TIMEOUT_MILLIS = 45_000
        private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
        private const val MAX_JSON_BYTES = 2_000_000L
        private const val MAX_RELEASE_NOTES_CHARACTERS = 12_000
        private val TRUSTED_DOWNLOAD_HOSTS = setOf("github.com", "api.github.com")
        private val TRUSTED_DOWNLOAD_SUFFIXES = setOf(
            ".githubusercontent.com",
            ".github.com"
        )
    }
}

private data class ReleaseCandidateScan(
    val candidates: List<DriveDeckRelease>,
    val manifestCount: Int
)

internal data class ParsedGitHubRelease(
    val id: Long,
    val tag: String,
    val name: String,
    val notes: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val assets: List<GitHubReleaseAsset>
)

internal object GitHubReleaseParser {
    fun parseReleaseList(payload: String): List<ParsedGitHubRelease> {
        if (!boundedJson(payload)) throw GitHubReleaseException("GitHub returned invalid release data.")
        val root = try { com.grack.nanojson.JsonParser.array().from(payload) }
            catch (_: Exception) { throw GitHubReleaseException("GitHub returned invalid release data.") }
        return root.take(50).mapNotNull { (it as? com.grack.nanojson.JsonObject)?.let(::parseRelease) }
    }
    fun looksLikeAssetMetadata(payload: String): Boolean = objectValue(payload)?.let {
        it.text("url")?.startsWith("https://api.github.com/repos/") == true &&
            it.text("browser_download_url")?.startsWith("https://github.com/") == true &&
            it.text("name") == GitHubReleaseClient.RELEASE_MANIFEST_NAME
    } == true
    fun looksLikeReleaseManifest(payload: String): Boolean = objectValue(payload)?.let {
        it.number("schemaVersion") == 1L && it.text("packageName") != null && it["assets"] is com.grack.nanojson.JsonArray
    } == true
    fun parseManifest(payload: String): DriveDeckReleaseManifest? {
        val value = objectValue(payload) ?: return null
        val version = value.number("schemaVersion")?.takeIf { it == 1L }?.toInt() ?: return null
        val values = value["assets"] as? com.grack.nanojson.JsonArray ?: return null
        if (values.size !in 1..8) return null
        val assets = mutableListOf<DriveDeckReleaseAsset>()
        for (raw in values) {
            val asset = raw as? com.grack.nanojson.JsonObject ?: return null
            assets += DriveDeckReleaseAsset(asset.text("name") ?: return null, asset.text("abi") ?: return null,
                asset.text("sha256") ?: return null, asset.number("sizeBytes") ?: return null)
        }
        return DriveDeckReleaseManifest(version, value.text("packageName") ?: return null,
            value.number("versionCode") ?: return null, value.text("versionName") ?: return null,
            value.text("tag") ?: return null, value.text("commitSha") ?: return null, assets).takeIf { it.isPlausible() }
    }
    fun describePayload(payload: String): String {
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        if (payload.isBlank()) return "an empty response"
        if (looksLikeAssetMetadata(payload)) return "GitHub asset metadata ($bytes bytes)"
        val objectValue = objectValue(payload)
        if (objectValue != null) {
            return if (objectValue.text("message") != null) {
                "a GitHub error object ($bytes bytes)"
            } else {
                "a non-manifest JSON object ($bytes bytes)"
            }
        }
        val cleanStart = payload.trimStart()
        return when {
            cleanStart.startsWith("<!DOCTYPE html", ignoreCase = true) ||
                cleanStart.startsWith("<html", ignoreCase = true) ->
                "an HTML response ($bytes bytes)"
            cleanStart.startsWith("<?xml", ignoreCase = true) || cleanStart.startsWith("<Error") ->
                "an XML response ($bytes bytes)"
            else -> "an unrecognized response ($bytes bytes)"
        }
    }

    private fun parseRelease(value: com.grack.nanojson.JsonObject): ParsedGitHubRelease? {
        val id = value.number("id") ?: return null
        val tag = value.text("tag_name")?.takeIf { it.length in 1..96 } ?: return null
        val assets = value["assets"] as? com.grack.nanojson.JsonArray ?: return null
        return ParsedGitHubRelease(id, tag, value.text("name").orEmpty().take(160), value.text("body").orEmpty().take(12_000),
            value.text("published_at").orEmpty().take(64), value["prerelease"] as? Boolean ?: false,
            value["draft"] as? Boolean ?: false, assets.take(12).mapNotNull { raw ->
                val asset = raw as? com.grack.nanojson.JsonObject ?: return@mapNotNull null
                val name = asset.text("name") ?: return@mapNotNull null
                val url = asset.text("url") ?: return@mapNotNull null
                val size = asset.number("size") ?: return@mapNotNull null
                if (name.length !in 1..160 || !url.matches(Regex("https://api\\.github\\.com/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/releases/assets/[0-9]+")) || size !in 1..900_000_000) null
                else GitHubReleaseAsset(name, url, size)
            })
    }
    private fun objectValue(payload: String): com.grack.nanojson.JsonObject? =
        if (!boundedJson(payload)) null else try { com.grack.nanojson.JsonParser.`object`().from(payload) } catch (_: Exception) { null }
    private fun boundedJson(payload: String): Boolean {
        if (payload.length > 2_000_000 || payload.toByteArray(Charsets.UTF_8).size > 2_000_000) return false
        var quoted = false; var escaped = false; var depth = 0
        for (c in payload) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; if (depth > 64) return false }; '}', ']' -> { depth--; if (depth < 0) return false } }
        }
        return !quoted && depth == 0
    }
    private fun com.grack.nanojson.JsonObject.text(key: String): String? = get(key) as? String
    private fun com.grack.nanojson.JsonObject.number(key: String): Long? = (get(key) as? Number)?.toString()?.toLongOrNull()
}
