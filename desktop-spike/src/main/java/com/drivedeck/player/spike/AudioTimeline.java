package com.drivedeck.player.spike;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.json.JSONObject;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.log.*;
import uk.co.caprica.vlcj.player.base.MediaPlayer;

/** Opt-in bounded observation. Never calls VLC or performs IO on a native log callback. */
final class AudioTimeline {
    static final int SAMPLE_LIMIT=1600, EVENT_LIMIT=2000;
    private static final String SAMPLE_HEADER="epochMs,monotonicMs,clockMs,decodedAudio,audioBuffers,audioLost,decodedVideo,pictures,inputBytes,demuxBytes";
    private static final String EVENT_HEADER="epochMs,monotonicMs,event,value";
    private static final Map<String,Pattern> NUMERIC_EVENTS = Map.ofEntries(
        Map.entry("late_flush",Pattern.compile("playback (?:way )?too late \\((-?[0-9]+)\\): flushing buffers")),
        Map.entry("early_silence",Pattern.compile("playback way too early \\((-?[0-9]+)\\): playing silence")),
        Map.entry("insert_zero_frames",Pattern.compile("inserting ([0-9]+) zeroes")),
        Map.entry("late_upsample",Pattern.compile("playback too late \\((-?[0-9]+)\\): up-sampling")),
        Map.entry("early_downsample",Pattern.compile("playback too early \\((-?[0-9]+)\\): down-sampling")),
        Map.entry("resampling_stop",Pattern.compile("resampling stopped \\(drift: (-?[0-9]+) us\\)")),
        Map.entry("resampling_abort",Pattern.compile("timing screwed \\(drift: (-?[0-9]+) us\\): stopping resampling")),
        Map.entry("late_drop",Pattern.compile("buffer too late \\((-?[0-9]+) us\\): dropped")),
        Map.entry("early_drop",Pattern.compile("buffer too early \\((-?[0-9]+) us\\): dropped")),
        Map.entry("output_buffer_frames",Pattern.compile("buffer size    : ([0-9]+) frames")),
        Map.entry("mixer_sample_rate",Pattern.compile("output '[A-Za-z0-9 ]{4}' ([0-9]+) Hz (?:Mono|Stereo) frame=[0-9]+ samples/[0-9]+ bytes")),
        Map.entry("output_sample_rate",Pattern.compile("nSamplesPerSec ([0-9]+)")));
    private static final Map<String,String> FIXED_EVENTS=Map.of(
        "using audio output module \"mmdevice\"","selected_mmdevice",
        "using audio output module \"directsound\"","selected_directsound",
        "using aout stream module \"wasapi\"","selected_wasapi",
        "reset","output_reset", "end of audio preroll","audio_preroll_end",
        "discarded audio buffer","audio_discard", "non-dated audio buffer received","audio_undated");
    record Event(String name,long value) {}
    static Event parse(String module,String message) {
        if (module==null || !Set.of("main","wasapi","mmdevice","directsound").contains(module) || message==null || message.length()>160) return null;
        if (module.equals("directsound")) return "Opening DirectSound Audio Output".equals(message) ? new Event("directsound_open",0) : null;
        // The generic 'reset' is accepted only from the WASAPI output module.
        if (message.equals("reset")) return module.equals("wasapi") ? new Event("output_reset",0) : null;
        var fixed=FIXED_EVENTS.get(message);
        if (fixed!=null && module.equals("main")) return new Event(fixed,0);
        if (module.equals("mmdevice")) return null;
        for (var entry:NUMERIC_EVENTS.entrySet()) {
            boolean output=entry.getKey().startsWith("output_");
            if (output!=module.equals("wasapi")) continue;
            var matcher=entry.getValue().matcher(message);
            if (matcher.matches()) {
                try { return new Event(entry.getKey(),Long.parseLong(matcher.group(1))); }
                catch(NumberFormatException ignored) { return null; }
            }
        }
        return null;
    }
    private final Path directory;
    private final NativeLog log;
    private final long start=System.nanoTime();
    private final List<String> samples=new ArrayList<>(), events=new ArrayList<>();
    private int droppedEvents,droppedSamples;
    private boolean detached;
    AudioTimeline(MediaPlayerFactory factory,Path directory) {
        this.directory=directory;
        log=factory.application().newLog();
        log.setLevel(LogLevel.DEBUG);
        log.addLogListener((level,module,file,line,name,header,id,message)->record(module,message));
    }
    private long monotonicMs() { return (System.nanoTime()-start)/1_000_000; }
    private synchronized void record(String module,String message) {
        if(detached) return;
        var event=parse(module,message);
        if(event==null) return;
        if(events.size()>=EVENT_LIMIT) { droppedEvents++; return; }
        events.add(System.currentTimeMillis()+","+monotonicMs()+","+event.name()+","+event.value());
    }
    void sample(MediaPlayer player) {
        if(samples.size()>=SAMPLE_LIMIT) { droppedSamples++; return; }
        var stats=player.media().info().statistics();
        if(stats==null) return;
        samples.add(System.currentTimeMillis()+","+monotonicMs()+","+player.status().time()+","+stats.decodedAudio()+","+stats.audioBuffersPlayed()+","+stats.audioBuffersLost()+","+stats.decodedVideo()+","+stats.picturesDisplayed()+","+stats.inputBytesRead()+","+stats.demuxBytesRead());
    }
    void detach() {
        synchronized(this) { detached=true; }
        log.release();
    }
    synchronized void save(JSONObject report) throws IOException {
        Files.writeString(directory.resolve("audio-timeline.csv"),SAMPLE_HEADER+"\n"+String.join("\n",samples)+"\n");
        Files.writeString(directory.resolve("audio-native-events.csv"),EVENT_HEADER+"\n"+String.join("\n",events)+"\n");
        report.put("audioTimeline",new JSONObject().put("optIn",true).put("samplePeriodTargetMs",200)
            .put("samples",samples.size()).put("events",events.size()).put("droppedSamples",droppedSamples)
            .put("droppedEvents",droppedEvents).put("rawMessagesSaved",false).put("outputConsumptionClock",false)
            .put("nativeLogDetached",detached));
    }
}
