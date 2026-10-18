package com.drivedeck.player.spike;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Numeric, bounded, flushed sidecar: survives a killed worker without expanding its stdout protocol. */
final class RecoveryTrace {
    static final int LIMIT=601;
    static final String HEADER="epochMs,elapsedMs,phase,clockMs,decodedVideo,decodedAudio,pictures,audioBuffers,painted,callbacks,videoBytes,audioBytes,videoReadMs,audioReadMs,videoWriteMs,audioWriteMs,videoPendingReadMs,audioPendingReadMs,videoPendingWriteMs,audioPendingWriteMs,videoRequests,audioRequests,videoFailures,audioFailures,gcMs,gcCollections,cpuMs";
    private final Path file;
    private int samples;
    RecoveryTrace(Path directory) throws IOException {
        file=directory.resolve("recovery-diagnostics.csv");
        Files.writeString(file,HEADER+"\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
    }
    void append(long... values) throws IOException {
        if(values.length!=27) throw new IllegalArgumentException("RecoveryTraceColumns");
        if(samples>=LIMIT) return;
        Files.writeString(file,Arrays.stream(values).mapToObj(Long::toString).collect(Collectors.joining(","))+"\n",StandardOpenOption.APPEND);
        samples++;
    }
    int samples() { return samples; }
}
