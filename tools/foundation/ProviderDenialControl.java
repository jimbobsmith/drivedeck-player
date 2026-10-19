package com.drivedeck.player.spike;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Local HTTP boundary controls only. Does not play media or reproduce a YouTube denial. */
public final class ProviderDenialControl {
 public static void main(String[] args) throws Exception {
  var cases=new JSONArray();
  for(String kind:List.of("header_sensitive","transient","persistent")) {
   var count=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   upstream.createContext("/private",e->{
    int n=count.incrementAndGet();boolean vision=e.getRequestHeaders().getFirst("User-Agent").startsWith("com.google.visionos.youtube/");
    int code=(kind.equals("header_sensitive")?vision:kind.equals("transient") && n>1)?206:403;
    byte[] body=new byte[4096];Arrays.fill(body,(byte)7);
    if(code==206)e.getResponseHeaders().set("Content-Range","bytes 4-4099/8192");
    e.getResponseHeaders().set("Set-Cookie","private-token");
    try{e.sendResponseHeaders(code,body.length);e.getResponseBody().write(body);}finally{e.close();}
   });upstream.start();
   try(var relay=new ProbeRelay(true)) {
    String url=relay.add("video","http://127.0.0.1:"+upstream.getAddress().getPort()+"/private?c=VISIONOS&expire=1&token=private-token");
    var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).header("Range","bytes=4-").build();
    var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.discarding());
    if(response.statusCode()!=403 || count.get()!=1)throw new IllegalStateException("OriginalDenialChanged");
    relay.close();var witness=relay.runDenialWitness();
    int[] expected=kind.equals("header_sensitive")?new int[]{403,206,403}:kind.equals("transient")?new int[]{206,206,206}:new int[]{403,403,403};
    var rows=witness.getJSONArray("requests");
    for(int i=0;i<3;i++)if(rows.getJSONObject(i).getInt("status")!=expected[i] || rows.getJSONObject(i).getInt("bytesRead")!=1024)throw new IllegalStateException("WitnessControlMismatch");
    if(count.get()!=4 || relay.report().getLong("requests")!=1)throw new IllegalStateException("WitnessTrafficAccounting");
    var value=new JSONObject().put("control",kind).put("originalHttpStatus",response.statusCode()).put("upstreamCalls",count.get())
      .put("relay",relay.report()).put("witness",witness);
    if(value.toString().contains("private-token") || value.toString().contains("http://"))throw new IllegalStateException("PrivateControlDataLeak");
    cases.put(value);
   }finally{upstream.stop(0);}
  }
  System.out.println(new JSONObject().put("result","passed").put("localHttpBoundaryOnly",true).put("nativePlaybackAttempted",false)
    .put("realYouTube403Reproduced",false).put("cases",cases).toString(2));
 }
}
