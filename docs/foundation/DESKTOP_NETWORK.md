# Desktop network interruption validation

Issue #2, Windows x64 experiment. PR #6 is integrated in main at `cb0b86d0a82ef2e2bd09fd6413d2aa7ded1d6daa`. The continuation remains an isolated validation worker, not a finished network recovery product policy.

## Scoped fault injection

`ProbeRelay` binds only 127.0.0.1 on an ephemeral port. Two unpredictable, exact-match routes refer to the chosen video/audio sources held in worker memory. It is not an arbitrary URL proxy: query parameters, extra path suffixes, unknown routes and non-GET/HEAD methods are rejected. Only Range is forwarded upstream; only media length/type/range headers are returned. Provider URLs, cookies, exception messages and raw payloads are never included in the report. Local synthetic fixtures support GET/HEAD, single/suffix byte ranges and 416 responses.

The relay can hold active reads or close active connections and reject new requests with 503. Host network adapters, firewall, routes, DNS and other applications are untouched. File fixtures are paced (minimum 64KiB/s, otherwise file size / 100 bytes/s, except tail metadata requests, with full bandwidth restored after the fault) to prevent downloading an entire short synthetic fixture before the fault. Real provider traffic is not paced. This is an application-scoped transport fault, not a physical network outage.

## Run and interpret

```powershell
./tools/foundation/test-desktop.ps1 -VideoId aqz-KE-bpKQ -Present -Profile hd1080 -ObserveSeconds 140 -NetworkFault hold -NetworkDownSeconds 25
# Fault modes: hold, disconnect, permanent, cancel; default none.
# relay selects the same transport without injecting a fault (control run).
python tools/foundation/validate-presentation.py --java-home <jdk25> --distribution desktop-spike/build/install/drivedeck-player-desktop-spike --output-directory D:\tmp\dd\network-result --video-id aqz-KE-bpKQ --profile hd1080 --observe-seconds 140 --network-fault disconnect
```

A fault is explicitly requested for a single probe, 15 seconds after seek readiness. The outage is bounded to 5..45 seconds (default 25), with at least 100 seconds of observation required. Native buffers are allowed to drain; successful interruption evidence requires an actual clock/audio stall, not merely toggling a flag. The report records native state, media-clock/output progress, bytes forwarded, fault/recovery events and a resumed checkpoint. Byte progress is sampled after one second of settling, allowing at most one 32KiB race at the boundary.

After restoration, require advancing clock, audio/video output and newly painted Swing frames near the last advancing-output checkpoint; restarting at zero or an unrelated future position cannot qualify. If native recovery has not occurred after ten seconds, the explicitly requested experiment permits one restart of the same media at its last advancing-output checkpoint; this is reported separately from native reconnect. Restored output must be observed within thirty seconds of transport restoration, then demonstrate at least thirty consecutive samples of resumed clock/audio/video/paint progress. There is no unbounded or background replay, and a native-process crash is never automatically retried. Natural recovery may catch up/drop buffered output; this does not prove lossless presentation of every sample across the outage. Permanent outage fails after outage duration + ten seconds; cancellation is requested five seconds into the outage. The process supervisor remains the outer crash/hang deadline, and relay reads are released/closed before native stop. Reports distinguish expected failure/cancellation from successful recovery.

For controlled flash/beep fixtures, qualify a separate post-recovery interval starting two seconds after observed recovery, with at least twelve identified pulses over thirty seconds. All capture flags are retained. Any discontinuity or timestamp error inside that measurement interval fails; full-capture continuity is reported independently and is not silently waived. Original uninterrupted tests retain their strict whole-capture continuity rule. This measures software timing after recovery, not physical display/acoustic latency or provider source alignment.

## Evidence and remaining gates

Two five-minute actual 1080p60 controls pass. Controlled 1080p60 hold and disconnect timing pass after recovery; permanent outage and cancellation exit deliberately and cleanly. Actual-provider hard-disconnect recovery remains unqualified: final trials resume but fail stable-output qualification at 14/18 consecutive samples. These failures are retained, not converted to passes. Exact outcomes and per-run binaries are recorded in RESULTS.md and curated desktop-network evidence. Five-minute 1080p60 provider playback and application-scoped outage results are separate gates. Controlled fixtures do not substitute for actual YouTube delivery. Keep #2 open for wider provider/network/hardware/routes, local JavaScript/adaptive/live delivery and packaging. No phone installation or release is performed.

Primary references: [JDK 25 HttpServer](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html), [HTTP Range semantics (RFC 9110)](https://www.rfc-editor.org/rfc/rfc9110.html#name-range). The relay uses the JDK and existing OkHttp dependency; no new runtime dependency is added.
