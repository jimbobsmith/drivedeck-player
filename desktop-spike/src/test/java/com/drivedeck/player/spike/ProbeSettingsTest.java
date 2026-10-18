package com.drivedeck.player.spike;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProbeSettingsTest {
    @Test public void defaultRemainsShortAndConservative() {
        assertEquals(new ProbeSettings(20, 100, "baseline"), ProbeSettings.from(Map.of()));
    }
    @Test public void longRunGetsBoundedShutdownHeadroom() {
        var settings = ProbeSettings.from(Map.of("DD_PLAYER_OBSERVE_SECONDS", "180", "DD_PLAYER_PROFILE", "hd720"));
        assertEquals(3000, settings.networkCacheMs());
        assertEquals(1000, ProbeSettings.from(Map.of()).networkCacheMs());
        assertEquals(260, settings.timeoutSeconds()); assertEquals(1280, settings.surfaceWidth());
    }
    @Test public void tenMinutesRetainsBoundedShutdownHeadroom() {
        var settings=ProbeSettings.from(Map.of("DD_PLAYER_OBSERVE_SECONDS","600"));
        assertEquals(600,settings.observationSeconds());assertEquals(680,settings.timeoutSeconds());
    }
    @Test public void explicitShortDeadlineStillPermitsTimeoutTesting() {
        assertEquals(5, ProbeSettings.from(Map.of("DD_PLAYER_PROBE_TIMEOUT_SECONDS", "5")).timeoutSeconds());
    }
    @Test public void rejectsUnboundedAndUnknownSettings() {
        for (var env : java.util.List.of(Map.of("DD_PLAYER_OBSERVE_SECONDS", "601"), Map.of("DD_PLAYER_OBSERVE_SECONDS", "0"),
                Map.of("DD_PLAYER_PROBE_TIMEOUT_SECONDS", "721"), Map.of("DD_PLAYER_PROFILE", "auto"))) {
            assertThrows(IllegalArgumentException.class, () -> ProbeSettings.from(env));
        }
    }
}
