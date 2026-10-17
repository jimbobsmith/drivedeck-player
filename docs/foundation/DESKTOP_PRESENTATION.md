# Desktop presentation and timing validation

Issue #2; Windows x64 experiment, 2026-10-17. This is a small validation window inside the supervised native worker, not a finished desktop client. PR #4 is integrated in main at `68acddb68b2670e9fd97a7336938ebc52fdf82bf`.

## Run visible and audible YouTube

Build the standalone desktop module with JDK 25 and installed VLC 3.x:

```powershell
./gradlew.bat -p desktop-spike test installDist
./tools/foundation/test-desktop.ps1 -VideoId aqz-KE-bpKQ -Present
```

`-Present` uses a Swing video surface backed by VLCJ RV32 frame callbacks and native default audio output. Explicit software avcodec decoding remains mandatory. The final probe selects the installed avformat demuxer globally and per media. Visible YouTube playback uses a conservative AVC progressive-MP4 profile capped at 720p/30fps, selecting the highest offered stream inside that ceiling; this item offers 480p30 as the admitted baseline. Unknown rates/codecs, higher-rate tracks and manifest delivery are excluded from the presentation profile. Headless decoder probes remain separate. The native MP4 demuxer showed intermittent long seek buffering on the fragmented YouTube tracks; the measured avformat path completed seek in about 0.8 seconds. This is a scoped adapter decision, not a general upstream root-cause fix. It sets this player's volume to 35 percent and unmutes this player; it never changes host master volume or other applications. The window closes after the automatic probe or when cancelled. Closing it requests cancellation; native shutdown remains bounded by the supervising process deadline. The parent does not load libVLC and does not automatically restart playback.

The probe checks actual painted frames, native audio-output buffers, stable pause, resumed clock, an observed seek jump with both decoders advancing, 20 seconds of observation with at least 15 seconds of clock advancement plus continued video/audio output, and clean stop/release. Presentation seeks to 15 seconds; the headless regression keeps its 7-second target. Use sufficiently long content (the calibration tracks are 45 seconds). The observed seek has a ten-second deadline; a command acknowledgement or natural clock advancement cannot satisfy it. `avSyncProven` remains false in worker reports: that worker cannot independently measure endpoint timing or certify arbitrary content synchronization.

Only the worker's own painted video buffer can be saved as `presented-frame.png`. No desktop or unrelated-window capture occurs. Local CSV event files and screenshots stay in scratch (`DD_PLAYER_PRESENTATION_DIR`, or a generated directory under `DD_PLAYER_SCRATCH`). They are diagnostic artifacts, never library/sync data.

## Controlled separate-track timing test

Generate synthetic H.264 video and AAC audio as independent tracks. The same libVLC input-slave pairing is used for the YouTube probe. Every content second has a 66.7ms white flash and a simultaneous 20ms tone. Six green image markers encode the content-second ID; tone frequency encodes the same ID (1000Hz + ID * 100Hz). A quiet 300Hz carrier keeps the audio endpoint active between pulses. This prevents silent endpoint suspension from contaminating the capture test. No third-party media is generated or stored.

The generator requires PyAV 19.0.1 and NumPy. Loopback validation requires Windows, SoundCard 0.4.6 and its CFFI/NumPy dependencies. These are scratch/test dependencies, not packaged player runtime dependencies. The measured tools used Python 3.12.14, NumPy 2.5.4, CFFI 2.1.1 and pycparser 3.11. Keep tools under `D:\tmp\dd`, not in the checkout or global runtime.

```powershell
# With the test tools available on PYTHONPATH:
python tools/foundation/make-sync-fixture.py --output-directory D:\tmp\dd\sync-fixture
python tools/foundation/validate-presentation.py --java-home <jdk25> --distribution desktop-spike/build/install/drivedeck-player-desktop-spike --output-directory D:\tmp\dd\sync-result --fixture-directory D:\tmp\dd\sync-fixture
python tools/foundation/make-sync-fixture.py --output-directory D:\tmp\dd\sync-delayed --audio-delay-ms 250
# Same validation command with --fixture-directory ...sync-delayed must fail AVSyncBudgetFailed.
python -m unittest discover -s tools/foundation -p test_av_sync_metrics.py
# For a public-provider presentation/audio-output check:
python tools/foundation/validate-presentation.py --java-home <jdk25> --distribution desktop-spike/build/install/drivedeck-player-desktop-spike --output-directory D:\tmp\dd\youtube-result --video-id aqz-KE-bpKQ
```

