package com.drivedeck.player.spike;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import okhttp3.*;
import org.json.*;
import org.schabi.newpipe.extractor.*;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.stream.*;
/** Standalone bounded diagnostic tool. Compile into disposable storage using the installed spike classpath. */
public class ProviderRequestProbe {
 static final String UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36";
 static JSONObject request(OkHttpClient client,String url,String method,String profile,String rangeProfile) {
  long start=System.nanoTime(); var row=new JSONObject().put("method",method).put("headerProfile",profile).put("rangeProfile",rangeProfile);
  try {
   var b=new okhttp3.Request.Builder().url(url).method(method,null).header("Accept-Encoding","identity");
   if(method.equals("GET") && !rangeProfile.equals("none")) b.header("Range",rangeProfile.equals("open")?"bytes=0-":"bytes=0-1023");
   if(profile.equals("extractor_user_agent")) b.header("User-Agent",UA);
   try(var response=client.newCall(b.build()).execute()) {
    row.put("status",response.code()).put("declaredBodyBytes",response.body()==null?-1:response.body().contentLength())
     .put("bytesRead",method.equals("HEAD")||response.body()==null?0:response.body().byteStream().readNBytes(1024).length);
    var m=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(response.header("Content-Range",""));
    if(m.matches()) row.put("rangeStart",Long.parseLong(m.group(1))).put("rangeEnd",Long.parseLong(m.group(2))).put("rangeTotal",Long.parseLong(m.group(3)));
    try{row.put("serverClockSkewSeconds",ZonedDateTime.parse(response.header("Date"),DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()-Instant.now().getEpochSecond());}catch(Exception ignored){}
    int redirects=0;for(var prior=response.priorResponse();prior!=null;prior=prior.priorResponse())redirects++;
    row.put("redirects",redirects);
   }
  }catch(Exception e){row.put("errorType",e.getClass().getSimpleName());}
  return row.put("elapsedMs",(System.nanoTime()-start)/1000000);
 }
 public static void main(String[] args) {
  var standardOut=System.out;
  var watchdog=Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(120000);standardOut.println("{\"result\":\"failed\",\"errorType\":\"ProviderProbeDeadline\"}");System.exit(124);}catch(InterruptedException ignored){}});
  System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
  var result=new JSONObject().put("schemaVersion",1).put("publicVideoId","aqz-KE-bpKQ").put("rawBodySaved",false).put("accountCredentialsSupplied",false);
  var client=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(5)).callTimeout(Duration.ofSeconds(8)).build();
  try {
   var c=Class.forName("com.drivedeck.player.spike.DesktopSpike$DesktopDownloader").getDeclaredConstructor();c.setAccessible(true);
   try(var downloader=(AutoCloseable)c.newInstance()) {
    NewPipe.init((Downloader)downloader);var info=StreamInfo.getInfo("https://www.youtube.com/watch?v=aqz-KE-bpKQ");
    var video=info.getVideoOnlyStreams().stream().filter(s->s.getFormat()==MediaFormat.MPEG_4&&s.getHeight()==1080&&s.getFps()==60&&s.getCodec().startsWith("avc")&&s.getDeliveryMethod()==DeliveryMethod.PROGRESSIVE_HTTP).findFirst().orElseThrow();
    var audio=info.getAudioStreams().stream().filter(s->s.getFormat()==MediaFormat.M4A&&s.getDeliveryMethod()==DeliveryMethod.PROGRESSIVE_HTTP).findFirst().orElseThrow();
    var tracks=new JSONArray();
    for(var entry:List.of(Map.entry("video",(Stream)video),Map.entry("audio",(Stream)audio))) {
     String url=entry.getValue().getContent();long resolvedAt=System.nanoTime();var query=URI.create(url).getRawQuery();var params=new HashMap<String,String>();
     if(query!=null)for(var part:query.split("&")){var pair=part.split("=",2);if(pair.length==2)params.put(pair[0],pair[1]);}
     var track=new JSONObject().put("track",entry.getKey()).put("itag",entry.getKey().equals("video")?video.getItag():audio.getItag()).put("nParameterPresent",params.containsKey("n")).put("rangeQueryPresent",params.containsKey("range"));
     try{track.put("expirySecondsFromProbe",Long.parseLong(params.get("expire"))-Instant.now().getEpochSecond());}catch(Exception ignored){}
     String clientClass=params.getOrDefault("c","unknown");
     track.put("sourceClientClass",Set.of("WEB","MWEB","ANDROID","ANDROID_VR","TVHTML5","TVHTML5_SIMPLY_EMBEDDED_PLAYER","WEB_EMBEDDED_PLAYER","IOS").contains(clientClass)?clientClass:"other")
       .put("proofTokenParameterPresent",params.containsKey("pot")).put("ipBindingParameterPresent",params.containsKey("ip"));
     var rows=new JSONArray();
     for(String profile:List.of("relay_default","extractor_user_agent")) {
      rows.put(request(client,url,"HEAD",profile,"none"));
      for(String rangeProfile:List.of("closed","open","none")) rows.put(request(client,url,"GET",profile,rangeProfile));
     }
     try(var relay=new ProbeRelay()) {var local=relay.add(entry.getKey(),url);rows.put(request(client,local,"GET","loopback_relay_default","open"));relay.close();track.put("relay",relay.report());}
     track.put("requests",rows).put("probeSpanMs",(System.nanoTime()-resolvedAt)/1000000);tracks.put(track);
    }
    result.put("tracks",tracks).put("result","completed");
   }
  }catch(Throwable e){result.put("result","failed").put("errorType",e.getClass().getSimpleName());}
  finally{client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow();}
  watchdog.interrupt();standardOut.println(result.toString(2));System.exit(result.optString("result").equals("completed")?0:1);
 }
}
