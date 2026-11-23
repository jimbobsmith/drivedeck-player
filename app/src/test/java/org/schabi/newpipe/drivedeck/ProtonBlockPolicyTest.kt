package org.schabi.newpipe.drivedeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.drivedeck.ProtonBlockPolicy.Block
import org.schabi.newpipe.drivedeck.ProtonBlockPolicy.Decision
import org.schabi.newpipe.drivedeck.ProtonBlockPolicy.Mode
import org.schabi.newpipe.extractor.exceptions.AntiBotException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.player.mediasource.FailedMediaSource
import java.io.IOException

class ProtonBlockPolicyTest {
    @Test fun theBotCheckCountsWhereverItIsInTheCauses() {
        val bot = AntiBotException("Sign in to confirm you’re not a bot")
        assertEquals(Block.IP_BLOCK, ProtonBlockPolicy.classify(bot))
        // How a play queue item that failed to load reaches the player.
        val queued = IOException(FailedMediaSource.StreamInfoLoadException(bot))
        assertEquals(Block.IP_BLOCK, ProtonBlockPolicy.classify(RuntimeException("Source error", queued)))
    }

    @Test fun streamStatusesCount() {
        assertEquals(Block.STREAM_403, ProtonBlockPolicy.classify(IOException("Response code: 403")))
        assertEquals(Block.RATE_LIMIT, ProtonBlockPolicy.classify(RuntimeException(IOException("Response code: 429"))))
    }

    @Test fun otherFailuresDoNotSwitch() {
        assertNull(ProtonBlockPolicy.classify(null))
        assertNull(ProtonBlockPolicy.classify(ContentNotAvailableException("This video is private")))
        assertNull(ProtonBlockPolicy.classify(IOException("Response code: 404")))
        assertNull(ProtonBlockPolicy.classify(IOException("Unable to connect")))
    }

    @Test fun modeDefaultsToAutomatic() {
        assertEquals(Mode.AUTOMATIC, Mode.of(null))
        assertEquals(Mode.AUTOMATIC, Mode.of("nonsense"))
        assertEquals(Mode.ASK, Mode.of("ask"))
        assertEquals(Mode.OFF, Mode.of("off"))
    }

    @Test fun theSettingDecides() {
        assertEquals(Decision.SWITCH, ProtonBlockPolicy.decide(Mode.AUTOMATIC, true, true, false))
        assertEquals(Decision.PAUSED, ProtonBlockPolicy.decide(Mode.AUTOMATIC, true, false, true))
        assertEquals(Decision.ASK, ProtonBlockPolicy.decide(Mode.ASK, true, false, true))
        // Background playback has no screen to ask on.
        assertEquals(Decision.NONE, ProtonBlockPolicy.decide(Mode.ASK, true, true, false))
        assertEquals(Decision.NONE, ProtonBlockPolicy.decide(Mode.OFF, true, true, true))
        assertEquals(Decision.NONE, ProtonBlockPolicy.decide(Mode.AUTOMATIC, false, true, true))
    }

    @Test fun automaticSwitchesStopAfterTwoPerVideoAndFourPerQuarterHour() {
        var now = 0L
        val budget = ProtonBlockPolicy.Budget(now = { now })
        assertTrue(budget.allows("a"))
        budget.record("a"); budget.record("a")
        assertFalse(budget.allows("a"))
        assertTrue(budget.allows("b"))
        budget.record("b"); budget.record("c")
        assertFalse(budget.allows("d"))
        assertEquals(4, budget.count())
        now += 15 * 60_000L + 1
        assertTrue(budget.allows("a"))
        assertEquals(0, budget.count())
    }
}
