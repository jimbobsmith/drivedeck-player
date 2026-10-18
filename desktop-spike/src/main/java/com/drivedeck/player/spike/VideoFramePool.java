package com.drivedeck.player.spike;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/** Three owned images per format. A borrowed paint/capture image is never overwritten. */
final class VideoFramePool {
    private static final int CAPACITY = 3;
    private static final class Slot {
        final BufferedImage image;
        int readers;
        boolean writing;
        long sequence;
        Slot(int width, int height) { image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); }
    }
    final class Lease implements AutoCloseable {
        private Slot slot;
        private Lease(Slot slot) { this.slot = slot; }
        BufferedImage image() { return slot.image; }
        long sequence() { return slot.sequence; }
        @Override public void close() {
            synchronized (VideoFramePool.this) {
                if (slot != null) { slot.readers--; slot = null; }
            }
        }
    }
    private final Slot[] slots = new Slot[CAPACITY];
    private Slot latest;
    private long dropped;
    VideoFramePool(int width, int height) {
        for (int i = 0; i < CAPACITY; i++) slots[i] = new Slot(width, height);
    }
    boolean publish(int[] pixels, long sequence) {
        Slot target = null;
        synchronized (this) {
            for (Slot slot : slots) {
                if (slot != latest && slot.readers == 0 && !slot.writing) { target = slot; target.writing = true; break; }
            }
            if (target == null) { dropped++; return false; }
        }
        try {
            int[] destination = ((DataBufferInt) target.image.getRaster().getDataBuffer()).getData();
            if (pixels.length != destination.length) throw new IllegalArgumentException("FrameSizeChanged");
            System.arraycopy(pixels, 0, destination, 0, destination.length);
            synchronized (this) { target.sequence = sequence; latest = target; }
            return true;
        } finally { synchronized (this) { target.writing = false; } }
    }
    synchronized Lease acquireLatest() {
        if (latest == null) return null;
        latest.readers++;
        return new Lease(latest);
    }
    synchronized long dropped() { return dropped; }
    int capacity() { return CAPACITY; }
}