Use the chosen Python executable explicitly when the host has multiple Python installations. `-Fixture <video.mp4> -AudioFixture <audio.m4a> -Present` also works with the PowerShell helper, without loopback analysis.

The audio capture endpoint must be the default speaker's WASAPI loopback endpoint. Physical microphones are rejected. The harness waits at most ten seconds for a one-second quiet baseline, then launches the explicitly requested probe. It holds PCM only transiently and saves pulse IDs/timestamps and aggregate levels, never raw audio. Close other audio applications for an attributable measurement; unrelated audio during the run can invalidate it. No device identifier/name or provider transport URL is included in curated evidence.

Video time is sampled after Swing draws each identified flash. Audio uses the WASAPI capture packet's QPC timestamp plus the within-packet onset sample offset, mapped to the host wall clock. The harness checks mapping stability and timestamp validity. A 100ms capture buffer tolerates scheduler latency without substituting packet-arrival time for render timestamps. The recorder temporarily registers its own thread with Windows MMCSS Audio scheduling and reverts it in finally; it changes no persistent host setting. The result records registration and restoration. Device format is cached before capture; native packets are copied and released before analysis, and silent-packet flags are checked too. Only the first measurement packet's expected startup discontinuity flag is excluded. An earlier unscheduled run failed capture continuity and remains recorded as a failure, even though its pulse offsets fit the budget. SoundCard's pinned private COM bindings expose the timestamp that its high-level recorder otherwise discards; an upgrade requires revalidation.

Matching requires the same pulse ID and consumes each audio pulse once. This rejects whole-second aliasing and duplicate-pulse reuse. Own test budgets (not a standards certification): at least 12 matched pulses over 15 seconds, 80 percent coverage, absolute median offset at most 80ms, p95 absolute offset at most 120ms, and end-minus-start drift at most 40ms. The steady post-seek interval is checked separately. A real 250ms delayed-audio control must fail. Missing evidence, invalid timestamps, wall-clock mapping changes over 5ms and any capture discontinuity fail qualification. Startup/transition classifications remain diagnostic; they do not waive that rule.

## Interpretation and remaining gates

This measures software presentation timing: Swing draw completion versus the default render endpoint's loopback stream. It does not measure monitor scanout, display processing, acoustic propagation or a physical speaker/headphone delay. The maintainer separately confirmed that the video and physical sound both work. That is an observation, not an acoustic latency measurement.

A paired synthetic timing pass validates a controlled path through the adapter. A YouTube presentation/audio-output pass validates actual provider delivery and output on this host; it does not independently certify the provider's source A/V alignment. Long-run drift, provider-specific A/V landmarks, arbitrary network stalls/reconnect, Bluetooth routes, other OS/hardware, hardware acceleration, local JavaScript/SABR/live/adaptive delivery, finished UI, media keys and packaging remain separate gates. Keep #2 open.

Primary API references: [Windows multimedia thread registration](https://learn.microsoft.com/en-us/windows/win32/api/avrt/nf-avrt-avsetmmthreadcharacteristicsw), [restoration](https://learn.microsoft.com/en-us/windows/win32/api/avrt/nf-avrt-avrevertmmthreadcharacteristics), [VLCJ 4.11.0 render callbacks](https://github.com/caprica/vlcj/blob/vlcj-4.11.0/src/main/java/uk/co/caprica/vlcj/player/embedded/videosurface/callback/RenderCallback.java), [SoundCard loopback implementation](https://github.com/bastibe/SoundCard/blob/master/soundcard/mediafoundation.py), [Microsoft IAudioCaptureClient::GetBuffer timestamps and flags](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudiocaptureclient-getbuffer). API use is through the dependencies; no FreeTube or SoundCard implementation is copied into the player.
