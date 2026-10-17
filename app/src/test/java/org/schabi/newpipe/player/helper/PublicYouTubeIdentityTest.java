package org.schabi.newpipe.player.helper;

import org.junit.Test;
import static org.junit.Assert.*;

public class PublicYouTubeIdentityTest {
    @Test public void publishesExactPublicIdentity() {
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ",
                PublicYouTubeIdentity.canonicalWatchUrl(0, "aqz-KE-bpKQ"));
    }
    @Test public void rejectsOtherProvidersAndNonIds() {
        assertNull(PublicYouTubeIdentity.canonicalWatchUrl(1, "aqz-KE-bpKQ"));
        assertNull(PublicYouTubeIdentity.canonicalWatchUrl(0, null));
        assertNull(PublicYouTubeIdentity.canonicalWatchUrl(0, "https://example.org?token=secret"));
        assertNull(PublicYouTubeIdentity.canonicalWatchUrl(0, "aqz-KE-bpKQ&token=secret"));
        assertNull(PublicYouTubeIdentity.canonicalWatchUrl(0, "short"));
    }
}
