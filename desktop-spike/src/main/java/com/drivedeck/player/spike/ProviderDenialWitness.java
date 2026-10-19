package com.drivedeck.player.spike;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import okhttp3.*;
import org.json.*;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

/** Explicit post-shutdown HTTP experiment. Never feeds data or a retry into playback. */
final class ProviderDenialWitness {
    static final int BODY_LIMIT=1024, CALL_TIMEOUT_MS=3000;
    private final boolean enabled;
    private record Denial(String source,long registeredNs,Long expiry,String track,String method,String range,JSONObject original) {}
    private Denial first;
    private JSONObject completed;
    private static final class Attempt { int exchanges; Integer firstStatus; boolean followUpBlocked; }

    ProviderDenialWitness(boolean enabled) { this.enabled=enabled; }
    static boolean requested(Map<String,String> env,boolean relay) {
        String value=env.getOrDefault("DD_PLAYER_PROVIDER_DENIAL_WITNESS","0");
        if(!Set.of("0","1").contains(value)) throw new IllegalArgumentException("InvalidProviderWitnessFlag");
        if(value.equals("1") && !relay) throw new IllegalArgumentException("ProviderWitnessNeedsRelay");
        return value.equals("1");
    }
    synchronized void capture(String source,long registeredNs,Long expiry,String track,String method,String range,JSONObject original) {
        if(!enabled || first!=null || completed!=null || original.optInt("status")!=403) return;
        if(!Set.of("video","audio").contains(track) || !Set.of("GET","HEAD").contains(method)) throw new IllegalArgumentException("WitnessRequestShape");
        if(range!=null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw new IllegalArgumentException("WitnessRange");
        first=new Denial(source,registeredNs,expiry,track,method,method.equals("HEAD")?null:range,new JSONObject(original.toString()));
    }
    static JSONObject diagnosticFailure(Throwable failure) {
        return new JSONObject().put("enabled",true).put("result","diagnostic_error")
            .put("errorType",failure.getClass().getSimpleName()).put("playbackRetriedByWitness",false)
            .put("rootCauseProven",false).put("requests",new JSONArray());
    }
    static String sourceClient(String source) {
        try {
            String query=URI.create(source).getRawQuery();
            if(query!=null) for(String part:query.split("&")) if(part.startsWith("c=")) {
                String value=part.substring(2);
                return Set.of("VISIONOS","WEB","MWEB","ANDROID","IOS","TVHTML5","TVHTML5_SIMPLY_EMBEDDED_PLAYER").contains(value)?value:"other";
            }
        } catch(IllegalArgumentException ignored) {}
        return "other";
    }
    synchronized JSONObject run() {
        if(completed!=null) return new JSONObject(completed.toString());
        var report=new JSONObject().put("enabled",enabled).put("playbackRetriedByWitness",false).put("rawBodySaved",false)
            .put("sourceUrlSaved",false).put("maximumBytesReadPerRequest",BODY_LIMIT)
            .put("callTimeoutMs",CALL_TIMEOUT_MS).put("maximumRequests",3).put("followRedirects",false).put("retryOnConnectionFailure",false).put("rootCauseProven",false);
        Denial context=first;first=null; // Release the field; this private local context lives until the run returns.
        if(!enabled || context==null) {
            completed=report.put("result",enabled?"not_triggered":"disabled").put("requests",new JSONArray());
            return new JSONObject(completed.toString());
        }
        String sourceClient=sourceClient(context.source());
        report.put("originalDenial",context.original()).put("track",context.track()).put("sourceClientClass",sourceClient);
        var rows=new JSONArray();
        var client=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(2))
            .callTimeout(Duration.ofMillis(CALL_TIMEOUT_MS)).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addNetworkInterceptor(chain -> {
                var attempt=chain.request().tag(Attempt.class);
                if(attempt==null || attempt.exchanges!=0) {
                    if(attempt!=null) attempt.followUpBlocked=true;
                    throw new java.net.ProtocolException("DiagnosticFollowUpBlocked");
                }
                attempt.exchanges=1;
                var response=chain.proceed(chain.request());
                attempt.firstStatus=response.code();
                return response;
            }).build();
        try {
            var profiles=sourceClient.equals("VISIONOS")?List.of("relay_default_first","visionos_user_agent","relay_default_second")
                    :List.of("relay_default_first","relay_default_second");
            for(String profile:profiles) {
                if(Thread.currentThread().isInterrupted()) { report.put("interrupted",true);break; }
                long started=System.nanoTime();
                var row=ProbeRelay.rangeReport(context.range()).put("profile",profile).put("method",context.method())
                    .put("sourceAgeMs",(started-context.registeredNs())/1_000_000).put("expiryKnown",context.expiry()!=null);
                if(context.expiry()!=null) row.put("expirySecondsAtRequest",context.expiry()-java.time.Instant.now().getEpochSecond());
                var attempt=new Attempt();
                try {
                    var request=new Request.Builder().url(context.source()).tag(Attempt.class,attempt).method(context.method(),null).header("Accept-Encoding","identity");
                    if(context.range()!=null) request.header("Range",context.range());
                    if(profile.equals("visionos_user_agent")) request.header("User-Agent",YoutubeParsingHelper.getVisionOsUserAgent(Localization.DEFAULT));
                    try(var response=client.newCall(request.build()).execute()) {
                        row.put("status",response.code());
                        row.put("bytesRead",context.method().equals("HEAD") || response.body()==null ? 0 : response.body().byteStream().readNBytes(BODY_LIMIT).length);
                        int redirects=0;for(var prior=response.priorResponse();prior!=null;prior=prior.priorResponse()) redirects++;
                        row.put("redirectCount",redirects);
                    }
                } catch(Exception failure) { row.put("errorType",failure.getClass().getSimpleName()); }
                if(attempt.firstStatus!=null) row.put("status",attempt.firstStatus);
                row.put("networkExchangeAttempts",attempt.exchanges).put("followUpBlocked",attempt.followUpBlocked);
                rows.put(row.put("elapsedMs",(System.nanoTime()-started)/1_000_000));
            }
        } finally { client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow(); }
        // 'completed' means the experiment ended, not that a request or the original playback succeeded.
        completed=report.put("result","completed").put("requests",rows);
        return new JSONObject(completed.toString());
    }
}
