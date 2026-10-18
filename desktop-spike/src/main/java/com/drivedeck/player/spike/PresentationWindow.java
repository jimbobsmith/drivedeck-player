package com.drivedeck.player.spike;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.*;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.json.JSONObject;

/** Small validation surface, owned by the isolated native worker, not a finished desktop UI. */
final class PresentationWindow implements AutoCloseable {
    private record Frame(BufferedImage image, long sequence) { }
    private volatile Frame latest;
    private Frame shown;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong painted = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.List<String> events = new ArrayList<>();
    private final java.util.List<String> phases = new ArrayList<>();
    private final Path directory;
    private JFrame window;
    private JPanel surface;
    private long lastSequence = -1;
    private boolean wasWhite;
    private final RenderCallbackAdapter renderer = new RenderCallbackAdapter() {
        @Override protected void onDisplay(MediaPlayer ignored, int[] pixels) {
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            System.arraycopy(pixels, 0, ((DataBufferInt) image.getRaster().getDataBuffer()).getData(), 0, width * height);
            latest = new Frame(image, delivered.incrementAndGet());
            surface.repaint(); // No libVLC calls on a native callback thread.
        }
    };
    private int width, height;

    PresentationWindow(MediaPlayerFactory factory, EmbeddedMediaPlayer player, Path directory, int maxSurfaceWidth) throws Exception {
        this.directory = directory;
        Files.createDirectories(directory);
        SwingUtilities.invokeAndWait(() -> {
            window = new JFrame("DriveDeck Player — Playback validation");
            surface = new JPanel() {
                @Override protected void paintComponent(Graphics graphics) {
                    super.paintComponent(graphics);
                    var frame = latest;
                    if (frame == null) return;
                    int w = getWidth(), h = getHeight();
                    double scale = Math.min((double) w / frame.image.getWidth(), (double) h / frame.image.getHeight());
                    int dw = (int) (frame.image.getWidth() * scale), dh = (int) (frame.image.getHeight() * scale);
                    graphics.drawImage(frame.image, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
                    shown = frame;
                    if (frame.sequence != lastSequence) {
                        lastSequence = frame.sequence;
                        painted.incrementAndGet();
                        int rgb = frame.image.getRGB(frame.image.getWidth() / 2, frame.image.getHeight() / 2);
                        boolean white = ((rgb >> 16) & 255) > 220 && ((rgb >> 8) & 255) > 220 && (rgb & 255) > 220;
                        // Epoch milliseconds align with WASAPI QPC mapped to the host wall clock.
                        if (white && !wasWhite && events.size() < 500) {
                            int second = 0;
                            for (int bit = 0; bit < 6; bit++) {
                                int marker = frame.image.getRGB((22 + bit * 40) * frame.image.getWidth() / 320,
                                        12 * frame.image.getHeight() / 180);
                                if (((marker >> 8) & 255) > 100 && ((marker >> 16) & 255) < 100) second |= 1 << bit;
                            }
                            events.add(System.currentTimeMillis() + "," + frame.sequence + "," + second);
                        }
                        wasWhite = white;
                    }
                }
            };
            surface.setBackground(Color.BLACK);
            surface.setPreferredSize(new Dimension(960, 540));
            window.add(surface);
            var caption = new JLabel("Playback validation • automatic pause / resume / seek • close to cancel", SwingConstants.CENTER);
            caption.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));
            window.add(caption, BorderLayout.SOUTH);
            window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            window.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent ignored) { closed.set(true); }
            });
            window.pack(); window.setLocationRelativeTo(null); window.setVisible(true);
        });
        var format = new BufferFormatCallbackAdapter() {
            @Override public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
                width = Math.min(sourceWidth, maxSurfaceWidth);
                height = Math.max(1, sourceHeight * width / sourceWidth);
                renderer.setBuffer(new int[width * height]);
                return new RV32BufferFormat(width, height);
            }
        };
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(format, renderer, true));
        player.audio().setVolume(35); // Per-player volume; never change the host's master volume.
        player.audio().setMute(false);
    }

    long paintedFrames() { return painted.get(); }

    void mark(String phase) throws Exception {
        SwingUtilities.invokeAndWait(() -> phases.add(System.currentTimeMillis() + "," + phase));
    }

    void checkOpen() throws InterruptedException {
        if (closed.get()) throw new InterruptedException("Presentation cancelled");
    }

    void report(JSONObject output) throws Exception {
        checkOpen();
        SwingUtilities.invokeAndWait(() -> {
            output.put("paintedVideoFrames", painted.get()).put("videoCallbacks", delivered.get())
                    .put("renderWidth", width).put("renderHeight", height)
                    .put("windowShowing", window.isShowing()).put("playerVolumePercent", 35);
            var frame = shown;
            try {
                Files.write(directory.resolve("flash-events.csv"), events);
                Files.write(directory.resolve("phases.csv"), phases);
                // Only our decoded video surface is captured; no desktop or other window pixels.
                if (frame != null) ImageIO.write(frame.image, "png", directory.resolve("presented-frame.png").toFile());
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
        if (painted.get() < 20) throw new java.io.IOException("No painted video evidence");
    }

    @Override public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> window.dispose());
    }
}
