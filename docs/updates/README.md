# Standalone DD Player updates

Issue [#24](https://github.com/jimbobsmith/drivedeck-player/issues/24). This opt-in updater belongs to the Android PipePipe fork. It runs without DD, ADB, a GitHub account, a token, Google services or the optional personal-library sync service.

## Tablet setup and operation

The published alpha01 Test APK has no updater. The [current alpha03 Test prerelease](https://github.com/jimbobsmith/drivedeck-player/releases/tag/player-test-v0.1.0-alpha03) includes it; [alpha02](https://github.com/jimbobsmith/drivedeck-player/releases/tag/player-test-v0.1.0-alpha02) was the first bootstrap. Install the current universal APK over an older Player Test once using [normal APK installation](../install/ANDROID.md), or choose Check now if alpha02 is already installed. Keep the existing app/data; do not uninstall. The package and signer must match. Universal alpha03 is code **304**, following alpha02/code204 and alpha01/codes101-104.

Open **Settings → Updates**, enable **Check for DD Player updates automatically** and, if desired, **Download updates on unmetered networks**. Checks are requested daily with an online/battery-not-low constraint. Downloads require an unmetered connection, battery not low and storage not low. WorkManager/Android decide the actual execution time. Enabling checks also checks immediately. Both options default off. Disabling checks cancels scheduled work; cancelling or disabling a background operation leaves no partial install candidate. Already verified downloads remain available for manual installation.

Updates produce a notification when the OS permits notifications. The Updates page also offers Check now, download, cancel and install actions. Allow Player to install unknown apps when Android requests it, then return and choose **Install verified update**. Android still presents its own Update confirmation. This is automatic discovery/download with a confirmed installation; no silent installer or device-owner privilege is used. Vendor restrictions, denied notifications and battery limits may require opening Updates manually. If a vendor cannot open the installer/settings activity, the verified APK remains staged and a message is shown.

Package replacement reschedules opted-in checks. The updater does not alter the subscriptions/history database, resume positions, playback, DD control authority or optional metadata-sync configuration.

## Source and trust contract

The source is fixed to public `jimbobsmith/drivedeck-player` GitHub releases. Test (`com.drivedeck.player.test`) accepts only `player-test-` tags. Production (`com.drivedeck.player`) accepts only `player-release-` tags. Other packages fail closed. Legacy releases without `release-manifest.json` are skipped. Upstream PipePipe's `ENABLE_APP_UPDATES` remains false; its automatic updater does not run.

Discovery scans at most three pages of twenty releases. JSON responses are bounded to 2 MB and 64 nesting levels; private prepared metadata is bounded to 32 KB. Assets must originate in the same repository. HTTPS redirects are restricted to trusted GitHub hosts, one hop, without user information, custom ports or fragments. The app never stores signed download URLs or provider cookies. No authorization header is used by this updater.

Before staging, after process restoration and immediately before installation, the app verifies declared size, SHA-256, exact application ID, exact newer version code and the installed app's signing-certificate set using Android PackageManager. Test and production cannot replace one another. The manifest is obtained over trusted HTTPS; the APK signer must also match the installed app. Signer rotation is deliberately not inferred. Invalid, truncated and cancelled candidates cannot reach the installer. Prepared metadata uses AtomicFile and only a narrow private update directory is exposed read-only by FileProvider. The new `REQUEST_INSTALL_PACKAGES` permission exists solely to hand an explicitly chosen, verified update to Android's installer. The private MY_PACKAGE_REPLACED receiver restores scheduling after this app is upgraded.

The current Test candidate uses the previously published alpha01 local debug signer, whose public certificate SHA-256 is `e7ed1f8a11ae706f798b2c592edbdef82b2185c17846ccb6fc83a7fed2555bd2`. This preserves existing installations from this build host. It does not establish a durable production signing key or compatibility with other independently generated debug keys. Never substitute another key or uninstall to get around a signature conflict.

## Preparing future releases

Build normally with the pinned JDK25/Gradle9.5.1/SDK37 toolchain. Increment `ddPlayerVersionBase` for every offered update; the universal code is `100 × base + 4`. The alpha02 default base is 2. Supply a matching `ddPlayerVersionName`. The publisher emits a **universal-only** manifest so every ABI observes the same manifest/APK version code.

From a clean reviewed commit:

```powershell
python tools/updates/prepare-release.py --apk <universal-debug.apk> --sdk-root <sdk> --java <jdk25/bin/java.exe> --package com.drivedeck.player.test --signer-sha256 <expected-public-certificate-sha256> --tag player-test-v0.1.0-alpha02 --commit <full-clean-head-sha> --output D:/tmp/dd/player-updates/release-candidate
```

The tool invokes aapt2 and apksigner, enforces the package/tag/signature/universal-ABI contract and writes `release-manifest.json` and `SHA256SUMS.txt` from the actual APK. It never signs, uploads or publishes. After explicit maintainer approval, attach those files and the matching universal APK to a public Test prerelease at that exact tag/commit. Retain upstream attribution/notices. A release without the manifest will not be offered in-app. Do not publish the synthetic alpha03 validation fixture. The public alpha03 release is a separate clean-main build with explicit `-PddPlayerVersionBase=3 -PddPlayerVersionName=0.1.0-alpha03`; see [its provenance and physical acceptance](IN_APP_UPDATE.md).

## Verification and remaining gates

Run `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:writeFoundationRuntimeAudit` with `--no-configuration-cache` (the existing runtime audit task requires it). Run `python -m unittest discover -s tools/updates -p test_prepare_release.py -v`. Keep Python TEMP/TMP and Android scratch output under `D:/tmp/dd`.

The opt-in `tools/updates/validate-emulator.py` requires a fresh named **DDPlayerUpdaterApi36** AVD, an explicit emulator serial, a private one-time permit and controlled APK fixtures. It refuses physical devices before installing anything, does not clear an existing installation and runs only the chosen instrumentation method. Fixtures include a higher-version same-signer APK, a re-signed APK, another package and the current version. Native UI confirmation is a separate observed step. Wait for Android to report App installed before invoking assertUpgradeRetained; instrumentation started while the package is being replaced can be killed by the installer. No connected-device Gradle task is used.

See [evidence.json](evidence.json) for measured builds, verifier negative controls, background work/cancellation, process restoration, both orientations and the synthetic-library upgrade. [Physical bootstrap preservation](PHYSICAL_BOOTSTRAP.md) and the [real alpha02-to-alpha03 in-app update](IN_APP_UPDATE.md) now pass with non-empty libraries on Pixel/ API37 and Fire/ API30. The latter used Player downloads, human-confirmed Android installers and explicitly authorized ADB UI/read-only observation. Browser bootstrap installation, API23-27 installer behavior, production signing, and elapsed notification/OS scheduling across vendors remain field gates. The [physical background scheduler baseline](PHYSICAL_BACKGROUND.md) observes one registered daily check per device. A subsequent [physical notification/settings check](PHYSICAL_NOTIFICATION_CONTROLS.md) passes genuine update-completed notification navigation and automatic/download toggles on both devices, with original policy restored. Toggling restarted the first 24-hour delay; no naturally elapsed check or newer-release AVAILABLE/READY notification is claimed. No A/V, playback or provider behavior is recertified by updater tests. Issue #24 stays open with field-validation until the applicable device/channel gates pass.

## Source provenance

Core manifest models, HTTPS release transport, APK verifier and version policy, with corresponding tests, were adapted from GPL-3.0-or-later DriveDeckG commit `a552c0c5029c16c0ce1153aa113441a441156c5d`, `app/src/main/java/com/drivedeck/g/updates/`. Copyright/provenance headers are retained. Fork changes add anonymous public assets, fixed Test/production channels, existing pinned nanojson parsing, bounded reads/nesting, cancellation, pre-API28 signer flags and standalone scheduling/staging/settings. No new runtime dependency is added. Prepared-update relay policy is retained for version-test compatibility; DD relay/control functionality is not enabled here. The extractor remains pinned at `c68e10e2e97495877832d8df6cbac55478083019`.
