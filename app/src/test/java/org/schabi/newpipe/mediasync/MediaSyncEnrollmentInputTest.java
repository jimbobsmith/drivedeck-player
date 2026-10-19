package org.schabi.newpipe.mediasync;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MediaSyncEnrollmentInputTest {
    @Test public void acceptsEmptyAndExactLimitWithoutTruncation() throws Exception {
        assertArrayEquals(new byte[0], MediaSyncEnrollmentInput.read(new ByteArrayInputStream(new byte[0])));
        byte[] bytes = new byte[MediaSyncEnrollmentInput.MAX_BYTES];
        Arrays.fill(bytes, (byte) 0xAB);
        assertArrayEquals(bytes, MediaSyncEnrollmentInput.read(new ByteArrayInputStream(bytes)));
    }

    @Test public void rejectsOversizeAfterOnlyOneExtraByte() throws Exception {
        final int[] consumed = {0};
        InputStream endless = new InputStream() {
            @Override public int read() { consumed[0]++; return 65; }
            @Override public int read(byte[] bytes, int offset, int length) {
                Arrays.fill(bytes, offset, offset + length, (byte) 65);
                consumed[0] += length; return length;
            }
        };
        try {
            MediaSyncEnrollmentInput.read(endless); fail("Oversized input accepted");
        } catch (IOException expected) {
            assertEquals("invalid_sync_data", expected.getMessage());
            assertEquals(MediaSyncEnrollmentInput.MAX_BYTES + 1, consumed[0]);
        }
    }

    @Test public void retainsBytesAcrossShortAndZeroBulkReads() throws Exception {
        byte[] bytes = "{synthetic enrollment}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InputStream fragmented = new ByteArrayInputStream(bytes) {
            private boolean zero = true;
            @Override public synchronized int read(byte[] target, int offset, int length) {
                zero = !zero;
                return zero ? 0 : super.read(target, offset, Math.min(3, length));
            }
        };
        assertArrayEquals(bytes, MediaSyncEnrollmentInput.read(fragmented));
    }

    @Test public void preservesReadFailures() {
        IOException failure = new IOException("synthetic input failure");
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw failure; }
        };
        try {
            MediaSyncEnrollmentInput.read(broken); fail("Input failure swallowed");
        } catch (IOException expected) {
            assertSame(failure, expected);
        }
    }
}
