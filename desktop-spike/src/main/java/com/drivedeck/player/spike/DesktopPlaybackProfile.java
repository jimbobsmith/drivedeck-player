package com.drivedeck.player.spike;

import java.util.*;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.*;

/** Conservative software-rendered baseline. Higher-rate/unknown codecs need separate qualification. */
final class DesktopPlaybackProfile {
    static Optional<VideoStream> select(List<VideoStream> streams) {
        return select(streams, "baseline");
    }
    static Optional<VideoStream> select(List<VideoStream> streams, String profile) {
        int maxHeight = switch (profile) { case "baseline", "hd720" -> 720; case "hd1080" -> 1080; default -> throw new IllegalArgumentException("Unknown profile"); };
        int maxFps = profile.equals("baseline") ? 30 : 60;
        return streams.stream().filter(stream -> stream.isUrl()
                && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                && stream.getFormat() == MediaFormat.MPEG_4
                && stream.getHeight() > 0 && stream.getHeight() <= maxHeight
                && stream.getFps() > 0 && stream.getFps() <= maxFps
                && stream.getCodec() != null && stream.getCodec().startsWith("avc1"))
                .max(Comparator.comparingInt(VideoStream::getHeight).thenComparingInt(VideoStream::getFps));
    }
}
