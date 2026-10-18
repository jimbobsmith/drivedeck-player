# Desktop network interruption validation

Issue #2, Windows x64 experiment. PR #7 is integrated in main at `f4f85211ca267bf0825e7e86baddf45ab2c2bda5`. The continuation remains an isolated validation worker, not a finished network recovery product policy.

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

After restoration, require advancing clock, audio/video output and newly painted Swing frames near the last advancing-output checkpoint; restarting at zero or an unrelated future position cannot qualify. If native recovery has not occurred after ten seconds, the explicitly requested experiment permits one restart of the same media at its last advancing-output checkpoint; this is reported separately from native reconnect. Restored output must be observed before thirty seconds have elapsed after transport restoration, then demonstrate at least thirty confirmed wall seconds of resumed clock/audio/video/paint progress, including a final thirty-second confirmed interval at completion. See Confirmed audio publication batches below for the versioned audio-counter rule. There is no unbounded or background replay, and a native-process crash is never automatically retried. Natural recovery may catch up/drop buffered output; this does not prove lossless presentation of every sample across the outage. Permanent outage fails after outage duration + ten seconds; cancellation is requested five seconds into the outage. The process supervisor remains the outer crash/hang deadline, and relay reads are released/closed before native stop. Reports distinguish expected failure/cancellation from successful recovery.

For controlled flash/beep fixtures, qualify a separate post-recovery interval starting two seconds after observed recovery, with at least twelve identified pulses over thirty seconds. All capture flags are retained. Any discontinuity or timestamp error inside that measurement interval fails; full-capture continuity is reported independently and is not silently waived. Original uninterrupted tests retain their strict whole-capture continuity rule. This measures software timing after recovery, not physical display/acoustic latency or provider source alignment.

## Recovery diagnostics and bounded rendering

The inline report retains up to 300 numeric recovery decisions for observations up to 300 seconds, or a 60-row preview for longer observations, with column names in `recoverySampleColumns`: elapsed time, position, clock/audio/native-picture/Swing-paint deltas, forwarded-byte delta and actual wall-time delta. The first comparison starts at the observed recovery sample. That original strict per-tick rule is retained for historical comparison; current validation uses confirmed publication batches as described below. A diagnostic trace contains one genuine reduced-output interval (zero clock/audio delta and only 11 native pictures/13 paints); it is still rejected by the progress predicate. Native Playing or a later catch-up cannot waive a failed sample.

The validation surface now allocates three RGB images per negotiated format and reuses them. Paint/capture leases protect image pixels from concurrent overwrite; an incoming callback is dropped when all buffers are busy rather than blocking on a paint. This bounds image ownership and removes full-image allocation from every callback. At 1080p60, the previous callback allocated approximately 0.5GB of image pixels per second. The image-pool capacity/drop count is reported for the current format. It is not a total JVM/native/GPU memory cap or a guarantee of lossless frame presentation. Format changes may temporarily retain an older borrowed image.

`validate-presentation.py` also writes `audio-activity.json` in disposable storage: epoch-second bins containing captured-frame counts, frames from packets not flagged SILENT, frames from packets with RMS above 0.0005, and peak amplitude. These summaries help compare endpoint activity with counter stalls; they do not prove provider source alignment, and natural source silence is not itself a playback failure. Raw audio, output-device identity and provider URLs remain excluded.

For a disposable GC comparison, set `JAVA_TOOL_OPTIONS=-Xlog:gc,safepoint=info:stderr:uptimemillis` for the probe process, then restore the previous environment value. Worker native logs remain private scratch artifacts; curate only numeric GC summaries. The source, runtime binary hash and logging option must accompany the comparison. Lower allocation pressure is measured independently from causal diagnosis of an intermittent provider failure.

## Reproduction trace and deadline audit

PR #8 is integrated at `033aec46b8563c9637b4adb4dd06599b6b7075bf`. The exact historical PR #7 binary reproduces the 14-sample failure. Its instrumented variants pass, including two runs retaining the original gate/render/relay class bytes; causal diagnosis remains open. A separate historical result incorrectly accepted recovery at 30304ms. The current policy tests the 30000ms deadline before accepting ready output or attempting a restart. Native blocking operations still have the separate outer process deadline.

