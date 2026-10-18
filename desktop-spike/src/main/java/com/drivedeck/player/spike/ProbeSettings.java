package com.drivedeck.player.spike;

import java.util.Map;

/** Explicit, bounded validation settings, inherited identically by supervisor and worker. */
record ProbeSettings(int observationSeconds, int timeoutSeconds, String profile) {
    static ProbeSettings from(Map<String, String> env) {
        int observe = bounded(env, "DD_PLAYER_OBSERVE_SECONDS", 20, 20, 600);
        int timeout = bounded(env, "DD_PLAYER_PROBE_TIMEOUT_SECONDS", observe + 80, 1, 720);
        String profile = env.getOrDefault("DD_PLAYER_PROFILE", "baseline");
        if (!java.util.Set.of("baseline", "hd720", "hd1080").contains(profile))
            throw new IllegalArgumentException("Unknown playback profile");
        return new ProbeSettings(observe, timeout, profile);
    }
    private static int bounded(Map<String, String> env, String key, int fallback, int min, int max) {
        int value = Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("Out-of-range probe setting: " + key);
        return value;
    }
    int networkCacheMs() { return profile.equals("baseline") ? 1000 : 3000; }
    int surfaceWidth() { return profile.equals("hd1080") ? 1920 : profile.equals("hd720") ? 1280 : 640; }
}
