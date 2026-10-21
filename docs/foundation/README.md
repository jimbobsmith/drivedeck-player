# Foundation build and desktop feasibility

Date: 2026-10-17. Work item: #1; parent: jimbobsmith/drivedeckg#244.

## Scope and baseline

YouTube first, personal devices first. Local-file support as a product feature, hosted sync, playlists, a complete desktop UI, release signing and deployment are later milestones. Synthetic local media is used only as a decoder/transport test fixture.

The fork starts at stable PipePipe v5.4.0, not the newer beta/development snapshot:

| Component | Pin |
| --- | --- |
| PipePipe wrapper release | v5.4.0 |
| Android client | c2a166f7df05e5ace4c80fb3e3fd27702cfc3e56 |
| Extractor source submodule | c68e10e2e97495877832d8df6cbac55478083019 |
| Gradle | 9.5.1, binary distribution with wrapper SHA-256 |
| Java | JDK 25 (tested portable Temurin 25.0.4.1+1) |
| Android compile / target / minimum SDK | 37 / 36 / 23, retained from upstream |
| Desktop bridge | VLCJ 4.11.0; locally installed VLC 3.x |

Use `git submodule update --init --recursive` after checkout. Source integration uses Gradle composite substitution; no extractor JitPack build is required. Other upstream JitPack dependencies still require their repositories. No floating extractor branch is consumed.

## Android identity and controls

Release application ID is reserved as `com.drivedeck.player`; debug is `com.drivedeck.player.test` and labelled `DriveDeck Player Test`. Internal `org.schabi.newpipe` namespaces stay intact to reduce fork maintenance. Version starts at 0.1.0-alpha01. Release signing, if deliberately configured later, uses only `DD_PLAYER_KEY_*` variables rather than inheriting another project's generic signing variables. Debug signing is for development, not a release update trust root.

The existing platform-compatible media session and foreground playback service are retained. Metadata gains an exact canonical public YouTube content URL only for service 0 and a valid eleven-character ID. Resolved CDN URLs, cookies and arbitrary service IDs are excluded by the new helper and regression tests. This is a controller interoperability foundation; DD package discovery and explicit audio/video handoff integration are separate work.

`ENABLE_APP_UPDATES` is false. Both work enqueue and execution are gated, and the update settings page explains manual development builds. First-run upstream update prompts, upstream release-note/donation popups and automatic announcement fetches are suppressed; the notification permission flow remains available. Fork bug-report links point to this repository. Upstream attribution/donation and explanatory references are retained.

The manifest disables app backup. Existing permissions are inherited, not new grants: Internet/network state, wake lock, notifications, foreground playback/data-sync/special-use, legacy external storage and optional overlay. Their complete merged manifest is part of build inspection. Overlay and download permissions remain optional upstream features; no new permission is added by the DD identity helper.

ACRA's existing sender opens a local error activity, rather than posting crash reports automatically. Existing third-party features, including SponsorBlock, dislike data, YouTube player/signature helpers and login cookie functionality, remain upstream code and require explicit dependency/privacy review before a distributed release. This foundation is not a completed privacy certification.

## Build

```powershell
./tools/foundation/build.ps1 -JdkHome <jdk25> -SdkRoot <android-sdk>
# Optional: use an already verified portable Gradle 9.5.1 with -GradleHome <path>.
```

SDK package naming is `platforms;android-37.0`, not `platforms;android-37`. Use the official SDK manager/Android CLI. No platform or Java source level is silently downgraded. The build script expects the installed SDK and does not accept licences or install system tooling.

All APKs and generated reports remain ignored build outputs. `tools/foundation/audit-apk.ps1 -Apk <apk> -SdkRoot <sdk> -OutputDirectory <scratch>` verifies the Test package/label, disabled backup and reviewed manifest component boundary, and records the APK hash. `writeFoundationRuntimeAudit` records resolved runtime coordinates and rejects Play Services, Firebase, Play libraries and Crashlytics. Google-authored open-source ExoPlayer, Material, annotation and protobuf artifacts are distinct from Play Services; their licences still apply. The APK audit allows only two reviewed Android Auto meta-data names (`com.google.android.gms.car.application` and `com.google.android.gms.car.notification.SmallIcon`); they are not runtime Play Services components. Other service/credential/telemetry matches fail. No permission or runtime-service claim follows from group-name matching alone.

Upstream FFmpeg AAR is retained unchanged. Record its SHA-256 with the evidence; preserve its embedded notices. Its exact native-source reproducibility, ABI/page alignment and complete redistribution compliance remain release gates. This milestone does not replace that binary or declare the release audit complete.

## Desktop test

The same extractor builds for a Java desktop process. VLCJ supplies playback control and a native decoding backend, not YouTube extraction. The module can be built without configuring Android:

```powershell
./gradlew.bat -p desktop-spike test installDist
./tools/foundation/test-desktop.ps1 -VideoId aqz-KE-bpKQ
./tools/foundation/test-desktop.ps1 -Fixture D:\tmp\dd\youtube-player-design\fixture.mp4
```

