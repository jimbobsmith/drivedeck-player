# Fire display tablet validation

Work items: Android #17, desktop #4, platform #27; parent DriveDeck #244.
The maintainer explicitly requested this display-device check and coordination
with the DD Spotify API Player chat. Spotify explicitly released a Fire-only window after its paired playback checks.
The tablet run passed, DD Home was restored, and the device was handed back.
The phone remained reserved to Spotify and was not accessed during this window.

## Older-Android enrollment compatibility

The display is ARM64 on Android API30. Local inspection of the previous APK's
enrollment importer found a direct InputStream.readNBytes(int) invocation.
[Android's API33 diff](https://developer.android.com/sdk/api_diff/33/changes/java.io.InputStream)
adds this method after the tablet's API level. The compiled call was not
rewritten to a desugared helper. This was found before installing on the tablet,
rather than by deliberately crashing its app.

MediaSyncEnrollmentInput now reads through older InputStream.read APIs,
allocates at most 16,385 bytes for the boundary probe, rejects inputs over
16 KiB and leaves stream ownership with the existing caller's use block.
Short/zero bulk reads cannot spin and I/O errors remain failures. The enrollment
format, crypto, Android26 enrollment gate, ordinary app minimum SDK, manifests,
dependencies, playback and DD ownership are unchanged.

All six Android unit tests pass (four new bounded-input regressions and the two
existing identity tests). Debug app/instrumentation assembly succeeds. A
compiled-Dex check confirms that the importer invokes the bounded helper and
does not retain readNBytes. The physical-tablet import/exchange now passes as recorded below.

## Scoped runtime and result

The explicitly released Fire target received a fresh Test app and test runner.
The same packaged FreeTube artifact used a fresh profile and separate disposable
owner. The helper's --approved-fresh-tablet flag recorded the target as a tablet;
existing-package protection, private fresh-install permit and empty-library
preflight all remained active. MediaSyncPhoneValidationTest's historical name
also covers ARM64 physical tablets; its native-window tests require API29+
separately from the product's Android26 sync gate.

All eleven invocations of six unique Android methods passed on the first tablet
attempt. Native protected import/Sync now passed in 7.147 seconds. The real
Room/Keystore and production FreeTube IPC/NeDB stores converged:

| Check | Observed |
| --- | --- |
| Desktop to tablet | Subscription A and 90,000 ms checkpoint |
| Tablet to desktop | Subscription B and 20-second checkpoint |
| Remove/re-add and deliberate rewind | Subscription removal reaches tablet, 15,000 ms retained, tablet re-add reaches desktop |
| Service outage and process stop | Local changes/outbox retained; tablet 8,000 ms checkpoint reaches desktop after restart |
| Concurrent resume | Conflict explicit; tablet keep-local 7,000 ms reaches desktop |
| Native settings | Protected import, masked passphrase, Sync now, portrait/landscape and disconnect pass |
| Secrets | Wrong passphrase/changed keys rejected; token/library keys absent from Room state |
| Cleanup | Disconnect preserves library; scoped reverse/service stop, own desktop exits 0, private staged files/permit removed |

Own-window 1200x1920 and 1920x1200 captures were visually inspected; all settings
are visible in both Fire layouts. Only the external file selection is intercepted.
Normal provider subscription gestures and actual playback/resume execution are
separate gates. Independent desktop/phone and desktop/tablet runs used different
disposable owners; simultaneous three-device convergence was not tested.

Spotify/DD packages, verified pairing, roles and debug keep-awake were left
intact, and no media command was sent. The only force-stop targeted the Player
Test package for durable-outbox testing. The tablet was returned to
com.drivedeck.g.test/com.drivedeck.g.MainActivity as agreed; Android confirmed
delivery to the existing top-most instance. The Player Test app remains installed,
disconnected, with only its synthetic local library. The phone was not accessed
during this tablet-only window.

Repeat from the desktop checkout only with fresh approved physical-device
packages and an exclusive handoff:

~~~text
node tools/fork/setup-media-sync-validation.mjs PLATFORM_CHECKOUT D:/tmp/dd/NEW_TABLET_ENROLLMENT
node tools/fork/validate-media-sync-phone.cjs build/win-unpacked/FreeTube.exe D:/tmp/dd/NEW_TABLET_EVIDENCE D:/tmp/dd/NEW_TABLET_ENROLLMENT PLATFORM_CHECKOUT ADB_EXECUTABLE APPROVED_TABLET_SERIAL ARM64_DEBUG_APK ANDROID_TEST_APK --approved-fresh-tablet
~~~

tablet-evidence.json retains exact compatibility source, source/artifact/raw-log
hashes and the successful phase count without identifiers, credentials or raw
app data. The historical phone evidence remains unchanged and refers to its
earlier APK. This Fire APK includes the compatible importer and its six passing
unit tests. No dependency, release signing, installer or hosting change occurred.

This is standalone Player metadata-sync validation on the display hardware.
Embedded DD video presentation and phone/display live-control integration need
their own runtime proof. Tablet proof must not be inferred from the earlier
phone/API37 pass.
