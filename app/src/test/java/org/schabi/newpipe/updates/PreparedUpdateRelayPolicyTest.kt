// Adapted from DriveDeckG updater, GPL-3.0-or-later, Copyright (C) 2026 DriveDeckG contributors.
// Source provenance and bounded fork adaptations: docs/updates/README.md.
package org.schabi.newpipe.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedUpdateRelayPolicyTest {
    @Test
    fun installVerificationRequiresADeclaredNewerVersion() {
        assertTrue(
            PreparedUpdateRelayPolicy.acceptsArchiveVersion(
                archiveVersionCode = 273L,
                expectedVersionCode = 273L,
                installedVersionCode = 272L,
                purpose = ApkVerificationPurpose.INSTALL
            )
        )
        assertFalse(
            PreparedUpdateRelayPolicy.acceptsArchiveVersion(
                archiveVersionCode = 273L,
                expectedVersionCode = 273L,
                installedVersionCode = 273L,
                purpose = ApkVerificationPurpose.INSTALL
            )
        )
    }

    @Test
    fun retainedRelayAcceptsTheExactlyDeclaredInstalledArchive() {
        assertTrue(
            PreparedUpdateRelayPolicy.acceptsArchiveVersion(
                archiveVersionCode = 273L,
                expectedVersionCode = 273L,
                installedVersionCode = 273L,
                purpose = ApkVerificationPurpose.RETAINED_RELAY
            )
        )
        assertFalse(
            PreparedUpdateRelayPolicy.acceptsArchiveVersion(
                archiveVersionCode = 272L,
                expectedVersionCode = 273L,
                installedVersionCode = 273L,
                purpose = ApkVerificationPurpose.RETAINED_RELAY
            )
        )
    }

    @Test
    fun newerPreparedBuildStillRequiresInstallation() {
        assertTrue(PreparedUpdateRelayPolicy.shouldPresentInstall(273L, 272L))
    }

    @Test
    fun installedBuildRemainsRelayableWithoutOfferingReinstallation() {
        assertFalse(PreparedUpdateRelayPolicy.shouldPresentInstall(273L, 273L))
    }

    @Test
    fun olderRetainedBuildIsNotOfferedForInstallation() {
        assertFalse(PreparedUpdateRelayPolicy.shouldPresentInstall(272L, 273L))
    }

    @Test
    fun olderPeerTriggersExactInstalledReleasePreparation() {
        assertTrue(
            PreparedUpdateRelayPolicy.shouldPrepareInstalledRelease(
                peerVersionCode = 272L,
                installedVersionCode = 273L,
                retainedVersionCode = null,
                credentialsConfigured = true
            )
        )
    }

    @Test
    fun relayPreparationSkipsCurrentPeersMissingCredentialsAndRetainedBuilds() {
        assertFalse(PreparedUpdateRelayPolicy.shouldPrepareInstalledRelease(273L, 273L, null, true))
        assertFalse(PreparedUpdateRelayPolicy.shouldPrepareInstalledRelease(272L, 273L, null, false))
        assertFalse(PreparedUpdateRelayPolicy.shouldPrepareInstalledRelease(272L, 273L, 273L, true))
        assertFalse(PreparedUpdateRelayPolicy.shouldPrepareInstalledRelease(272L, 273L, 274L, true))
        assertFalse(PreparedUpdateRelayPolicy.shouldPrepareInstalledRelease(0L, 273L, null, true))
    }
}
