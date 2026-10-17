package com.drivedeck.player.spike;

import okhttp3.*;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.media.MediaStatistics;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Disposable feasibility harness. It does not store history, cookies or resolved stream URLs. */
public final class DesktopSpike {
    private static volatile String stage = "arguments";
    public static void main(String[] args) {
        var resultOutput = System.out;
        System.setOut(System.err); // Keep upstream diagnostics out of the machine-readable result.
        var output = new JSONObject().put("schemaVersion", 1);
        int exit = 0;
        var scratch = java.nio.file.Path.of(System.getenv().getOrDefault("DD_PLAYER_SCRATCH", "D:/tmp/dd/player-desktop-spike"));
        try { java.nio.file.Files.createDirectories(scratch); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        System.setProperty("jna.tmpdir", scratch.toAbsolutePath().toString());
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            var work = executor.submit(() -> run(args, output));
            try { work.get(100, TimeUnit.SECONDS); }
            catch (Exception failure) {
                work.cancel(true);
                var cause = failure instanceof ExecutionException ? failure.getCause() : failure;
                output.put("result", "failed").put("stage", stage)
                      .put("errorType", cause.getClass().getSimpleName());
                exit = 1;
            }
        }
        finally {
            executor.shutdownNow();
            resultOutput.println(output.toString(2));
        }
        System.exit(exit);
    }

