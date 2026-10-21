# PipePipe Android media sync (#17)

The maintainer approved merging platform PR #28 and continuing actual FreeTube/PipePipe integration on 2026-10-19. This accepts the local contract implementation direction, not an independent security audit or hosted-production claim. This branch starts from player main `27e5c39dd75bf962a26bdde190e577d127913d47`; integrated platform service baseline is `a032d084b23aacdf5a503e343e034ea8acade6b2`. Desktop application work is desktop #4; the cross-client service remains platform #27 and parent drivedeckg#244.

## Use

Settings → Personal media sync is explicit opt-in and manual. Select an owner-created **protected enrollment** file with Android's document picker and enter its passphrase; credentials/library keys are protected by Android Keystore and a no-backup atomic ciphertext file. No storage permission, provider credential or new background service is added. Sync requires Android 8+ for the pinned PBKDF2-HMAC-SHA256 format; ordinary PipePipe behavior retains its existing minimum SDK. Import does not imply a completed sync. Select **Sync now** on each device to exchange the library metadata.

Create one owner recovery file and a distinct per-device credential/bundle using the desktop owner-side tool documented in [desktop media-sync guide](https://github.com/jimbobsmith/drivedeck-desktop/blob/main/docs/fork/media-sync/README.md). Bundle crypto uses 600,000 PBKDF2-SHA256 iterations, a random 16-byte salt, AES-256-GCM with random 12-byte nonce and appended 16-byte tag, canonical unpadded base64url and `drivedeck.media-sync.enrollment.v1` UTF-8 AAD. Passphrases are 12..128 printable ASCII characters for deterministic cross-runtime encoding. This is a pinned interoperable format, not an audited password-strength claim. The service never receives library keys or the passphrase. Keep the protected recovery file and passphrase privately/offline; the server cannot recover lost client keys. Compromised library keys require a new owner/key namespace and explicit migration, not just token revocation.

The importer confirms enrollment, and the sync settings expose pending/offline, conflict and restored-service states. Both clients preserve deliberate rewinds. Conflicts require explicit bulk selection of this device's or the service's versions. Disconnect explicitly forgets enrollment/sync metadata while retaining normal subscriptions and local history/state; server token revocation is a separate operator action. Lost Keystore access is an error, not a reason to silently create replacement keys over existing state. See [Android Keystore](https://developer.android.com/privacy-and-security/keystore) for platform protection semantics; no new account/Google runtime is used.

## Actual application mapping

The adapter reads/writes the app's real default Room database through its existing DAOs. Canonical service-0 YouTube channel/video URLs map to encrypted stable IDs, and StreamStateEntity.progressMillis remains integer milliseconds. Each device resolves its own stream UID locally. Incoming unknown subscriptions initially show the channel ID; unknown video checkpoints create a minimal local StreamEntity plus StreamStateEntity. They do not create a fictitious watch-history event, fetch a provider catalogue through the server, or require a provider account. Existing metadata stays intact; normal channel/video extraction can hydrate details when the user opens them.

The ordinary HistoryRecordManager lookup resolves the imported progress from the canonical URL/local UID, and the existing player continues to apply its short/finished/resume preferences on normal opening. No play/seek command or live queue is synchronized. Existing DD media-session/phone authority is unchanged. Titles/thumbnails, resolved/signed URLs, cookies, OS/output preferences, local paths and keys never enter portable metadata. Full history/settings/playlists, background periodic sync, Android TV/device field validation and richer metadata hydration are later slices.

## Persistence, migration and limits

Room schema 901→902 adds only `dd_media_sync(id, body)` and an additive migration; existing app tables and upstream extractor pin stay intact. `body` holds a local baseline, durable mutation intents/wires, remote revisions, cursors and conflict state, not credentials or library keys. Logical local edits come from the already durable application database; manual sync captures changes before network and again inside the pull transaction to preserve edits made while HTTP was in flight. Pending intent plus baseline commit atomically, and pulled subscription/progress changes plus cursor commit in **one Room transaction**. No network request holds that transaction. Process termination before acknowledgement retries the exact saved mutation ID/wire. Reopening with different keys/owner/device fails closed.

Causal bases are frozen when an intent is captured; consecutive local edits acknowledge only a committed predecessor, not an unrelated incoming change. Wall clocks and maximum positions do not pick winners. An operator resume reset advances the shared epoch and removes old queued checkpoints; local UI history deletion is per-record removal, not a claim of global forensic erasure or a new server reset API. Old encrypted service logs/backups can remain. A server restore changes generation and pauses uploads without losing local work. Automatic restore reconciliation/retention/compaction and public/multi-user enrollment are not complete.

The service's existing bounds still apply: 1,000 queued intents, 5,000 server records, 10,000 log entries, 20,000 receipts, 100 changes/page and 16 enrolled devices per owner. A large initial library can fail visibly at a bound; it is never silently truncated. Cleartext is admitted only for literal `127.0.0.1` local development; the Android network-security exception is scoped to that loopback address. The default remote policy remains verified HTTPS, with redirects refused. The server still binds loopback; no hosted deployment is included.

## Validation

Use pinned JDK 25 / Gradle 9.5.1 / Android SDK 37 from the foundation guide:

```text
gradle :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

Actual cross-client validation is orchestrated by desktop `tools/fork/validate-media-sync.cjs` against the packaged FreeTube executable and the matching Test APKs on the explicitly selected `DD_Player_Foundation_API36` emulator. The default helper rejects physical hardware and all other AVD names. A separately authorized fresh-phone path is described below. Install only on that dedicated emulator; never invoke an unqualified connected-device task while a personal phone is attached. The Test identity remains `com.drivedeck.player.test`; extractor source pin remains `c68e10e2e97495877832d8df6cbac55478083019`.

`MediaSyncIntegrationTest` uses the actual default app DB and Android Keystore, verifies the ordinary HistoryRecordManager read, checks protected Node→Android enrollment/record interoperability and changed-key rejection, migrates a reconstructed 901 schema without losing the existing library, and exercises real settings in portrait/landscape. Named phases cover bidirectional exchange, removal/rewind, offline edits, process-stop retry and explicit concurrent-resume resolution. Synthetic credentials are staged in the Test app's private cache. The helper resets only task-owned sync state and its own synthetic rows; never run it against a personal app profile. That original emulator slice performed no installer, personal-device installation, release or hosting; the explicitly authorized phone follow-up is recorded below. Build success is not an A/V or audible-output claim.


## Physical-phone follow-up

Approved PR #18 is integrated at e74c24db4bc469065424ec7d2df73375f04fe60c;
desktop #5 and platform #29 are also merged. The fresh ARM64 Test install
passes the physical-phone subscription/resume sequence and native settings
import, Sync now and disconnect controls. MediaSyncPhoneValidationTest is
separately guarded by a private random permit and an empty-library preflight;
MediaSyncIntegrationTest remains emulator-only. Process-local UI inspection
avoids the shared phone-wide automation service. See
[PHONE_VALIDATION.md](PHONE_VALIDATION.md) and
[phone-evidence.json](phone-evidence.json) for exact evidence and limitations.
The real DocumentsUI choice is intercepted, full provider gestures/playback and
existing-library upgrade remain unproved, and no service is deployed.

## Older Android / display tablet validation

The Fire display's API30 audit found a direct API33-only readNBytes call in the
protected importer. The bounded MediaSyncEnrollmentInput helper replaces that
call without changing the 16 KiB limit or product minimum API. Four focused
input regressions plus the two existing unit tests pass; the rebuilt importer
calls the helper in Dex. Physical-tablet validation passed in the exclusive Fire-only window coordinated
with the Spotify chat: eleven invocations/six methods, real stores/Keystore,
native enrollment, two-way sync, recovery, both orientations and safe disconnect.
DD Home was restored; see [TABLET_VALIDATION.md](TABLET_VALIDATION.md) and
[tablet-evidence.json](tablet-evidence.json) for exact scope and the preserved
display/phone ownership boundary.
## Expanded library follow-up

Playlists, Watch Later and latest per-video watch history are an explicit opt-in follow-up; see [library v2 contract](LIBRARY_V2.md). Existing subscription/resume enrollment does not silently enable the new data.
