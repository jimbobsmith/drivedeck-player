# Physical background scheduler baseline - 2026-10-21

Issue [#24](https://github.com/jimbobsmith/drivedeck-player/issues/24); shared-device queue [DD #257](https://github.com/jimbobsmith/drivedeckg/issues/257). PR #29 is merged and verified on main at `05fd21e5224c15985b9556c3a7ab6b82b4c89cf9`. Control Tower granted a bounded no-install Pixel/Fire slot after Spotify's paused handback. This report records partial observations; notification, toggle and naturally elapsed scheduling tests remain pending.

## Observed registration

Both installed Player APKs are alpha03/code304 and hash-identical to the [published Test release](https://github.com/jimbobsmith/drivedeck-player/releases/tag/player-test-v0.1.0-alpha03), source `5a3c22b561c7fc14c1374a6c1d2f7ce5681b21a8`. Its previously verified certificate is the existing Test/debug signer. No application, dependency, version or signing change is included here.

| Device | API | Enqueued check / matching OS job | First eligible check, Winnipeg time | Automatic check / download |
| --- | --- | --- | --- | --- |
| Pixel 9 Pro XL | 37 | 1 / 1 | October 22, about 11:15 a.m. CDT | true / true |
| Fire KFTUWI | 30 | 1 / 1 | October 22, about 11:15 a.m. CDT | true / true |

The periodic work has a 24-hour first delay and interval, connected-network and battery-not-low constraints, zero run attempts and period count0. WorkManager's system-job mapping matches one registered Player SystemJobService job on each OS. Both OS dumps report only TIMING_DELAY unsatisfied; network, battery, background-restriction and quota conditions are satisfied at that instant. This is registration/constraint metadata, not proof of an actual future run or successful background network delivery. Android may defer beyond the first eligibility time. No forced job command, clock/OS-policy change or shortened schedule was used.

The earlier verified-download worker is recorded SUCCEEDED, consistent with the separately observed [alpha02-to-alpha03 in-app update](IN_APP_UPDATE.md). Phone notification permission is granted; Fire/API30 does not require POST_NOTIFICATIONS runtime consent. This does not establish channel delivery or a notification tap route. At baseline, Player library integrity/schema902 and non-empty counts remain 2 subscriptions /2 history /4 resume records on the phone and 3 /1 /3 on Fire. These are before-only observations; no new post-action preservation comparison is claimed.

## Test interruption and handback

Read-only package/foreground baselines initially showed DD Test MainActivity on both. DD official and Test were version0.1.0-alpha.261017.352/code2026101701, and production Spotify was alpha.2/code2. Their exact current source/certificates were not independently re-measured; the queue records the prior signed Spotify handback. Player's installed-byte hash establishes identity with the already verified public APK.

The only navigation input was the NOTIFICATION key on each device. Neither opened the notification shade. The phone observation then showed official DD's Updates page actively downloading v353; Fire still showed DD Test Home and a paused Play card. No DD download/update was initiated by this test. User activity takes precedence: UI and all further physical calls stopped immediately, and Control Tower accepted the handback for the user/official-DD update. No attempt was made to dismiss the download or restore a screen over the user. Final foreground, process and installed-DD state after that activity were deliberately not re-measured.

**READY for user; remaining Player tests BLOCKED.** Both Player update preferences were captured true/true and never changed. No restore was needed, and the original first-delay timestamps were not restarted. No install, force-stop, data clear/restore, notification injection, library fixture, playback/selection, authorization/recording, OS/network/lock/role change or emulator action occurred. Only Player-scoped stable database/package/job reads and the two navigation keys were used. A new explicit Control Tower slot and fresh package/foreground baselines are required before resuming; there is no direct handoff to another worker.

Stable database/WAL copies passed before/read/after hash matching and read-only SQLite integrity checks on the first attempt. OS dump filtering differs across these APIs and did not expose the WorkSpec UUID. Matching uses the package/service plus the actual system-job mapping; it does not treat the absent UUID as a missing job. Raw dumps and captures stay local, and unrelated OS entries are excluded from [portable aggregate evidence](physical-background-evidence.json).

## Remaining acceptance

Resume with notification/channel inspection and a genuine notification tap to Updates if one remains, then Player-only opt-out/cancellation/re-enable checks restoring the captured policy. Record a deliberately restarted first24-hour delay if toggling is performed. A naturally elapsed daily check is still unobserved. New available/ready notification delivery needs a genuinely newer approved release; no public release is implied by this test request. Wider vendor/API23-27, browser bootstrap and durable production-signing gates remain open. Keep #24 field-validation; do not infer playback, A/V or sync-service acceptance.