For relay and fault modes, disposable `recovery-diagnostics.csv` flushes at most 601 numeric rows with a header. Columns are epoch/elapsed time; phase (0 before cut or no-fault control, 1 blackout, 2 restored/waiting, 3 recovered); media clock; decoded video/audio; native pictures/audio buffers; Swing paints and callbacks; separate video/audio forwarded bytes; cumulative body-read/write milliseconds; longest active body-read/write wait milliseconds; per-track request/failure counts; GC time/collections; and process CPU time. Samples precede the fault/gate decision. Phase 3 indicates recovery was previously observed, not that every later sample passed. Use the original eight-column `recoverySamples` decision trace to identify failed stability predicates. The no-fault relay trace diagnoses the same transport without injecting a fault.

Cumulative waits can include concurrent transfers and scheduling, and do not measure connect/header time or prove server throttling. Fault/seek cancellation can increment failures. No URLs, HTTP headers, track payloads, PCM, output-device identifiers or exception messages are saved in these records. Sidecar write errors fail the probe rather than silently losing evidence.

For a historical caller-only experiment, apply [legacy-recovery-observer.patch](../../tools/foundation/fixtures/legacy-recovery-observer.patch) only to a disposable checkout of `2169186fa5da940b51a3dca342ce2b0d72d3a795`, initialise its pinned extractor, and build with the existing JDK 25 instructions. Run the same 140-iteration/25-second disconnect command into a fresh output directory. The patch changes `DesktopSpike` and adds a reflective observer; it does not change the historical gate, rendering or relay. Compare their three class-file hashes with the exact baseline. `original-gate-failures.csv` is created only after a failed stability decision. Its clock/audio/picture deltas come from the original reference updates; `paintDeltaAtReference` is read after the predicate and may include an extra frame. No file means no failed decision was observed after recovery; reflection can still affect timing. Do not apply this fixture to the current product sources or promote historical deadline acceptance into qualification.

