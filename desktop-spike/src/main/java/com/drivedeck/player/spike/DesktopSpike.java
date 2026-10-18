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
            int timeout = ProbeSettings.from(System.getenv()).timeoutSeconds();
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
        var settings = ProbeSettings.from(System.getenv());
        boolean present = args.length > 0 && args[0].equals("--present");
        if (present) args = Arrays.copyOfRange(args, 1, args.length);
        if (args.length < 2 || !(args[0].equals("--youtube") || args[0].equals("--youtube-paired")
                || args[0].equals("--fixture") || args[0].equals("--fixture-paired"))
                || args.length != (args[0].equals("--fixture-paired") ? 3 : 2)) {
            throw new IllegalArgumentException("Use --youtube VIDEO_ID or --fixture TEST_MEDIA_PATH");
        }
        var network = new NetworkScenario(System.getenv(), settings.observationSeconds());
        if (network.enabled() && !present) throw new IllegalArgumentException("Network fault needs presentation");
        String media;
        String audioMedia = null;
        var mediaOptions = new ArrayList<String>();
        mediaOptions.add(":network-caching=" + settings.networkCacheMs());
        mediaOptions.add(":avcodec-hw=none");
        mediaOptions.add(":demux=avformat");
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
                Stream selected = present ? DesktopPlaybackProfile.select(video, settings.profile()).orElse(null)
                        : video.stream().filter(DesktopSpike::canPlayDirect).findFirst().orElse(null);
                if (selected == null) {
                    var separateVideo = present ? DesktopPlaybackProfile.select(videoOnly, settings.profile()).orElse(null)
                            : videoOnly.stream().filter(DesktopSpike::canPlaySeparate)
                                    .filter(s -> s.getFormat() == MediaFormat.MPEG_4).findFirst().orElse(null);
                    var separateAudio = audio.stream().filter(DesktopSpike::canPlaySeparate)
                            .filter(s -> s.getFormat() == MediaFormat.M4A).findFirst().orElse(null);
                    if (separateVideo == null || separateAudio == null) {
                        output.put("reason", "no_supported_combined_or_separate_av_tracks");
                        throw new UnsupportedOperationException("Unsupported delivery; SABR needs an adapter");
                    }
                    selected = separateVideo;
                    audioMedia = separateAudio.getContent();
                    output.put("pairedAudio", true).put("videoResolution", separateVideo.getResolution());
                } else {
                    output.put("pairedAudio", false);
                }
                output.put("selectedDelivery", selected.getDeliveryMethod().name());
                if (selected instanceof org.schabi.newpipe.extractor.stream.VideoStream chosen) {
                    output.put("selectedCodec", chosen.getCodec()).put("selectedFps", chosen.getFps())
                            .put("selectedHeight", chosen.getHeight());
                }
                output.put("playbackProfile", present ? settings.profile() : "headless_probe");
                media = selected.getContent();
            }
        } else {
            stage("fixture");
            var fixture = java.nio.file.Path.of(args[1]).toAbsolutePath();
            if (!java.nio.file.Files.isRegularFile(fixture)) throw new IllegalArgumentException("Missing fixture");
            media = fixture.toString();
            if (args[0].equals("--fixture-paired")) {
                var audioFixture = java.nio.file.Path.of(args[2]).toAbsolutePath();
                if (!java.nio.file.Files.isRegularFile(audioFixture)) throw new IllegalArgumentException("Missing audio fixture");
                audioMedia = audioFixture.toUri().toString();
                output.put("pairedAudio", true);
            }
        }
        ProbeRelay relay = null;
        if (network.usesRelay()) {
            relay = new ProbeRelay();
            String relaySource = media.startsWith("https:") || media.startsWith("http:") ? media : java.nio.file.Path.of(media).toUri().toString();
            media = relay.add("video",relaySource);
            if (audioMedia != null) audioMedia = relay.add("audio",audioMedia);
        }
        if (audioMedia != null) mediaOptions.add(":input-slave=" + audioMedia);
        stage("native_discovery");
        new uk.co.caprica.vlcj.factory.discovery.NativeDiscovery().discover();
        stage("native_factory");
        // Feasibility policy: explicitly select software avcodec for every media.
        // Automatic D3D11 decode crashed during stop after seeking on the measured host.
        var options = new ArrayList<>(List.of("--no-video-title-show", "--quiet", "--avcodec-hw=none", "--codec=avcodec,none", "--demux=avformat"));
        options.add("--network-caching=" + settings.networkCacheMs());
        output.put("networkCacheMs", settings.networkCacheMs());
        if (!present) options.addAll(List.of("--vout=dummy", "--aout=dummy"));
        var factory = new MediaPlayerFactory(options.toArray(String[]::new));
        MediaPlayer player = null;
        PresentationWindow presentation = null;
        try {
            stage("native_player");
            if (present) {
                var embedded = factory.mediaPlayers().newEmbeddedMediaPlayer();
                player = embedded;
                var capture = java.nio.file.Path.of(System.getenv().getOrDefault("DD_PLAYER_PRESENTATION_DIR",
                        scratch().resolve("presentation-" + UUID.randomUUID()).toString()));
                presentation = new PresentationWindow(factory, embedded, capture, settings.surfaceWidth());
            } else player = factory.mediaPlayers().newMediaPlayer();
            stage("native_play");
            if (presentation != null) presentation.mark("start");
            if (!player.media().play(media, mediaOptions.toArray(String[]::new))) throw new IOException("Start rejected");
            stage("decode");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            MediaStatistics stats = null;
            while (System.nanoTime() < deadline) {
                Thread.sleep(200);
                if (presentation != null) presentation.checkOpen();
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
            if (presentation != null) presentation.mark("pause");
            player.controls().setPause(true);
            Thread.sleep(500);
            long paused = player.status().time();
            Thread.sleep(700);
            boolean pauseStable = Math.abs(player.status().time() - paused) < 250;
            if (presentation != null) presentation.mark("resume");
            player.controls().setPause(false);
            Thread.sleep(1500);
            boolean resumed = player.status().time() > paused + 500;
            boolean seekable = player.status().isSeekable();
            stage("seek");
            var beforeSeek = player.media().info().statistics();
            output.put("beforeSeekDecodedVideo", beforeSeek.decodedVideo()).put("beforeSeekDecodedAudio", beforeSeek.decodedAudio());
            boolean seekWorked = false;
            long seekTarget = present ? 15000 : 7000;
            output.put("seekTargetMs", seekTarget);
            if (seekable && player.status().length() > seekTarget + 3000) {
                if (presentation != null) presentation.mark("seek");
                long beforePosition = player.status().time();
                long seekStarted = System.nanoTime();
                player.controls().setTime(seekTarget);
                MediaStatistics afterSeek = null;
                while (System.nanoTime() - seekStarted < TimeUnit.SECONDS.toNanos(10)) {
                    Thread.sleep(200);
                    if (presentation != null) presentation.checkOpen();
                    afterSeek = player.media().info().statistics();
                    long position = player.status().time();
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - seekStarted);
                    seekWorked = afterSeek != null && beforeSeek != null && seekReady(seekTarget, beforePosition,
                            elapsed, position, afterSeek.decodedVideo() > beforeSeek.decodedVideo()
                                    && afterSeek.decodedAudio() > beforeSeek.decodedAudio(),
                            !present || (afterSeek.audioBuffersPlayed() > beforeSeek.audioBuffersPlayed()
                                    && afterSeek.picturesDisplayed() > beforeSeek.picturesDisplayed()));
                    if (seekWorked) break;
                }
                output.put("postSeekPositionMs", player.status().time()).put("seekWaitMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - seekStarted))
                        .put("seekReadyClockAdvancedMs", player.status().time() - seekTarget);
                if (afterSeek != null) output.put("postSeekDecodedVideo", afterSeek.decodedVideo())
                        .put("postSeekDecodedAudio", afterSeek.decodedAudio());
            }
            output.put("pauseStable", pauseStable).put("resumeAdvanced", resumed)
                    .put("seekable", seekable).put("seekWorked", seekWorked)
                    .put("output", present ? "swing_video_native_audio" : "dummy_decode_only").put("avSyncProven", false)
                    .put("decoderPolicy", "software_avcodec").put("demuxPolicy", "avformat")
                    .put("javaVersion", System.getProperty("java.version"))
                    .put("jnaVersion", com.sun.jna.Native.VERSION)
                    .put("vlcVersion", factory.application().version());
            if (!pauseStable || !resumed || !seekWorked) {
                if (presentation != null) presentation.report(output);
                throw new IOException("Transport probe failed");
            }
            if (presentation != null) {
                stage("presentation_observe");
                presentation.mark("steady_after_seek");
                long observedStart = player.status().time();
                var observedStats = player.media().info().statistics();
                var samples = new org.json.JSONArray();
                long observationWallStart = System.nanoTime();
                long cpuStart = ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toMillis();
                long lastPosition = observedStart, stalledMs = 0, longestStallMs = 0;
                for (int i = 0; i < settings.observationSeconds(); i++) {
                    for (int tick = 0; tick < 5; tick++) { presentation.checkOpen(); Thread.sleep(200); }
                    if (network.usesRelay()) network.tick(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-observationWallStart),player,relay,presentation,media,mediaOptions.toArray(String[]::new));
                    long position = player.status().time();
                    var sampleStats = player.media().info().statistics();
                    stalledMs = position - lastPosition < 250 ? stalledMs + 1000 : 0;
                    longestStallMs = Math.max(longestStallMs, stalledMs);
                    lastPosition = position;
                    if (settings.observationSeconds() <= 60 || (i + 1) % 5 == 0) samples.put(new JSONObject().put("wallElapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - observationWallStart))
                            .put("positionMs", position).put("playing", player.status().isPlaying())
                            .put("decodedVideo", sampleStats.decodedVideo()).put("picturesDisplayed", sampleStats.picturesDisplayed())
                            .put("picturesLost", sampleStats.picturesLost()).put("audioBuffersPlayed", sampleStats.audioBuffersPlayed())
                            .put("inputBytesRead", sampleStats.inputBytesRead()));
                }
                output.put("observationSamples", samples).put("observationRequestedSeconds", settings.observationSeconds())
                        .put("observationWallMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - observationWallStart))
                        .put("longestStalledClockMs", longestStallMs)
                        .put("workerCpuMsDuringObservation", ProcessHandle.current().info().totalCpuDuration().orElse(Duration.ZERO).toMillis() - cpuStart);
                long observationWallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - observationWallStart);
                presentation.report(output);
                long observedEnd = player.status().time();
                var finalStats = player.media().info().statistics();
                output.put("observationStartMs", observedStart).put("observationEndMs", observedEnd)
                        .put("observationClockAdvancedMs", observedEnd - observedStart)
                        .put("picturesDisplayed", finalStats.picturesDisplayed())
                        .put("audioBuffersPlayed", finalStats.audioBuffersPlayed())
                        .put("audioBuffersLost", finalStats.audioBuffersLost());
                if (network.enabled()) network.requireSuccess();
                if (!network.enabled() && (observedEnd - observedStart < observationWallMs * .95 || longestStallMs > 2000
                        || finalStats.audioBuffersPlayed() <= observedStats.audioBuffersPlayed() + 100
                        || finalStats.picturesDisplayed() <= observedStats.picturesDisplayed() + 50)) {
                    throw new IOException("Post-seek presentation did not continue");
                }
            }
        } finally {
            // Completion is published only after every native resource has shut down cleanly.
            String failedStage = stage;
            if (network.enabled()) {
                network.finishRelayBytes(relay.bytes());
                output.put("networkScenario",network.report());
                if (presentation != null) try { presentation.report(output); } catch (Exception reportFailure) { output.put("presentationReportError",reportFailure.getClass().getSimpleName()); }
            }
            // Close the relay first: release any held reads before stopping native playback.
            if (relay != null) { relay.close(); output.put("relay",relay.report()); }
            stage("native_stop");
            if (player != null) {
                player.controls().stop();
                stage("native_release");
                player.release();
            }
            stage("factory_release");
            factory.release();
            if (presentation != null) presentation.close();
            output.put("cleanShutdown", true);
            stage(failedStage);
        }
        return null;
    }

    static boolean seekReady(long target, long beforePosition, long elapsedMs, long position,
                             boolean bothDecodersAdvanced, boolean bothOutputsAdvanced) {
        // setTime can publish the target while native preroll still holds audio and the clock.
        return position >= target + 500 && position < target + 5000
                && position > beforePosition + elapsedMs + 500
                && bothDecodersAdvanced && bothOutputsAdvanced;
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
