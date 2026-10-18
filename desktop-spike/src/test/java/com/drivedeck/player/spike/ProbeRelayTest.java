package com.drivedeck.player.spike;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class ProbeRelayTest {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Path temp(String suffix) throws java.io.IOException {
        var directory=Path.of(System.getenv().getOrDefault("DD_PLAYER_SCRATCH","D:/tmp/dd/player-desktop-spike"),"relay-tests");
        Files.createDirectories(directory); return Files.createTempFile(directory,"relay-",suffix);
    }
    private HttpResponse<byte[]> get(String url,String range) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3));
        if (range!=null) b.header("Range",range);
        return client.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    @Test public void fileRangeSuffixHeadAndUnsatisfiableAreCorrect() throws Exception {
        var p=temp(".mp4"); byte[] data=new byte[10000]; new Random(42).nextBytes(data); Files.write(p,data);
        try(var relay=new ProbeRelay()) {
            String url=relay.add("video",p.toUri().toString());
            var partial=get(url,"bytes=20-39"); assertEquals(206,partial.statusCode()); assertArrayEquals(Arrays.copyOfRange(data,20,40),partial.body());
            assertEquals("bytes 20-39/10000",partial.headers().firstValue("Content-Range").orElseThrow());
            assertArrayEquals(Arrays.copyOfRange(data,9990,10000),get(url,"bytes=-10").body());
            assertEquals(416,get(url,"bytes=10000-").statusCode());
            assertEquals(400,get(url,"bytes=0-1,3-4").statusCode());
            var head=client.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD",HttpRequest.BodyPublishers.noBody()).header("Range","bytes=20-39").build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,head.statusCode()); assertEquals(0,head.body().length); assertEquals("10000",head.headers().firstValue("Content-Length").orElseThrow());
        } finally {Files.deleteIfExists(p);}
    }
    @Test public void inaccessibleRoutesDoNotBecomeAnOpenProxy() throws Exception {
        try(var relay=new ProbeRelay()) {
            String url=relay.add("video","https://example.invalid/private?token=secret");
            assertEquals(404,get(url+"?url=https://example.invalid",null).statusCode());
            assertEquals(404,get(url+"/extra",null).statusCode());
            assertFalse(relay.report().toString().contains("secret"));
        }
    }
    @Test public void forwardsRemoteRangesWithoutLeakingProviderHeaders() throws Exception {
        var upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/secret",e->{
            assertEquals("bytes=4-7",e.getRequestHeaders().getFirst("Range"));
            e.getResponseHeaders().set("Content-Range","bytes 4-7/100"); e.getResponseHeaders().set("Set-Cookie","private");
            e.sendResponseHeaders(206,4); e.getResponseBody().write(new byte[]{4,5,6,7}); e.close();
        });upstream.start();
        try(var relay=new ProbeRelay()) {
            var response=get(relay.add("video","http://127.0.0.1:"+upstream.getAddress().getPort()+"/secret?token=secret"),"bytes=4-7");
            assertEquals(206,response.statusCode()); assertArrayEquals(new byte[]{4,5,6,7},response.body());
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty()); assertFalse(relay.report().toString().contains("secret"));
        } finally {upstream.stop(0);}
    }
    @Test public void offlineRejectsNewRequestsThenRestoresRangePlayback() throws Exception {
        var p=temp(".mp4");Files.write(p,new byte[]{1,2,3});
        try(var relay=new ProbeRelay()) {
            String url=relay.add("video",p.toUri().toString());relay.offline(true);
            assertEquals(503,get(url,null).statusCode()); relay.restore(); assertArrayEquals(new byte[]{2,3},get(url,"bytes=1-").body());
        } finally {Files.deleteIfExists(p);}
    }
    @Test public void closingDuringHeldStreamTerminatesOwnedHandlers() throws Exception {
        var p=temp(".mp4");Files.write(p,new byte[1024*1024]);
        var relay=new ProbeRelay();
        try {
            var response=client.send(HttpRequest.newBuilder(URI.create(relay.add("video",p.toUri().toString()))).build(),HttpResponse.BodyHandlers.ofInputStream());
            response.body().readNBytes(16384); relay.offline(false);
            long started=System.nanoTime();relay.close();
            assertTrue(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(4));
            assertTrue(relay.report().getBoolean("closed")); assertEquals(0,relay.report().getInt("activeRequests")); response.body().close();
        } finally {relay.close();Files.deleteIfExists(p);}
    }
    @Test public void heldStreamCanContinueWithoutByteLoss() throws Exception {
        var p=temp(".mp4");byte[] data=new byte[300000];new Random(7).nextBytes(data);Files.write(p,data);
        try(var relay=new ProbeRelay()) {
            var response=client.send(HttpRequest.newBuilder(URI.create(relay.add("video",p.toUri().toString()))).build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] first=response.body().readNBytes(16384); relay.offline(false);Thread.sleep(300);
            long bytes=relay.bytes();Thread.sleep(200);assertEquals(bytes,relay.bytes());relay.restore();long restoredAt=System.nanoTime();
            byte[] rest=response.body().readAllBytes();byte[] joined=new byte[first.length+rest.length];System.arraycopy(first,0,joined,0,first.length);System.arraycopy(rest,0,joined,first.length,rest.length);
            assertArrayEquals(data,joined);assertTrue(System.nanoTime()-restoredAt<TimeUnit.SECONDS.toNanos(2));response.body().close();
        } finally {Files.deleteIfExists(p);}
    }
}
