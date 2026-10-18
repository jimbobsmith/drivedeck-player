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
    private record Source(String location,long registeredNs,Long expiryEpochSeconds) {}
    private final Map<String,Source> sources = new ConcurrentHashMap<>();
    private final List<JSONObject> upstreamResponses = new ArrayList<>();
    private int droppedResponses;
    static Long expiry(String source) {
        try {
            String query=URI.create(source).getRawQuery();
            if(query!=null) for(String part:query.split("&")) if(part.matches("expire=[0-9]{1,19}")) return Long.parseLong(part.substring(7));
        } catch(IllegalArgumentException ignored) {}
        return null;
    }
    private synchronized void response(JSONObject value) { if(upstreamResponses.size()<64) upstreamResponses.add(value); else droppedResponses++; }
    private synchronized JSONObject responseReport() { return new JSONObject().put("upstreamResponses",new org.json.JSONArray(upstreamResponses)).put("droppedUpstreamResponses",droppedResponses); }
    static JSONObject rangeReport(String range) {
        var row=new JSONObject().put("rangeKind","none");
        if(range==null) return row;
        var parts=range.substring(6).split("-",-1);
        try {
            if(parts[0].isEmpty()) return row.put("rangeKind","suffix").put("suffixBytes",Long.parseLong(parts[1]));
            row.put("rangeKind",parts[1].isEmpty()?"open":"closed").put("rangeStart",Long.parseLong(parts[0]));
            if(!parts[1].isEmpty()) row.put("rangeEnd",Long.parseLong(parts[1]));
        } catch(NumberFormatException ignored) { return new JSONObject().put("rangeKind","unrepresentable"); }
        return row;
    }
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Set<HttpExchange> exchanges = ConcurrentHashMap.newKeySet();
    private final AtomicLong bytes = new AtomicLong(), requests = new AtomicLong(), rejected = new AtomicLong(), failures = new AtomicLong();
    record TrackSnapshot(long bytes, long readMs, long writeMs, long pendingReadMs, long pendingWriteMs, long requests, long failures) { }
    private static final class TrackMeter {
        final String label;
        TrackMeter(String label) { this.label=label; }
        final AtomicLong bytes=new AtomicLong(), readNs=new AtomicLong(), writeNs=new AtomicLong(), requests=new AtomicLong(), failures=new AtomicLong();
        final Set<Transfer> transfers=ConcurrentHashMap.newKeySet();
        TrackSnapshot snapshot() {
            long now=System.nanoTime(), pending=0, pendingWrite=0;
            for (var transfer:transfers) { long since=transfer.readStarted; if(since!=0) pending=Math.max(pending,(now-since)/1_000_000); since=transfer.writeStarted; if(since!=0) pendingWrite=Math.max(pendingWrite,(now-since)/1_000_000); }
            return new TrackSnapshot(bytes.get(),readNs.get()/1_000_000,writeNs.get()/1_000_000,pending,pendingWrite,requests.get(),failures.get());
        }
    }
    private static final class Transfer {
        final TrackMeter meter;
        volatile long readStarted, writeStarted;
        Transfer(TrackMeter meter) { this.meter=meter; meter.requests.incrementAndGet(); meter.transfers.add(this); }
        int read(InputStream input, byte[] buffer, int length) throws IOException {
            readStarted=System.nanoTime();
            try { return input.read(buffer,0,length); }
            finally { meter.readNs.addAndGet(System.nanoTime()-readStarted); readStarted=0; }
        }
        void write(OutputStream output, byte[] buffer, int count) throws IOException {
            writeStarted=System.nanoTime();
            try { output.write(buffer,0,count); output.flush(); meter.bytes.addAndGet(count); }
            finally { meter.writeNs.addAndGet(System.nanoTime()-writeStarted); writeStarted=0; }
        }
        void close() { meter.transfers.remove(this); }
    }
    private final Map<String,TrackMeter> tracks=Map.of("video",new TrackMeter("video"),"audio",new TrackMeter("audio"));
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
        sources.put(prefix + name, new Source(source,System.nanoTime(),expiry(source)));
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
        Transfer transfer=null;
        try {
            Source source = sources.get(exchange.getRequestURI().getRawPath());
            if (source == null || exchange.getRequestURI().getRawQuery() != null) { reject(exchange,404); return; }
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) { reject(exchange,405); return; }
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) { reject(exchange,400); return; }
            if (method.equals("HEAD")) range=null;
            if (offline || closed) { reject(exchange,503); return; }
            requests.incrementAndGet();
            transfer=new Transfer(tracks.get(exchange.getRequestURI().getRawPath().substring(prefix.length())));
            URI uri = URI.create(source.location());
            if (uri.getScheme().equals("file")) serveFile(exchange, Path.of(uri), range, method.equals("HEAD"),transfer);
            else serveRemote(exchange, source, range, method,transfer);
        } catch (IOException | RuntimeException failure) {
            failures.incrementAndGet(); if(transfer!=null) transfer.meter.failures.incrementAndGet(); // Never log exception messages or provider URLs.
        } finally { if(transfer!=null) transfer.close(); exchanges.remove(exchange); exchange.close(); }
    }
    private void reject(HttpExchange exchange, int code) throws IOException { rejected.incrementAndGet(); exchange.sendResponseHeaders(code,-1); }
    private void serveRemote(HttpExchange exchange, Source source, String range, String method, Transfer transfer) throws IOException {
        long requestStarted=System.nanoTime();
        var request = new okhttp3.Request.Builder().url(source.location()).method(method,null).header("Accept-Encoding","identity");
        if (range != null) request.header("Range",range);
        var call = client.newCall(request.build()); calls.add(call);
        try (var response = call.execute()) {
            statuses.computeIfAbsent(response.code(),ignored->new AtomicLong()).incrementAndGet();
            var diagnostic=rangeReport(range).put("track",transfer.meter.label).put("method",method).put("status",response.code())
                    .put("sourceAgeMs",(requestStarted-source.registeredNs())/1_000_000)
                    .put("requestElapsedMs",(System.nanoTime()-requestStarted)/1_000_000)
                    .put("expiryKnown",source.expiryEpochSeconds()!=null);
            if(source.expiryEpochSeconds()!=null) diagnostic.put("expirySecondsAtResponse",source.expiryEpochSeconds()-java.time.Instant.now().getEpochSecond());
            int redirectCount=0; for(var prior=response.priorResponse();prior!=null;prior=prior.priorResponse()) redirectCount++;
            diagnostic.put("redirectCount",redirectCount);
            String declared=response.header("Content-Length","");
            if(declared.matches("[0-9]{1,18}")) diagnostic.put("declaredContentLength",Long.parseLong(declared));
            response(diagnostic);
            for (String name : List.of("Content-Type","Content-Range","Accept-Ranges","Content-Length")) {
                String value = response.header(name); if (value != null) exchange.getResponseHeaders().set(name,value);
            }
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            long length = response.body() == null ? 0 : response.body().contentLength();
            exchange.sendResponseHeaders(response.code(), method.equals("HEAD") || length == 0 ? -1 : Math.max(0,length));
            if (!method.equals("HEAD") && response.body() != null) copy(response.body().byteStream(),exchange.getResponseBody(),length,0,transfer);
        } finally { calls.remove(call); }
    }
    private void serveFile(HttpExchange exchange, Path path, String range, boolean head, Transfer transfer) throws IOException {
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
            copy(input,exchange.getResponseBody(),length,pace,transfer);
        }
    }
    private void copy(InputStream input, OutputStream output, long remaining, long pace, Transfer transfer) throws IOException {
        byte[] buffer=new byte[16384]; long sent=0, started=System.nanoTime();
        while (remaining != 0) {
            awaitOnline();
            int count=transfer.read(input,buffer,(int)Math.min(buffer.length,remaining<0?buffer.length:remaining));
            if (count < 0) break;
            awaitOnline(); transfer.write(output,buffer,count); bytes.addAndGet(count);
            sent+=count; if (remaining>0) remaining-=count;
            if (pace>0 && !restoredOnce) {
                long wait=sent*1_000_000_000L/pace-(System.nanoTime()-started);
                if (wait>0) try { TimeUnit.NANOSECONDS.sleep(Math.min(wait,TimeUnit.MILLISECONDS.toNanos(250))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("RelayCancelled"); }
            }
        }
    }
    TrackSnapshot track(String name) { return tracks.get(name).snapshot(); }
    long bytes() { return bytes.get(); }
    JSONObject report() { return responseReport().put("bytesForwarded",bytes()).put("requests",requests.get())
            .put("rejectedRequests",rejected.get()).put("streamFailures",failures.get()).put("activeRequests",exchanges.size())
            .put("tracks",new JSONObject(tracks.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->{var t=e.getValue().snapshot(); return new JSONObject().put("bytes",t.bytes()).put("readMs",t.readMs()).put("writeMs",t.writeMs()).put("pendingReadMs",t.pendingReadMs()).put("pendingWriteMs",t.pendingWriteMs()).put("requests",t.requests()).put("failures",t.failures());}))))
            .put("loopbackOnly",true).put("closed",closed).put("upstreamHttpStatuses",new JSONObject(statuses.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().toString(),e->e.getValue().get())))); }
    @Override public void close() {
        closed=true; restore(); calls.forEach(Call::cancel); exchanges.forEach(HttpExchange::close); server.stop(0);
        executor.shutdownNow(); client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
        try { executor.awaitTermination(3,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
