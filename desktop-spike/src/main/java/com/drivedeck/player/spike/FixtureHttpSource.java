package com.drivedeck.player.spike;

import java.net.URI;

/** Synthetic HTTP origins only; never an arbitrary remote-media entry point. */
record FixtureHttpSource(String video, String audio) {
    static FixtureHttpSource from(String video, String audio, boolean relay, boolean fault) {
        if (!relay || fault) throw new IllegalArgumentException("HttpFixtureNeedsUninterruptedRelay");
        validate(video); validate(audio);
        return new FixtureHttpSource(video, audio);
    }
    private static void validate(String value) {
        try {
            var uri = URI.create(value);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            // Never include a supplied URI in an exception/report.
            throw new IllegalArgumentException("LoopbackHttpFixtureRequired");
        }
    }
}
