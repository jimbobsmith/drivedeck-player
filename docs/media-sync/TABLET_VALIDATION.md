# Fire display tablet validation

Work items: Android #17, desktop #4, platform #27; parent DriveDeck #244.
The maintainer explicitly requested this display-device check and coordination
with the DD Spotify API Player chat. Device use is reserved to Spotify until its
release handoff; no tablet runtime result is claimed by this preparation record.

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
does not retain readNBytes. Full physical-tablet import/exchange remains pending.

## Planned scoped runtime

After Spotify's explicit release, use only the approved physical tablet serial
with matching ARM64 app/test APKs, a fresh disposable owner and isolated
FreeTube profile. The desktop helper accepts --approved-fresh-tablet, refuses an
existing Player/Test-runner package and requires the same private fresh-install
permit/empty-library proof. MediaSyncPhoneValidationTest's historical class
name also covers ARM64 physical tablets; it has no telephony requirement.
Its process-local native-window UI checks require API29+, separately from the
product's Android26 sync gate.

Exercise subscription/resume exchange, remove/re-add, deliberate rewind,
offline/process-stop recovery, explicit conflicts, native protected import/
Sync now, portrait/landscape and safe disconnect. Intercept only the enrollment
file choice. Keep Spotify/DD packages, verified pairing, stay-awake settings,
phone playback authority and other devices intact. Stop the disposable service
and scoped reverse, remove task-owned staged files, and return the tablet to
the agreed DD display activity after the tests.

This is standalone Player metadata-sync validation on the display hardware.
Embedded DD video presentation and phone/display live-control integration need
their own runtime proof. Tablet proof must not be inferred from the earlier
phone/API37 pass.
