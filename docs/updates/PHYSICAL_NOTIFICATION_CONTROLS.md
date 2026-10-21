# Physical update notifications and settings — 2026-10-21

Issue [#24](https://github.com/jimbobsmith/drivedeck-player/issues/24); shared-device queue [DD #257](https://github.com/jimbobsmith/drivedeckg/issues/257). PR #30 is merged and verified at `a4e6e93f73190e3ee358aa1fb6781c78c190f7cf`. In a fresh Control Tower slot, the original Pixel 9 Pro XL and Fire KFTUWI passed genuine update-completed notification navigation and Player update-policy controls. This is evidence only; no runtime, dependency, APK, version, release or signing change.

## Installed baseline and method

Both Player Test installations remain `com.drivedeck.player.test`, alpha03/code 304, source `5a3c22b561c7fc14c1374a6c1d2f7ce5681b21a8`. Installed APK SHA-256 on each is `bbef3a7987f22650ac446299497fca9cde6104869f15f534b19aba63ef2fbb95`, identical to the [public alpha03 artifact](https://github.com/jimbobsmith/drivedeck-player/releases/tag/player-test-v0.1.0-alpha03). Its previously verified public certificate is `e7ed1f8a11ae706f798b2c592edbdef82b2185c17846ccb6fc83a7fed2555bd2`; this slot checked installed-byte equality rather than independently re-reading the certificate.

The approved 16:06–16:24 Winnipeg/CDT slot used ADB only for UI inputs and read-only package, selected updater preferences, OS-job and activity/service checks. Both devices initially had automatic checks and unmetered downloads enabled, with one Player OS job. DD Test was code 2026102101/version alpha.261021.353, using the installed `34f1552b` source/hash baseline granted by Control Tower. Official DD and production Music were also hash-pinned; all four installed package hashes were identical before and after on both devices. Exact hashes are in [aggregate evidence](physical-notification-controls-evidence.json).

Raw UI hierarchies stayed transient in memory; only generic allowlisted controls were reported. Fire's native launcher and shade repeatedly failed the inspector's idle-state requirement. With explicit human permission, local screenshots enabled navigation; they remain outside Git and were not forwarded to Control Tower. Player's static Settings screen worked with the inspector. The idle timeout alone does not establish a Player defect.

No installation, force-stop, data clear/restore, injected notification, forced job, clock/network/OS-permission/lock/role/pairing/account change, media selection, playback, authorization or recording command occurred. Pixel 9a and emulator were excluded. No personal library database was copied or queried in this slice.

## Results

| Observation | Pixel 9 Pro XL / API37 | Fire KFTUWI / API30 |
| --- | --- | --- |
| Genuine **DD Player updated** notification present | Pass | Pass, after scrolling the native notification list |
| Tap opens Player **Updates** | Pass, SettingsActivity | Pass, SettingsActivity |
| Notification auto-cancels after tap | Pass | Pass |
| Updates status | No newer verified update available | No newer verified update available |
| Notification navigation retains original scheduled job | Pass | Pass |
| Automatic checks OFF | Preference false; zero Player OS jobs | Preference false; zero Player OS jobs |
| Automatic checks ON | Preference true; exactly one new OS job | Preference true; exactly one new OS job |
| Unmetered-download OFF then ON | Preference persisted/restored; daily job identity retained | Preference persisted/restored; daily job identity retained |
| Final automatic/download policy | Original true/true restored | Original true/true restored |
| Final registered Player OS jobs | 1 | 1 |
| Notification consent | POST_NOTIFICATIONS granted | Not runtime-gated on API30 |
| Player playback service at sampled checks | Absent | Absent |

The notification was the genuine **UPDATED** message left by the earlier alpha03 installation. It was not a new **AVAILABLE** or **READY** notification. No newer release was published for this test. Opening Updates without a new release and tapping an existing notification do not prove background discovery/download delivery.

The new daily jobs had connected-network and battery-not-low constraints, with only timing delay unsatisfied at final observation. Pixel additionally exposed Android's flexibility constraint. Fire exposed the package/SystemJobService but not a worker name in its OS header; a missing name is not treated as a missing job. Job identities were compared locally and omitted from portable evidence. This slice did not re-read WorkManager's private database.

## Restored state and scheduling consequence

Turning checks off and on deliberately restarted the first 24-hour delay:

| Device | New first eligibility, America/Winnipeg | Interpretation |
| --- | --- | --- |
| Pixel 9 Pro XL | October 22, about 4:10 p.m. CDT | Earliest scheduling metadata, not an execution guarantee |
| Fire KFTUWI | October 22, about 4:22 p.m. CDT | Earliest scheduling metadata, not an execution guarantee |

These supersede the approximately 11:15 a.m. eligibility in the [earlier scheduler baseline](PHYSICAL_BACKGROUND.md). Android may defer longer. No naturally elapsed daily execution is claimed.

Both devices were restored to their original `com.drivedeck.g.test/com.drivedeck.g.MainActivity` foregrounds. Fire's All apps drawer was closed; the phone's specific Home destination was not independently asserted. Both original Player preferences are true/true and one daily job remains. No Player playback service was running at sampled checks. An earlier scoped Music observation saw Fire paused; the phone Music state was unresolved. No Music command was issued, and these observations do not recertify playback or authentication.

**READY:** all device calls stopped at 16:24 CDT. The [explicit handback](https://github.com/jimbobsmith/drivedeckg/issues/257#issuecomment-6326893340) and direct Control Tower update include package hashes, restored policy/jobs, foreground and playback-state limits. Control Tower retains next-slot authority; the test does not transfer devices directly to another worker.

## Remaining acceptance

Issue #24 remains open with field-validation for naturally elapsed periodic execution and background delivery over time, a genuine newer-release AVAILABLE/READY notification, wider vendor/API23–27 behavior, browser bootstrap and durable production signing. A later read-only observation needs a fresh Control Tower slot and baselines; it should preserve the current delay rather than toggle it again.

This slice makes no new subscriptions/history/resume preservation comparison. The separately measured [alpha02-to-alpha03 in-app update](IN_APP_UPDATE.md) remains the applicable preservation evidence. No A/V, playback, provider or shared-sync acceptance follows from these update-control checks.
