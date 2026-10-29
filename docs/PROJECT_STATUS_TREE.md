# DriveDeck Player status

Updated: 2026-10-18. Product line: `main`. Foundation PR #3 merged at `832a8824515e44867b3e34d770c12ac127260524`. Headless PR #4 merged at `68acddb68b2670e9fd97a7336938ebc52fdf82bf`. Presentation PR #5 merged at `667502a7aa69904ab661b5a6d19b5790fe461ea4`. HD/timing PR #6 merged at `cb0b86d0a82ef2e2bd09fd6413d2aa7ded1d6daa`. Network PR #7 merged at `f4f85211ca267bf0825e7e86baddf45ab2c2bda5`. Recovery/buffer PR #8 merged at `033aec46b8563c9637b4adb4dd06599b6b7075bf`. Deadline/reproduction PR #9 merged at `f28680c9e7295b1563a3e33b495adcb8d6748cf3`. Terminal stability/component PR #10 merged at `e6365223e89654ff18507f7ca600a616e7e9a640`. Native timeline/output PR #11 merged at `2aa91b791a64e456726c22ac5f9025e400a245a3`. WASAPI/provider PR #12 merged at `3ff60af71f6c6d3412e0992969f19dc823d0a683`. Confirmed-batch PR #13 merged at `d7f2a3f073fffebf42940e07f9cdd16cce537c91`. Long-observation PR #14 merged at `a21fdb86742eb10b8d1e78fa219ad0feb14e99dc`. Active continuation: issue #2, branch `codex/issue-2-denial-witness-recovery`; opt-in post-shutdown denial witnesses and further recovery validation have 107 passing tests and require their own reviewed merge. No release published.

- [x] Standalone fork with PipePipe v5.4.0 client/extractor pins.
- [x] Independent Android package/version/Test label and isolated upstream updates.
- [x] Android compile, two exact-identity unit tests and resolved dependency/manifest audit.
- [x] Dedicated-emulator YouTube playback, MediaSession identity/pause/resume/seek test and portrait/landscape evidence.
- [x] Desktop extractor and VLCJ harness compile and package on Windows/JDK 25.
- [x] Native desktop H.264/AAC decode, position, pause/resume and seek on synthetic media.
- [x] Headless desktop YouTube MP4+M4A decode, pause/resume, post-seek decoding and clean software-decoder shutdown on the measured Windows host.
- [x] Native child-process crash/timeout/cancellation containment tests and real-provider deadline/recovery probe.
- [x] Experimental Windows video window and native sound output, with maintainer confirmation and two sustained 480p30 YouTube presentation passes.
- [x] Controlled separate-track software presentation timing: two qualified runs plus a correctly rejected 250ms delayed-audio control; physical latency and provider alignment remain unmeasured.
- [x] Integrated opt-in 720p60 YouTube presentation: three-minute pass plus one-minute repeat, strict progress/stall gates and clean native shutdown.
- [x] Integrated controlled 720p60 A/V timing: two three-minute passes and correctly rejected three-minute 250ms delayed-audio control, zero capture gaps.
- [x] Integrated direct and no-fault-relay 1080p60 actual YouTube controls: five minutes each, no sampled stalls.
- [x] Integrated scoped held-stream/disconnect tests and controlled 1080p60 post-recovery A/V proof; permanent outage/cancellation fail deliberately with clean cleanup.
- [x] Integrated bounded HD image buffers and component recovery traces; two scoped YouTube recovery repeats pass at 79/82 stable samples, controlled post-recovery timing and delayed-audio rejection pass.
- [x] Integrated reproduction of the original 14-sample failure using the exact historical binary; per-track I/O/callback/GC/CPU diagnostics, strict recovery deadline precedence and 51 passing automated tests. Final no-fault control and 80-sample recovery pass.
- [x] Integrated capture of historical and current audio-counter holds; a current first-140-observation prefix reproduces the 14-sample maximum. Terminal completion now also needs 30 stable samples; 63 automated tests pass.
- [x] Integrated output-cadence diagnosis: uninterrupted playback also has held audio counters; matched WASAPI/DirectSound runs show 9/0 post-recovery plateaus, and controlled DirectSound timing passes. Default output and strict recovery gates are unchanged.
- [x] Integrated controlled confirmation: 22 known-pilot hold interiors contain output, the input-rate change shifts batch size while the WASAPI output buffer stays fixed, and current provider method/range/header probes pass. The 44.1kHz terminal failure stays failed; future 403 response context is bounded and sanitized.
- [x] Integrated confirmed-duration recovery validation: current 1080p60 provider recovery and controlled timing pass; a four-second silent-audio control correctly fails the terminal pilot gate. Forty-four fresh/aged method/range requests pass; intermittent 403 remains unexplained.
- [x] Integrated ten-minute observation support: five/ten-minute actual 1080p60 recovery passes with 242/544 confirmed terminal seconds and no resets; full decision sidecars prevent terminal truncation. Current VisionOS diagnostics classify both tracks, all 96 provider checks pass, and the intermittent 403 remains unexplained.
- [ ] Review/integrate opt-in first-denial diagnostics: bounded post-cleanup request comparisons preserve original outcomes. Controlled denial patterns pass; ten-minute held streams recover without restart (one retained clock-jump reset), final five-minute disconnect passes, and the real 403 remains unresolved.
- [ ] General actual-provider recovery/continuity: native output batching is confirmed for controlled fixtures; an exact native wait stack, exclusive source continuity and broader route/hardware behavior remain unproven. Separate late paint suppression, seek-read and HTTP 403 failures are retained. #2 remains open.
- [ ] Provider-specific source A/V alignment, broader physical network/route/hardware qualification; #2 remains open.
- [ ] Desktop local JavaScript/SABR/adaptive delivery adapter decision and proof.
- [ ] Finished desktop UI, OS media keys and packaging matrix.
- [ ] Hosted personal-library sync contract/service (Platform #27).
- [ ] Android/desktop sync clients and offline convergence.
- [ ] DD source discovery, deterministic exact-item/mode commands and interruption field checks.
- [ ] Complete native dependency/source/notice, privacy, compatibility and release audit.

See `docs/foundation/RESULTS.md` for measured outcomes and remaining gates. A checkbox for a compile or synthetic test is not a claim of product or provider completeness.

## Cross-app library imports (#38)

Import implementation is integrated via PR39 at `bd235bd76c03`. The dated candidate receipts are historical. Three isolated Room import tests passed; browser and desktop UI evidence are separate. [Integration qualification](media-sync/INTEGRATION_20261029.md) distinguishes combined-source compile/unit checks from the original runtime campaign. Import Activity orientation, personal libraries/provider metadata, nonempty cross-app playlist/Watch Later, NAS and release gates remain open. No personal target was selected.
