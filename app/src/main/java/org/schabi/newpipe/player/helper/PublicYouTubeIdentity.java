package org.schabi.newpipe.player.helper;

/** Public media identity for controllers; credentials and extraction URLs never enter metadata. */
public final class PublicYouTubeIdentity {
    private PublicYouTubeIdentity() { }

    public static String canonicalWatchUrl(final int serviceId, final String videoId) {
        if (serviceId != 0 || videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            return null;
        }
        return "https://www.youtube.com/watch?v=" + videoId;
    }
}
