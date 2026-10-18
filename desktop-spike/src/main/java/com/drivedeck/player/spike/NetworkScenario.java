package com.drivedeck.player.spike;

import org.json.*;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import java.io.IOException;
import java.util.Map;

/** One explicitly requested fault/recovery experiment, never a background retry policy. */
final class NetworkScenario {
    final String mode;
    private final int downSeconds;
    private boolean down, restored, resumed, stalled, restarted;
    private long cutAt, upAt, resumeAt, cutPosition, checkpoint, referencePosition, referenceAudio, referencePictures;
    private long quietBytes, blockedByteDelta, referencePainted, cutPainted, recoveredPainted;
    private int steadySeconds, longestSteadySeconds;
    private long lastElapsedMs;
    private String terminalReason="";
    private final JSONArray events=new JSONArray();
    private final JSONArray recoverySamples=new JSONArray();
    private long referenceElapsed, referenceBytes;
    NetworkScenario(Map<String,String> env, int observeSeconds) {
        mode=env.getOrDefault("DD_PLAYER_NETWORK_FAULT","none");
        if (!java.util.Set.of("none","relay","hold","disconnect","permanent","cancel").contains(mode)) throw new IllegalArgumentException("Unknown fault");
        downSeconds=Integer.parseInt(env.getOrDefault("DD_PLAYER_NETWORK_DOWN_SECONDS","25"));
        if (downSeconds<5 || downSeconds>45 || (enabled() && observeSeconds<100)) throw new IllegalArgumentException("Fault observation needs 100s, outage 5..45s");
    }
    boolean enabled() { return !mode.equals("none") && !mode.equals("relay"); }
    boolean usesRelay() { return !mode.equals("none"); }
    private void event(String name, long elapsed, long position) { events.put(new JSONObject().put("event",name).put("elapsedMs",elapsed).put("positionMs",position)); }
    void tick(long elapsed, MediaPlayer player, ProbeRelay relay, PresentationWindow window, String media, String[] options) throws Exception {
        lastElapsedMs=elapsed;
        long position=player.status().time(); var stats=player.media().info().statistics();
        if (!down && elapsed>=15000) {
            down=true; cutPainted=window.paintedFrames(); cutAt=elapsed; cutPosition=checkpoint=referencePosition=position;
            referenceAudio=stats.audioBuffersPlayed(); referencePictures=stats.picturesDisplayed(); referencePainted=window.paintedFrames();
            window.mark("network_down"); relay.offline(!mode.equals("hold"));
            event("network_down",elapsed,position);
            return;
        }
        if (!down) return;
        if (!restored) {
            if (elapsed-cutAt>=1000 && quietBytes==0) quietBytes=relay.bytes();
            if (outputStalled(elapsed-cutAt,position-referencePosition,stats.audioBuffersPlayed()-referenceAudio)) {
                if (!stalled) event("output_stall_observed",elapsed,position);
                stalled=true;
            }
            if (position-referencePosition>250 && stats.audioBuffersPlayed()>referenceAudio && stats.picturesDisplayed()>referencePictures && window.paintedFrames()>referencePainted) checkpoint=position;
            referencePosition=position; referenceAudio=stats.audioBuffersPlayed(); referencePictures=stats.picturesDisplayed(); referencePainted=window.paintedFrames();
            if (mode.equals("cancel") && elapsed-cutAt>=5000) { terminalReason="cancelled_during_outage"; event("cancel_during_outage",elapsed,position); throw new InterruptedException("RequestedNetworkProbeCancellation"); }
            if (mode.equals("permanent") && elapsed-cutAt>=downSeconds*1000L+10000) throw failure("permanent_outage_deadline",elapsed,position);
            if (!mode.equals("permanent") && elapsed-cutAt>=downSeconds*1000L) {
                blockedByteDelta=relay.bytes()-quietBytes;
                relay.restore(); restored=true; upAt=elapsed;
                referencePosition=position; referenceAudio=stats.audioBuffersPlayed(); referencePictures=stats.picturesDisplayed(); referencePainted=window.paintedFrames();
                window.mark("network_up"); event("network_up",elapsed,position);
            }
        } else if (!resumed) {
            if (recoveryPosition(checkpoint,position,elapsed-upAt) && position-referencePosition>=500 && stats.audioBuffersPlayed()>referenceAudio+10 && stats.picturesDisplayed()>referencePictures+10 && window.paintedFrames()>referencePainted+10) {
                referenceElapsed=elapsed; referenceBytes=relay.bytes();
                referencePosition=position; referenceAudio=stats.audioBuffersPlayed(); referencePictures=stats.picturesDisplayed(); referencePainted=window.paintedFrames();
                resumed=true; recoveredPainted=window.paintedFrames(); resumeAt=elapsed; window.mark("network_recovered"); event("output_recovered",elapsed,position);
            } else if (elapsed-upAt>=10000 && !restarted) {
                restarted=true; event("explicit_recovery_restart",elapsed,checkpoint);
                player.controls().stop();
                if (!player.media().play(media,options)) throw new IOException("RecoveryStartRejected");
                // Opening can retain stale metadata; require a newly advancing clock before resume seek.
                long start=System.nanoTime(), minimumClock=Long.MAX_VALUE;
                boolean prepared=false;
                while (System.nanoTime()-start<10_000_000_000L) {
                    window.checkOpen(); Thread.sleep(100);
                    long clock=player.status().time();
                    if (clock>=0) minimumClock=Math.min(minimumClock,clock);
                    var preparing=player.media().info().statistics();
                    prepared=clock>=0 && minimumClock!=Long.MAX_VALUE && clock-minimumClock>=500 && player.status().isPlaying()
                            && player.status().length()>checkpoint+3000 && preparing!=null
                            && preparing.decodedVideo()>20 && preparing.decodedAudio()>10;
                    if (prepared) break;
                }
                if (!prepared) throw failure("recovery_input_not_ready",elapsed+(System.nanoTime()-start)/1_000_000,player.status().time());
                player.controls().setTime(checkpoint);
                referencePosition=checkpoint;
                var reset=player.media().info().statistics(); referenceAudio=reset.audioBuffersPlayed(); referencePictures=reset.picturesDisplayed(); referencePainted=window.paintedFrames();
            } else if (elapsed-upAt>=30000) throw failure("recovery_deadline",elapsed,position);
        } else {
            long painted=window.paintedFrames(), bytes=relay.bytes();
            if (recoverySamples.length()<300) recoverySamples.put(new JSONArray(new long[]{elapsed,position,position-referencePosition,stats.audioBuffersPlayed()-referenceAudio,stats.picturesDisplayed()-referencePictures,painted-referencePainted,bytes-referenceBytes,elapsed-referenceElapsed}));
            referenceElapsed=elapsed; referenceBytes=bytes;
            if (progressing(position-referencePosition,stats.audioBuffersPlayed()-referenceAudio,stats.picturesDisplayed()-referencePictures,painted-referencePainted)) steadySeconds++;
            else steadySeconds=0;
            longestSteadySeconds=Math.max(longestSteadySeconds,steadySeconds);
            referencePosition=position; referenceAudio=stats.audioBuffersPlayed(); referencePictures=stats.picturesDisplayed(); referencePainted=window.paintedFrames();
        }
    }
    private IOException failure(String reason,long elapsed,long position) {
        terminalReason=reason; event(reason,elapsed,position); return new IOException("NetworkRecoveryDeadline");
    }
    static boolean progressing(long clockDelta, long audioDelta, long pictureDelta, long paintDelta) {
        return clockDelta>=500 && audioDelta>0 && pictureDelta>0 && paintDelta>0;
    }
    static boolean recoveryPosition(long checkpoint, long position, long elapsedSinceRestore) {
        // Output at the beginning or an unrelated future position is not resume-at-checkpoint evidence.
        return position>=checkpoint+500 && position<=checkpoint+elapsedSinceRestore+5000;
    }
    static boolean outputStalled(long elapsedSinceCut, long clockDelta, long audioDelta) {
        return elapsedSinceCut>=1000 && clockDelta<250 && audioDelta==0;
    }
    void finishRelayBytes(long value) { if (down && !restored && quietBytes>0) blockedByteDelta=value-quietBytes; }
    JSONObject report() { return new JSONObject().put("mode",mode).put("outageRequestedSeconds",downSeconds).put("faultInjected",down)
            .put("stallObserved",stalled).put("restored",restored).put("recovered",resumed).put("explicitRestartAttempts",restarted?1:0)
            .put("paintedFramesAtCut",cutPainted).put("paintedFramesAtRecovery",recoveredPainted).put("terminalReason",terminalReason).put("lastObservationElapsedMs",lastElapsedMs).put("cutPositionMs",cutPosition).put("resumeCheckpointMs",checkpoint).put("blockedBytesAfterSettling",blockedByteDelta)
            .put("recoveryLatencyMs",resumed?resumeAt-upAt:-1).put("steadyRecoveredSeconds",steadySeconds).put("longestConsecutiveRecoveredSeconds",longestSteadySeconds).put("events",events).put("recoverySampleColumns",new JSONArray(java.util.List.of("elapsedMs","positionMs","clockDeltaMs","audioBufferDelta","pictureDelta","paintDelta","relayByteDelta","wallDeltaMs"))).put("recoverySamples",recoverySamples); }
    void requireSuccess() throws IOException {
        if (!down || !stalled || !resumed || longestSteadySeconds<30 || blockedByteDelta>32768) { terminalReason="recovery_not_proven"; throw new IOException("NetworkRecoveryNotProven"); }
    }
}
