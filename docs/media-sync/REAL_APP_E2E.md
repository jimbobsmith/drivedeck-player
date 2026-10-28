# Real local application convergence campaign (in progress)

Issues: platform #47, desktop #17, Player #40. Parent DriveDeck #244.

This test uses the real encrypted loopback service, a real Electron application with a fresh userData/DPAPI vault and a real DD PipePipe application in the separately reserved AOSP guest. It does not instantiate model-only client fixtures or prepopulate expected peer databases. Desktop actions go through the production preload IPC and actual NeDB handlers; Android actions use production subscription/feed repositories and MediaSyncClient with its real Room database and Keystore. Other-client storage and actual UI are observed separately from server acceptance.

## Supported contract

The implemented kinds are subscription, resume, playlist, watch-later, history, profile and profile-member. Categories/profiles are named colored channel subsets, separate from video playlists. Provider titles/thumbnails, credentials, API tokens, device settings and paths remain local. No general portable preference/settings kind is implemented. Appearance, active profile, volume/output, download paths and provider/API credentials must not be described as syncing.

Both applications use explicit manual Sync actions. There is no automatic foreground/scheduled/real-time propagation. This campaign drives the same production sync commands and records trigger/latency; repeated manual actions check idempotency. Automatic propagation remains an unimplemented product choice.

## Sources and invocation

The first milestone starts from integrated main baselines: platform d128ec1, desktop 18e2a90, Player f6ee480. Branches add test drivers only; the separate import drafts platform46/desktop16/Player39 are not silently substituted. Frozen test heads and app/runner hashes are recorded before installation. Room3 was a prior, explicitly recorded candidate run on the same owned guest; the runner clears ONLY its owned test app data before this clean transition.

After building the pinned desktop bundles and Android test APK, invoke from the platform checkout:

```powershell
node tools/media-sync/real-app-e2e.mjs --desktop D:/tmp/dd/actual-sync-e2e-20261028/desktop --player D:/tmp/dd/actual-sync-e2e-20261028/player --slot D:/tmp/dd/sync-validation-20261028/android-test-set.json --output D:/tmp/dd/actual-sync-e2e-20261028/run-NEW
```

A separately assigned OWN test set is mandatory: fresh API36 AOSP default x86_64, emulator5594, 2 cores/2560MiB/headless/no accounts/snapshots/audio; the private ADB server5067 filters USB and every call has an explicit serial. The runner verifies AVD/PID/creation identity, caps the campaign at30 method invocations (including prior3), and preserves production loopback-only HTTP validation through adb reverse. It does not create or claim arbitrary existing emulators. The loopback QA portal on8861 has a separate database and stays available for human inspection; E2E uses8872.

First milestone: FreeTube subscription/category creation -> service -> Android storage/UI, then Android repository changes -> service -> desktop storage/UI. JSON results include expected/observed state, explicit sync trigger and elapsed time. Screenshots/logs/configs stay in fresh D:/tmp/dd output. Enrollment keys/tokens are random, protected and synthetic; never commit them. During the initial bounded milestone the owned guest remains reserved for follow-up, with a mandatory final teardown/READY receipt before handback. A final runner must provide complete process/resource teardown after the full campaign.

Remaining expansion: membership/rename and deletion, idempotency, service/client restart, offline queue/reconnect and explicit conflicts; history/resume where supported. Actual isolated UI, source/adapter and service evidence remain distinct. Nothing here authorizes merge, release/signing, personal installations or NAS deployment.
