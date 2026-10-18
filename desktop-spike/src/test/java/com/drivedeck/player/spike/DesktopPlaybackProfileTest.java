package com.drivedeck.player.spike;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.*;

public class DesktopPlaybackProfileTest {
    private VideoStream track(int height, int fps, String codec, DeliveryMethod method, boolean url) {
        return new VideoStream.Builder().setId("fixture").setContent("https://example.invalid/test", url)
                .setMediaFormat(MediaFormat.MPEG_4).setDeliveryMethod(method).setIsVideoOnly(true)
                .setResolution(height + "p").setHeight(height).setFps(fps).setCodec(codec).build();
    }
    private VideoStream avc(int height, int fps) { return track(height, fps, "avc1.4d401f", DeliveryMethod.PROGRESSIVE_HTTP, true); }
    @Test public void selectsBaselineInsteadOfHigherRateTracks() {
        var baseline = avc(720, 30);
        assertSame(baseline, DesktopPlaybackProfile.select(List.of(avc(1080,60), avc(720,60), avc(480,30), baseline)).orElseThrow());
    }
    @Test public void rejectsUnknownOrUnqualifiedCodecAndRate() {
        assertTrue(DesktopPlaybackProfile.select(List.of(avc(720,0), track(720,30,"av01",DeliveryMethod.PROGRESSIVE_HTTP,true), avc(1080,30))).isEmpty());
    }
    @Test public void rejectsManifestAndNonUrlDelivery() {
        assertTrue(DesktopPlaybackProfile.select(List.of(track(720,30,"avc1",DeliveryMethod.HLS,true), track(720,30,"avc1",DeliveryMethod.PROGRESSIVE_HTTP,false))).isEmpty());
    }
    @Test public void hdProfilesAreExplicitAndBounded() {
        var hd = avc(720,60); var full = avc(1080,60);
        var tracks = List.of(avc(2160,60), avc(1080,120), full, hd, avc(480,30));
        assertSame(hd, DesktopPlaybackProfile.select(tracks, "hd720").orElseThrow());
        assertSame(full, DesktopPlaybackProfile.select(tracks, "hd1080").orElseThrow());
        assertEquals(480, DesktopPlaybackProfile.select(tracks).orElseThrow().getHeight());
        assertThrows(IllegalArgumentException.class, () -> DesktopPlaybackProfile.select(tracks, "auto"));
    }
    @Test public void admitsLowerResolutionFallback() {
        var low = avc(360,24);
        assertSame(low, DesktopPlaybackProfile.select(List.of(avc(1080,60),low)).orElseThrow());
    }
}
