package com.drivedeck.player.spike;

import com.sun.net.httpserver.*;
import okhttp3.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Disposable, loopback-only fault injector. Upstream URLs live only in this worker's memory. */
final class ProbeRelay implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().name("probe-relay-", 0).factory());
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10)).followRedirects(true).build();
    private final Map<String,String> sources = new ConcurrentHashMap<>();
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Set<HttpExchange> exchanges = ConcurrentHashMap.newKeySet();
    private final AtomicLong bytes = new AtomicLong(), requests = new AtomicLong(), rejected = new AtomicLong(), failures = new AtomicLong();
    private final Map<Integer,AtomicLong> statuses=new ConcurrentHashMap<>();
    private final String prefix = "/" + UUID.randomUUID() + "/";
    private final Object gate = new Object();
    private volatile boolean offline, closed, restoredOnce;
    ProbeRelay() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(executor);
        server.createContext("/", this::serve);
        server.start();
    }
    String add(String name, String source) {
        if (!Set.of("video", "audio").contains(name)) throw new IllegalArgumentException("Unknown track");
        sources.put(prefix + name, source);
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefix + name;
    }
    void offline(boolean disconnect) {
        offline = true;
        if (disconnect) { calls.forEach(Call::cancel); exchanges.forEach(HttpExchange::close); }
    }
    void restore() { synchronized (gate) { restoredOnce=true; offline = false; gate.notifyAll(); } }
    private void awaitOnline() throws IOException {
        synchronized (gate) {
            while (offline && !closed) try { gate.wait(100); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("RelayCancelled"); }
        }
        if (closed) throw new IOException("RelayClosed");
    }
    private void serve(HttpExchange exchange) {
        exchanges.add(exchange);
        try {
            String source = sources.get(exchange.getRequestURI().getRawPath());
            if (source == null || exchange.getRequestURI().getRawQuery() != null) { reject(exchange,404); return; }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) { reject(exchange,405); return; }
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) { reject(exchange,400); return; }
            if (method.equals("HEAD")) range=null;
            if (offline || closed) { reject(exchange,503); return; }
            requests.incrementAndGet();
            URI uri = URI.create(source);
            if (uri.getScheme().equals("file")) serveFile(exchange, Path.of(uri), range, method.equals("HEAD"));
            else serveRemote(exchange, source, range, method);
        } catch (IOException | RuntimeException failure) {
            failures.incrementAndGet(); // Never log exception messages or provider URLs.
        } finally { exchanges.remove(exchange); exchange.close(); }
    }
    private void reject(HttpExchange exchange, int code) throws IOException { rejected.incrementAndGet(); exchange.sendResponseHeaders(code,-1); }
    private void serveRemote(HttpExchange exchange, String source, String range, String method) throws IOException {
        var request = new okhttp3.Request.Builder().url(source).method(method,null).header("Accept-Encoding","identity");
        if (range != null) request.header("Range",range);
        var call = client.newCall(request.build()); calls.add(call);
        try (var response = call.execute()) {
            statuses.computeIfAbsent(response.code(),ignored->new AtomicLong()).incrementAndGet();
            for (String name : List.of("Content-Type","Content-Range","Accept-Ranges","Content-Length")) {
                String value = response.header(name); if (value != null) exchange.getResponseHeaders().set(name,value);
            }
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            long length = response.body() == null ? 0 : response.body().contentLength();
            exchange.sendResponseHeaders(response.code(), method.equals("HEAD") || length == 0 ? -1 : Math.max(0,length));
            if (!method.equals("HEAD") && response.body() != null) copy(response.body().byteStream(),exchange.getResponseBody(),length,0);
        } finally { calls.remove(call); }
    }
    private void serveFile(HttpExchange exchange, Path path, String range, boolean head) throws IOException {
        long size = Files.size(path), start=0, end=size-1;
        try {
            if (range != null) {
                var parts = range.substring(6).split("-",-1);
                if (parts[0].isEmpty()) start = Math.max(0,size-Long.parseLong(parts[1]));
                else { start=Long.parseLong(parts[0]); if (!parts[1].isEmpty()) end=Math.min(end,Long.parseLong(parts[1])); }
            }
            if (start < 0 || start >= size || end < start) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            exchange.getResponseHeaders().set("Content-Range","bytes */"+size); reject(exchange,416); return;
        }
        long length=end-start+1;
        var headers=exchange.getResponseHeaders();
        headers.set("Accept-Ranges","bytes"); headers.set("Cache-Control","no-store");
        headers.set("Content-Type",path.toString().endsWith(".m4a") ? "audio/mp4" : "video/mp4");
        headers.set("Content-Length",Long.toString(length));
        if (range != null) headers.set("Content-Range","bytes "+start+"-"+end+"/"+size);
        exchange.sendResponseHeaders(range==null?200:206,head?-1:length);
        if (!head) try (var input=Files.newInputStream(path)) {
            input.skipNBytes(start);
            // Synthetic files are paced to avoid downloading the entire small fixture before the fault.
            // Tail/metadata requests remain quick. This pacing is not used for actual provider streams.
            long pace = start > size-262144 ? 0 : Math.max(65536,size/100);
            copy(input,exchange.getResponseBody(),length,pace);
        }
    }
    private void copy(InputStream input, OutputStream output, long remaining, long pace) throws IOException {
        byte[] buffer=new byte[16384]; long sent=0, started=System.nanoTime();
        while (remaining != 0) {
            awaitOnline();
            int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining<0?buffer.length:remaining));
            if (count < 0) break;
            awaitOnline(); output.write(buffer,0,count); output.flush(); bytes.addAndGet(count);
            sent+=count; if (remaining>0) remaining-=count;
            if (pace>0 && !restoredOnce) {
                long wait=sent*1_000_000_000L/pace-(System.nanoTime()-started);
                if (wait>0) try { TimeUnit.NANOSECONDS.sleep(Math.min(wait,TimeUnit.MILLISECONDS.toNanos(250))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("RelayCancelled"); }
            }
        }
    }
    long bytes() { return bytes.get(); }
    JSONObject report() { return new JSONObject().put("bytesForwarded",bytes()).put("requests",requests.get())
            .put("rejectedRequests",rejected.get()).put("streamFailures",failures.get()).put("activeRequests",exchanges.size())
            .put("loopbackOnly",true).put("closed",closed).put("upstreamHttpStatuses",new JSONObject(statuses.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().toString(),e->e.getValue().get())))); }
    @Override public void close() {
        closed=true; restore(); calls.forEach(Call::cancel); exchanges.forEach(HttpExchange::close); server.stop(0);
        executor.shutdownNow(); client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
        try { executor.awaitTermination(3,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
