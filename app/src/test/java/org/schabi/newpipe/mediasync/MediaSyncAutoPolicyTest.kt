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
}
