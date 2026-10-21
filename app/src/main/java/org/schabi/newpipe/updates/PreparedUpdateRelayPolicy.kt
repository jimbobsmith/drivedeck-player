// Adapted from DriveDeckG updater, GPL-3.0-or-later, Copyright (C) 2026 DriveDeckG contributors.
// Source provenance and bounded fork adaptations: docs/updates/README.md.
package org.schabi.newpipe.updates

/** Keeps a verified APK relayable after this device has installed that same build. */
internal object PreparedUpdateRelayPolicy {
    fun acceptsArchiveVersion(
        archiveVersionCode: Long,
        expectedVersionCode: Long,
        installedVersionCode: Long,
        purpose: ApkVerificationPurpose
    ): Boolean =
        archiveVersionCode == expectedVersionCode &&
            (purpose == ApkVerificationPurpose.RETAINED_RELAY ||
                archiveVersionCode > installedVersionCode)

    fun shouldPresentInstall(preparedVersionCode: Long, installedVersionCode: Long): Boolean =
        preparedVersionCode > installedVersionCode

    fun shouldPrepareInstalledRelease(
        peerVersionCode: Long,
        installedVersionCode: Long,
        retainedVersionCode: Long?,
        credentialsConfigured: Boolean
    ): Boolean =
        credentialsConfigured &&
            peerVersionCode > 0L &&
            peerVersionCode < installedVersionCode &&
            (retainedVersionCode == null || retainedVersionCode < installedVersionCode)
}

internal enum class ApkVerificationPurpose {
    INSTALL,
    RETAINED_RELAY
}