All eleven outcomes and provenance, including a seek-read failure and four-HTTP-403 startup failure, are retained in [desktop-recovery-reproduction.json](evidence/desktop-recovery-reproduction.json). The final current control and recovery pass, but do not close the original causal gate. Late non-suspending [JDWP field watches](https://docs.oracle.com/en/java/javase/25/docs/specs/jdwp/jdwp-protocol.html) also observed a passing historical run; the debugger configuration can perturb execution. Its scratch launcher/client remain experimental and are not runtime dependencies.

## Completion and component diagnosis

PR #10 introduced thirty stable samples at the end of observation as well as a longest stable interval of at least thirty. The version-2 publication gate below now measures confirmed wall duration and replaces that per-tick audio assumption. A historical good interval cannot qualify a run that finishes with painting or another required component held. At PR #10 the per-sample predicate, thirty-second acceptance deadline, one-restart limit and outer supervisor were unchanged. The current batching change preserves the deadline, restart limit and outer supervisor. Original reports remain immutable; separate audits identify old false passes and diagnostic prefixes.

Run one presentation probe at a time. With the same pinned capture dependencies/JDK/VLC as README, the optional Windows observer is:

```powershell
powershell.exe -NoProfile -STA -File tools/foundation/observe-presentation.ps1 -Python <python3> -JavaHome <jdk25> -Distribution <installed-dist> -OutputDirectory D:\tmp\dd\fresh-probe -ObserveSeconds 300 -NetworkFault disconnect -KeepDisplayAwake
python tools/foundation/recovery_diagnostics.py D:\tmp\dd\fresh-probe
```

Use a fresh output directory inside `D:\tmp\dd`. The observer reads only the fixed validation-window identity and numeric state: visibility, minimization, cloaking, foreground overlap estimate, session display power and idle time. It never records other window titles, pixels, device identities or input content. Local idle data is excluded from curated shared evidence. Geometry overlap does not prove painting stopped. The observer does not focus/minimize windows, inject input or change lock/power policy. `-KeepDisplayAwake` explicitly requests temporary display/system execution state and restores the previous state in `finally`; it is optional and does not suppress the screen saver. [Microsoft API behavior](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-setthreadexecutionstate). Observer completion is distinct from playback success: read `presentation-result.json` and `window-observer.json.harnessExit`.

The offline analyzer recomputes recovery intervals, identifies held/reset components and correlates exact trace ticks with nearby window/energy context. It rejects missing/partial decision columns, refuses to infer a one-second failure from five-second native summaries, and does not override the reported worker result or claim a native root cause. Historical paint values read after a predicate cannot exclude a paint-read timing race. Context more than 750ms away is not attributed to a decision. Mixed-endpoint energy and partially overlapping bins cannot establish provider source alignment or exclusive source continuity.

`resample-sync-fixture.py` makes a 44.1kHz variant from the existing generated 48kHz fixture. `fragment-sync-fixture.py` remuxes its audio, optionally adding `--video` to fragment the master video too. They require PyAV 19.0.1 in disposable tooling, check source hashes, preserve the video or encoded video packets, and require exact remuxed AAC/video payload and packet-timeline identity. Use `--fragment-ms` for the selected fragment duration. These helpers process generated fixtures, not provider media. Reproduction example:

```powershell
python tools/foundation/resample-sync-fixture.py D:\tmp\dd\sync-48k D:\tmp\dd\sync-44100
python tools/foundation/fragment-sync-fixture.py D:\tmp\dd\sync-44100 D:\tmp\dd\sync-fragmented --video
# In observe-presentation.ps1, -FixtureDirectory selects a no-fault relay by default.
# Pass -NetworkFault disconnect explicitly for its matched outage control.
```

The measured actual-source first-140 prefix again reaches only fourteen stable samples because audio submission counters hold while clock/video/paint advance. VLC statistics are not an independent audio-device clock. Generated fragmented-container controls and correctly returned source byte ranges do not establish the complete cause. Full retained outcomes, separate timing failures and remaining limits: [component evidence](evidence/desktop-recovery-components.json). Keep #2 open; do not waive audio-counter failures or describe a later passing interval as proof of the original failing prefix.

## Opt-in native audio timeline and output comparison

`DD_PLAYER_AUDIO_TIMELINE=1` enables bounded 200ms statistics sampling during observation and a numeric native-audio event sidecar. The worker saves `audio-timeline.csv` and `audio-native-events.csv` after native cleanup. A forced process kill can lose these in-memory diagnostics; the existing flushed recovery trace remains separate. Native callbacks retain only allowlisted event codes/numbers, perform no file IO or player calls, and ignore URLs, native object identifiers, device names and arbitrary messages. The sample/event caps are 3100/2000, with dropped counts and log-detachment state reported. Enabling this trace also enables native debug logging; uncopied raw native diagnostics stay in disposable storage. Its observation overhead can change timing.

`DD_PLAYER_AUDIO_OUTPUT` accepts `default`, `mmdevice` or `directsound`. `default` adds no audio output override. The explicit alternatives are presentation-only diagnostic experiments and request a module with fallback disabled (`none`); the worker reports the requested policy, while allowlisted native selection events independently identify the loaded backend. An unsupported module must fail the existing audio/transport gates. The output option alone changes neither codec/demux policy nor recovery validation, its thirty-second deadline or terminal stability requirement. No product default is changed.

Example matched Windows experiment, using the existing observer and dependencies (`$Python` is the same Python 3 interpreter passed to the observer):

```powershell
$env:DD_PLAYER_AUDIO_TIMELINE='1'
$env:DD_PLAYER_AUDIO_OUTPUT='mmdevice' # Repeat separately with 'directsound' and a fresh directory.
# Run observe-presentation.ps1 with -NetworkFault disconnect -ObserveSeconds 180 -Profile hd1080.
# Keep both source/profile and built distribution identical; never run audible probes concurrently.
& $Python tools/foundation/audio_timeline.py D:\tmp\dd\<fresh-run-directory>
Remove-Item Env:DD_PLAYER_AUDIO_TIMELINE,Env:DD_PLAYER_AUDIO_OUTPUT
```

The analyzer labels observed audio-counter plateaus of at least 1000ms, excludes sampling gaps over 500ms, checks whether other measured statistics change, and reports the distribution of positive counter updates. These are observation times, quantized by the 200ms poll, not audio-device timestamps. Per-phase summaries split samples at presentation marks, preventing an outage-spanning plateau from being attributed wholly to recovered playback. The whole-run overview is retained separately. Nearby native events use a +/-250ms window, have a deliberately partial allowlist, and cannot rule out every native error. Drift values are microseconds; inserted silence/output buffer values are frames; mixer/output rates are Hz. No field claims output consumption, exclusive continuity or provider source alignment.

The measured comparison and its limits are in [audio timeline evidence](evidence/desktop-audio-counter-timeline.json) and RESULTS.md. Recovery is not necessary for the observed holds: the uninterrupted control and a pre-outage interval also contain them. The alternate backend is a diagnostic comparison, not a general device/route qualification.

## Controlled pilot continuity and provider response diagnostics

`observe-presentation.ps1 -PilotContinuity` adds `--pilot-continuity` to the existing capture harness. It requires a generated fixture recipe with a continuous carrier. Transient loopback packets are reduced to bounded numeric `pilot-packets.json` entries (70000 maximum); no raw PCM is saved and no physical microphone is opened. This measures the mixed default render endpoint, with no exclusive process attribution.

Run `& $Python tools/foundation/pilot_continuity.py <run-directory>` for offline analysis. For each >=1000ms counter plateau in steady/recovered playback, it checks the interior after trimming 100ms at both edges. The predeclared pilot threshold is 0.00002; qualification requires every intersecting packet to contain the pilot, no SILENT/discontinuity/timestamp-error flag, maximum packet gap <=1ms and coverage >=98%. Invalid/non-finite amplitudes cannot qualify. The generated 300Hz tone is not a method for measuring arbitrary YouTube audio. Original worker/harness results remain authoritative and unchanged even when an independent signal/timing analysis passes.

The remote relay now reports at most 64 numeric/enum `upstreamResponses` plus a dropped count: track, GET/HEAD, response status, range class/offsets, time since relay registration, response-header latency, known expiry remaining, declared length and redirect count. Expiry is observational metadata, never a retry/authentication decision. No URL, query token, provider header value, cookie, IP or device identity is recorded. Request headers and denial forwarding are unchanged.

`tools/foundation/ProviderRequestProbe.java` is a standalone diagnostic for the fixed public foundation item. It compares default versus downloader-default browser User-Agent strings, HEAD, closed/open/no-range GET, and a loopback-relay GET. It reads at most 1024 body bytes per GET, stores no body, emits JSON and has a 120-second process watchdog plus an explicitly requested revisit delay (below). `completed` means the request matrix finished, not that all responses passed or playback succeeded. Closing a larger response after the bounded read can increment a relay transfer-failure meter intentionally. The profile named `extractor_user_agent` uses the downloader's configured default browser string; it does not emulate every extractor request header.

Compile/run with the installed spike classpath, into a fresh disposable directory:

```powershell
$probe='D:\tmp\dd\<fresh-provider-probe>'
New-Item -ItemType Directory $probe
$cp=Join-Path $Distribution 'lib\*'
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -cp $cp -d $probe tools/foundation/ProviderRequestProbe.java
& (Join-Path $JavaHome 'bin\java.exe') -cp "$cp;$probe" com.drivedeck.player.spike.ProviderRequestProbe > (Join-Path $probe 'result.json')
```

The current matrix succeeds but cannot retrospectively explain the earlier four HTTP 403 responses, whose request/expiry context was not recorded. A future denial should supply that context. Neither a User-Agent workaround nor automatic retries are introduced. See [confirmation/provider evidence](evidence/desktop-wasapi-provider-diagnosis.json).

## Evidence and remaining gates

Two integrated five-minute actual 1080p60 controls pass. Controlled 1080p60 hold/disconnect timing and permanent-outage/cancellation cleanup are integrated through PR #7. Its final provider trials resumed but failed stability at 14/18 samples; those failures remain recorded. The follow-up diagnostic repeats pass 74/82/82 samples before any rendering fix, so their original cause remains unresolved. A confirmed per-frame image-allocation defect is fixed with the bounded pool: two current provider runs pass 79/82 stable samples, and controlled post-recovery timing passes at 24.76ms median audio delay over 84 seconds. The known 250ms delayed-audio control remains correctly rejected. Numeric traces and exact provenance are in [desktop-recovery-stability.json](evidence/desktop-recovery-stability.json); RESULTS.md distinguishes resource improvement from unresolved intermittent-failure causality.

Keep #2 open for the original intermittent failure's causal diagnosis and wider provider/network/hardware/routes, local JavaScript/adaptive/live delivery, media keys and packaging. Five-minute playback, scoped outage recovery, software fixture timing and provider source alignment are separate evidence gates. No phone installation or release is performed.

Primary references: [JDK 25 HttpServer](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html), [HTTP Range semantics (RFC 9110)](https://www.rfc-editor.org/rfc/rfc9110.html#name-range). The relay uses the JDK and existing OkHttp dependency; no new runtime dependency is added.

## Confirmed audio publication batches (issue #2)

Recovery reports identify their predicate with `recoveryValidation`. Historical records without a version retain `strict_per_tick_v1`; current workers use `confirmed_audio_batches_v2`. A positive audio publication confirms the preceding short hold only if both decisions fit within 2250ms. Each decision still needs 500..1250ms elapsed wall time, at least 500ms clock advancement (no jump beyond wall + 500ms), and positive picture/paint deltas. Negative counters, two consecutive nominal one-second audio holds, scheduler gaps and clock/video/paint failures reset the stable run. The 1250/2250ms limits allow the measured one-second publication cadence plus bounded sampling tolerance; they are validation limits, not output-device guarantees.

Stable duration is now confirmed wall milliseconds. Both longest and terminal runs need 30000ms. Pending audio is never terminal qualification. If observation ends pending, the worker takes exactly one additional nominal one-second confirmation sample; it does not retry playback. A second hold or any invalid output sample fails terminal qualification. The existing 30-second recovery deadline, single explicit restart, checkpoint and blackout-byte gates remain.

`recovery_diagnostics.py` preserves the strict per-tick audit for comparison and adds an explicit version-2 batch replay with worker-duration matching. A counterfactual replay of old samples does not change the old worker result. These counters measure publication progress; `audibleContinuityProven` stays false.

With `-PilotContinuity` (at least 40 seconds of observation), generated-fixture qualification additionally checks the last 30 seconds of observation, ending 100ms before the observation boundary, with the existing packet/pilot thresholds. The interval must start at least two seconds after recovery. Dropped pilot records fail. This independently rejects silent audio content even when playback counters advance. It still measures a known tone on the mixed endpoint, without exclusive process attribution or provider A/V proof.

Generate a deliberate four-second audio gap while retaining video and continuing the AAC timeline:

```powershell
# PyAV/NumPy disposable test runtime; source must be a generated fixture with a verified recipe.
& $Python tools/foundation/silence-sync-fixture.py <generated-source> D:\tmp\dd\<fresh-negative-fixture> --start 165 --end 169
# Run the same observer with -PilotContinuity and that fixture directory.
```

The provider request diagnostic accepts an optional revisit delay in seconds (0..240). It keeps the same two resolved source strings in process memory and repeats the matrix after that delay, without re-extracting. Each round also tests closed ranges at the midpoint and last 1024 bytes when extractor length metadata is available; each GET still reads at most 1024 bytes. Numeric source age is recorded per request. The watchdog is 120 seconds plus the requested delay. No URL, body or provider header is persisted. For a three-minute revisit, append `180` to the Java command above. Matrix completion remains distinct from successful HTTP responses and playback.


## Longer bounded observations

The worker/capture harness admits 20..600 observation seconds; the passive Windows observer admits 100..600. Supervisor timeout defaults to observation + 80 seconds, with an explicit 1..720-second bound. Existing 20-second/default and five-minute commands keep their defaults. Worker stdout remains capped at 64KiB and native scratch logs at 1MiB. Longer observations sample compact presentation summaries every ten seconds rather than five.

Fault runs now flush each eight-column decision into `recovery-decisions.csv` (601 maximum). Exceeding that limit or failing a write fails the probe. `totalRecoveryDecisionSamples` and `recoverySamplesComplete` distinguish the full evidence from the inline preview; `recoveryDecisionSidecar` contains only the fixed basename. Observations above 300 seconds retain just the first 60 inline decisions, keeping stdout bounded while the sidecar records the terminal interval. The 27-column diagnostic trace has capacity for 601 ticks, native audio sampling for 3100 samples, and pilot collection for 70000 packets. Native event limits stay unchanged.

`recovery_diagnostics.py <run-directory>` reads the full decision sidecar when present, verifies its count and inline prefix, and replays the complete sequence. A truncated inline report without its full sidecar is rejected; it cannot imply terminal qualification. The unit regression places a paint stall at decision 550, outside the preview, and verifies that it fails the terminal gate. Keep the full CSV beside the worker report when moving scratch evidence.

For an actual-source ten-minute observation, use the normal observer with `-ObserveSeconds 600 -NetworkFault disconnect`. Ensure the public item's remaining duration accommodates the observation. Long playback still requires its own provider delivery and audible/source-alignment qualification; longer counters alone do not supply that proof.


The pinned extractor defaults to its `visionos` client. Diagnostic schema 3 records an allowlisted `configuredExtractorClient` independently from `sourceClientClass` parsed from each signed URL's `c` parameter. `VISIONOS` is now an admitted source-client label; older `other` records remain unchanged and cannot identify that historical value. The standalone matrix additionally tests `visionos_user_agent`, using the pinned extractor's public VisionOS User-Agent helper with its default localization. That profile is diagnostic only and does not change relay/playback headers. A metadata/playability check is not a media-byte authorization test: the extractor validates the player response before the relay's GETs can observe a CDN 403. No causal attribution follows solely from identifying the client.
