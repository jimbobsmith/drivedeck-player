package com.drivedeck.player.spike;
import org.junit.Test;
import static org.junit.Assert.*;
public class SeekReadinessTest {
    @Test public void targetTimestampAndDecoderActivityDoNotProveResume() {
        assertFalse(DesktopSpike.seekReady(15000, 5000, 200, 15000, true, true));
    }
    @Test public void advancingClockRequiresNewAudioAndVideoOutput() {
        assertFalse(DesktopSpike.seekReady(15000, 5000, 800, 15600, true, false));
        assertFalse(DesktopSpike.seekReady(15000, 5000, 800, 15600, false, true));
    }
    @Test public void naturalClockAdvancementCannotSatisfySeek() {
        assertFalse(DesktopSpike.seekReady(15000, 14500, 800, 15500, true, true));
    }
    @Test public void actualJumpAndResumedOutputPass() {
        assertTrue(DesktopSpike.seekReady(15000, 5000, 3000, 15600, true, true));
    }
}
