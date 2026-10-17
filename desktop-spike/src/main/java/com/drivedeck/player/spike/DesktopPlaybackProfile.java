package com.drivedeck.player.spike;

import java.util.*;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.*;

/** Conservative software-rendered baseline. Higher-rate/unknown codecs need separate qualification. */
final class DesktopPlaybackProfile {
    static Optional<VideoStream> select(List<VideoStream> streams) {
        return streams.stream().filter(stream -> stream.isUrl()
                && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                && stream.getFormat() == MediaFormat.MPEG_4
                && stream.getHeight() > 0 && stream.getHeight() <= 720
                && stream.getFps() > 0 && stream.getFps() <= 30
                && stream.getCodec() != null && stream.getCodec().startsWith("avc1"))
                .max(Comparator.comparingInt(VideoStream::getHeight).thenComparingInt(VideoStream::getFps));
    }
}
