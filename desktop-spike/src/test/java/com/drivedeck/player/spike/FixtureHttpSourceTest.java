package com.drivedeck.player.spike;
import org.junit.Test;
import static org.junit.Assert.*;
public class FixtureHttpSourceTest {
    private final String valid="http://127.0.0.1:1234/video?c=VISIONOS";
    @Test public void acceptsOnlyExplicitUninterruptedRelay() {
        assertEquals(valid,FixtureHttpSource.from(valid,valid,true,false).video());
        assertThrows(IllegalArgumentException.class,()->FixtureHttpSource.from(valid,valid,false,false));
        assertThrows(IllegalArgumentException.class,()->FixtureHttpSource.from(valid,valid,true,true));
    }
    @Test public void rejectsRemoteAmbiguousAndCredentialledSourcesWithoutEchoingThem() {
        for(String source:java.util.List.of("https://127.0.0.1:1234/v","http://localhost:1234/v",
                "http://127.0.0.2:1234/v","http://127.0.0.1/v","http://127.0.0.1:0/v",
                "http://127.0.0.1:65536/v","http://127.0.0.1.example:1234/v",
                "http://private-secret@127.0.0.1:1234/v","http://127.0.0.1:1234/v#private-secret",
                "http://[::1]:1234/v","http://2130706433:1234/v","invalid private-secret")) {
            var failure=assertThrows(IllegalArgumentException.class,()->FixtureHttpSource.from(valid,source,true,false));
            assertEquals("LoopbackHttpFixtureRequired",failure.getMessage());
        }
    }
}
