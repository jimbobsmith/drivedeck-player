// Adapted from DriveDeckG updater, GPL-3.0-or-later, Copyright (C) 2026 DriveDeckG contributors.
// Source provenance and bounded fork adaptations: docs/updates/README.md.
package org.schabi.newpipe.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal object AppUpdateVerifier {
    fun verify(
        context: Context,
        apk: File,
        expected: DriveDeckReleaseManifest,
        expectedAsset: DriveDeckReleaseAsset,
        purpose: ApkVerificationPurpose = ApkVerificationPurpose.INSTALL
    ): ApkVerificationResult {
        return try { verifyArchive(context, apk, expected, expectedAsset, purpose) }
        catch (_: Exception) { ApkVerificationResult.Invalid("Android could not verify this APK.") }
    }

    private fun verifyArchive(context: Context, apk: File, expected: DriveDeckReleaseManifest,
        expectedAsset: DriveDeckReleaseAsset, purpose: ApkVerificationPurpose): ApkVerificationResult {
        if (!expected.isPlausible() || expectedAsset !in expected.assets) {
            return ApkVerificationResult.Invalid("The update manifest or selected asset is invalid.")
        }
        if (!apk.isFile || apk.length() != expectedAsset.sizeBytes) {
            return ApkVerificationResult.Invalid("The downloaded APK size does not match the release manifest.")
        }
        val digest = runCatching { apk.inputStream().use(::sha256) }.getOrNull()
            ?: return ApkVerificationResult.Invalid("DD Player could not hash the downloaded APK.")
        if (digest != expectedAsset.sha256) {
            return ApkVerificationResult.Invalid("The downloaded APK failed its SHA-256 check.")
        }

        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            signingFlags()
        ) ?: return ApkVerificationResult.Invalid("Android could not read the downloaded APK.")
        if (archive.packageName != context.packageName || archive.packageName != expected.packageName) {
            return ApkVerificationResult.Invalid("The APK belongs to a different application package.")
        }
        val archiveVersion = versionCode(archive)
        val current = packageManager.getPackageInfo(
            context.packageName,
            signingFlags()
        )
        if (!PreparedUpdateRelayPolicy.acceptsArchiveVersion(
                archiveVersionCode = archiveVersion,
                expectedVersionCode = expected.versionCode,
                installedVersionCode = versionCode(current),
                purpose = purpose
            )
        ) {
            return ApkVerificationResult.Invalid("The APK version is not a newer declared DD Player release.")
        }
        val archiveSigners = signerDigests(archive)
        val currentSigners = signerDigests(current)
        if (archiveSigners.isEmpty() || currentSigners.isEmpty() || archiveSigners != currentSigners) {
            return ApkVerificationResult.Invalid("The APK signing certificate does not match this DD Player install.")
        }
        return ApkVerificationResult.Valid(archive.packageName, archiveVersion)
    }

    internal fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION") val version = info.versionCode
        version.toLong()
    }
    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else {
        @Suppress("DEPRECATION") val flags = PackageManager.GET_SIGNATURES
        flags
    }
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
