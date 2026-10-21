# Physical bootstrap preservation - 2026-10-21

Issue [#24](https://github.com/jimbobsmith/drivedeck-player/issues/24). The maintainer requested testing the published bootstrap on the actual phone and tablet and verifying subscriptions, history and resume positions. This branch starts from integrated main `0b7b5445b675b67c4ab0a8ead13d7a9c53751f13`; no application code, dependency, version, signing or release asset changes are included.

## Measured result

Both existing Player Test installations upgraded from alpha01/code104 to the exact published alpha02/code204 APK. The following records match **all columns and rows**, both immediately after installation and after opening the actual app, its library screens and Updates:

| Device | Android API | Subscriptions | Watch-history rows | Resume records | Result |
| --- | --- | --- | --- | --- | --- |
| Pixel 9 Pro XL phone | 37 | 2 -> 2 | 2 -> 2 | 4 -> 4 | Exact match |
| Fire KFTUWI tablet | 30 | 3 -> 3 | 1 -> 1 | 3 -> 3 | Exact match |

Every saved `progress_time` millisecond value and its exact stream service/content reference is unchanged. SQLite integrity checks pass, and schema902 is unchanged. The real Subscriptions screen shows the original 2/3 entries with their existing names; History shows the original 2/1 distinct items and their progress views. Names, URLs, positions and timestamps stay in private local evidence.

Both actual apps successfully check the public GitHub release feed and report **No newer verified update is available.** Automatic checks and unmetered automatic downloads are enabled and their saved Player-only preference values were verified. The phone's explicit DD Player Test notification prompt was allowed; no DD or Spotify permission/setting was changed. Future installation still requires Android confirmation.

## Procedure and safety boundaries

The Spotify chat granted a bounded physical-device window and held its mutations. Only the explicitly addressed existing `com.drivedeck.player.test` installations were stopped or replaced. No global ADB restart, emulator use, library fixture seeding, instrumented personal-profile test, uninstall, data clear, playback, provider sign-in, sync enrollment, DD role/pairing change or network-setting change occurred.

Before replacing each app, the installed code APK was copied privately and independently verified with aapt2/apksigner: Test package, code104, valid v2 signature and the same single signing certificate as the bootstrap. The installed APK bytes differed from the published alpha01 universal file; the initial conservative hash guard stopped before installation. Explicit signature verification resolved that difference without relaxing package/version/signer checks.

The published bootstrap's SHA-256 is `aaacec51ffe53bf64e99c5acd544a2e1c8a707aff7138344af9e395d75a51afc`; certificate SHA-256 is `e7ed1f8a11ae706f798b2c592edbdef82b2185c17846ccb6fc83a7fed2555bd2`. Replacement used scoped `adb -s <authorized-device> install -r <published-apk>`. This validates physical package upgrade/data preservation; it does not validate a browser's unknown-source installer UI.

For each before/after snapshot, only Player was force-stopped, then its Room database and extant WAL/SHM files were copied to private local scratch. Remote before/read/after hashes confirm stable transfer. A read-only SQLite connection passes `PRAGMA quick_check` and compares the complete subscription/history/state records, rather than relying on row counts. Normal UI opening and live update checks precede the final repeated comparison. No backup is restored over live data, and no sync/provider credential file is read.

At the handoff, both original DD Test MainActivity surfaces were confirmed and no Player playback service was running. An initial too-early foreground assertion raced the activity transition; the premature coordination handback was corrected and settled read-only checks confirmed the final state. The physical window was returned to Spotify, and no later physical calls are part of this slice.

Raw databases, installed APK copies, UI XML, content titles and device identifiers remain under private disposable storage outside Git. The [portable evidence](physical-bootstrap-evidence.json) contains only aggregate counts, comparison results, public artifact identities and explicit scope limits.

## Remaining acceptance

Phone/API37 and Fire/API30 package upgrade, non-empty library preservation, normal library UI and current public-feed checks pass. A future higher-version public release is still needed to test actual in-app download, notification and Android installer confirmation on these devices. Browser installation, API23-27 behavior, elapsed daily/vendor scheduling and durable production signing also remain open. No playback-seek or A/V recertification follows from retaining resume data.

Keep issue #24 open with field-validation. This evidence branch is parked for maintainer review via its PR; no additional merge or publication authority is inferred from the physical test request.
