package org.schabi.newpipe.mediasync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncAutoPolicyTest {
    @Test fun runsOnlyWhenOnAndNothingNeedsTheUser() {
        assertTrue(MediaSyncAutoPolicy.shouldRun(autoSync = true, blocked = false, conflicts = 0))
        assertFalse(MediaSyncAutoPolicy.shouldRun(autoSync = false, blocked = false, conflicts = 0))
        assertFalse(MediaSyncAutoPolicy.shouldRun(autoSync = true, blocked = true, conflicts = 0))
        assertFalse(MediaSyncAutoPolicy.shouldRun(autoSync = true, blocked = false, conflicts = 2))
    }

    @Test fun retriesOnlyWhileTheServiceIsUnreachable() {
        assertTrue(MediaSyncAutoPolicy.retry("offline"))
        for (code in listOf("synced", "pending", "conflict", "restored_service", "ready")) assertFalse(MediaSyncAutoPolicy.retry(code))
    }

    @Test fun pullsWhenTheAppComesToTheFrontUnlessPausedOrJustSynced() {
        val now = 10_000_000L
        assertTrue(MediaSyncAutoPolicy.syncOnForeground(autoSync = true, lastSyncAt = 0, now = now))
        assertTrue(MediaSyncAutoPolicy.syncOnForeground(autoSync = true, lastSyncAt = now - 31_000, now = now))
        assertFalse(MediaSyncAutoPolicy.syncOnForeground(autoSync = true, lastSyncAt = now - 5_000, now = now))
        assertFalse(MediaSyncAutoPolicy.syncOnForeground(autoSync = false, lastSyncAt = 0, now = now))
        // A clock moved backwards must not suppress the pull forever.
        assertTrue(MediaSyncAutoPolicy.syncOnForeground(autoSync = true, lastSyncAt = now + 60_000, now = now))
    }
}
