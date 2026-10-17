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
import java.util.concurrent.TimeUnit;

/** Disposable feasibility harness. It does not store history, cookies or resolved stream URLs. */
public final class DesktopSpike {
    private static volatile String stage = "arguments";
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("--worker")) {
            var scratch = scratch();
            var command = new ArrayList<String>();
            command.add(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(DesktopSpike.class.getName());
            command.add("--worker");
            command.addAll(Arrays.asList(args));
            int timeout = Integer.parseInt(System.getenv().getOrDefault("DD_PLAYER_PROBE_TIMEOUT_SECONDS", "100"));
            if (timeout < 1 || timeout > 100) throw new IllegalArgumentException("Timeout must be 1..100 seconds");
            var outcome = WorkerSupervisor.run(command, Duration.ofSeconds(timeout),
                    scratch.resolve("native-" + UUID.randomUUID() + ".log"));
            System.out.println(outcome.report().toString(2));
            System.exit(outcome.exitCode());
        }
        var resultOutput = System.out;
        System.setOut(System.err);
        var output = new JSONObject().put("schemaVersion", 2);
        System.setProperty("jna.tmpdir", scratch().toString());
        int exit = 0;
        try {
            run(Arrays.copyOfRange(args, 1, args.length), output);
            output.put("result", "passed").put("stage", "complete");
        } catch (Exception | LinkageError failure) {
            output.put("result", "failed").put("stage", stage)
                    .put("errorType", failure.getClass().getSimpleName());
            exit = 1;
        }
        resultOutput.println(output.toString());
        System.exit(exit);
    }

    private static java.nio.file.Path scratch() throws IOException {
        var path = java.nio.file.Path.of(System.getenv().getOrDefault("DD_PLAYER_SCRATCH",
                "D:/tmp/dd/player-desktop-spike")).toAbsolutePath();
        java.nio.file.Files.createDirectories(path);
        return path;
    }

    private static void stage(String value) {
        stage = value;
        System.err.println("DD_STAGE " + value);
    }

    private static Void run(String[] args, JSONObject output) throws Exception {
        if (args.length != 2 || !(args[0].equals("--youtube") || args[0].equals("--youtube-paired") || args[0].equals("--fixture"))) {
            throw new IllegalArgumentException("Use --youtube VIDEO_ID or --fixture TEST_MEDIA_PATH");
        }
        String media;
        var mediaOptions = new ArrayList<String>();
        mediaOptions.add(":network-caching=1000");
        mediaOptions.add(":avcodec-hw=none");
        if (args[0].startsWith("--youtube")) {
            if (!args[1].matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Invalid video ID");
            stage("extract");
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
                stage("select_stream");
                Stream selected = video.stream().filter(DesktopSpike::canPlayDirect).findFirst().orElse(null);
                if (selected == null) {
                    var separateVideo = videoOnly.stream().filter(DesktopSpike::canPlaySeparate)
                            .filter(s -> s.getFormat() == MediaFormat.MPEG_4).findFirst().orElse(null);
                    var separateAudio = audio.stream().filter(DesktopSpike::canPlaySeparate)
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
                output.put("selectedDelivery", selected.getDeliveryMethod().name());
                media = selected.getContent();
            }
        } else {
            stage("fixture");
            var fixture = java.nio.file.Path.of(args[1]).toAbsolutePath();
            if (!java.nio.file.Files.isRegularFile(fixture)) throw new IllegalArgumentException("Missing fixture");
            media = fixture.toString();
        }
        stage("native_discovery");
        new uk.co.caprica.vlcj.factory.discovery.NativeDiscovery().discover();
        stage("native_factory");
        // Headless feasibility policy: explicitly select software avcodec for every media.
        // Automatic D3D11 decode crashed during stop after seeking on the measured host.
        var factory = new MediaPlayerFactory("--vout=dummy", "--aout=dummy",
                "--no-video-title-show", "--quiet", "--avcodec-hw=none", "--codec=avcodec,none");
        MediaPlayer player = null;
        try {
            stage("native_player");
            player = factory.mediaPlayers().newMediaPlayer();
            stage("native_play");
            if (!player.media().play(media, mediaOptions.toArray(String[]::new))) throw new IOException("Start rejected");
            stage("decode");
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
            stage("pause_resume");
            player.controls().setPause(true);
            Thread.sleep(500);
            long paused = player.status().time();
            Thread.sleep(700);
            boolean pauseStable = Math.abs(player.status().time() - paused) < 250;
            player.controls().setPause(false);
            Thread.sleep(1500);
            boolean resumed = player.status().time() > paused + 500;
            boolean seekable = player.status().isSeekable();
            stage("seek");
            var beforeSeek = player.media().info().statistics();
            boolean seekWorked = false;
            if (seekable && player.status().length() > 10000) {
                player.controls().setTime(7000);
                Thread.sleep(3000);
                var afterSeek = player.media().info().statistics();
                seekWorked = afterSeek != null && beforeSeek != null
                        && player.status().time() >= 6500 && player.status().time() < 13000
                        && afterSeek.decodedVideo() > beforeSeek.decodedVideo()
                        && afterSeek.decodedAudio() > beforeSeek.decodedAudio();
                if (afterSeek != null) output.put("postSeekDecodedVideo", afterSeek.decodedVideo())
                        .put("postSeekDecodedAudio", afterSeek.decodedAudio());
            }
            output.put("pauseStable", pauseStable).put("resumeAdvanced", resumed)
                    .put("seekable", seekable).put("seekWorked", seekWorked)
                    .put("output", "dummy_decode_only").put("avSyncProven", false)
                    .put("decoderPolicy", "software_avcodec")
                    .put("javaVersion", System.getProperty("java.version"))
                    .put("jnaVersion", com.sun.jna.Native.VERSION)
                    .put("vlcVersion", factory.application().version());
            if (!pauseStable || !resumed || !seekWorked) throw new IOException("Transport probe failed");
        } finally {
            // Completion is published only after every native resource has shut down cleanly.
            String failedStage = stage;
            stage("native_stop");
            if (player != null) {
                player.controls().stop();
                stage("native_release");
                player.release();
            }
            stage("factory_release");
            factory.release();
            output.put("cleanShutdown", true);
            stage(failedStage);
        }
        return null;
    }

    private static boolean canPlaySeparate(Stream stream) {
        return stream.isUrl() && stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP;
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