Run with JDK 25 (`JAVA_HOME`) and a same-architecture VLC 3.x installation discoverable by VLCJ. Native temporary extraction defaults to `D:\tmp\dd\player-desktop-spike`; override with `DD_PLAYER_SCRATCH` on another host. The synthetic test clip has 20 seconds of generated H.264 video and AAC stereo audio; it contains no third-party media. The recipe lives in `tools/foundation/make-fixture.py` (`--output <path>`); its Python test dependency is PyAV/NumPy, not a player runtime requirement.

The launcher supervises a separate Java worker; only that worker loads libVLC. The default process deadline is observation + 80 seconds (100 seconds for the default 20-second observation), followed by at most 5 seconds for forced termination and bounded output-reader joins. Use `-TimeoutSeconds 5` with the PowerShell helper (or `DD_PLAYER_PROBE_TIMEOUT_SECONDS`, 1..720) for a shorter deadline. A nonzero process exit overrides any earlier success JSON. Worker output is capped at 64KiB and raw native diagnostics at 1MiB per run in disposable storage. No automatic retry starts playback. The harness has bounded HTTP calls, native-resource cleanup and sanitized JSON results. It does not persist media URLs, cookies, library data or provider payloads. Native diagnostic output stays in disposable logs. Default headless tests use dummy audio/video output, so positive decode counters do not prove visible presentation or audible output.

A successful decode test requires decoded audio and video, advancing position, stable pause, resumed clock, a successful seek with both decode counters continuing afterwards, and clean stop/release. A process opening successfully or extraction returning metadata is insufficient. The normal YouTube path first admits combined direct progressive/HLS video streams, then separate progressive MP4+M4A tracks through libVLC input-slave. Separate HLS/DASH/SABR tracks are not admitted by this adapter. `-ExperimentalPairedTracks` / `--youtube-paired` remain compatibility aliases for the same supervised probe.

The measured sample returned fourteen separate video streams and five audio streams, all PROGRESSIVE_HTTP; no combined video was returned. Automatic hardware decoding crashed in the installed VLC D3D11 decoder during stop after seeking. The headless adapter explicitly selects software avcodec globally and per media; repeated actual-YouTube probes now pass decode, transport and clean shutdown. This is a measured host workaround, not a general hardware-driver fix. Do not enable hardware acceleration based on this evidence. Tests still use dummy outputs and explicitly report `avSyncProven: false`: decode counters and a shared clock do not measure actual presentation or long-run audio/video synchronization. GUI rendering, audible output, A/V sync, network-disruption recovery, live/adaptive delivery, media keys and OS packaging remain gates in #2 and later milestones.

The desktop downloader refuses PipePipe's hosted signature-decoder endpoint instead of silently depending on it. This may expose a missing local JavaScript-decoder adapter. Such a failure is a measured feasibility result, not a claim that all YouTube playback works on desktop. Android already has its own WebView/local decoder integration; sharing domain code alone does not share this platform adapter.

## Visible/audio presentation and A/V timing

Issue #2 adds an opt-in `-Present` validation window with native audio inside the supervised worker. See [DESKTOP_PRESENTATION.md](DESKTOP_PRESENTATION.md) for commands, separate flash/beep fixtures, render-loopback timestamps, negative controls and interpretation. The selected avformat demuxer improves the measured seek path; a conservative software AVC profile is capped at 720p30 and qualifies this sample at 480p30. Opt-in `-Profile hd720` / `-Profile hd1080` and `-ObserveSeconds` support bounded HD and longer-run qualification; see the presentation guide and latest results before interpreting the higher profiles. Worker decode/transport success still does not certify arbitrary provider A/V sync. The controlled timing harness and human output confirmation are separate evidence.

See [DESKTOP_NETWORK.md](DESKTOP_NETWORK.md) for the scoped loopback relay, explicit interruption/recovery scenarios and separate post-recovery measurement.

## Delivery and evidence

Measured results are recorded in `RESULTS.md` and linked from issue #1. Do not mark the cross-product parent complete after this spike. Remaining provider, device, desktop presentation, compatibility, signing and release gates stay open.




## Repeat the Android controller check

Build `:app:assembleDebugAndroidTest`, install the Test app and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` on a dedicated API29+ emulator, complete notification setup, then select only this class:

```powershell
adb -s emulator-5580 shell am instrument -w -e disableAnalytics true -e class org.schabi.newpipe.player.MediaSessionFoundationTest -e videoId aqz-KE-bpKQ com.drivedeck.player.test.test/androidx.test.runner.AndroidJUnitRunner
```

The test starts its own public video through ACTION_VIEW with a temporary Video player router choice, restores that choice, and uses adopted shell media-control authority only within the instrumentation process. It rejects physical hardware. Run on an explicitly chosen emulator and never use `connectedDebugAndroidTest` while personal devices are attached.

## Subsequent optional metadata integration

The custom desktop feasibility harness above remains parked. Player issue #17
adds manual shared subscription/resume metadata to the actual Android app,
paired with the FreeTube fork. See [../media-sync/README.md](../media-sync/README.md)
for the Room migration, protected enrollment, evidence and field/hosting gates.
The existing playback engine, media session and extractor pin remain intact.

## Fork artwork

The approved provisional DD Android icons and their build/field evidence are documented in [branding](../branding/README.md) (issue #20).

## Install on tablets without debugging

Use the normal Android APK installer with the universal Test APK; see [manual installation](../install/ANDROID.md) (issue #22).
