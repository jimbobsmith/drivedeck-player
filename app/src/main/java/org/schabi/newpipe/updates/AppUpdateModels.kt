// Adapted from DriveDeckG updater, GPL-3.0-or-later, Copyright (C) 2026 DriveDeckG contributors.
// Source provenance and bounded fork adaptations: docs/updates/README.md.
package org.schabi.newpipe.updates

import java.net.URI

data class GitHubReleaseRepository(
    val owner: String,
    val name: String
) {
    val releasesUrl: String
        get() = "https://github.com/$owner/$name/releases"
    val apiReleasesUrl: String
        get() = "https://api.github.com/repos/$owner/$name/releases"
}

object GitHubReleaseRepositoryPolicy {
    const val DEFAULT_RELEASES_URL = "https://github.com/jimbobsmith/drivedeck-player/releases"

    fun parse(raw: String): GitHubReleaseRepository? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (
            uri.scheme != "https" ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.port != -1 ||
            uri.userInfo != null ||
            uri.query != null ||
            uri.fragment != null
        ) return null
        val segments = uri.path.split('/').filter(String::isNotBlank)
        if (segments.size < 3 || segments[2] != "releases") return null
        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        if (!owner.matches(OWNER_PATTERN) || !repository.matches(REPOSITORY_PATTERN)) return null
        if (segments.size > 3 && segments[3] != "tag") return null
        return GitHubReleaseRepository(owner, repository)
    }

    fun normalizedUrl(raw: String): String? = parse(raw)?.releasesUrl

    private val OWNER_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")
}

data class DriveDeckReleaseManifest(
    val schemaVersion: Int = 1,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val tag: String,
    val commitSha: String,
    val assets: List<DriveDeckReleaseAsset>
) {
    fun isPlausible(): Boolean =
        schemaVersion == 1 &&
            packageName.matches(PACKAGE_PATTERN) &&
            versionCode > 0L &&
            versionName.length in 1..96 &&
            tag.length in 1..96 &&
            commitSha.matches(COMMIT_PATTERN) &&
            assets.isNotEmpty() &&
            assets.size <= 8 &&
            assets.all { asset -> asset.isPlausible() } &&
            assets.map { asset -> asset.name }.distinct().size == assets.size

    fun preferredAsset(supportedAbis: List<String>): DriveDeckReleaseAsset? {
        supportedAbis.forEach { abi ->
            assets.firstOrNull { it.abi == abi }?.let { return it }
        }
        return assets.firstOrNull { it.abi == "universal" }
    }

    private companion object {
        val PACKAGE_PATTERN = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        val COMMIT_PATTERN = Regex("[a-fA-F0-9]{40}")
    }
}

data class DriveDeckReleaseAsset(
    val name: String,
    val abi: String,
    val sha256: String,
    val sizeBytes: Long
) {
    fun isPlausible(): Boolean =
        name.matches(FILE_PATTERN) &&
            abi in SUPPORTED_ABIS &&
            sha256.matches(SHA_PATTERN) &&
            sizeBytes in MIN_APK_BYTES..MAX_APK_BYTES

    companion object {
        private val FILE_PATTERN = Regex("[A-Za-z0-9._-]+\\.apk")
        private val SHA_PATTERN = Regex("[a-f0-9]{64}")
        private val SUPPORTED_ABIS = setOf(
            "arm64-v8a",
            "armeabi-v7a",
            "x86",
            "x86_64",
            "universal"
        )
        private const val MIN_APK_BYTES = 1_000_000L
        private const val MAX_APK_BYTES = 800_000_000L
    }
}

data class GitHubReleaseAsset(
    val name: String,
    val apiUrl: String,
    val sizeBytes: Long
)

data class DriveDeckRelease(
    val id: Long,
    val name: String,
    val notes: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val manifest: DriveDeckReleaseManifest,
    val asset: DriveDeckReleaseAsset,
    val assetApiUrl: String
)

data class ReleaseLookupResult(
    val newestRelease: DriveDeckRelease?,
    val manifestReleaseCount: Int
)

enum class UpdatePhase {
    NOT_CONFIGURED,
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    FAILED
}

data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.NOT_CONFIGURED,
    val credentialConfigured: Boolean = false,
    val repositoryUrl: String? = null,
    val release: DriveDeckRelease? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val lastCheckedAtEpochMillis: Long? = null,
    val message: String = "Enter a GitHub Releases URL and token to receive private updates.",
    val isLinkedRelease: Boolean = false,
    val credentialIssue: Boolean = false
) {
    val progressFraction: Float?
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            null
        }
}

sealed interface ApkVerificationResult {
    data class Valid(val packageName: String, val versionCode: Long) : ApkVerificationResult
    data class Invalid(val reason: String) : ApkVerificationResult
}

object UpdateCredentialPolicy {
    fun isPlausible(token: String): Boolean {
        val clean = token.trim()
        return clean.length in 20..255 && clean.none(Char::isWhitespace)
    }

    fun maskedLabel(token: String?): String = when {
        token.isNullOrBlank() -> "Not connected"
        token.length < 8 -> "Connected"
        else -> "Connected ••••${token.takeLast(4)}"
    }
}

object UpdateAssetPolicy {
    fun abiFromApkName(name: String): String? = when {
        "arm64-v8a" in name -> "arm64-v8a"
        "armeabi-v7a" in name -> "armeabi-v7a"
        "x86_64" in name -> "x86_64"
        Regex("(^|[-_])x86([-_.]|$)").containsMatchIn(name) -> "x86"
        "universal" in name -> "universal"
        else -> null
    }
}
