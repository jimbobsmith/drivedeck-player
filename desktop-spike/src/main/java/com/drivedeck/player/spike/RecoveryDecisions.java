package com.drivedeck.player.spike;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Full decision evidence, independent of the bounded inline JSON preview. */
final class RecoveryDecisions {
    static final int LIMIT=601;
    static final String NAME="recovery-decisions.csv";
    static final String HEADER="elapsedMs,positionMs,clockDeltaMs,audioBufferDelta,pictureDelta,paintDelta,relayByteDelta,wallDeltaMs";
    private final Path file;
    private int samples;
    RecoveryDecisions(Path directory) throws IOException {
        file=directory.resolve(NAME);
        Files.writeString(file,HEADER+"\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
    }
    void append(long... values) throws IOException {
        if(values.length!=8) throw new IllegalArgumentException("RecoveryDecisionColumns");
        if(samples>=LIMIT) throw new IOException("RecoveryDecisionLimit");
        Files.writeString(file,Arrays.stream(values).mapToObj(Long::toString).collect(Collectors.joining(","))+"\n",StandardOpenOption.APPEND);
        samples++;
    }
    int samples() { return samples; }
}
