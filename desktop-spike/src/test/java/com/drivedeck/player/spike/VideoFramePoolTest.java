package com.drivedeck.player.spike;

import org.junit.Test;
import java.util.*;
import java.awt.image.BufferedImage;
import static org.junit.Assert.*;

public class VideoFramePoolTest {
    @Test public void borrowedImagesRemainImmutableAndBusyProducerDropsInsteadOfBlocking() {
        var pool = new VideoFramePool(1, 1);
        assertNull(pool.acquireLatest());
        assertTrue(pool.publish(new int[]{1}, 1));
        var first = pool.acquireLatest();
        assertTrue(pool.publish(new int[]{2}, 2));
        var second = pool.acquireLatest();
        assertTrue(pool.publish(new int[]{3}, 3));
        var third = pool.acquireLatest();
        assertFalse(pool.publish(new int[]{4}, 4));
        assertEquals(1, pool.dropped());
        assertEquals(1, first.image().getRGB(0,0) & 255);
        assertEquals(2, second.image().getRGB(0,0) & 255);
        assertEquals(3, third.image().getRGB(0,0) & 255);
        first.close(); first.close(); // An old shown image is released exactly once.
        assertTrue(pool.publish(new int[]{4}, 4));
        assertEquals(2, second.image().getRGB(0,0) & 255);
        second.close(); third.close();
    }
    @Test public void sustainedPresentationReusesTheFixedImageSet() {
        var pool = new VideoFramePool(32,18);
        Set<BufferedImage> images = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] pixels = new int[32*18];
        VideoFramePool.Lease shown = null;
        for (int i=1; i<=10000; i++) {
            Arrays.fill(pixels, i);
            assertTrue(pool.publish(pixels, i));
            var next = pool.acquireLatest();
            assertEquals(i, next.sequence());
            if (shown != null) shown.close();
            shown = next;
            images.add(shown.image());
            assertEquals(i & 0xffffff, shown.image().getRGB(0,0) & 0xffffff);
        }
        shown.close();
        assertTrue(images.size()<=pool.capacity());
        assertEquals(0, pool.dropped());
    }
    @Test public void sizeMismatchReleasesProducerOwnership() {
        var pool = new VideoFramePool(1,1);
        assertThrows(IllegalArgumentException.class, () -> pool.publish(new int[2], 1));
        assertTrue(pool.publish(new int[]{7}, 7));
        try (var frame = pool.acquireLatest()) { assertEquals(7, frame.image().getRGB(0,0) & 255); }
    }
    @Test public void concurrentProducerCannotTearBorrowedPaintPixels() throws Exception {
        var pool = new VideoFramePool(32,18);
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var producer = new Thread(() -> {
            try {
                int[] pixels = new int[32*18];
                for (int i=1; i<=20000; i++) { Arrays.fill(pixels,i); pool.publish(pixels,i); }
            } catch (Throwable problem) { failure.set(problem); }
            finally { done.set(true); }
        });
        producer.start();
        int reads=0;
        do {
            try (var frame = pool.acquireLatest()) {
                if (frame != null) {
                    int expected=(int)frame.sequence();
                    Thread.yield();
                    for (int y=0; y<18; y++) for (int x=0; x<32; x++)
                        assertEquals(expected,frame.image().getRGB(x,y)&0xffffff);
                    reads++;
                }
            }
        } while (!done.get());
        producer.join(5000);
        assertFalse(producer.isAlive()); assertNull(failure.get()); assertTrue(reads>0);
    }
}
