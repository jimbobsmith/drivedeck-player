package com.drivedeck.player.spike;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

public class ProviderDenialWitnessTest {
 private final HttpClient client=HttpClient.newHttpClient();
 private int request(String url,String method,String range) throws Exception {
  var b=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).method(method,HttpRequest.BodyPublishers.noBody());
  if(range!=null)b.header("Range",range);
  return client.send(b.build(),HttpResponse.BodyHandlers.discarding()).statusCode();
 }
 @Test public void explicitFlagIsValidatedBeforeAnyProviderCall() {
  var failure=ProviderDenialWitness.diagnosticFailure(new IllegalStateException("private-token"));
  assertEquals("diagnostic_error",failure.getString("result"));assertFalse(failure.toString().contains("private-token"));
  assertFalse(ProviderDenialWitness.requested(Map.of(),true));
  assertTrue(ProviderDenialWitness.requested(Map.of("DD_PLAYER_PROVIDER_DENIAL_WITNESS","1"),true));
  assertThrows(IllegalArgumentException.class,()->ProviderDenialWitness.requested(Map.of("DD_PLAYER_PROVIDER_DENIAL_WITNESS","yes"),true));
  assertThrows(IllegalArgumentException.class,()->ProviderDenialWitness.requested(Map.of("DD_PLAYER_PROVIDER_DENIAL_WITNESS","1"),false));
 }
 @Test public void originalDenialIsForwardedAndAbaProbeIsPostCloseBoundedAndIdempotent() throws Exception {
  var count=new AtomicInteger();var ranges=new CopyOnWriteArrayList<String>();
  var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  upstream.createContext("/secret",e->{
   count.incrementAndGet();ranges.add(e.getRequestHeaders().getFirst("Range"));
   assertEquals("identity",e.getRequestHeaders().getFirst("Accept-Encoding"));
   assertNull(e.getRequestHeaders().getFirst("Cookie"));assertNull(e.getRequestHeaders().getFirst("Authorization"));
   int code=e.getRequestHeaders().getFirst("User-Agent").startsWith("com.google.visionos.youtube/")?206:403;
   byte[] body=new byte[4096];Arrays.fill(body,(byte)7);e.getResponseHeaders().set("Set-Cookie","secret");
   try {e.sendResponseHeaders(code,body.length);e.getResponseBody().write(body);}finally{e.close();}
  });upstream.start();
  try(var relay=new ProbeRelay(true)) {
   String url=relay.add("video","http://127.0.0.1:"+upstream.getAddress().getPort()+"/secret?c=VISIONOS&expire=1&token=secret");
   assertEquals(403,request(url,"GET","bytes=4-"));assertEquals(1,count.get());
   assertThrows(IllegalStateException.class,relay::runDenialWitness);
   relay.close();var report=relay.runDenialWitness();var rows=report.getJSONArray("requests");
   assertEquals("completed",report.getString("result"));assertEquals(3,rows.length());
   assertEquals(403,rows.getJSONObject(0).getInt("status"));assertEquals(206,rows.getJSONObject(1).getInt("status"));assertEquals(403,rows.getJSONObject(2).getInt("status"));
   for(int i=0;i<3;i++){assertEquals(1024,rows.getJSONObject(i).getInt("bytesRead"));assertEquals(4,rows.getJSONObject(i).getLong("rangeStart"));}
   assertEquals(4,count.get());assertEquals(List.of("bytes=4-","bytes=4-","bytes=4-","bytes=4-"),ranges);
   assertEquals(403,report.getJSONObject("originalDenial").getInt("status"));
   assertFalse(report.getBoolean("playbackRetriedByWitness"));assertFalse(report.getBoolean("rootCauseProven"));
   assertEquals(1,relay.report().getLong("requests"));assertEquals(1,relay.report().getJSONObject("upstreamHttpStatuses").getLong("403"));
   relay.runDenialWitness();assertEquals(4,count.get());assertFalse(report.toString().contains("secret"));
  }finally{upstream.stop(0);}
 }
 @Test public void defaultPolicyMakesNoWitnessCallsEvenAfterA403() throws Exception {
  var count=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  upstream.createContext("/deny",e->{count.incrementAndGet();e.sendResponseHeaders(403,-1);e.close();});upstream.start();
  try(var relay=new ProbeRelay()) {
   assertEquals(403,request(relay.add("audio","http://127.0.0.1:"+upstream.getAddress().getPort()+"/deny?c=VISIONOS"),"GET",null));
   relay.close();assertEquals("disabled",relay.runDenialWitness().getString("result"));assertEquals(1,count.get());
  }finally{upstream.stop(0);}
 }
 @Test public void onlyFirstDenialIsRetainedAndHeadShapeHasNoBodyOrRange() throws Exception {
  var methods=new CopyOnWriteArrayList<String>();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  upstream.createContext("/deny",e->{methods.add(e.getRequestMethod());assertNull(e.getRequestHeaders().getFirst("Range"));e.sendResponseHeaders(403,-1);e.close();});upstream.start();
  try(var relay=new ProbeRelay(true)) {
   String source="http://127.0.0.1:"+upstream.getAddress().getPort()+"/deny?c=UNKNOWN&token=secret";
   assertEquals(403,request(relay.add("audio",source),"HEAD","bytes=4-"));
   assertEquals(403,request(relay.add("video",source),"HEAD",null));
   relay.close();var report=relay.runDenialWitness();assertEquals("audio",report.getString("track"));
   assertEquals(2,report.getJSONArray("requests").length());assertEquals(List.of("HEAD","HEAD","HEAD","HEAD"),methods);
   assertEquals(0,report.getJSONArray("requests").getJSONObject(0).getInt("bytesRead"));
   assertEquals("other",report.getString("sourceClientClass"));assertFalse(report.toString().contains("UNKNOWN"));assertFalse(report.toString().contains("secret"));
  }finally{upstream.stop(0);}
 }
 @Test public void noDenialMeansNoDiagnosticTrafficAndOnlyAllowlistedClientIdentityIsExposed() throws Exception {
  try(var relay=new ProbeRelay(true)){relay.close();assertEquals("not_triggered",relay.runDenialWitness().getString("result"));}
  assertEquals("VISIONOS",ProviderDenialWitness.sourceClient("https://example.invalid/?c=VISIONOS&token=secret"));
  assertEquals("other",ProviderDenialWitness.sourceClient("https://example.invalid/?c=private-device-name"));
  var witness=new ProviderDenialWitness(true);witness.capture("https://example.invalid/",System.nanoTime(),null,"video","GET",null,new JSONObject().put("status",206));
  assertEquals("not_triggered",witness.run().getString("result"));
 }
 @Test public void diagnosticRedirectsCannotCreateAdditionalRequestsOrLeakLocations() throws Exception {
  var starts=new AtomicInteger();var targets=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  upstream.createContext("/redirect",e->{starts.incrementAndGet();e.getResponseHeaders().set("Location","/private?token=secret");e.sendResponseHeaders(302,-1);e.close();});
  upstream.createContext("/private",e->{targets.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});upstream.start();
  try {
   var witness=new ProviderDenialWitness(true);witness.capture("http://127.0.0.1:"+upstream.getAddress().getPort()+"/redirect",System.nanoTime(),null,"video","GET",null,new JSONObject().put("status",403));
   var report=witness.run();assertEquals(2,starts.get());assertEquals(0,targets.get());
   assertFalse(report.getBoolean("followRedirects"));assertFalse(report.getBoolean("retryOnConnectionFailure"));
   assertEquals(302,report.getJSONArray("requests").getJSONObject(0).getInt("status"));assertFalse(report.toString().contains("secret"));
  }finally{upstream.stop(0);}
 }
 @Test public void serviceUnavailableFollowUpCannotExceedOneExchangePerDiagnosticCall() throws Exception {
  var count=new AtomicInteger();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  upstream.createContext("/busy",e->{count.incrementAndGet();e.getResponseHeaders().set("Retry-After","0");e.sendResponseHeaders(503,-1);e.close();});upstream.start();
  try {
   var witness=new ProviderDenialWitness(true);witness.capture("http://127.0.0.1:"+upstream.getAddress().getPort()+"/busy",System.nanoTime(),null,"video","GET",null,new JSONObject().put("status",403));
   var report=witness.run();assertEquals(2,count.get());
   for(int i=0;i<2;i++){
    var row=report.getJSONArray("requests").getJSONObject(i);assertEquals(503,row.getInt("status"));
    assertEquals(1,row.getInt("networkExchangeAttempts"));assertTrue(row.getBoolean("followUpBlocked"));assertEquals("ProtocolException",row.getString("errorType"));
   }
  }finally{upstream.stop(0);}
 }
 @Test(timeout=12000) public void replayTimeoutsAreBoundedAndDoNotExposeExceptionMessages() throws Exception {
  var executor=Executors.newCachedThreadPool();var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.setExecutor(executor);
  upstream.createContext("/slow",e->{try{Thread.sleep(5000);e.sendResponseHeaders(403,-1);}catch(Exception ignored){}finally{e.close();}});upstream.start();
  try {
   var witness=new ProviderDenialWitness(true);
   witness.capture("http://127.0.0.1:"+upstream.getAddress().getPort()+"/slow?token=secret",System.nanoTime(),null,"video","GET",null,new JSONObject().put("status",403));
   long started=System.nanoTime();var report=witness.run();long ms=(System.nanoTime()-started)/1_000_000;
   assertTrue(ms<8000);assertEquals(2,report.getJSONArray("requests").length());
   assertTrue(report.getJSONArray("requests").getJSONObject(0).has("errorType"));assertFalse(report.toString().contains("secret"));
  }finally{upstream.stop(0);executor.shutdownNow();}
 }
}
