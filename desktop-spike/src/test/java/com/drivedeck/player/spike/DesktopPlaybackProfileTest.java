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
    @Test public void admitsLowerResolutionFallback() {
        var low = avc(360,24);
        assertSame(low, DesktopPlaybackProfile.select(List.of(avc(1080,60),low)).orElseThrow());
    }
}
