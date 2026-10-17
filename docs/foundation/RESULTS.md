# Foundation evidence

Date: 2026-10-17. Work item: #1; provider/native follow-up: #2. No production release or personal-device installation.

## Desktop

Windows x64, Temurin JDK 25.0.4.1+1, Gradle 9.5.1, VLCJ 4.11.0 and installed libVLC 3.0.24. Client/extractor pins are in the foundation guide.

`gradle -p desktop-spike installDist --no-daemon --max-workers=1 --console=plain` passes. The initial uncached desktop build took 1m42s; later scoped rebuilds pass. This standalone build does not require an Android SDK.

`tools/foundation/test-desktop.ps1 -Fixture <synthetic-clip>` passes with the final safe-default harness: 201 decoded video frames, 375 decoded audio blocks, position 2653ms of a 20000ms clip, stable pause, advancing resume, seekable and successful seek. Output is dummy decode only: no audible/visible output or long-run A/V synchronization is proved. Curated result: `evidence/desktop-fixture.json`.

The generated H.264/AAC stereo clip contains only synthetic pixels and a low-amplitude sine wave. Its SHA-256 for this run is `2cac5d07d160a06e2040ad5a7b0b91962504bc1386419997c2190782dc5c4de8`. The clip stays in disposable storage; its source recipe is versioned.

`tools/foundation/test-desktop.ps1 -VideoId aqz-KE-bpKQ` resolves YouTube through the pinned extractor, then correctly fails before native playback: 0 combined video streams, 14 video-only and 5 audio streams, all separate deliveries PROGRESSIVE_HTTP. Curated result: `evidence/desktop-youtube.json`. This is not a successful YouTube playback result.

The experimental MP4+M4A pairing reached the isolated test process but exited with -1073741819 / 0xC0000005 without successful decoder evidence. The exact cause is unconfirmed. The original experiment ran while pairing was the selected probe; equivalent pairing is now explicitly gated behind `--youtube-paired` / `-ExperimentalPairedTracks`. Curated process result: `evidence/desktop-pairing-experiment.json`. Normal YouTube probes reject the missing adapter without starting that native path. Follow-up #2 owns diagnosis, native crash containment and a real dual-track provider pass.

Feasibility conclusion: the PipePipe extractor is reusable on the desktop JVM, and native audio/video decoding and transport work with this fixture. VLCJ/libVLC is a candidate backend; it is not yet qualified for YouTube's separate-track or SABR delivery. A shared Kotlin/JVM domain/sync layer remains feasible; a desktop playback adapter and local JavaScript integration need their own proof before choosing the final UI/backend.

## Native artifact and dependency boundaries

Inherited unchanged `ffmpeg/ffmpeg-kit.aar` SHA-256: `19d064dd952a51dd3015007007bf7740c66ce65c09812abbe573a3ec6858f968`. This is provenance evidence, not a complete native-source/licence/page-alignment audit. Preserve upstream notices and close the documented release gate before distribution.

Raw Gradle, native, provider and emulator logs and APK outputs remain in ignored build/disposable storage. Committed JSON is intentionally curated test evidence; it contains no signed stream URL, title/history, account cookie, token or provider payload.

## Android

The Android build and runtime foundation pass at evidence levels A-D, with physical/vehicle and wider provider checks still open.

- `:app:assembleDebug :app:testDebugUnitTest :app:writeFoundationRuntimeAudit`: successful first build in 12m16s, including toolchain/dependency bootstrap; 58 tasks, 2 unit tests, zero failures/errors. Four separate APKs are built for arm64-v8a, armeabi-v7a, x86_64 and x86; no universal APK is implied. Hashes/sizes are in `evidence/android-apks.json`.
- The documented `tools/foundation/build.ps1` helper itself passes in 51s with 62 tasks, including desktop packaging and runtime audit. The resolved graph contains 195 artifact-coordinate records and no forbidden service/telemetry coordinates. Curated graph: `evidence/android-runtime-dependencies.txt`.
- The x86_64 APK passes `audit-apk.ps1`: package `com.drivedeck.player.test`, label `DriveDeck Player Test`, backup disabled, and no unreviewed GMS/Firebase/Crashlytics component. Exactly two Android Auto meta-data names are permitted; no GMS runtime library is inferred from them. The installed APK SHA-256 is `c5fedf532a8b4d4325163a017870b9cf13f46307d5dccbc4876e66a01691c756`; summary: `evidence/android-manifest-summary.json`.
- Runtime uses only the dedicated `DD_Player_Foundation_API36` / `emulator-5580` (default Android API 36 x86_64 image, WebView 133.0.6943.137), with no personal device installation. App cold launch completed; first-run notification setup worked and upstream update/changelog/donation/announcement prompts did not appear.
- Explicit Android ACTION_VIEW of `https://www.youtube.com/watch?v=aqz-KE-bpKQ` followed by Video player / Just once displayed the actual video. Standard media-key Pause reached PAUSED around 30856ms; Resume returned to PLAYING. Video/metadata and layout were visually inspected in portrait (1080x2400) and settled landscape (2400x1080). Captures remain in D:\tmp\dd\youtube-player-design.
- `:app:assembleDebugAndroidTest` succeeds. A single explicitly selected `MediaSessionFoundationTest` passes in 12.998s via `adb -s emulator-5580 shell am instrument`. It asserts the exact public MEDIA_ID and MEDIA_URI, nonzero real duration, stable Pause, Playing after Resume and Seek to 15000ms. It starts its own public test item because Android instrumentation restarts the target process; it restores the temporary router preference and rejects physical hardware. No connected-device Gradle test task was used.

The inherited Android Auto integration exposes two sessions with the same tag on this emulator; one carries complete content metadata, while the other can be metadata-empty. The test selects the exact content-bound controller. DD provider discovery/session arbitration must handle that truthfully before claiming integrated DD playback. This foundation does not change DD's package allowlist or Phone Link contract.

The headless emulator has audio output disabled. Actual decoded video is visible and the media-session controls work; audible output, Bluetooth/car routes, physical phone lock/process/background recovery, tablet matrix, long-run delivery, background-only mode and DD interruption integration remain field gates. Native artifact reproducibility/licensing, 16KiB alignment, dependency privacy and distribution signing remain separate release gates. No release or daily-driver update is authorized or claimed.

Implementation branch is parked for review in a pull request against the fork's `main`; parent DD #244 and provider follow-up #2 stay open. Integration/merge still requires maintainer authority.