    private static Void run(String[] args, JSONObject output) throws Exception {
        if (args.length != 2 || !(args[0].equals("--youtube") || args[0].equals("--youtube-paired") || args[0].equals("--fixture"))) {
            throw new IllegalArgumentException("Use --youtube VIDEO_ID or --fixture TEST_MEDIA_PATH");
        }
        String media;
        var mediaOptions = new ArrayList<String>();
        mediaOptions.add(":network-caching=1000");
        if (args[0].startsWith("--youtube")) {
            if (!args[1].matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Invalid video ID");
            stage = "extract";
            try (var downloader = new DesktopDownloader()) {
                NewPipe.init(downloader);
                var info = StreamInfo.getInfo("https://www.youtube.com/watch?v=" + args[1]);
                var video = info.getVideoStreams();
                var audio = info.getAudioStreams();
                var videoOnly = info.getVideoOnlyStreams();
                output.put("videoStreams", video.size()).put("audioStreams", audio.size())
                      .put("videoOnlyStreams", videoOnly.size());
                output.put("videoOnlyDeliveries", videoOnly.stream().map(s -> s.getDeliveryMethod().name()).distinct().toList());
                output.put("audioDeliveries", audio.stream().map(s -> s.getDeliveryMethod().name()).distinct().toList());
                output.put("deliveries", video.stream().map(s -> s.getDeliveryMethod().name()).distinct().toList());
                stage = "select_stream";
                Stream selected = video.stream().filter(DesktopSpike::canPlayDirect).findFirst().orElse(null);
                if (selected == null && args[0].equals("--youtube-paired")) {
                    var separateVideo = videoOnly.stream().filter(DesktopSpike::canPlayDirect)
                            .filter(s -> s.getFormat() == MediaFormat.MPEG_4).findFirst().orElse(null);
                    var separateAudio = audio.stream().filter(DesktopSpike::canPlayDirect)
                            .filter(s -> s.getFormat() == MediaFormat.M4A).findFirst().orElse(null);
                    if (separateVideo == null || separateAudio == null) {
                        output.put("reason", "no_supported_combined_or_separate_av_tracks");
                        throw new UnsupportedOperationException("Unsupported delivery; SABR needs an adapter");
                    }
                    selected = separateVideo;
                    mediaOptions.add(":input-slave=" + separateAudio.getContent());
                    output.put("pairedAudio", true).put("videoResolution", separateVideo.getResolution());
                } else {
                    output.put("pairedAudio", false);
                }
                if (selected == null) {
                    output.put("reason", "no_combined_av_stream_separate_tracks_need_adapter");
                    throw new UnsupportedOperationException("Separate-track native probe is experimental");
                }
                output.put("selectedDelivery", selected.getDeliveryMethod().name());
                media = selected.getContent();
            }
        } else {
            stage = "fixture";
            var fixture = java.nio.file.Path.of(args[1]).toAbsolutePath();
            if (!java.nio.file.Files.isRegularFile(fixture)) throw new IllegalArgumentException("Missing fixture");
            media = fixture.toString();
        }
        stage = "decode";
        // Dummy outputs decode both tracks without opening a window or playing sound on the user's PC.
        new uk.co.caprica.vlcj.factory.discovery.NativeDiscovery().discover();
        var factory = new MediaPlayerFactory("--vout=dummy", "--aout=dummy", "--no-video-title-show", "--quiet");
        MediaPlayer player = null;
        try {
            player = factory.mediaPlayers().newMediaPlayer();
            if (!player.media().play(media, mediaOptions.toArray(String[]::new))) throw new IOException("Start rejected");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            MediaStatistics stats = null;
            while (System.nanoTime() < deadline) {
                Thread.sleep(200);
                stats = player.media().info().statistics();
                if (stats != null && stats.decodedVideo() > 20 && stats.decodedAudio() > 10
                        && player.status().time() > 2500) break;
            }
            if (stats == null) throw new IOException("No decoder statistics");
            output.put("decodedVideo", stats.decodedVideo()).put("decodedAudio", stats.decodedAudio())
                  .put("positionMs", player.status().time()).put("lengthMs", player.status().length());
            if (stats.decodedVideo() <= 20 || stats.decodedAudio() <= 10 || player.status().time() < 2500) {
                throw new IOException("Both audio/video decoding and clock advancement required");
            }
            stage = "transport";
            player.controls().setPause(true);
            Thread.sleep(500);
            long paused = player.status().time();
            Thread.sleep(700);
            boolean pauseStable = Math.abs(player.status().time() - paused) < 250;
            player.controls().setPause(false);
            Thread.sleep(1200);
            boolean resumed = player.status().time() > paused + 500;
            boolean seekable = player.status().isSeekable();
            boolean seekWorked = false;
            if (seekable && player.status().length() > 10000) {
                player.controls().setTime(7000);
                Thread.sleep(800);
                seekWorked = player.status().time() >= 6500;
            }
            output.put("pauseStable", pauseStable).put("resumeAdvanced", resumed)
                  .put("seekable", seekable).put("seekWorked", seekWorked)
                  .put("output", "dummy_decode_only");
            if (!pauseStable || !resumed || !seekWorked) throw new IOException("Transport probe failed");
            output.put("result", "passed").put("stage", "complete");
        } finally {
            if (player != null) { player.controls().stop(); player.release(); }
            factory.release();
        }
        return null;
    }

    private static boolean canPlayDirect(Stream stream) {
        return stream.isUrl() && (stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                || stream.getDeliveryMethod() == DeliveryMethod.HLS);
    }

    private static final class DesktopDownloader extends Downloader implements AutoCloseable {
        private final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15)).readTimeout(Duration.ofSeconds(25))
                .callTimeout(Duration.ofSeconds(40)).build();
        private Call call(Request request) throws IOException {
            // This spike never silently depends on PipePipe's hosted signature decoder.
            if (java.net.URI.create(request.url()).getHost().equals("api.pipepipe.dev")) {
                throw new IOException("Hosted decoder unavailable in local-only feasibility test");
            }
            var builder = new okhttp3.Request.Builder().url(request.url())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36");
            request.headers().forEach((name, values) -> {
                builder.removeHeader(name);
                values.forEach(value -> builder.addHeader(name, value));
            });
            byte[] body = request.dataToSend();
            boolean needsBody = Set.of("POST", "PUT", "PATCH").contains(request.httpMethod());
            builder.method(request.httpMethod(), body == null && !needsBody ? null :
                    RequestBody.create(body == null ? new byte[0] : body, null));
            return client.newBuilder().followRedirects(request.followRedirects()).build().newCall(builder.build());
        }
        private Response convert(okhttp3.Response response) throws IOException {
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            return new Response(response.code(), response.message(), response.headers().toMultimap(),
                    new String(bytes, StandardCharsets.UTF_8), bytes, response.request().url().toString());
        }
        @Override public Response execute(Request request) throws IOException {
            try (var response = call(request).execute()) { return convert(response); }
        }
        @Override public CancellableCall executeAsync(Request request, AsyncCallback callback) throws IOException {
            var call = call(request);
            var handle = new CancellableCall(call);
            call.enqueue(new Callback() {
                public void onFailure(Call ignored, IOException failure) {
                    try { callback.onError(failure); } finally { handle.setFinished(); }
                }
                public void onResponse(Call ignored, okhttp3.Response response) {
                    try (response) { callback.onSuccess(convert(response)); }
                    catch (Exception failure) { callback.onError(failure); }
                    finally { handle.setFinished(); }
                }
            });
            return handle;
        }
        @Override public void close() {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
