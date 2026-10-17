# DriveDeck Player

An experimental standalone YouTube player forked from PipePipe, with planned DriveDeck integration, desktop playback, and optional personal-library sync.

This foundation uses the working name **DriveDeck Player**. Debug builds are visibly named **DriveDeck Player Test** and install as `com.drivedeck.player.test`. They coexist with PipePipe and DriveDeck; their data and signing identities are independent. This is not an update to either application.

The pinned upstream client is PipePipe v5.4.0 (`c2a166f7df05e5ace4c80fb3e3fd27702cfc3e56`). The extractor is a source submodule at `c68e10e2e97495877832d8df6cbac55478083019`. Original source namespaces and copyright/licence notices are retained to keep upstream changes reviewable. The original PipePipe/NewPipe code and FFmpeg notices remain applicable; see [LICENSE](LICENSE) and [the foundation record](docs/foundation/README.md).

```powershell
git clone --recurse-submodules https://github.com/jimbobsmith/drivedeck-player.git
cd drivedeck-player
# JDK 25, Android platforms;android-37.0 and compatible build tools are required.
./tools/foundation/build.ps1 -JdkHome <jdk25> -SdkRoot <android-sdk>
```

No hosted sync or finished desktop client is implemented by this milestone. The desktop module is an executable extraction/decoding/transport feasibility harness. It uses an installed VLC 3.x runtime; it does not download or redistribute VLC. An opt-in `-Present` validation window and controlled A/V timing tools are described in [the presentation guide](docs/foundation/DESKTOP_PRESENTATION.md).

Upstream automatic update/announcement checks are disabled in this fork. Install only reviewed fork builds manually until an independently signed update channel exists. No signing key or release artifact is published here.

Work queue: [desktop playback #2](https://github.com/jimbobsmith/drivedeck-player/issues/2), [foundation #1](https://github.com/jimbobsmith/drivedeck-player/issues/1), [cross-product design DD #244](https://github.com/jimbobsmith/drivedeckg/issues/244), [hosted sync Platform #27](https://github.com/jimbobsmith/drivedeck-platform/issues/27).

Upstream: [PipePipe](https://github.com/InfinityLoop1308/PipePipe), [client](https://github.com/InfinityLoop1308/PipePipeClient), [extractor](https://github.com/InfinityLoop1308/PipePipeExtractor). Please report fork-specific problems to this repository rather than upstream.
