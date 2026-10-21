# Physical in-app update preservation - 2026-10-21

Issue [#24](https://github.com/jimbobsmith/drivedeck-player/issues/24). PR [#28](https://github.com/jimbobsmith/drivedeck-player/pull/28) is merged into main at `5a3c22b561c7fc14c1374a6c1d2f7ce5681b21a8`. The maintainer approved publication of the [alpha03 Test prerelease](https://github.com/jimbobsmith/drivedeck-player/releases/tag/player-test-v0.1.0-alpha03), then confirmed both real Android installer prompts on the devices.

## Measured result

Both existing alpha02/code204 installations discovered and downloaded alpha03/code304 through Player's own public update feed. Both displayed **Verified update ready** at 100%, followed by the Android package installer for **DriveDeck Player Test**. Installed package version and APK SHA-256 match the public release on both devices. No ADB installation command was used.

| Device | Android API / observed layout | Subscriptions | History rows | Resume records | Preservation |
| --- | --- | --- | --- | --- | --- |
| Pixel 9 Pro XL phone | 37 / portrait | 2 -> 2 | 2 -> 2 | 4 -> 4 | All rows and columns equal |
| Fire KFTUWI tablet | 30 / landscape | 3 -> 3 | 1 -> 1 | 3 -> 3 | All rows and columns equal |

Every saved resume millisecond value, history timestamp/repeat count, subscription field and exact stream service/content reference matches the original. The first post-install snapshot was taken after the installer had reopened Player; the final snapshot followed Subscriptions, History and a live Updates check. Both comparisons pass on both devices. Schema902 and SQLite integrity remain unchanged. Actual Subscriptions screens show all original names; History shows all original video titles and progress views. Personal values stay outside Git.

Automatic checks and automatic unmetered downloads remain enabled in Player's saved preferences. A fresh **Check now** on each updated app reports **No newer verified update is available.** This observes live foreground checking and automatic download after a manual check; it does not establish elapsed daily scheduling or notification delivery.

## Procedure and observation limits

The maintainer explicitly allowed ADB only for taps/navigation and read-only checks. It was used to navigate the real app/OS screens, capture private UI observations, read installed versions/hashes and inspect Player's library. Player downloaded and verified the APK and handed it to Android's installer. Player-only unknown-source permission was enabled through each OS settings UI. No DD/Spotify setting or permission was changed.

The native confirmation screens were observed. Before the automation's guarded confirmation taps ran, both prompts had already advanced; those taps refused missing controls and performed no action. The maintainer explicitly confirmed in chat that they had accepted both installers. The installed packages identify `com.android.packageinstaller`; the phone also attributes the originating package to Player. This is a human-confirmed in-app update with ADB observation, rather than an entirely debugging-free test.

No app was force-stopped. A stable before snapshot and two stable post-update snapshots copy the database and extant WAL read-only, checking the complete remote hash set before/after and against the copied bytes. Volatile SHM is omitted; the local read-only SQLite connection builds its own reader locks and checks integrity. Each successful snapshot stabilized on its first read attempt. An initial diagnostic directory listing needed `ls -1`; it failed before copying data and was corrected locally. No backup was restored, fixture seeded, library changed, video played, seek issued, sync enrolled, credential file read, instrumentation run or emulator touched.

The Spotify chat held a coordinated physical-device window. At completion, settled read-only checks and private screenshots confirmed both original DD Test MainActivity/Home surfaces, with no Player playback service. Chromium's non-playback sandbox remained and was left running. An initial overly strict handback assertion expected an older activity-dump field and zero services; corrected observation accepted the actual OS field names and distinguished Chromium from playback. Only then was the device window explicitly returned to Spotify; no further physical calls belong to this slice.

## Release and build provenance

Alpha03 is a genuine version-only rebuild of clean integrated main, using `-PddPlayerVersionBase=3 -PddPlayerVersionName=0.1.0-alpha03`. Source defaults remain base2/alpha02; release build arguments are in the public SOURCES.md and RELEASE-INFO.json. Runtime/library/updater source, dependencies, extractor pin and database schema are unchanged from alpha02. The private synthetic alpha03 fixture was not published.

- APK: `DriveDeckPlayer_0.1.0-alpha03-universal-debug.apk`, 89,677,202 bytes, code304.
- APK SHA-256: `bbef3a7987f22650ac446299497fca9cde6104869f15f534b19aba63ef2fbb95`.
- Existing Test certificate SHA-256: `e7ed1f8a11ae706f798b2c592edbdef82b2185c17846ccb6fc83a7fed2555bd2`.
- Build, runtime inventory, signature/manifest and all-four-ABI audit pass; 46 JVM tests and 4 publisher contract tests pass without failures. No physical instrumentation was run.
- All seven uploaded asset digests/sizes matched local staging before publication. An anonymous public APK transfer initially timed out; its completed resumed download matched the release bytes/hash. Each installed APK independently matches that same hash.

The release's RELEASE-INFO.json snapshot records physical acceptance as pending, accurately describing publication time. This later report and [portable evidence](in-app-update-evidence.json) record the completed device acceptance. Raw databases, installed APK copies, screenshots, UI XML and device identifiers remain in private disposable storage outside Git.

## Remaining acceptance

The actual higher-version feed, verified download, Android installer route and non-empty library preservation now pass on these two devices. Elapsed daily/vendor notification scheduling, API23-27 behavior, browser/file-manager bootstrap installation, other vendor routes, non-debuggable fleet observation and durable production signing remain open. No playback, A/V or optional metadata-sync acceptance follows from this preservation test. Issue #24 remains open with field-validation. These evidence changes require their own PR review; no later merge authority is inferred from approval to merge #28 or publish alpha03.
