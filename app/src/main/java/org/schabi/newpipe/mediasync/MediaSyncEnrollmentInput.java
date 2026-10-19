package org.schabi.newpipe.mediasync;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** Bounded enrollment input using APIs available before Android 13. Caller owns the stream. */
public final class MediaSyncEnrollmentInput {
    public static final int MAX_BYTES = 16_384;

    private MediaSyncEnrollmentInput() { }

    public static byte[] read(final InputStream input) throws IOException {
        final byte[] bytes = new byte[MAX_BYTES + 1];
        int used = 0;
        while (used < bytes.length) {
            final int count = input.read(bytes, used, bytes.length - used);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                // Even a nonconforming zero-length bulk read must not spin forever.
                final int single = input.read();
                if (single < 0) {
                    break;
                }
                bytes[used++] = (byte) single;
            } else {
                used += count;
            }
        }
        if (used > MAX_BYTES) {
            throw new IOException("invalid_sync_data");
        }
        return Arrays.copyOf(bytes, used);
    }
}
