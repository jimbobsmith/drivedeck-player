# Physical-phone subscription and resume validation

Date: 2026-10-19. Work items: desktop #4, Android #17, platform #27; parent
jimbobsmith/drivedeckg#244. The maintainer explicitly authorized the three merges
and testing on the connected phone.

## Integrated baseline and result

FreeTube PR #5 is integrated at c3892fc0bb1fd8231b734a85be710660b34154b0,
PipePipe PR #18 at e74c24db4bc469065424ec7d2df73375f04fe60c and platform
PR #29 at 3ce9e91f6b68b9ebc2e6876f6c11dcc01ad8694d. Each approved head is
an ancestor of its fetched origin/main. The follow-up validation branches start
at those commits; they add tests/tools/evidence, not another playback engine,
a provider dependency update, or a hosted deployment.

The actual unsigned packaged FreeTube executable and ARM64 Test Android app
pass a shared-service run on the authorized physical Android 37 phone. Both use
their real app databases and OS secret storage. The phone had no existing
DriveDeck Player package; the first explicitly selected install and empty-library
preflight are recorded. Existing apps, personal libraries and other connected
targets were untouched.

| Check | Observed result |
| --- | --- |
| Desktop subscription/checkpoint to phone | Channel A imported; 90,000 ms available through the ordinary HistoryRecordManager lookup |
| Phone subscription/checkpoint to desktop | Channel B imported; 20 seconds in the real FreeTube history store |
| Remove and deliberate rewind | A removed on desktop; phone observes removal and 15,000 ms |
| Acknowledged re-add | Phone re-adds A; desktop observes it |
| Service outage | Both retain local edits/pending work; desktop retry controls remain available |
| Phone process stop and service restart | Phone's 8,000 ms checkpoint survives force-stop, retries and reaches desktop |
| Concurrent resume | Original causal base yields an explicit conflict; keep-local resolution of 7,000 ms reaches desktop |
| Protected enrollment | Real masked Android dialog, KDF, Keystore and Sync now succeed; only file selection is intercepted |
| Secrets and key mismatch | Wrong passphrase/changed keys rejected; token/library keys absent from Room sync state |
| Layout | Own-window portrait/landscape captures visually inspected; long landscape settings remain scrollable |
| Disconnect | Native confirmation removes enrollment/sync state and preserves both checkpoints and subscriptions |

Six unique Android test methods execute in eleven successful invocations: six
data phases and five setup/UI/secret/disconnect phases. These are not eleven
different test methods. The additive 901-to-902 migration remains covered by
the earlier dedicated-emulator evidence; this fresh phone install does not prove
an existing-library upgrade. Android build/instrumentation assembly succeeds;
the two existing unit tests are UP-TO-DATE. Eight actual-NeDB desktop unit tests
pass again. Both desktop harnesses pass node --check.

The app-model writes use production FreeTube IPC/NeDB handlers and Android Room
DAOs. This does not prove full provider-channel subscribe gestures or that
opening a real provider video executes a saved resume. No media player was
started by the metadata test. Android settings controls run through native view
click handlers and the actual passphrase dialog. The test intercepts only the
external ACTION_OPEN_DOCUMENT file choice; real DocumentsUI navigation remains
a separate gate. Desktop likewise intercepts only the native file choice.

## Safe repetition

Use desktop tools/fork/validate-media-sync-phone.cjs only after explicit
maintainer authorization for a fresh physical-phone Test install. Supply a
specific serial, matching ARM64 debug/instrumentation APKs and fresh private
scratch directories. Never invoke an unqualified connected-device Gradle task.

From the desktop checkout, with matching built Test APKs:

~~~text
node tools/fork/setup-media-sync-validation.mjs PLATFORM_CHECKOUT D:/tmp/dd/NEW_PHONE_ENROLLMENT
node tools/fork/validate-media-sync-phone.cjs build/win-unpacked/FreeTube.exe D:/tmp/dd/NEW_PHONE_EVIDENCE D:/tmp/dd/NEW_PHONE_ENROLLMENT PLATFORM_CHECKOUT ADB_EXECUTABLE APPROVED_PHONE_SERIAL ARM64_DEBUG_APK ANDROID_TEST_APK --approved-fresh-phone
~~~

The wrapper refuses an existing or retained Test/test-runner package, an
emulator, an existing evidence directory or reverse mapping on port 8789.
It installs only when that preflight passes, stages synthetic enrollment in
private cache, and grants a random private no-backup permit to the phone test
class. The class rejects a missing/mismatched permit and requires an empty real
app library before exchange. It never resets/uninstalls app data. The ordinary
emulator harness and MediaSyncIntegrationTest retain their dedicated-emulator
guards. The phone entry point is not an upgrade/reset tool.

Android UI checks inspect only process-local native windows and render the
current Test Activity's decor view; they do not connect to the shared phone-wide
UiAutomation service or capture other applications. Network calls use a scoped
ADB reverse to the loopback-only service; no Wi-Fi/radio/network-adapter setting
changes, LAN listener, public host or browser sandbox bypass are used.

On success, native Disconnect forgets enrollment while retaining the ordinary
synthetic library; the disposable service, reverse mapping and desktop process
stop, and private staged enrollment/permit/captures are removed from the phone.
The fresh Test app remains installed and disconnected. A failed run preserves
app state for diagnosis; do not clear a personal library to make a retry pass.
Once installed, this wrapper deliberately refuses a second fresh-install run.
Negative checks confirm existing-package refusal before installation and
missing-permit refusal before database access.

## Diagnostics, evidence and remaining work

Three initial attempts failed in the test harness: a null-action Settings
launch was intercepted by the picker monitor; the picker monitor omitted its
MIME type; and the phone-wide UiAutomation service was already registered.
Explicit MAIN action, a MIME-aware monitor, bounded owned-process timeout
cleanup and process-local native view actions corrected those failures.
The final run passes. Retries updated only instrumentation on the same task-owned,
still empty/unenrolled Test installation, with the prior empty-library proof and
absent enrollment checked; no app data was reset or app uninstalled.

phone-evidence.json records exact source/artifact and raw-result hashes, method/
invocation counts, failed attempts, privacy boundaries and cleanup. Raw profiles,
keys, credentials, databases, logs, private retry scripts and captures stay under
D:/tmp/dd. The desktop executable/asar hashes match the previously validated
packaged artifact; no rebuild of the playback product was needed.

Remaining gates include actual DocumentsUI file selection, real-library setup/
upgrade and bounds, normal provider subscribe/playback-resume gestures, DD
live-control with sync disconnected, TV/tablet and other OS vaults, independent
security review, key/restore reconciliation, retention and verified remote HTTPS
hosting. Issues #4/#17/#27 remain open for these gates. This local validation is
not deployment, release publication or a new A/V qualification.
